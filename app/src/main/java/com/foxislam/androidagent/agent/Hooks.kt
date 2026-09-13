package com.foxislam.androidagent.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Standing rules about what the agent may do, checked before every tool call and before the
 * approval prompt. These hold without anyone reading a dialog: "never touch the banking app"
 * applies whether or not you are looking at the phone.
 *
 * One rule per line, first match wins:
 *
 *   allow tap in com.android.settings
 *   ask type_text matching (?i)password|otp|code
 *   deny * in com.*bank*
 *   deny mcp__*
 */
object Hooks {

    enum class Action { ALLOW, ASK, DENY }

    data class Rule(
        val action: Action,
        val tool: Regex,
        val pkg: Regex?,
        val matching: Regex?,
        val source: String,
    ) {
        fun matches(toolName: String, detail: String, packageName: String?): Boolean {
            if (!tool.matches(toolName)) return false
            if (pkg != null && (packageName == null || !pkg.matches(packageName))) return false
            if (matching != null && !matching.containsMatchIn(detail)) return false
            return true
        }
    }

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private var rules: List<Rule> = emptyList()
    private var file: File? = null

    fun load(context: Context) {
        if (file != null) return
        val target = File(context.applicationContext.filesDir, "hooks.txt")
        file = target
        if (!target.exists()) return
        runCatching { replace(target.readText()) }
            .onFailure { Log.w(TAG, "could not read hooks", it) }
    }

    fun replace(content: String) {
        _text.value = content
        rules = content.lines().mapNotNull(::parse)
        persist()
    }

    /** The first rule that matches, or null when none does */
    fun decide(tool: String, detail: String, packageName: String?): Rule? =
        rules.firstOrNull { it.matches(tool, detail, packageName) }

    /**
     * What "always allow this" writes down. Scoped to the app it was granted in, because
     * "yes, tap Send" in a notes app is not consent to tap Send in a bank
     */
    fun allowAlways(tool: String, packageName: String?) {
        val line = "allow $tool" + (packageName?.let { " in $it" } ?: "")
        if (_text.value.lines().any { it.trim() == line }) return
        replace((_text.value.trimEnd() + "\n" + line).trim())
    }

    fun parse(line: String): Rule? {
        val trimmed = line.substringBefore('#').trim()
        if (trimmed.isEmpty()) return null

        val words = trimmed.split(Regex("\\s+"))
        val action = when (words.first().lowercase()) {
            "allow" -> Action.ALLOW
            "ask" -> Action.ASK
            "deny", "block" -> Action.DENY
            else -> return null
        }
        val tool = words.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null

        var pkg: String? = null
        var matching: String? = null
        var i = 2
        while (i < words.size) {
            when (words[i].lowercase()) {
                "in" -> { pkg = words.getOrNull(i + 1); i += 2 }
                "matching" -> { matching = words.drop(i + 1).joinToString(" "); i = words.size }
                else -> i++
            }
        }

        return Rule(
            action = action,
            tool = glob(tool),
            pkg = pkg?.let(::glob),
            matching = matching?.let { runCatching { Regex(it) }.getOrNull() },
            source = trimmed,
        )
    }

    /** `*` is the only wildcard supported in a package name */
    private fun glob(pattern: String): Regex =
        Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)

    private fun persist() {
        val target = file ?: return
        runCatching { target.writeText(_text.value) }
            .onFailure { Log.w(TAG, "could not write hooks", it) }
    }

    private const val TAG = "Hooks"
}
