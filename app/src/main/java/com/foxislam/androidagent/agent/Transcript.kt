package com.foxislam.androidagent.agent

/**
 * What the chat renders, projected from the session log by [Projection] instead of stored.
 * A tool call is not a message: it is an activity pill that fills in its own result, so the
 * conversation stays readable
 */
sealed interface Entry {
    val id: Long

    data class Prompt(
        override val id: Long,
        val text: String,
        val imagePath: String? = null,
    ) : Entry

    /** [thinking] is the reasoning that produced this, shown folded away above it */
    data class Said(override val id: Long, val text: String, val thinking: String? = null) : Entry

    data class Failed(override val id: Long, val text: String) : Entry

    data class Finished(override val id: Long, val text: String) : Entry

    /** Housekeeping the user should see but need not act on: compaction, resume, forks */
    data class Note(override val id: Long, val text: String) : Entry

    data class Tool(
        override val id: Long,
        val callId: String,
        val name: String,
        val detail: String,
        val result: String? = null,
        val running: Boolean = true,
    ) : Entry

    /**
     * A held action waiting on the user. [decision] is null while it is still being asked;
     * one left pending when the process went away is settled on load, because nothing is
     * waiting on its answer any more
     */
    data class Ask(
        override val id: Long,
        val askId: String,
        val tool: String,
        val detail: String,
        val reason: String,
        val decision: String? = null,
    ) : Entry

    data class Plan(
        override val id: Long,
        val planId: String,
        val steps: List<String>,
        val note: String = "",
        val decision: String? = null,
    ) : Entry

    /** The agent's own question. Unlike an approval, there is no default answer */
    data class Question(
        override val id: Long,
        val questionId: String,
        val text: String,
        val options: List<String> = emptyList(),
        val answer: String? = null,
    ) : Entry

    data class Todos(override val id: Long, val items: List<TodoItem>) : Entry
}
