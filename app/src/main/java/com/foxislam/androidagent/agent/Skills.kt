package com.foxislam.androidagent.agent

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * What the agent knows about one app, kept out of the way until it is in that app.
 *
 * Memory is one paragraph that is true everywhere and therefore paid for on every turn.
 * A skill is the opposite: "the send button in this app is behind the ⋮ menu, and the
 * confirmation sheet takes two swipes to reach" matters in one app and is noise in every
 * other. Only the name and trigger are always in the prompt; the body arrives when the
 * trigger fires, or when the model asks for it by name
 */
@Serializable
data class Skill(
    val id: String,
    val name: String,
    /** Comma-separated package globs (`com.spotify.*`) and plain keywords (`groceries`) */
    val trigger: String,
    val body: String,
) {
    val packageGlobs: List<String>
        get() = triggers.filter { it.contains('.') || it.contains('*') }

    val keywords: List<String>
        get() = triggers.filterNot { it.contains('.') || it.contains('*') }

    private val triggers: List<String>
        get() = trigger.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun matches(packageName: String?, prompt: String): Boolean {
        val byPackage = packageName != null && packageGlobs.any { glob(it).matches(packageName) }
        val byKeyword = keywords.any { prompt.contains(it, ignoreCase = true) }
        return byPackage || byKeyword
    }

    private fun glob(pattern: String): Regex =
        Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
}

object Skills {

    private val store = JsonListStore("skills.json", Skill.serializer(), "skills") { it.id }

    val all: StateFlow<List<Skill>> get() = store.all

    fun load(context: Context) = store.load(context)

    fun add(name: String, trigger: String, body: String) {
        store.value = store.value + Skill(UUID.randomUUID().toString(), name.trim(), trigger.trim(), body.trim())
    }

    fun update(id: String, name: String, trigger: String, body: String) {
        store.value = store.value.map {
            if (it.id == id) it.copy(name = name.trim(), trigger = trigger.trim(), body = body.trim()) else it
        }
    }

    fun delete(id: String) = store.delete(id)

    fun byName(name: String): Skill? =
        store.value.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?: store.value.firstOrNull { it.name.contains(name.trim(), ignoreCase = true) }

    /** The always-on part: names and when they apply, so the model knows what it can ask for */
    fun catalogue(): String {
        val skills = store.value
        if (skills.isEmpty()) return ""
        return "\n\nApp playbooks you can load with the skill tool, by name:\n" +
            skills.joinToString("\n") { "- ${it.name} (for ${it.trigger})" }
    }

    /** The part that arrives on its own, because the agent is already standing in that app */
    fun autoLoaded(packageName: String?, prompt: String): List<Skill> =
        store.value.filter { it.matches(packageName, prompt) }

    fun render(skills: List<Skill>): String {
        if (skills.isEmpty()) return ""
        return "\n\n" + skills.joinToString("\n\n") { "Playbook - ${it.name}:\n${it.body}" }
    }

    fun merge(incoming: List<Skill>) = store.merge(incoming)
}
