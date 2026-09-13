package com.foxislam.androidagent.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: String,
)

data class McpResult(
    val text: String,
    val images: List<McpImage>,
    val isError: Boolean,
)

data class McpImage(val base64: String, val mime: String)

/**
 * A Model Context Protocol client, speaking Streamable HTTP - one POST per JSON-RPC message,
 * with the server free to answer either in JSON or as an SSE stream.
 *
 * Scope: remote HTTP servers with a bearer token or none. Not stdio (there is no process
 * to spawn on an unrooted phone) and not the full OAuth 2.1 authorisation flow
 * (a browser round trip and dynamic client registration, which is its own piece of work) -
 * a server needing it returns 401 instead of failing obscurely
 */
class McpClient(
    private val url: String,
    private val token: String?,
) {

    private var sessionId: String? = null
    private var initialised = false

    suspend fun listTools(): List<McpTool> {
        ensureInitialised()
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        do {
            val params = JSONObject().apply { cursor?.let { put("cursor", it) } }
            val result = request("tools/list", params)
            val array = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until array.length()) {
                val tool = array.optJSONObject(i) ?: continue
                val name = tool.optString("name").takeIf { it.isNotBlank() } ?: continue
                tools += McpTool(
                    name = name,
                    description = tool.optString("description").ifBlank { "A tool on this MCP server." },
                    inputSchema = (tool.optJSONObject("inputSchema") ?: EMPTY_SCHEMA).toString(),
                )
            }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
        } while (cursor != null && tools.size < MAX_TOOLS)
        return tools
    }

    suspend fun callTool(name: String, arguments: Map<String, Any?>): McpResult {
        ensureInitialised()
        val result = request(
            "tools/call",
            JSONObject().put("name", name).put("arguments", JSONObject(arguments)),
        )

        val text = StringBuilder()
        val images = mutableListOf<McpImage>()
        val content = result.optJSONArray("content") ?: JSONArray()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> text.appendLine(block.optString("text"))
                "image" -> images += McpImage(
                    base64 = block.optString("data"),
                    mime = block.optString("mimeType").ifBlank { "image/png" },
                )
                "resource" -> block.optJSONObject("resource")?.let { resource ->
                    text.appendLine(resource.optString("text").ifBlank { resource.optString("uri") })
                }
                else -> text.appendLine(block.toString())
            }
        }
        // Some servers answer only in structuredContent, and a tool result that reads as
        // empty is indistinguishable to the model from a tool that did nothing
        if (text.isBlank() && images.isEmpty()) {
            result.optJSONObject("structuredContent")?.let { text.append(it.toString()) }
        }

        return McpResult(
            text = text.toString().trim().ifEmpty { "The tool returned nothing." },
            images = images,
            isError = result.optBoolean("isError", false),
        )
    }


    private suspend fun ensureInitialised() {
        if (initialised) return
        val result = request(
            "initialize",
            JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject())
                .put(
                    "clientInfo",
                    JSONObject().put("name", "android-agent").put("title", "Android Agent"),
                ),
            capturingSession = true,
        )
        Log.i(TAG, "connected to ${result.optJSONObject("serverInfo")?.optString("name") ?: url}")
        initialised = true
        // A notification, so no id and no reply to wait for
        notify("notifications/initialized")
    }

    private suspend fun request(
        method: String,
        params: JSONObject,
        capturingSession: Boolean = false,
    ): JSONObject {
        val id = ids.incrementAndGet()
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)

        return post(body) { response ->
            if (capturingSession) {
                response.header("Mcp-Session-Id")?.let { sessionId = it }
            }
            val message = readMessage(response, id)
                ?: throw McpException("$method got no answer from the server.")
            message.optJSONObject("error")?.let {
                throw McpException("$method failed: ${it.optString("message", it.toString())}")
            }
            message.optJSONObject("result") ?: JSONObject()
        }
    }

    private suspend fun notify(method: String) {
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method)
        runCatching { post(body) { } }
            .onFailure { Log.w(TAG, "$method notification failed", it) }
    }

    /**
     * The reply is JSON or an SSE stream, at the server's discretion, and a stream may carry
     * unrelated notifications before the answer - so it is read until the id we asked about
     * comes back
     */
    private fun readMessage(response: Response, id: Long): JSONObject? {
        val body = response.body ?: return null
        if (!response.header("Content-Type").orEmpty().startsWith("text/event-stream")) {
            val text = body.string()
            if (text.isBlank()) return null
            return runCatching { JSONObject(text) }.getOrElse {
                throw McpException("The server sent something that is not JSON: ${text.take(200)}")
            }
        }

        val source = body.source()
        while (true) {
            val line = source.readUtf8Line() ?: return null
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            val message = runCatching { JSONObject(payload) }.getOrNull() ?: continue
            if (message.optLong("id", -1) == id) return message
        }
    }

    private suspend fun <T> post(body: JSONObject, read: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json, text/event-stream")
                .addHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
                .post(body.toString().toRequestBody(JSON_MEDIA))
            token?.takeIf { it.isNotBlank() }?.let { builder.addHeader("Authorization", "Bearer $it") }
            sessionId?.let { builder.addHeader("Mcp-Session-Id", it) }

            val call = http.newCall(builder.build())
            val cancelOnStop = coroutineContext.job.invokeOnCompletion { call.cancel() }
            try {
                val response = try {
                    call.execute()
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    throw McpException("Could not reach the server: ${e.message}", e)
                }
                response.use {
                    if (!it.isSuccessful) throw McpException(explain(it))
                    read(it)
                }
            } finally {
                cancelOnStop.dispose()
            }
        }

    private fun explain(response: Response): String = when (response.code) {
        401, 403 ->
            "The server wants authorisation (${response.code}). Put a bearer token in this " +
                "server's settings. OAuth sign-in is not supported here."
        404 -> {
            // A dead session id is a 404 on a server that had been working; forget it so the
            // next call re-initialises instead of failing forever
            sessionId = null
            initialised = false
            "The server returned 404. Check the URL ends in the MCP endpoint path."
        }
        else -> "The server returned ${response.code}: ${response.body?.string().orEmpty().take(200)}"
    }

    private companion object {
        const val TAG = "Mcp"
        const val PROTOCOL_VERSION = "2025-06-18"
        const val MAX_TOOLS = 200
        val EMPTY_SCHEMA: JSONObject get() = JSONObject().put("type", "object").put("properties", JSONObject())
        val JSON_MEDIA = "application/json".toMediaType()
        val ids = AtomicLong(0)
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
