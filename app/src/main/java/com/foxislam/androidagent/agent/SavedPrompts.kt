package com.foxislam.androidagent.agent

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SavedPrompt(val id: String, val text: String)

/**
 * Tasks done more than once. Choosing one opens a fresh chat with the text already in the
 * chat input, not sent: these are usually a starting point that needs a detail changed before
 * it is right
 */
object SavedPrompts {

    private val store = JsonListStore("saved_prompts.json", SavedPrompt.serializer(), "saved prompts") { it.id }

    val all: StateFlow<List<SavedPrompt>> get() = store.all

    fun load(context: Context) = store.load(context)

    fun add(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        store.value = store.value + SavedPrompt(UUID.randomUUID().toString(), trimmed)
    }

    fun update(id: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        store.value = store.value.map { if (it.id == id) it.copy(text = trimmed) else it }
    }

    fun delete(id: String) = store.delete(id)

    fun merge(incoming: List<SavedPrompt>) = store.merge(incoming)
}
