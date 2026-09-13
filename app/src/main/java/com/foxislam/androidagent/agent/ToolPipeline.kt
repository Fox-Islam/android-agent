package com.foxislam.androidagent.agent

import android.content.Context
import android.util.Log
import com.foxislam.androidagent.control.ControlService
import com.foxislam.androidagent.control.UiNode
import com.foxislam.androidagent.mcp.McpServers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything that happens around a tool call, in one place and in one order:
 *
 *   describe → rules and approval → run, under a timeout → cap the result → record both
 *
 * Every route to a tool comes through here, so an approval, a hook or a batched step is one
 * concern in one place instead of a branch in the agent loop. A batch inside [BATCH] runs its
 * steps back through this same path, so a sub-step is gated and recorded exactly like a
 * top-level one; the alternative is a tool that launders unapproved taps
 */
class ToolPipeline(
    private val tools: Tools,
    private val service: ControlService,
    private val context: Context,
    private val threadId: String,
    private val onStatus: (String) -> Unit,
) {

    suspend fun execute(call: ToolCall, depth: Int = 0): Outcome {
        val detail = describe(call)
        // Every call is recorded, `done` included: a chat that does not log the call that
        // ended it cannot be replayed, because the provider sees a call nothing answered.
        // The transcript hides the `done` pill instead - its summary covers it
        Chats.record(
            threadId,
            SessionEvent.ToolStarted(Chats.nextEventId(), call.id, call.name, detail),
        )
        onStatus(statusText(call, detail))

        val outcome = blocked(call) ?: gate(call, detail) ?: run(call, depth)
        val capped = cap(outcome)

        Chats.record(
            threadId,
            SessionEvent.ToolFinished(
                id = Chats.nextEventId(),
                callId = call.id,
                result = Projection.resultText(capped),
                image = capped is Outcome.Image,
            ),
        )
        return capped
    }

    /**
     * A dark or locked screen swallows gestures without reporting anything, so without a
     * check the agent taps into nothing and reports success
     */
    private fun blocked(call: ToolCall): Outcome? {
        if (call.name !in NEEDS_SCREEN) return null
        return service.screenProblem()?.let { Outcome.Text(it) }
    }

    /** What the pill and the shade show while this call runs */
    private fun statusText(call: ToolCall, detail: String) =
        listOf(call.name, detail).filter { it.isNotBlank() }.joinToString(" ")

    /** Returns a refusal to hand back to the model, or null when the call may proceed */
    private suspend fun gate(call: ToolCall, detail: String): Outcome? {
        val packageName = ControlService.foregroundPackage
        val verdict = Approvals.gate(
            context = context,
            threadId = threadId,
            tool = call.name,
            detail = approvalDetail(call, detail, packageName),
            packageName = packageName,
        )
        // An approval card puts its own question on the pill, so put this call back on it
        onStatus(statusText(call, detail))
        return if (verdict.allowed) null else Outcome.Text(verdict.message)
    }

    private suspend fun run(call: ToolCall, depth: Int): Outcome = try {
        when {
            call.name == BATCH -> batch(call, depth)
            call.name == SCRIPT -> script(call, depth)
            call.name == CALL_SCRIPT -> saved(call, depth)
            // A tool whose whole job is to wait for a person cannot be given a deadline of
            // its own; it has one already, and it is measured in minutes
            call.name in Tools.blocking -> tools.call(call.name, call.input)
            else -> withTimeout(timeoutFor(call.name)) { tools.call(call.name, call.input) }
        }
    } catch (e: CancellationException) {
        // A timeout is a cancellation of the tool, not of the run: report it and let the
        // model decide what to do, instead of killing a task because one screen was slow
        if (e is TimeoutCancellationException) {
            Log.w(TAG, "${call.name} timed out")
            Outcome.Text("${call.name} took too long and was abandoned. Look again before retrying.")
        } else {
            throw e
        }
    } catch (e: Throwable) {
        Log.w(TAG, "tool ${call.name} failed", e)
        Outcome.Text("Tool failed: ${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * A run of actions in one turn. Each model turn on a phone is a whole network round trip,
     * and most steps in a task are mechanical - tap, wait, look - so paying a round trip for
     * each of them is most of what makes an agent on a phone feel slow
     */
    private suspend fun batch(call: ToolCall, depth: Int): Outcome {
        if (depth >= MAX_DEPTH) return Outcome.Text("run_steps cannot be nested.")
        val steps = call.input["steps"] as? JSONArray
            ?: return Outcome.Text("run_steps needs steps, each {tool, args}.")

        val report = StringBuilder()
        val count = minOf(steps.length(), MAX_STEPS)
        if (steps.length() > MAX_STEPS) {
            report.appendLine("Only the first $MAX_STEPS steps were run; send the rest separately.")
        }

        for (index in 0 until count) {
            val step = steps.optJSONObject(index) ?: continue
            val name = step.optString("tool")
            if (name !in Tools.batchable) {
                report.appendLine("${index + 1}. $name is not allowed in run_steps, so this batch stopped here.")
                break
            }

            val arguments = step.optJSONObject("args") ?: JSONObject()
            val outcome = execute(
                ToolCall("${call.id}_$index", name, Projection.decode(arguments.toString())),
                depth + 1,
            )
            val text = Projection.resultText(outcome)
            report.appendLine("${index + 1}. $name -> $text")
            if (failed(text)) {
                report.appendLine("Stopped after step ${index + 1}. Look at the screen before continuing.")
                break
            }
        }
        return Outcome.Text(report.toString().trim())
    }

    /**
     * A script decides for itself what to do next, which a list of steps cannot. It gets no
     * privilege for it: every tool it calls comes back through [execute], so a tap inside a
     * loop is gated and recorded exactly like one the model asked for directly
     */
    private suspend fun script(call: ToolCall, depth: Int): Outcome {
        if (depth >= MAX_DEPTH) return Outcome.Text("run_script cannot be nested.")
        val source = call.input["script"] as? String
            ?: return Outcome.Text("run_script needs a script.")
        return interpret(call, depth, source, emptyMap())
    }

    /**
     * The same runtime, reached by name instead of by value: the model spends a dozen tokens
     * on a call instead of re-deriving thirty lines it already worked out once
     */
    private suspend fun saved(call: ToolCall, depth: Int): Outcome {
        if (depth >= MAX_DEPTH) return Outcome.Text("call_script cannot be nested.")
        val name = call.input["name"] as? String
            ?: return Outcome.Text("call_script needs a name.")
        val script = SavedScripts.byName(name) ?: return Outcome.Text(
            "No saved script called '$name'. Available: ${SavedScripts.names()}.",
        )
        val args = when (val raw = call.input["args"]) {
            is JSONObject -> Projection.decode(raw.toString())
            else -> emptyMap()
        }
        return interpret(call, depth, script.source, args)
    }

    private suspend fun interpret(
        call: ToolCall,
        depth: Int,
        source: String,
        args: Map<String, Any?>,
    ): Outcome {
        var step = 0
        val host = object : ScriptHost {
            override suspend fun tool(name: String, args: Map<String, Any?>): String {
                if (name !in Tools.scriptable) {
                    return "$name cannot be called from a script."
                }
                val outcome = execute(ToolCall("${call.id}_${step++}", name, args), depth + 1)
                return Projection.resultText(outcome)
            }

            override fun nodes(): List<UiNode> = service.nodes
        }
        return Outcome.Text(Scripts.run(source, host, args))
    }

    /**
     * Blunt on purpose. The failure that matters in a batch is a stale node index, and every
     * tool here reports it in words instead of a status code
     */
    private fun failed(result: String): Boolean =
        FAILURE_WORDS.any { result.contains(it, ignoreCase = true) }

    /** Long results are truncated before they reach the model */
    private fun cap(outcome: Outcome): Outcome {
        if (outcome !is Outcome.Text || outcome.text.length <= MAX_RESULT_CHARS) return outcome
        return Outcome.Text(
            outcome.text.take(MAX_RESULT_CHARS) +
                "\n[…cut off at $MAX_RESULT_CHARS characters. Narrow it down and look again.]",
        )
    }

    private fun timeoutFor(name: String): Long =
        if (McpServers.isMcpTool(name)) REMOTE_TIMEOUT_MS else LOCAL_TIMEOUT_MS

    /** What the user is asked about. It has to read as the thing itself, not as an index */
    private fun approvalDetail(call: ToolCall, detail: String, packageName: String?): String {
        val label = (call.input["node"] as? Number)?.let { service.describeNode(it.toInt()) }
        val target = when {
            label != null -> "\"$label\""
            detail.isNotBlank() -> detail
            else -> ""
        }
        val server = McpServers.serverFor(call.name)?.name
        val where = server?.let { " on $it" } ?: packageName?.let { " in $it" }.orEmpty()
        return (target + where).trim().ifBlank { call.name }
    }

    fun describe(call: ToolCall): String = with(call.input) {
        when (call.name) {
            "tap" -> this["node"]?.let { node ->
                service.describeNode((node as? Number)?.toInt() ?: -1)
                    ?.let { "node $node (\"$it\")" } ?: "node $node"
            } ?: "${this["x"]},${this["y"]}"
            // A password is the tool's argument, not the transcript's business
            "type_text" -> if (service.isSecretTarget((this["node"] as? Number)?.toInt())) {
                "(hidden)"
            } else {
                "\"${this["text"]}\""
            }
            "press" -> this["key"].toString()
            "open_app" -> this["app"].toString()
            "swipe" -> "${this["x1"]},${this["y1"]} -> ${this["x2"]},${this["y2"]}"
            "wait" -> "${this["ms"] ?: 1000}ms"
            "skill" -> this["name"].toString()
            "ask_user_question" -> this["question"].toString().take(60)
            "session_search" -> "\"${this["query"]}\""
            BATCH -> "${(this["steps"] as? JSONArray)?.length() ?: 0} steps"
            CALL_SCRIPT -> listOfNotNull(
                this["name"] as? String,
                (this["args"] as? JSONObject)?.takeIf { it.length() > 0 }?.toString()?.take(48),
            ).joinToString(" ")
            SCRIPT -> (this["script"] as? String)?.let { source ->
                val lines = source.trim().lines()
                "${lines.size} lines: ${lines.first().trim().take(48)}"
            } ?: "a script"
            "remember", "forget" -> "\"${this["note"]}\""
            else -> if (McpServers.isMcpTool(call.name)) {
                entries.take(2).joinToString(" ") { "${it.key}=${it.value}" }.take(60)
            } else {
                ""
            }
        }
    }

    private companion object {
        const val TAG = "ToolPipeline"
        const val BATCH = "run_steps"
        const val SCRIPT = "run_script"
        const val CALL_SCRIPT = "call_script"
        const val MAX_STEPS = 6
        const val MAX_DEPTH = 1
        const val MAX_RESULT_CHARS = 12_000
        const val LOCAL_TIMEOUT_MS = 60_000L
        const val REMOTE_TIMEOUT_MS = 120_000L
        val NEEDS_SCREEN = setOf(
            "tap", "swipe", "type_text", "press", "run_steps", "run_script", "call_script",
        )
        val FAILURE_WORDS = listOf(
            "No node", "Failed", "was cancelled", "not connected", "needs", "Unknown tool",
            "rejected", "refused", "too long",
        )
    }
}
