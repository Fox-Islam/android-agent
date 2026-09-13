package com.foxislam.androidagent.agent

import android.util.Log
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Chats on disk: one directory each, a small metadata file rewritten atomically, and an
 * append-only log of events
 *
 * A directory per chat keeps a kill mid-write - an ordinary thing to happen to a background
 * process on a phone - to the last line of one log, and keeps the cost of appending a line
 * the same however much the chat already holds
 */
class SessionStore(private val root: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Every chat's metadata and nothing else. Reading the logs here would make startup cost
     * grow with the size of the whole history; a log is read when its chat is opened
     */
    fun load(): List<ChatMeta> {
        if (!root.exists()) return emptyList()
        return root.listFiles { file -> file.isDirectory }
            .orEmpty()
            .mapNotNull(::readMeta)
            .sortedByDescending { it.updatedAt }
    }

    private fun readMeta(dir: File): ChatMeta? {
        val meta = runCatching { json.decodeFromString(ChatMeta.serializer(), File(dir, META).readText()) }
            .getOrElse {
                Log.w(TAG, "unreadable chat ${dir.name}", it)
                return null
            }
        // The log is appended to constantly and the metadata is not, so the file itself
        // answers "when did anything last happen in this chat"
        val log = File(dir, LOG)
        return if (log.exists()) meta.copy(updatedAt = maxOf(meta.updatedAt, log.lastModified())) else meta
    }

    fun events(threadId: String): List<SessionEvent> {
        val log = File(File(root, threadId), LOG)
        if (!log.exists()) return emptyList()

        val events = mutableListOf<SessionEvent>()
        runCatching {
            log.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                // A line that will not parse was half-written when the process died. It is
                // dropped and the rest of the chat is kept
                runCatching { json.decodeFromString(SessionEvent.serializer(), line) }
                    .onSuccess { events += it }
                    .onFailure { Log.w(TAG, "skipping a damaged event in $threadId") }
            }
        }.onFailure { Log.w(TAG, "could not read the log for $threadId", it) }
        return events
    }

    /**
     * Keeps a long-lived chat's log from growing without end. The oldest events go, since
     * they are the ones neither the model nor the reader is going to reach for, and the file
     * is republished by rename so a kill mid-trim cannot leave half a log
     */
    fun trim(threadId: String, keep: Int): List<SessionEvent>? {
        val log = File(File(root, threadId), LOG)
        if (!log.exists() || log.length() < MAX_LOG_BYTES) return null

        val kept = events(threadId).takeLast(keep)
        val temp = File(log.parentFile, "$LOG.tmp")
        return runCatching {
            temp.writeText(kept.joinToString("") { json.encodeToString(SessionEvent.serializer(), it) + "\n" })
            if (!temp.renameTo(log)) throw IllegalStateException("could not republish the log")
            Log.i(TAG, "trimmed $threadId to the last $keep events")
            kept
        }.getOrElse {
            Log.w(TAG, "could not trim $threadId", it)
            temp.delete()
            null
        }
    }

    fun writeMeta(meta: ChatMeta) {
        val dir = File(root, meta.id).apply { mkdirs() }
        val target = File(dir, META)
        val temp = File(dir, "$META.tmp")
        runCatching {
            temp.writeText(json.encodeToString(ChatMeta.serializer(), meta))
            if (!temp.renameTo(target)) {
                // Rename is atomic; the copy is only a fallback
                target.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure { Log.w(TAG, "could not write meta for ${meta.id}", it) }
    }

    fun append(threadId: String, event: SessionEvent) {
        val dir = File(root, threadId).apply { mkdirs() }
        runCatching {
            File(dir, LOG).appendText(json.encodeToString(SessionEvent.serializer(), event) + "\n")
        }.onFailure { Log.w(TAG, "could not append to $threadId", it) }
    }

    /** Everything up to, but not including, [beforeEventId]. What a fork is made of */
    fun copyInto(threadId: String, events: List<SessionEvent>) {
        val dir = File(root, threadId).apply { mkdirs() }
        runCatching {
            File(dir, LOG).writeText(
                events.joinToString("") { json.encodeToString(SessionEvent.serializer(), it) + "\n" },
            )
        }.onFailure { Log.w(TAG, "could not seed $threadId", it) }
    }

    fun delete(threadId: String) {
        runCatching { File(root, threadId).deleteRecursively() }
            .onFailure { Log.w(TAG, "could not delete $threadId", it) }
    }

    /**
     * Chats written by an older version, which kept every one of them in a single file as a
     * list of rendered entries. They are converted once and the old file is left in place,
     * renamed, so a downgrade or a bad conversion is recoverable
     */
    fun migrate(legacy: File) {
        if (!legacy.exists()) return
        val threads = runCatching {
            json.decodeFromString(ListSerializer(LegacyThread.serializer()), legacy.readText())
        }.getOrElse {
            Log.w(TAG, "could not read the old chats file; leaving it alone", it)
            return
        }

        threads.forEach { thread ->
            val events = thread.entries.flatMap(LegacyEntry::asEvents)
            writeMeta(
                ChatMeta(
                    id = thread.id,
                    title = thread.title,
                    updatedAt = thread.updatedAt,
                    promptTokens = thread.promptTokens,
                    completionTokens = thread.completionTokens,
                    costUsd = thread.costUsd,
                    contextTokens = thread.contextTokens,
                    planMode = thread.planMode,
                ),
            )
            copyInto(thread.id, events)
            // The log's own timestamp is what orders the chat list, and writing it now would
            // tell every migrated chat it was touched this second
            File(File(root, thread.id), LOG).setLastModified(thread.updatedAt.coerceAtLeast(1))
        }

        legacy.renameTo(File(legacy.parentFile, "${legacy.name}.migrated"))
        Log.i(TAG, "migrated ${threads.size} chats out of the old single-file format")
    }

    private companion object {
        const val TAG = "SessionStore"

        /** About a thousand steps of a very long chat. Beyond this the oldest go */
        const val MAX_LOG_BYTES = 2L * 1024 * 1024
        const val META = "meta.json"
        const val LOG = "session.jsonl"
    }
}
