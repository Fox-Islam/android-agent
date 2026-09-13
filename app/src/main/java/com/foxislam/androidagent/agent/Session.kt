package com.foxislam.androidagent.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One thing that happened, in the order it happened.
 *
 * The log is the chat. Everything else - what the user sees, and what the model is sent - is
 * projected from it.
 * Anything the model can see must be reconstructable from here.
 *
 * It is append-only. Later facts about an earlier event - the result of a tool call, the
 * answer to a question - arrive as their own events, not as edits, so the file is appended
 * to a line at a time instead of rewritten
 */
@Serializable
sealed interface SessionEvent {
    val id: Long

    @Serializable
    @SerialName("user")
    data class UserMessage(
        override val id: Long,
        val text: String,
        val imagePath: String? = null,
        /** Typed at a run already in progress, instead of starting one */
        val steering: Boolean = false,
    ) : SessionEvent

    @Serializable
    @SerialName("assistant")
    data class AssistantMessage(
        override val id: Long,
        val text: String? = null,
        val thinking: String? = null,
        /**
         * The provider's own message, verbatim. Without it a resumed chat cannot name the
         * tool calls it made, and no provider will accept results for calls it never saw
         */
        val echo: String? = null,
        val calls: List<RecordedCall> = emptyList(),
    ) : SessionEvent

    @Serializable
    @SerialName("tool")
    data class ToolStarted(
        override val id: Long,
        val callId: String,
        val name: String,
        val detail: String,
    ) : SessionEvent

    @Serializable
    @SerialName("tool-done")
    data class ToolFinished(
        override val id: Long,
        val callId: String,
        val result: String,
        /** Screenshots are not replayed: by the next run that screen is long gone */
        val image: Boolean = false,
    ) : SessionEvent

    @Serializable
    @SerialName("note")
    data class Note(override val id: Long, val text: String) : SessionEvent

    @Serializable
    @SerialName("finished")
    data class Finished(override val id: Long, val text: String) : SessionEvent

    @Serializable
    @SerialName("failed")
    data class Failed(override val id: Long, val text: String) : SessionEvent

    @Serializable
    @SerialName("ask")
    data class AskRaised(
        override val id: Long,
        val askId: String,
        val tool: String,
        val detail: String,
        val reason: String,
    ) : SessionEvent

    @Serializable
    @SerialName("ask-done")
    data class AskDecided(override val id: Long, val askId: String, val decision: String) : SessionEvent

    @Serializable
    @SerialName("plan")
    data class PlanProposed(
        override val id: Long,
        val planId: String,
        val steps: List<String>,
        val note: String = "",
    ) : SessionEvent

    @Serializable
    @SerialName("plan-done")
    data class PlanDecided(override val id: Long, val planId: String, val decision: String) : SessionEvent

    @Serializable
    @SerialName("question")
    data class QuestionAsked(
        override val id: Long,
        val questionId: String,
        val text: String,
        val options: List<String> = emptyList(),
    ) : SessionEvent

    @Serializable
    @SerialName("question-done")
    data class QuestionAnswered(
        override val id: Long,
        val questionId: String,
        val answer: String,
    ) : SessionEvent

    @Serializable
    @SerialName("todos")
    data class TodosWritten(override val id: Long, val items: List<TodoItem>) : SessionEvent
}

@Serializable
data class RecordedCall(val id: String, val name: String, val input: String)

@Serializable
data class TodoItem(val text: String, val state: String = PENDING) {
    companion object {
        const val PENDING = "pending"
        const val DOING = "doing"
        const val DONE = "done"
    }
}

/**
 * Everything about a chat except what happened in it. Small, rewritten atomically, and the
 * only thing read at startup - a chat's log is opened when the chat is.
 *
 * [title] and [preview] are therefore denormalised out of the log: the chat list has to be
 * able to draw itself without parsing every conversation the phone has ever had
 */
@Serializable
data class ChatMeta(
    val id: String,
    val title: String = "",
    val preview: String = "",
    /** True while a run is in flight. Found true at startup, it means the app was killed */
    val running: Boolean = false,
    /** A run that was killed, and can be picked back up */
    val interrupted: Boolean = false,
    val updatedAt: Long = 0L,
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val costUsd: Double = 0.0,
    /** Prompt tokens of the most recent request: how full the window is */
    val contextTokens: Int = 0,
    /** Plan first, act second. Sticky per chat */
    val planMode: Boolean = false,
    /** The chat this one was forked from, if it was */
    val parent: String? = null,
)

/**
 * A chat as the app holds it: its metadata, its log, and the two views derived from the log.
 * The views are computed once per instance and a new instance is made per appended event, so
 * a screen that reads [entries] on every recomposition is not re-folding the whole run
 */
data class ChatThread(
    val meta: ChatMeta,
    val events: List<SessionEvent> = emptyList(),
    /** False until the log has been read off disk; the chat list never needs it */
    val loaded: Boolean = false,
) {
    val id: String get() = meta.id
    val title: String get() = meta.title
    val updatedAt: Long get() = meta.updatedAt
    val planMode: Boolean get() = meta.planMode
    val costUsd: Double get() = meta.costUsd
    val contextTokens: Int get() = meta.contextTokens
    val interrupted: Boolean get() = meta.interrupted

    val entries: List<Entry> by lazy { Projection.view(events) }
    val todos: List<TodoItem> by lazy { Projection.todos(events) }

    /** First thing the user asked, used as the chat's label */
    val displayTitle: String get() = title.ifBlank { "New chat" }

    /** The agent's last word, not the user's - the title already carries the prompt */
    val preview: String get() = meta.preview
}
