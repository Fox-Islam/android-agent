package com.foxislam.androidagent.agent

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One task the agent worked out how to do, kept.
 *
 * A script written into a `run_script` call is gone the moment the chat scrolls: doing the
 * same thing tomorrow means writing the same thirty lines again, and paying to think them
 * through again. Saved, it becomes a name and a sentence in the prompt, and calling it costs
 * a dozen tokens
 */
/**
 * One value a script expects, declared so it can be asked for properly.
 *
 * Without this the only way to run a script by hand is to type its arguments as JSON, which
 * nobody is going to do on a phone keyboard. With it, `on` is a switch and `label` is a text
 * field, and running a saved script is a form
 */
@Serializable
data class ScriptParam(
    val name: String,
    /** [TEXT], [BOOLEAN] or [NUMBER]. Anything unrecognised is treated as text */
    val type: String = TEXT,
    /** What to call it on screen. Falls back to the name */
    val label: String = "",
    val default: String = "",
) {
    val title: String get() = label.ifBlank { name }

    companion object {
        const val TEXT = "text"
        const val BOOLEAN = "boolean"
        const val NUMBER = "number"
    }
}

@Serializable
data class SavedScript(
    val id: String,
    val name: String,
    /** What it does and what it expects in `args` - this is what the model reads to choose */
    val description: String,
    val source: String,
    val params: List<ScriptParam> = emptyList(),
) {
    /**
     * What to put on the form. A script saved before it declared anything still gets usable
     * fields: every `args.x` it reads is a value it wants, and the source is right there
     */
    fun fields(): List<ScriptParam> = params.ifEmpty {
        ARGS_READ.findAll(source)
            .mapNotNull { it.groupValues[1].ifBlank { it.groupValues[2] }.ifBlank { null } }
            .distinct()
            .map { ScriptParam(it) }
            .toList()
    }

}

/** `args.label` and `args["label"]`, which are the two ways a script reads one */
private val ARGS_READ = Regex("""args\.([A-Za-z_][A-Za-z0-9_]*)|args\[\s*"([^"]+)"\s*]""")

object SavedScripts {

    private val store =
        JsonListStore("saved_scripts.json", SavedScript.serializer(), "saved scripts") { it.id }

    val all: StateFlow<List<SavedScript>> get() = store.all

    fun load(context: Context) = store.load(context)

    /**
     * Saving the same name twice replaces it, because the second attempt is nearly always
     * the first one corrected. Returns true when something was replaced
     */
    fun save(
        name: String,
        description: String,
        source: String,
        params: List<ScriptParam> = emptyList(),
    ): Boolean {
        val clean = name.trim()
        val existing = store.value.firstOrNull { it.name.equals(clean, ignoreCase = true) }
        val entry = SavedScript(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = clean,
            description = description.trim(),
            source = source.trim(),
            params = params,
        )
        store.value = if (existing == null) {
            store.value + entry
        } else {
            store.value.map { if (it.id == existing.id) entry else it }
        }
        return existing != null
    }

    fun update(id: String, name: String, description: String, source: String) {
        // The params stay as they were: the editor does not offer them, so it must not
        // silently drop them when someone fixes a typo in the body
        store.value = store.value.map {
            if (it.id == id) {
                it.copy(name = name.trim(), description = description.trim(), source = source.trim())
            } else {
                it
            }
        }
    }

    fun delete(id: String) = store.delete(id)

    fun byName(name: String): SavedScript? {
        val clean = name.trim()
        return store.value.firstOrNull { it.name.equals(clean, ignoreCase = true) }
            ?: store.value.firstOrNull { it.name.contains(clean, ignoreCase = true) }
    }

    fun names(): String = store.value.joinToString(", ") { it.name }.ifBlank { "none" }

    /**
     * The always-on part: what exists and what it is for, and nothing else. The body stays
     * on disk until something calls it, as playbooks do
     */
    fun catalogue(): String {
        val scripts = store.value
        if (scripts.isEmpty()) return ""
        return "\n\nScripts you have saved and can run with call_script, by name:\n" +
            scripts.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }

    /**
     * What someone can type on a phone. JSON is the wire format between the model and the
     * tool, but typing braces on a soft keyboard is not reasonable, so one `name = value`
     * per line, and JSON only if it is offered.
     *
     * Values are read the way they look: true and false are booleans, digits are numbers,
     * everything else is the text as written
     */
    fun parseArgs(text: String): Map<String, Any?> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyMap()
        if (trimmed.startsWith("{")) return Projection.decode(trimmed)
        return trimmed.lines().mapNotNull { line ->
            val at = line.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
            val key = line.take(at).trim().ifEmpty { return@mapNotNull null }
            key to line.drop(at + 1).trim().asValue()
        }.toMap()
    }

    /** A typed value out of what someone typed, so "true" in a text field is a boolean */
    fun coerce(text: String): Any? = text.trim().asValue()

    private fun String.asValue(): Any? = when {
        equals("true", ignoreCase = true) -> true
        equals("false", ignoreCase = true) -> false
        toLongOrNull() != null -> toLong()
        toDoubleOrNull() != null -> toDouble()
        else -> this
    }

    fun merge(incoming: List<SavedScript>) = store.merge(incoming)
}
