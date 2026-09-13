package com.foxislam.androidagent.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The shape chats were written in before the session log: one file holding every chat, each
 * a list of rendered transcript entries. Kept only so [SessionStore] can read those files
 * once and convert them; nothing writes this.
 *
 * The serial names are pinned to what the old sealed interface produced by default, because
 * that is what is sitting in the file on someone's phone
 */
@Serializable
internal data class LegacyThread(
    val id: String,
    val title: String = "",
    val entries: List<LegacyEntry> = emptyList(),
    val updatedAt: Long = 0L,
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val costUsd: Double = 0.0,
    val contextTokens: Int = 0,
    val planMode: Boolean = false,
)

@Serializable
internal sealed interface LegacyEntry {
    val id: Long

    @Serializable
    @SerialName("com.foxislam.androidagent.agent.Entry.Prompt")
    data class Prompt(
        override val id: Long,
        val text: String,
        val imagePath: String? = null,
    ) : LegacyEntry

    @Serializable
    @SerialName("com.foxislam.androidagent.agent.Entry.Thinking")
    data class Thinking(override val id: Long, val text: String) : LegacyEntry

    @Serializable
    @SerialName("com.foxislam.androidagent.agent.Entry.Said")
    data class Said(override val id: Long, val text: String) : LegacyEntry

    @Serializable
    @SerialName("com.foxislam.androidagent.agent.Entry.Failed")
    data class Failed(override val id: Long, val text: String) : LegacyEntry

    @Serializable
    @SerialName("com.foxislam.androidagent.agent.Entry.Finished")
    data class Finished(override val id: Long, val text: String) : LegacyEntry

    @Serializable
    @SerialName("com.foxislam.androidagent.agent.Entry.Note")
    data class Note(override val id: Long, val text: String) : LegacyEntry

    @Serializable
    @SerialName("com.foxislam.androidagent.agent.Entry.Tool")
    data class Tool(
        override val id: Long,
        val callId: String,
        val name: String,
        val detail: String,
        val result: String? = null,
        val running: Boolean = true,
    ) : LegacyEntry

    @Serializable
    @SerialName("com.foxislam.androidagent.agent.Entry.Ask")
    data class Ask(
        override val id: Long,
        val askId: String,
        val tool: String,
        val detail: String,
        val reason: String,
        val decision: String? = null,
    ) : LegacyEntry

    @Serializable
    @SerialName("com.foxislam.androidagent.agent.Entry.Plan")
    data class Plan(
        override val id: Long,
        val planId: String,
        val steps: List<String>,
        val note: String = "",
        val decision: String? = null,
    ) : LegacyEntry

    /**
     * An old entry as events. A tool becomes the pair it always was; the calls behind it are
     * gone for good, so the projection replays it as something that was done, not as an
     * exchange it can no longer reconstruct
     */
    fun asEvents(): List<SessionEvent> = when (this) {
        is Prompt -> listOf(SessionEvent.UserMessage(id, text, imagePath))
        is Said -> listOf(SessionEvent.AssistantMessage(id, text = text))
        is Thinking -> listOf(SessionEvent.AssistantMessage(id, thinking = text))
        is Failed -> listOf(SessionEvent.Failed(id, text))
        is Finished -> listOf(SessionEvent.Finished(id, text))
        is Note -> listOf(SessionEvent.Note(id, text))
        is Tool -> listOf(SessionEvent.ToolStarted(id, callId, name, detail)) +
            listOfNotNull(result?.let { SessionEvent.ToolFinished(id, callId, it) })
        is Ask -> listOf(SessionEvent.AskRaised(id, askId, tool, detail, reason)) +
            listOfNotNull(decision?.let { SessionEvent.AskDecided(id, askId, it) })
        is Plan -> listOf(SessionEvent.PlanProposed(id, planId, steps, note)) +
            listOfNotNull(decision?.let { SessionEvent.PlanDecided(id, planId, it) })
    }
}
