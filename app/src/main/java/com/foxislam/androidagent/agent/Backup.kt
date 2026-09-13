package com.foxislam.androidagent.agent

import android.content.Context
import android.util.Base64
import android.util.Log
import com.foxislam.androidagent.SettingsStore
import com.foxislam.androidagent.mcp.McpServer
import com.foxislam.androidagent.mcp.McpServers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Everything the user has, in one file.
 *
 * There is no backend, so this is the only route through a reinstall or onto a second phone:
 * the chats, the scripts, the playbooks, the saved tasks, the rules, the memory, the servers
 * and the settings, written out and read back.
 *
 * Importing merges instead of replacing. A backup restored onto an empty install is a full
 * restore; the same file opened on a phone already in use adds what is missing and updates
 * what shares an id, so nothing that was only on this device is lost by opening the wrong
 * file. The one exception is settings, which are a single set of values and are taken whole
 */
@Serializable
data class BackupChat(val meta: ChatMeta, val events: List<SessionEvent>)

@Serializable
data class BackupImage(val name: String, val base64: String)

@Serializable
data class BackupSettings(
    val apiUrl: String = "",
    val apiKey: String = "",
    val modelsRaw: String = "",
    val selectedModel: String = "",
    val reasoning: String = "",
    val approvalMode: String = "",
    val contextLimit: Int = 0,
    val fastModel: String = "",
)

@Serializable
data class BackupFile(
    val format: Int = Backup.FORMAT,
    val app: String = "android-agent",
    val exportedAt: Long = 0,
    /** Absent when the user chose not to include the key and server tokens */
    val settings: BackupSettings? = null,
    val memory: String = "",
    val rules: String = "",
    val prompts: List<SavedPrompt> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val scripts: List<SavedScript> = emptyList(),
    val servers: List<McpServer> = emptyList(),
    val chats: List<BackupChat> = emptyList(),
    val images: List<BackupImage> = emptyList(),
)

data class Restored(
    val chats: Int,
    val scripts: Int,
    val prompts: Int,
    val skills: Int,
    val servers: Int,
    val settings: Boolean,
) {
    val summary: String
        get() {
            val parts = buildList {
                if (chats > 0) add(counted(chats, "chat"))
                if (scripts > 0) add(counted(scripts, "script"))
                if (prompts > 0) add(counted(prompts, "saved task"))
                if (skills > 0) add(counted(skills, "playbook"))
                if (servers > 0) add(counted(servers, "MCP server"))
                if (settings) add("your settings")
            }
            if (parts.isEmpty()) return "That file had nothing new in it."
            return "Restored " + parts.joinToString(", ").replaceLast(", ", " and ") + "."
        }

    private fun counted(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"

    private fun String.replaceLast(find: String, with: String): String {
        val at = lastIndexOf(find)
        return if (at < 0) this else take(at) + with + substring(at + find.length)
    }
}

object Backup {

    /** Bumped only when an older file would be read wrongly instead of just incompletely */
    const val FORMAT = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun filename(): String = "android-agent-backup-${System.currentTimeMillis()}.json"

    /**
     * [withSecrets] carries the API key and any MCP bearer tokens. A restore without them
     * leaves the phone unable to talk to a provider until a key is typed in, so it is the
     * default, but it does put the key in a file, which is why it is a choice
     */
    fun export(context: Context, withSecrets: Boolean): String {
        val app = context.applicationContext
        val store = SettingsStore.get(app)
        val sessions = SessionStore(File(app.filesDir, "chats"))

        val chats = sessions.load().map { meta ->
            BackupChat(meta = meta, events = sessions.events(meta.id))
        }

        return json.encodeToString(
            BackupFile.serializer(),
            BackupFile(
                exportedAt = System.currentTimeMillis(),
                settings = BackupSettings(
                    apiUrl = store.apiUrl,
                    apiKey = if (withSecrets) store.apiKey else "",
                    modelsRaw = store.modelsRaw,
                    selectedModel = store.selectedModel,
                    reasoning = store.reasoning.name,
                    approvalMode = store.approvalMode.name,
                    contextLimit = store.contextLimit,
                    fastModel = store.fastModel,
                ),
                memory = Memory.notes.value,
                rules = Hooks.text.value,
                prompts = SavedPrompts.all.value,
                skills = Skills.all.value,
                scripts = SavedScripts.all.value,
                servers = McpServers.all.value.map {
                    if (withSecrets) it else it.copy(token = "")
                },
                chats = chats,
                images = sharedImages(app),
            ),
        )
    }

    /** Throws [BackupError] with a readable message when the file is not one of ours */
    fun import(context: Context, text: String): Restored {
        val file = runCatching { json.decodeFromString(BackupFile.serializer(), text) }
            .getOrElse { throw BackupError("That file is not an Android Agent backup.") }
        if (file.format > FORMAT) {
            throw BackupError(
                "That backup was written by a newer version of the app. Update first, " +
                    "then import it.",
            )
        }

        val app = context.applicationContext
        restoreImages(app, file.images)

        val sessions = SessionStore(File(app.filesDir, "chats"))
        file.chats.forEach { chat ->
            // A chat that was mid-run when it was exported is not running here
            sessions.writeMeta(chat.meta.copy(running = false))
            sessions.copyInto(chat.meta.id, chat.events)
        }
        if (file.chats.isNotEmpty()) Chats.reload()

        if (file.memory.isNotBlank()) Memory.replace(merged(Memory.notes.value, file.memory))
        if (file.rules.isNotBlank()) Hooks.replace(merged(Hooks.text.value, file.rules))

        SavedPrompts.merge(file.prompts)
        Skills.merge(file.skills)
        SavedScripts.merge(file.scripts)
        McpServers.merge(file.servers)

        var settings = false
        file.settings?.let {
            applySettings(app, it)
            settings = true
        }

        return Restored(
            chats = file.chats.size,
            scripts = file.scripts.size,
            prompts = file.prompts.size,
            skills = file.skills.size,
            servers = file.servers.size,
            settings = settings,
        )
    }

    /**
     * Two bodies of free text, joined without duplicating what is already there. Replacing
     * outright would quietly delete notes the model had written on this phone
     */
    internal fun merged(existing: String, incoming: String): String {
        if (existing.isBlank()) return incoming.trim()
        val have = existing.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val extra = incoming.lines().map { it.trim() }
            .filter { it.isNotEmpty() && it !in have }
        if (extra.isEmpty()) return existing
        return (existing.trimEnd() + "\n" + extra.joinToString("\n")).trim()
    }

    private fun applySettings(context: Context, values: BackupSettings) {
        val store = SettingsStore.get(context)
        values.apiUrl.takeIf { it.isNotBlank() }?.let { store.apiUrl = it }
        // Blank means the export left it out, which must not wipe a key already here
        values.apiKey.takeIf { it.isNotBlank() }?.let { store.apiKey = it }
        values.modelsRaw.takeIf { it.isNotBlank() }?.let { store.modelsRaw = it }
        values.selectedModel.takeIf { it.isNotBlank() }?.let { store.selectedModel = it }
        values.fastModel.let { store.fastModel = it }
        values.contextLimit.takeIf { it > 0 }?.let { store.contextLimit = it }
        runCatching { store.reasoning = Reasoning.valueOf(values.reasoning) }
        runCatching {
            store.approvalMode = Approvals.Mode.valueOf(values.approvalMode)
            Approvals.mode = store.approvalMode
        }
    }

    private fun sharedImages(context: Context): List<BackupImage> {
        val dir = File(context.filesDir, "shared")
        if (!dir.exists()) return emptyList()
        return dir.listFiles().orEmpty().mapNotNull { file ->
            runCatching {
                BackupImage(file.name, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            }.getOrNull()
        }
    }

    private fun restoreImages(context: Context, images: List<BackupImage>) {
        if (images.isEmpty()) return
        val dir = File(context.filesDir, "shared").apply { mkdirs() }
        images.forEach { image ->
            val target = File(dir, image.name)
            if (target.exists()) return@forEach
            runCatching { target.writeBytes(Base64.decode(image.base64, Base64.NO_WRAP)) }
                .onFailure { Log.w(TAG, "could not restore ${image.name}", it) }
        }
    }

    private const val TAG = "Backup"
}

class BackupError(message: String) : Exception(message)
