package com.foxislam.androidagent.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * OpenAI-compatible chat completions - the shape OpenRouter uses to front every model, and
 * what most local and self-hosted gateways speak too, which is why the endpoint is a setting
 * instead of a constant.
 *
 * Replies stream: a phone agent spends most of a turn waiting, so the text is painted as it
 * arrives. A gateway that ignores `stream` and answers with plain JSON is handled on the
 * same path.
 *
 * Images are the awkward part: content parts are legal only on the `user` role, so a
 * screenshot cannot ride inside a tool result. The tool message carries a text placeholder
 * and the image follows as a user message - see [appendResults]
 */
class OpenRouterBackend(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    private val reasoning: Reasoning = Reasoning.OFF,
    /** Used for summaries, and for whichever steps the loop decides do not need the good one */
    private val fastModel: String = "",
) : LlmBackend {

    override suspend fun next(
        system: String,
        turns: List<Turn>,
        tools: List<ToolSpec>,
        model: String?,
        onDelta: (Delta) -> Unit,
    ): Turn.Assistant {
        val body = requestBody(system, turns, model ?: this.model)
            .put("tools", JSONArray().apply { tools.forEach { put(it.toJson()) } })
            // "auto", not "required": a model forced to call something on every turn has
            // nothing to call once it has finished, and re-reads the screen instead of
            // stopping. [AgentLoop] records the ending when a turn comes back as bare text
            .put("tool_choice", "auto")
            .put("max_tokens", MAX_TOKENS)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))

        val reply = withRetries { attempt ->
            if (attempt > 0) onDelta(Delta.Reset)
            send(body) { response -> readReply(response, onDelta) }
        }

        Log.i(
            TAG,
            "turn model=${model ?: this.model} id=${reply.generationId} " +
                "finish=${reply.finishReason} calls=${reply.calls.size} " +
                "text=${reply.text?.length ?: 0} tokens=${reply.usage?.total ?: 0}",
        )
        return reply
    }

    override suspend fun complete(system: String, prompt: String): String {
        val body = JSONObject()
            .put("model", fastModel.ifBlank { model })
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", prompt)),
            )
            .put("max_tokens", SUMMARY_MAX_TOKENS)

        return withRetries {
            send(body) { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?.let { if (it.isNull("content")) null else it.optString("content") }
                    ?: throw LlmException(json.describeFailure())
            }
        }
    }


    private fun requestBody(system: String, turns: List<Turn>, model: String): JSONObject {
        val messages = JSONArray().put(
            JSONObject().put("role", "system").put("content", system),
        )
        turns.forEach { it.appendTo(messages) }
        pruneOldImages(messages)

        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            // OpenRouter-specific and ignored elsewhere: without it there is no cost figure
            // to show per chat
            .put("usage", JSONObject().put("include", true))
            .apply {
                when {
                    // Nothing sent: whatever the provider does by default is what happens
                    reasoning == Reasoning.DEFAULT -> Unit
                    reasoning == Reasoning.OFF -> put("reasoning", JSONObject().put("enabled", false))
                    // Asking for reasoning is only useful if it is also returned, so never exclude it
                    else -> put(
                        "reasoning",
                        JSONObject().put("effort", reasoning.effort).put("exclude", false),
                    )
                }
            }
    }

    /**
     * Retries the transport, not the model. A 429 or a dropped socket is worth another
     * attempt; a bad request or a bad key is not going to fix itself, so those fail
     * immediately
     */
    private suspend fun <T> withRetries(block: suspend (attempt: Int) -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block(attempt)
            } catch (e: Retryable) {
                coroutineContext.ensureActive()
                if (attempt >= MAX_RETRIES) {
                    throw LlmException(e.message ?: "The provider kept failing.", e.cause)
                }
                val wait = e.retryAfterMs ?: (BASE_BACKOFF_MS shl attempt)
                Log.w(TAG, "retrying after ${wait}ms: ${e.message}")
                delay(wait)
                attempt++
            }
        }
    }

    /**
     * Cancelling the coroutine cancels the HTTP call. Without that, Stop only takes effect
     * once the current generation finishes arriving, which on a reasoning model can be the
     * better part of a minute
     */
    private suspend fun <T> send(body: JSONObject, read: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Title", "Android Agent")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()

            val call = http.newCall(request)
            val cancelOnStop = coroutineContext.job.invokeOnCompletion { call.cancel() }

            try {
                val response = try {
                    call.execute()
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    throw Retryable("The network dropped: ${e.message}", e, null)
                }

                response.use {
                    if (!it.isSuccessful) {
                        val text = it.body?.string().orEmpty().take(ERROR_SNIPPET_CHARS)
                        val message = "${it.code}: $text"
                        if (it.code in RETRYABLE_CODES) {
                            throw Retryable(message, null, it.header("Retry-After")?.toRetryMs())
                        }
                        throw LlmException("The provider rejected the request. $message")
                    }
                    read(it)
                }
            } finally {
                cancelOnStop.dispose()
            }
        }

    private fun String.toRetryMs(): Long? =
        trim().toLongOrNull()?.times(1000)?.coerceAtMost(MAX_RETRY_AFTER_MS)


    /** Streamed or not, both shapes end up as the same turn */
    private fun readReply(response: Response, onDelta: (Delta) -> Unit): Turn.Assistant {
        val streaming = response.header("Content-Type").orEmpty().startsWith("text/event-stream")
        if (!streaming) {
            val json = JSONObject(response.body?.string().orEmpty())
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
                ?: throw LlmException(json.describeFailure())
            val message = choice.optJSONObject("message")
                ?: throw LlmException("The reply had no message: $json")
            message.stringOrNull("content")?.let { onDelta(Delta.Text(it)) }
            return message.toAssistant(
                finishReason = choice.optString("finish_reason"),
                generationId = json.optString("id"),
                usage = json.optJSONObject("usage").toUsage(),
            )
        }

        val source = response.body?.source() ?: throw Retryable("The reply had no body", null, null)
        val text = StringBuilder()
        val thinking = StringBuilder()
        val calls = sortedMapOf<Int, PartialCall>()
        val reasoningDetails = JSONArray()
        var finishReason: String? = null
        var generationId: String? = null
        var usage = Usage()
        var sawAnything = false

        while (true) {
            val line = try {
                source.readUtf8Line()
            } catch (e: IOException) {
                // Nothing usable yet means the whole attempt can be retried cleanly; a stream
                // that died mid-sentence has already been painted, so keep what arrived
                if (!sawAnything) throw Retryable("The stream dropped: ${e.message}", e, null)
                Log.w(TAG, "stream cut short", e)
                null
            } ?: break

            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            if (payload.isEmpty()) continue

            val chunk = runCatching { JSONObject(payload) }.getOrNull()
            if (chunk == null) {
                Log.w(TAG, "unparseable chunk: ${payload.take(200)}")
                continue
            }
            sawAnything = true

            chunk.optString("id").takeIf { it.isNotBlank() }?.let { generationId = it }
            chunk.optJSONObject("usage")?.toUsage()?.let { if (it.total > 0) usage = it }
            chunk.optJSONObject("error")?.let {
                throw Retryable("The provider errored mid-stream: ${it.optString("message")}", null, null)
            }

            val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
            choice.stringOrNull("finish_reason")?.let { finishReason = it }
            val delta = choice.optJSONObject("delta") ?: continue

            delta.stringOrNull("content")?.let {
                text.append(it)
                onDelta(Delta.Text(text.toString()))
            }
            (delta.stringOrNull("reasoning") ?: delta.stringOrNull("reasoning_content"))?.let {
                thinking.append(it)
                onDelta(Delta.Thinking(thinking.toString()))
            }
            delta.optJSONArray("reasoning_details")?.let { details ->
                for (i in 0 until details.length()) reasoningDetails.put(details.get(i))
            }
            delta.optJSONArray("tool_calls")?.let { accumulate(it, calls) }
        }

        return assembled(text, thinking, calls, reasoningDetails, finishReason, generationId, usage)
    }

    /** A streamed tool call arrives as a name once and its arguments a few characters at a time */
    private fun accumulate(deltas: JSONArray, into: MutableMap<Int, PartialCall>) {
        for (i in 0 until deltas.length()) {
            val entry = deltas.optJSONObject(i) ?: continue
            val index = entry.optInt("index", i)
            val partial = into.getOrPut(index) { PartialCall() }
            entry.stringOrNull("id")?.let { partial.id = it }
            entry.optJSONObject("function")?.let { function ->
                function.stringOrNull("name")?.let { partial.name = it }
                if (!function.isNull("arguments")) partial.arguments.append(function.optString("arguments"))
            }
        }
    }

    private fun assembled(
        text: StringBuilder,
        thinking: StringBuilder,
        calls: Map<Int, PartialCall>,
        reasoningDetails: JSONArray,
        finishReason: String?,
        generationId: String?,
        usage: Usage,
    ): Turn.Assistant {
        val echoCalls = JSONArray()
        val parsed = mutableListOf<ToolCall>()
        calls.entries.sortedBy { it.key }.forEachIndexed { position, (_, partial) ->
            val name = partial.name
            if (name.isNullOrBlank()) {
                Log.w(TAG, "dropping a streamed tool call that never got a name")
                return@forEachIndexed
            }
            val id = partial.id ?: "call_$position"
            val arguments = partial.arguments.toString()
            parsed += ToolCall(id, name, parseArguments(arguments))
            echoCalls.put(
                JSONObject()
                    .put("id", id)
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject().put("name", name).put("arguments", arguments.ifBlank { "{}" }),
                    ),
            )
        }

        // The assistant message we will replay next turn. Reasoning is echoed back when the
        // provider gave us a structured copy, because some models refuse a tool result that
        // does not carry the thinking that produced the call
        val echo = JSONObject()
            .put("role", "assistant")
            .put("content", text.toString().ifBlank { null })
            .apply {
                if (echoCalls.length() > 0) put("tool_calls", echoCalls)
                if (reasoningDetails.length() > 0) put("reasoning_details", reasoningDetails)
            }

        return Turn.Assistant(
            text = text.toString().trim().ifEmpty { null },
            thinking = thinking.toString().trim().ifEmpty { null },
            calls = parsed,
            echo = echo,
            refused = finishReason == "content_filter",
            finishReason = finishReason?.takeIf { it.isNotBlank() },
            generationId = generationId?.takeIf { it.isNotBlank() },
            usage = usage,
        )
    }

    private class PartialCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private fun JSONObject?.toUsage(): Usage? {
        if (this == null) return null
        return Usage(
            promptTokens = optInt("prompt_tokens"),
            completionTokens = optInt("completion_tokens"),
            costUsd = optDouble("cost", 0.0).takeUnless { it.isNaN() } ?: 0.0,
        )
    }

    private fun JSONObject.describeFailure(): String {
        optJSONObject("error")?.let { return "The provider returned an error: ${it.optString("message", it.toString())}" }
        return "The provider returned no choices: ${toString().take(ERROR_SNIPPET_CHARS)}"
    }

    private fun JSONObject.toAssistant(
        finishReason: String?,
        generationId: String?,
        usage: Usage?,
    ): Turn.Assistant {
        val calls = mutableListOf<ToolCall>()
        val raw = optJSONArray("tool_calls")
        if (raw != null) {
            for (i in 0 until raw.length()) {
                val entry = raw.optJSONObject(i)
                val function = entry?.optJSONObject("function")
                val name = function?.optString("name").orEmpty()
                if (entry == null || function == null || name.isBlank()) {
                    Log.w(TAG, "dropping malformed tool_call at $i: $entry")
                    continue
                }
                calls += ToolCall(
                    id = entry.optString("id", "call_$i"),
                    name = name,
                    // A model that names a tool but mangles its arguments gets the tool's
                    // report of what is missing, not a dropped call
                    input = parseArguments(function.optString("arguments")),
                )
            }
        }

        // Seen in the wild from Gemini: finish_reason "tool_calls" with ~3 completion tokens
        // and nothing usable in the array. The turn carries no progress, so the caller must
        // retry it instead of reading it as the model choosing to stop
        if (finishReason == "tool_calls" && calls.isEmpty()) {
            Log.w(TAG, "finish=tool_calls but no usable calls; raw message: ${this.toString().take(ERROR_SNIPPET_CHARS)}")
        }

        return Turn.Assistant(
            text = stringOrNull("content"),
            // Reasoning models surface their trace here; most models omit it entirely
            thinking = stringOrNull("reasoning"),
            calls = calls,
            echo = this,
            refused = finishReason == "content_filter",
            finishReason = finishReason?.takeIf { it.isNotBlank() },
            generationId = generationId?.takeIf { it.isNotBlank() },
            usage = usage,
        )
    }

    /**
     * org.json's optString returns the *string* "null" for a JSON null instead of "", and
     * `content` is null on every tool-only turn - so the naive read puts the word "null" in
     * front of the user
     */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }

    /** Arguments arrive as a JSON *string*, and models occasionally emit an empty one */
    private fun parseArguments(raw: String): Map<String, Any?> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            val parsed = JSONObject(raw)
            parsed.keys().asSequence().associateWith { key ->
                when (val value = parsed.get(key)) {
                    JSONObject.NULL -> null
                    else -> value
                }
            }
        }.getOrElse { emptyMap() }
    }

    private fun Turn.appendTo(messages: JSONArray) = when (this) {
        is Turn.User -> messages.put(
            JSONObject().put("role", "user").put(
                "content",
                imageBase64?.let { image ->
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", text))
                        .put(
                            JSONObject()
                                .put("type", "image_url")
                                .put(
                                    "image_url",
                                    JSONObject().put("url", "data:image/jpeg;base64,$image"),
                                ),
                        )
                } ?: text,
            ),
        )
        is Turn.Assistant -> messages.put(echo as? JSONObject ?: rebuildAssistant())
        is Turn.ToolResults -> appendResults(messages)
    }

    private fun Turn.Assistant.rebuildAssistant(): JSONObject =
        JSONObject().put("role", "assistant").put("content", text ?: "")

    /**
     * A `tool` message may only carry a string. When a tool produced a screenshot, the text
     * placeholder goes in the tool message (the API requires one per tool_call_id) and the
     * image follows as a user message, which is the only role allowed content parts
     */
    private fun Turn.ToolResults.appendResults(messages: JSONArray): JSONArray {
        val images = mutableListOf<Outcome.Image>()

        outputs.forEach { output ->
            val summary = when (val value = output.outcome) {
                is Outcome.Text -> value.text
                is Outcome.Done -> value.summary
                is Outcome.Image -> { images += value; "${value.note} The image follows in the next message." }
            }
            messages.put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", output.call.id)
                    .put("content", summary),
            )
        }

        if (images.isNotEmpty()) {
            val parts = JSONArray()
            images.forEach { image ->
                parts.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject().put("url", "data:${image.mime};base64,${image.base64}"),
                        ),
                )
            }
            messages.put(JSONObject().put("role", "user").put("content", parts))
        }
        return messages
    }

    /**
     * Only the most recent screenshots are kept. Older ones cost the most of anything in the
     * context and show a screen several actions out of date
     */
    private fun pruneOldImages(messages: JSONArray) {
        var seen = 0
        for (i in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(i) ?: continue
            val parts = message.optJSONArray("content") ?: continue
            val hasImage = (0 until parts.length()).any {
                parts.optJSONObject(it)?.optString("type") == "image_url"
            }
            if (!hasImage) continue
            seen++
            if (seen > MAX_IMAGES_IN_CONTEXT) {
                message.put("content", "[earlier screenshot dropped to save context]")
            }
        }
    }

    private fun ToolSpec.toJson(): JSONObject {
        val parameters = rawSchema?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().apply {
                        properties.forEach { (key, spec) -> put(key, JSONObject(spec.toMap())) }
                    },
                )
                .put("required", JSONArray(required))

        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
    }

    /** Carries a failure the caller should retry, not one it should report */
    private class Retryable(
        message: String,
        cause: Throwable?,
        val retryAfterMs: Long?,
    ) : Exception(message, cause)

    private companion object {
        const val TAG = "OpenRouter"
        val JSON_MEDIA = "application/json".toMediaType()
        const val MAX_TOKENS = 4096
        const val SUMMARY_MAX_TOKENS = 700
        const val MAX_IMAGES_IN_CONTEXT = 2
        const val MAX_RETRIES = 3
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_RETRY_AFTER_MS = 30_000L

        /** How much of a failed body to quote: enough to recognise, short enough to log */
        const val ERROR_SNIPPET_CHARS = 400
        val RETRYABLE_CODES = setOf(408, 409, 425, 429, 500, 502, 503, 504, 520, 522, 524)

        /**
         * No call timeout: a streamed turn with reasoning can legitimately run for minutes,
         * and the read timeout already catches a stream that has stalled
         */
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
