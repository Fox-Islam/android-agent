package com.foxislam.androidagent.agent

import android.util.Log
import org.json.JSONObject

/**
 * The two views of a session log: what the user reads, and what the model is sent.
 *
 * Both are derived, never stored, so a bug here shows up the same way in both places instead
 * of leaving the transcript and the conversation describing different runs
 */
object Projection {


    fun view(events: List<SessionEvent>): List<Entry> {
        val entries = mutableListOf<Entry>()
        val byCallId = mutableMapOf<String, Int>()
        val byAskId = mutableMapOf<String, Int>()
        val byPlanId = mutableMapOf<String, Int>()
        val byQuestionId = mutableMapOf<String, Int>()
        var latestTodos: Long? = null

        fun <T : Entry> replace(index: Int?, block: (T) -> Entry) {
            if (index == null) return
            @Suppress("UNCHECKED_CAST")
            entries[index] = block(entries[index] as T)
        }

        events.forEach { event ->
            when (event) {
                is SessionEvent.UserMessage ->
                    entries += Entry.Prompt(event.id, event.text, event.imagePath)

                is SessionEvent.AssistantMessage ->
                    if (!event.text.isNullOrBlank() || !event.thinking.isNullOrBlank()) {
                        entries += Entry.Said(event.id, event.text.orEmpty(), event.thinking)
                    }

                // `done` is the run's conclusion, rendered as its summary - a pill reading
                // "done" above the summary it produced is the same sentence twice
                is SessionEvent.ToolStarted -> if (event.name != "done") {
                    byCallId[event.callId] = entries.size
                    entries += Entry.Tool(event.id, event.callId, event.name, event.detail)
                }

                is SessionEvent.ToolFinished -> replace<Entry.Tool>(byCallId[event.callId]) {
                    it.copy(result = event.result, running = false)
                }

                is SessionEvent.Note -> entries += Entry.Note(event.id, event.text)
                is SessionEvent.Finished -> {
                    // A run that ended in prose recorded the message and this summary, which
                    // are the same sentence, so the summary replaces it
                    val said = entries.lastOrNull() as? Entry.Said
                    if (said != null && said.text == event.text) entries.removeAt(entries.size - 1)
                    entries += Entry.Finished(event.id, event.text)
                }
                is SessionEvent.Failed -> entries += Entry.Failed(event.id, event.text)

                is SessionEvent.AskRaised -> {
                    byAskId[event.askId] = entries.size
                    entries += Entry.Ask(event.id, event.askId, event.tool, event.detail, event.reason)
                }
                is SessionEvent.AskDecided -> replace<Entry.Ask>(byAskId[event.askId]) {
                    it.copy(decision = event.decision)
                }

                is SessionEvent.PlanProposed -> {
                    byPlanId[event.planId] = entries.size
                    entries += Entry.Plan(event.id, event.planId, event.steps, event.note)
                }
                is SessionEvent.PlanDecided -> replace<Entry.Plan>(byPlanId[event.planId]) {
                    it.copy(decision = event.decision)
                }

                is SessionEvent.QuestionAsked -> {
                    byQuestionId[event.questionId] = entries.size
                    entries += Entry.Question(event.id, event.questionId, event.text, event.options)
                }
                is SessionEvent.QuestionAnswered -> replace<Entry.Question>(byQuestionId[event.questionId]) {
                    it.copy(answer = event.answer)
                }

                // Only the current list is shown. Superseded ones are filtered out at the
                // end, not removed as we go: taking one out mid-fold would shift every index
                // recorded above it, and the results would fill in the wrong pills
                is SessionEvent.TodosWritten -> {
                    latestTodos = event.id
                    entries += Entry.Todos(event.id, event.items)
                }
            }
        }
        return entries.filterNot { it is Entry.Todos && it.id != latestTodos }
    }

    fun todos(events: List<SessionEvent>): List<TodoItem> =
        (events.lastOrNull { it is SessionEvent.TodosWritten } as? SessionEvent.TodosWritten)
            ?.items
            .orEmpty()

    /** Anything still waiting on the user when the process went away */
    fun unsettled(events: List<SessionEvent>): List<SessionEvent> {
        val settled = events.mapNotNullTo(mutableSetOf()) {
            when (it) {
                is SessionEvent.AskDecided -> it.askId
                is SessionEvent.PlanDecided -> it.planId
                is SessionEvent.QuestionAnswered -> it.questionId
                is SessionEvent.ToolFinished -> it.callId
                else -> null
            }
        }
        return events.filter {
            when (it) {
                is SessionEvent.AskRaised -> it.askId !in settled
                is SessionEvent.PlanProposed -> it.planId !in settled
                is SessionEvent.QuestionAsked -> it.questionId !in settled
                is SessionEvent.ToolStarted -> it.callId !in settled
                else -> false
            }
        }
    }


    /**
     * Assistant turns and the results answering them travel together or not at all: a
     * provider rejects a tool result for a call it never issued, and equally rejects a call
     * that nothing answered. A run killed between the two leaves exactly that, so the block
     * it left behind is dropped instead of replayed
     */
    fun messages(events: List<SessionEvent>): List<Turn> {
        val turns = mutableListOf<Turn>()
        var open: Block? = null

        fun close() {
            val block = open ?: return
            open = null
            if (block.calls.isEmpty()) {
                turns += block.assistant
                return
            }
            val outputs = block.calls.mapNotNull { call -> block.results[call.id] }
            if (outputs.size != block.calls.size) {
                Log.i(TAG, "dropping an unfinished step: ${block.calls.size - outputs.size} calls unanswered")
                return
            }
            turns += block.assistant
            turns += Turn.ToolResults(outputs)
        }

        events.forEach { event ->
            when (event) {
                is SessionEvent.UserMessage -> {
                    close()
                    turns += Turn.User(event.text)
                }

                is SessionEvent.AssistantMessage -> {
                    close()
                    open = Block(
                        assistant = Turn.Assistant(
                            text = event.text,
                            thinking = event.thinking,
                            calls = event.calls.map { it.toCall() },
                            echo = event.echo?.let { runCatching { JSONObject(it) }.getOrNull() },
                        ),
                        calls = event.calls,
                    )
                }

                is SessionEvent.ToolFinished -> {
                    val block = open
                    val call = block?.calls?.firstOrNull { it.id == event.callId }
                    if (block != null && call != null) {
                        block.results[call.id] = ToolOutput(call.toCall(), Outcome.Text(event.result))
                    } else {
                        // A chat from before the log existed: the action is known, the call
                        // it belonged to is not. Record what was done and continue
                        close()
                        turns += Turn.User("(did: ${event.result.take(ORPHAN_CHARS)})")
                    }
                }

                else -> Unit
            }
        }
        close()
        return turns
    }

    private class Block(
        val assistant: Turn.Assistant,
        val calls: List<RecordedCall>,
        val results: MutableMap<String, ToolOutput> = mutableMapOf(),
    )

    private fun RecordedCall.toCall(): ToolCall = ToolCall(id, name, decode(input))

    /**
     * Replays as much of the tail as the budget allows, in whole blocks, and always opens on
     * the original task.
     *
     * Trim to the tail alone and a long chat can arrive with no user turn in it: the provider
     * rejects that, and the model is being told to continue something it was never given
     */
    fun trim(turns: List<Turn>, budgetChars: Int): List<Turn> {
        val blocks = mutableListOf<MutableList<Turn>>()
        turns.forEach { turn ->
            if (turn is Turn.ToolResults && blocks.isNotEmpty()) blocks.last() += turn
            else blocks += mutableListOf(turn)
        }

        val kept = ArrayDeque<List<Turn>>()
        var budget = budgetChars
        for (block in blocks.asReversed()) {
            val size = block.sumOf { it.weight() }
            if (size > budget && kept.isNotEmpty()) break
            budget -= size
            kept.addFirst(block)
        }

        val flat = kept.flatten().toMutableList()
        // A tool result whose call was trimmed away has nothing to attach to
        while (flat.isNotEmpty() && flat.first() is Turn.ToolResults) flat.removeAt(0)

        val opening = turns.firstOrNull { it is Turn.User } as? Turn.User ?: return flat
        val first = flat.firstOrNull()
        return when {
            first === opening -> flat
            // Two user turns in a row is the other shape providers refuse, so they merge
            first is Turn.User -> flat.also { it[0] = Turn.User(opening.text + "\n\n" + first.text) }
            else -> listOf(opening) + flat
        }
    }

    private fun Turn.weight(): Int = when (this) {
        is Turn.User -> text.length
        is Turn.Assistant -> (text?.length ?: 0) + calls.sumOf { it.name.length + CALL_CHARS }
        is Turn.ToolResults -> outputs.sumOf {
            when (val outcome = it.outcome) {
                is Outcome.Text -> outcome.text.length
                is Outcome.Done -> outcome.summary.length
                is Outcome.Image -> IMAGE_CHARS
            }
        }
    }


    fun recorded(call: ToolCall): RecordedCall =
        RecordedCall(call.id, call.name, JSONObject(call.input).toString())

    fun recorded(id: Long, reply: Turn.Assistant): SessionEvent.AssistantMessage =
        SessionEvent.AssistantMessage(
            id = id,
            text = reply.text,
            thinking = reply.thinking,
            echo = (reply.echo as? JSONObject)?.toString(),
            calls = reply.calls.map(::recorded),
        )

    /** How a tool result reads once the pixels are gone */
    fun resultText(outcome: Outcome): String = when (outcome) {
        is Outcome.Text -> outcome.text
        is Outcome.Done -> outcome.summary
        is Outcome.Image -> "${outcome.note} (the image itself is not replayed; the screen has changed)"
    }

    fun decode(json: String): Map<String, Any?> = runCatching {
        val parsed = JSONObject(json)
        parsed.keys().asSequence().associateWith { key ->
            parsed.get(key).takeUnless { it == JSONObject.NULL }
        }
    }.getOrElse { emptyMap() }

    private const val TAG = "Projection"
    private const val ORPHAN_CHARS = 200

    // Weights, in the same rough currency as a character of text: what the JSON around a
    // tool call costs, and what an image costs once the provider has tokenised it
    private const val CALL_CHARS = 40
    private const val IMAGE_CHARS = 400
}
