package com.foxislam.androidagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.foxislam.androidagent.agent.AgentSession
import com.foxislam.androidagent.agent.Approvals
import com.foxislam.androidagent.agent.Chats
import com.foxislam.androidagent.agent.Entry
import com.foxislam.androidagent.agent.OpenRouterBackend
import com.foxislam.androidagent.agent.Projection
import com.foxislam.androidagent.agent.Questions
import com.foxislam.androidagent.agent.ToolCall
import com.foxislam.androidagent.agent.ToolPipeline
import com.foxislam.androidagent.agent.Tools
import com.foxislam.androidagent.control.ControlService
import com.foxislam.androidagent.mcp.McpServers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug-only. The seam the eval harness drives the app through, so a prompt change can be
 * tested against a real device:
 *
 *   adb shell am broadcast -a com.foxislam.androidagent.EVAL \
 *     -n com.foxislam.androidagent/.EvalReceiver --es op run --es prompt "open settings"
 *
 * Ops: config (point at a model and an endpoint), run (start a task in a fresh chat),
 * dump (write the whole transcript out), stop
 */
class EvalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Chats.load(context)
        when (intent.getStringExtra("op")) {
            "config" -> config(context, intent.getStringExtra("json"))
            "run" -> run(context, intent.getStringExtra("prompt").orEmpty())
            "answer" -> answer(intent.getStringExtra("text").orEmpty())
            "mcp" -> mcp(
                intent.getStringExtra("name").orEmpty(),
                intent.getStringExtra("url").orEmpty(),
                intent.getStringExtra("token").orEmpty(),
            )
            "pipeline" -> pipeline(
                context,
                intent.getStringExtra("tool").orEmpty(),
                intent.getStringExtra("args") ?: "{}",
            )
            "dump" -> dump(context)
            "stop" -> AgentSession.stop()
            else -> Log.e(TAG, "EVAL unknown op")
        }
    }

    private fun config(context: Context, raw: String?) {
        val json = runCatching { JSONObject(raw.orEmpty()) }.getOrElse {
            Log.e(TAG, "EVAL config is not JSON")
            return
        }
        val store = SettingsStore.get(context)
        json.optString("url").takeIf { it.isNotBlank() }?.let { store.apiUrl = it }
        json.optString("key").takeIf { it.isNotBlank() }?.let { store.apiKey = it }
        json.optString("models").takeIf { it.isNotBlank() }?.let { store.modelsRaw = it }
        json.optString("model").takeIf { it.isNotBlank() }?.let { store.selectedModel = it }
        // Blank clears it, so a suite can test routing both ways
        if (json.has("fast")) store.fastModel = json.optString("fast")
        json.optInt("limit").takeIf { it > 0 }?.let { store.contextLimit = it }
        json.optString("approvals").takeIf { it.isNotBlank() }?.let {
            store.approvalMode = runCatching { Approvals.Mode.valueOf(it) }.getOrDefault(Approvals.Mode.AUTO)
        }
        Log.i(
            TAG,
            "EVAL configured model=${store.selectedModel} fast=${store.fastModel} " +
                "approvals=${store.approvalMode}",
        )
    }

    private fun run(context: Context, prompt: String) {
        if (prompt.isBlank()) {
            Log.e(TAG, "EVAL run needs a prompt")
            return
        }
        val store = SettingsStore.get(context)
        val threadId = Chats.newChat()
        AgentSession.start(
            context = context.applicationContext,
            threadId = threadId,
            prompt = prompt,
            backend = OpenRouterBackend(
                endpoint = store.apiUrl,
                apiKey = store.apiKey,
                model = store.selectedModel,
                reasoning = store.reasoning,
                fastModel = store.fastModel,
            ),
            contextLimit = store.contextLimit,
            fastModel = store.fastModel,
        )
        Log.i(TAG, "EVAL started $threadId")
    }

    /**
     * Runs one tool through the whole pipeline - rules, approval, timeout, recording - on the
     * active chat. The TOOL receiver bypasses all of that, so this is how the pipeline
     * around a tool gets tested without a model in the loop
     */
    private fun pipeline(context: Context, tool: String, args: String) {
        val service = ControlService.instance
        if (service == null || tool.isBlank()) {
            Log.e(TAG, "EVAL pipeline needs the service and a tool")
            return
        }
        val threadId = Chats.active()?.id ?: Chats.newChat()
        val pipeline = ToolPipeline(
            tools = Tools(service, context.applicationContext, threadId),
            service = service,
            context = context.applicationContext,
            threadId = threadId,
            onStatus = {},
        )
        service.scope.launch {
            val call = ToolCall("debug_${System.currentTimeMillis()}", tool, Projection.decode(args))
            val outcome = pipeline.execute(call)
            Log.i(TAG, "EVAL pipeline $tool -> ${Projection.resultText(outcome).take(300)}")
        }
    }

    /** Registers an MCP server and asks it what it can do, without going through settings */
    private fun mcp(name: String, url: String, token: String) {
        if (name.isBlank() || !url.startsWith("http")) {
            Log.e(TAG, "EVAL mcp needs a name and an http url")
            return
        }
        val scope = ControlService.instance?.scope
        if (scope == null) {
            Log.e(TAG, "EVAL mcp needs the accessibility service to be running")
            return
        }
        val id = McpServers.add(name, url, token)
        scope.launch { Log.i(TAG, "EVAL mcp $name: ${McpServers.refresh(id)}") }
    }

    /** Answers whatever the agent is currently asking, so a suite can run unattended */
    private fun answer(text: String) {
        val question = Questions.pending.value
        if (question == null) {
            Log.e(TAG, "EVAL nothing is being asked")
            return
        }
        Questions.answer(question.id, text)
        Log.i(TAG, "EVAL answered \"$text\"")
    }

    /**
     * The whole transcript, on disk, in a shape a scorer can read - instead of scraped back
     * out of logcat, which truncates and interleaves
     */
    private fun dump(context: Context) {
        val thread = Chats.active() ?: Chats.threads.value.firstOrNull()
        if (thread == null) {
            Log.e(TAG, "EVAL no chats to dump")
            return
        }

        val entries = JSONArray()
        thread.entries.forEach { entry ->
            entries.put(
                JSONObject()
                    .put("kind", entry::class.simpleName)
                    .put(
                        "text",
                        when (entry) {
                            is Entry.Prompt -> entry.text
                            is Entry.Said -> entry.text
                            is Entry.Finished -> entry.text
                            is Entry.Failed -> entry.text
                            is Entry.Note -> entry.text
                            // This file is written to shared storage, so nothing typed into a
                            // password field goes in it whatever the transcript shows
                            is Entry.Tool -> {
                                val detail = if (entry.name == "type_text") "(hidden)" else entry.detail
                                "${entry.name} $detail -> ${entry.result.orEmpty()}"
                            }
                            is Entry.Ask -> "${entry.tool} ${entry.detail} -> ${entry.decision}"
                            is Entry.Plan -> entry.steps.joinToString("; ") + " -> ${entry.decision}"
                            is Entry.Question -> "${entry.text} -> ${entry.answer.orEmpty()}"
                            is Entry.Todos -> entry.items.joinToString("; ") { "${it.state}: ${it.text}" }
                        },
                    )
                    .put("tool", (entry as? Entry.Tool)?.name),
            )
        }

        val report = JSONObject()
            .put("id", thread.id)
            .put("title", thread.displayTitle)
            .put("running", AgentSession.isRunning(thread.id))
            .put("asking", Questions.pending.value?.text.orEmpty())
            .put("promptTokens", thread.meta.promptTokens)
            .put("completionTokens", thread.meta.completionTokens)
            .put("costUsd", thread.meta.costUsd)
            .put("entries", entries)

        // The app's own external directory: readable over adb and needing no permission -
        // clearing app data revokes the one Downloads would want
        val target = File(context.getExternalFilesDir(null), "agent-eval.json")
        runCatching { target.writeText(report.toString(2)) }
            .onSuccess { Log.i(TAG, "EVAL dumped ${entries.length()} entries to $target") }
            .onFailure { Log.e(TAG, "EVAL dump failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "DebugTool"
    }
}
