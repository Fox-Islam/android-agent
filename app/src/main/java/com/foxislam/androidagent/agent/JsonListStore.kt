package com.foxislam.androidagent.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A list of [T] mirrored to one JSON file in the app's own storage: read once at [load], and
 * written again on every change to [value]. The stores built on it differ only in their type,
 * their file name and the name they log under
 */
class JsonListStore<T>(
    private val fileName: String,
    private val serializer: KSerializer<T>,
    private val label: String,
    private val id: (T) -> String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _all = MutableStateFlow<List<T>>(emptyList())
    private var file: File? = null

    val all: StateFlow<List<T>> = _all.asStateFlow()

    /** Assigning also writes the file, so nothing can change the list without saving it */
    var value: List<T>
        get() = _all.value
        set(next) {
            _all.value = next
            val target = file ?: return
            runCatching { target.writeText(json.encodeToString(ListSerializer(serializer), next)) }
                .onFailure { Log.w(TAG, "could not write $label", it) }
        }

    fun load(context: Context) {
        if (file != null) return
        val target = File(context.applicationContext.filesDir, fileName)
        file = target
        if (!target.exists()) return
        runCatching { json.decodeFromString(ListSerializer(serializer), target.readText()) }
            .onSuccess { _all.value = it }
            .onFailure { Log.w(TAG, "could not read $label", it) }
    }

    fun delete(entryId: String) {
        value = value.filterNot { id(it) == entryId }
    }

    /**
     * Entries in [incoming] replace those with the same id, and the rest are added. Anything
     * [incoming] does not name is kept, so importing a backup onto a phone already in use
     * never drops what was only here
     */
    fun merge(incoming: List<T>) {
        if (incoming.isEmpty()) return
        val byId = value.associateBy(id).toMutableMap()
        incoming.forEach { byId[id(it)] = it }
        value = byId.values.toList()
    }

    private companion object {
        const val TAG = "JsonListStore"
    }
}
