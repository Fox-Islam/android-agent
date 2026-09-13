package com.foxislam.androidagent.agent

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.foxislam.androidagent.BuildConfig
import com.foxislam.androidagent.SettingsStore
import com.foxislam.androidagent.control.ControlService
import com.foxislam.androidagent.mcp.McpServers
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A report you can send someone.
 *
 * There is no telemetry and no backend, so a bug report would otherwise be "it stopped
 * working". This puts the run, the settings that shaped it and the state of the permissions
 * into one file the user chooses to share.
 *
 * What it must never contain: the API key, MCP bearer tokens, or anything typed into a
 * password field. Those are stripped here instead of at the point of reading, so a new
 * caller cannot forget
 */
object Diagnostics {

    fun write(context: Context, threadId: String?): File? {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        // No spaces or colons: the name travels through share targets, shells and file
        // pickers
        val file = File(dir, "android-agent-${fileStamp()}.txt")

        return runCatching {
            file.writeText(report(context, threadId))
            file
        }.getOrElse {
            Log.w(TAG, "could not write diagnostics", it)
            null
        }
    }

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Android Agent diagnostics")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(Intent.createChooser(intent, "Send diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "nothing could share it", it) }
    }

    private fun report(context: Context, threadId: String?): String {
        val store = SettingsStore.get(context)
        val notifications = context.getSystemService(NotificationManager::class.java)
            ?.areNotificationsEnabled() ?: false

        return buildString {
            appendLine("Android Agent diagnostics")
            appendLine(stamp())
            appendLine()

            appendLine("## App")
            appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()

            appendLine("## Permissions")
            appendLine("accessibility service: ${if (ControlService.isConnected) "connected" else "not connected"}")
            appendLine("status indicator: ${if (OverlayIndicator.canShow(context)) "allowed" else "not allowed"}")
            appendLine("notifications: ${if (notifications) "on" else "off"}")
            appendLine()

            appendLine("## Settings")
            appendLine("endpoint: ${store.apiUrl}")
            appendLine("api key: ${if (store.apiKey.isBlank()) "not set" else "set (not included)"}")
            appendLine("model: ${store.selectedModel}")
            appendLine("cheap model: ${store.fastModel.ifBlank { "none" }}")
            appendLine("reasoning: ${store.reasoning.label}")
            appendLine("approvals: ${store.approvalMode.name}")
            appendLine("context limit: ${store.contextLimit}")
            appendLine()

            appendLine("## Rules")
            appendLine(Hooks.text.value.ifBlank { "(none)" })
            appendLine()

            appendLine("## Playbooks")
            val skills = Skills.all.value
            // Names and triggers only: a playbook body is the user's own writing about their
            // own apps, and none of it is needed to understand a bug
            appendLine(skills.joinToString("\n") { "- ${it.name} (${it.trigger})" }.ifBlank { "(none)" })
            appendLine()

            appendLine("## MCP servers")
            val servers = McpServers.all.value
            appendLine(
                servers.joinToString("\n") { server ->
                    val tools = McpServers.tools.value[server.id]?.size ?: 0
                    val status = McpServers.status.value[server.id] ?: "not refreshed"
                    "- ${server.name} ${server.url} enabled=${server.enabled} " +
                        "token=${if (server.token.isBlank()) "none" else "set (not included)"} " +
                        "tools=$tools status=$status"
                }.ifBlank { "(none)" },
            )
            appendLine()

            appendLine("## Chat")
            val thread = threadId?.let { Chats.ensureLoaded(it) }
            if (thread == null) {
                appendLine("(no chat selected)")
                return@buildString
            }
            appendLine("id: ${thread.id}")
            appendLine("cost: $${"%.4f".format(thread.costUsd)}")
            appendLine("tokens: ${thread.meta.promptTokens} in, ${thread.meta.completionTokens} out")
            appendLine("plan mode: ${thread.planMode}")
            appendLine("interrupted: ${thread.interrupted}")
            appendLine()
            thread.events.forEach { appendLine(line(it)) }
        }
    }

    private fun line(event: SessionEvent): String = when (event) {
        is SessionEvent.UserMessage -> "user: ${event.text}"
        is SessionEvent.AssistantMessage ->
            "agent: ${event.text.orEmpty()} ${event.calls.joinToString { it.name }}".trim()
        // The detail was already hidden when it was recorded; this is the second lock on it
        is SessionEvent.ToolStarted ->
            "tool: ${event.name} ${if (event.name == "type_text") "(hidden)" else event.detail}"
        is SessionEvent.ToolFinished -> "  -> ${event.result.take(RESULT_CHARS)}"
        is SessionEvent.Note -> "note: ${event.text}"
        is SessionEvent.Finished -> "finished: ${event.text}"
        is SessionEvent.Failed -> "failed: ${event.text}"
        is SessionEvent.AskRaised -> "ask: ${event.tool} ${event.detail} (${event.reason})"
        is SessionEvent.AskDecided -> "  -> ${event.decision}"
        is SessionEvent.PlanProposed -> "plan: ${event.steps.joinToString("; ")}"
        is SessionEvent.PlanDecided -> "  -> ${event.decision}"
        is SessionEvent.QuestionAsked -> "question: ${event.text}"
        is SessionEvent.QuestionAnswered -> "  -> ${event.answer}"
        is SessionEvent.TodosWritten ->
            "todos: " + event.items.joinToString("; ") { "${it.state} ${it.text}" }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())

    private fun fileStamp(): String =
        SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.UK).format(Date())

    private const val TAG = "Diagnostics"
    private const val RESULT_CHARS = 300
}
