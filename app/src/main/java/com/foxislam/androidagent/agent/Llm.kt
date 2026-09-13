package com.foxislam.androidagent.agent

/** A tool as the model sees it. The backend converts this to the provider's schema shape */
data class ToolSpec(
    val name: String,
    val description: String,
    val properties: Map<String, Map<String, Any>> = emptyMap(),
    val required: List<String> = emptyList(),
    /**
     * A complete JSON Schema, used verbatim in place of [properties]. MCP servers hand us
     * one already written, and rewriting it into our flat shape would lose nested objects,
     * enums and arrays
     */
    val rawSchema: String? = null,
)

data class ToolCall(val id: String, val name: String, val input: Map<String, Any?>)

data class ToolOutput(val call: ToolCall, val outcome: Outcome)

/** What a turn cost. Providers report this after the fact; a missing block reads as zero */
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val costUsd: Double = 0.0,
) {
    val total: Int get() = promptTokens + completionTokens
}

sealed interface Turn {
    /**
     * [imageBase64] is a picture the user shared in from another app, so an ask like "why
     * does this look wrong" has something to refer to
     */
    data class User(val text: String, val imageBase64: String? = null) : Turn

    /**
     * [echo] is the provider's own representation of this turn, replayed verbatim so
     * tool_calls (and any reasoning payload) survive the round trip unaltered
     */
    data class Assistant(
        val text: String?,
        val thinking: String?,
        val calls: List<ToolCall>,
        val echo: Any?,
        val refused: Boolean = false,
        /** Provider's own word for why it stopped: stop, length, tool_calls, error... */
        val finishReason: String? = null,
        /** Provider-side generation id, so a bad turn can be looked up in their logs */
        val generationId: String? = null,
        val usage: Usage? = null,
    ) : Turn {
        val isEmpty: Boolean get() = text == null && calls.isEmpty()
    }

    data class ToolResults(val outputs: List<ToolOutput>) : Turn
}

sealed interface Outcome {
    data class Text(val text: String) : Outcome
    data class Image(val base64: String, val note: String, val mime: String = "image/jpeg") : Outcome
    data class Done(val summary: String) : Outcome
}

/**
 * A fragment of a reply as it arrives. The loop paints these into the transcript, so a slow
 * model shows a sentence being written instead of a spinner
 */
sealed interface Delta {
    /** The whole reply so far, not just the new characters */
    data class Text(val chunk: String) : Delta
    data class Thinking(val chunk: String) : Delta

    /** A retry is starting over: throw away what has been painted */
    data object Reset : Delta
}

interface LlmBackend {
    /**
     * [model] overrides the backend's default for this one request, so a run can use the
     * cheap model for its mechanical steps and the good one elsewhere
     */
    suspend fun next(
        system: String,
        turns: List<Turn>,
        tools: List<ToolSpec>,
        model: String? = null,
        onDelta: (Delta) -> Unit = {},
    ): Turn.Assistant

    /**
     * One prompt, one answer, no tools and no streaming. Used for the summaries that
     * compaction writes
     */
    suspend fun complete(system: String, prompt: String): String
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The reasoning control. Models that do not reason ignore it.
 *
 * [DEFAULT] and [OFF] are different things: omitting the field leaves the provider to
 * decide, which for a reasoning model means it reasons. Only OFF asks for it to be turned
 * off
 */
enum class Reasoning(val label: String, val effort: String?) {
    DEFAULT("Provider default", null),
    OFF("Off", null),
    LOW("Low", "low"),
    MEDIUM("Medium", "medium"),
    HIGH("High", "high"),
}
