package com.foxislam.androidagent.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Durable notes that outlive a chat: how this phone is set up, which app the user means by
 * a nickname, preferences learned the hard way. Appended to the system prompt on every turn,
 * and editable from both sides - the user in Settings, the agent through its tools
 */
object Memory {

    private val _notes = MutableStateFlow("")
    val notes: StateFlow<String> = _notes.asStateFlow()

    private var file: File? = null

    fun load(context: Context) {
        if (file != null) return
        val target = File(context.applicationContext.filesDir, "memory.md")
        file = target
        if (target.exists()) {
            runCatching { _notes.value = target.readText() }
                .onFailure { Log.w(TAG, "could not read memory", it) }
        }
    }

    fun replace(content: String) {
        _notes.value = content
        persist()
    }

    /** Appends one note, ignoring an exact duplicate so a loop cannot bloat the file */
    fun remember(note: String): String {
        val trimmed = note.trim()
        if (trimmed.isEmpty()) return "Nothing to remember."
        val existing = _notes.value.lines().map(String::trim)
        if (trimmed in existing) return "Already remembered."
        _notes.value = (_notes.value.trimEnd() + "\n- " + trimmed).trim()
        persist()
        return "Remembered."
    }

    fun forget(match: String): String {
        val needle = match.trim()
        if (needle.isEmpty()) return "Nothing to forget."
        val kept = _notes.value.lines().filterNot { it.contains(needle, ignoreCase = true) }
        val removed = _notes.value.lines().size - kept.size
        if (removed == 0) return "No note mentioned \"$needle\"."
        _notes.value = kept.joinToString("\n").trim()
        persist()
        return "Forgot $removed note(s)."
    }

    /** The block appended to the system prompt; empty when there are no notes */
    fun promptSection(): String {
        val content = _notes.value.trim()
        if (content.isEmpty()) return ""
        return "\n\nWhat you have been asked to remember about this phone and this user:\n$content"
    }

    private fun persist() {
        val target = file ?: return
        runCatching { target.writeText(_notes.value) }
            .onFailure { Log.w(TAG, "could not write memory", it) }
    }

    private const val TAG = "Memory"
}
