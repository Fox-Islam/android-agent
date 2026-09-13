package com.foxislam.androidagent.agent

import android.util.Log

/**
 * Keeps a long run inside the model's window.
 *
 * A run that taps its way through thirty screens accumulates thirty accessibility trees, and
 * left alone the context grows until the provider refuses the request. Old tool output goes
 * first because it is both the biggest and the most stale - the tree from twelve taps ago
 * describes a screen nobody is looking at
 */
class Compactor(private val backend: LlmBackend) {

    /**
     * Rough. Every provider tokenises differently and none of them will tell us up front,
     * so this errs high and compacts a little early
     */
    fun estimate(turns: List<Turn>): Int = turns.sumOf { turn ->
        when (turn) {
            is Turn.User -> turn.text.length / CHARS_PER_TOKEN
            is Turn.Assistant ->
                ((turn.text?.length ?: 0) + (turn.thinking?.length ?: 0)) / CHARS_PER_TOKEN +
                    turn.calls.sumOf { it.input.toString().length / CHARS_PER_TOKEN + 10 }
            is Turn.ToolResults -> turn.outputs.sumOf {
                when (val outcome = it.outcome) {
                    is Outcome.Text -> outcome.text.length / CHARS_PER_TOKEN
                    is Outcome.Done -> outcome.summary.length / CHARS_PER_TOKEN
                    is Outcome.Image -> IMAGE_TOKENS
                }
            }
        }
    }

    /**
     * Shrinks [turns] in place when it has grown too big, and reports what it did, so the
     * user can see that the agent forgot something
     */
    suspend fun compact(turns: MutableList<Turn>, contextLimit: Int): String? {
        val soft = (contextLimit * SOFT_FRACTION).toInt()
        if (estimate(turns) < soft) return null

        val elided = elideOldResults(turns)
        if (estimate(turns) < soft) {
            return if (elided > 0) "Dropped $elided stale tool results to stay inside the context window." else null
        }

        val summarised = summariseOldest(turns)
        return when {
            summarised && elided > 0 ->
                "Summarised the earlier steps and dropped $elided stale tool results."
            summarised -> "Summarised the earlier steps to stay inside the context window."
            elided > 0 -> "Dropped $elided stale tool results to stay inside the context window."
            else -> null
        }
    }

    /**
     * Replaces the body of old tool results with a one-line stub. The call itself is kept:
     * it is small, and the tree it returned is not
     */
    private fun elideOldResults(turns: MutableList<Turn>): Int {
        var elided = 0
        val cutoff = turns.size - RECENT_TURNS
        for (i in 0 until maxOf(cutoff, 0)) {
            val turn = turns[i] as? Turn.ToolResults ?: continue
            val trimmed = turn.outputs.map { output ->
                val outcome = output.outcome
                if (outcome is Outcome.Text && outcome.text.length > KEEP_RESULT_CHARS) {
                    elided++
                    output.copy(
                        outcome = Outcome.Text(
                            outcome.text.take(KEEP_RESULT_CHARS) +
                                "\n[…the rest of this result was dropped; the screen has changed since]",
                        ),
                    )
                } else if (outcome is Outcome.Image) {
                    elided++
                    output.copy(outcome = Outcome.Text("${outcome.note} [the screenshot itself was dropped]"))
                } else {
                    output
                }
            }
            turns[i] = Turn.ToolResults(trimmed)
        }
        return elided
    }

    /**
     * Hands the oldest half to the model and puts its summary back in their place. Whole
     * blocks only - an assistant turn and the tool results answering it move together, or
     * the provider rejects the conversation for referring to a tool call it never saw
     */
    private suspend fun summariseOldest(turns: MutableList<Turn>): Boolean {
        val blocks = blocks(turns)
        // The first block is the user's task; the last few are the live working set
        if (blocks.size < MIN_BLOCKS_TO_SUMMARISE) return false
        val head = blocks.first()
        val middle = blocks.drop(1).dropLast(KEEP_BLOCKS)
        val tail = blocks.takeLast(KEEP_BLOCKS)
        if (middle.isEmpty()) return false

        val transcript = middle.flatten().joinToString("\n") { it.asLine() }.take(MAX_SUMMARY_INPUT)
        val summary = runCatching {
            backend.complete(
                "You are compressing the log of a phone-automation run so it can be carried " +
                    "forward in a smaller context. Reply with terse notes only.",
                "Summarise what has already happened, in under 200 words. Keep: what was " +
                    "tried, what worked, what failed and why, any values discovered (names, " +
                    "amounts, codes), and where in the app the agent currently is. Drop all " +
                    "screen dumps.\n\n$transcript",
            )
        }.getOrElse {
            Log.w(TAG, "summary failed; leaving the history alone", it)
            return false
        }

        val note = "[Earlier steps in this run, summarised]\n$summary"
        val opening = head.last()
        turns.clear()
        turns += head.dropLast(1)
        // The head is the user's own task. Folding the summary into it keeps the exchange
        // alternating: two user messages in a row is a 400 on some providers
        turns += if (opening is Turn.User) Turn.User(opening.text + "\n\n" + note) else opening
        if (opening !is Turn.User) turns += Turn.User(note)
        turns += tail.flatten()
        return true
    }

    private fun blocks(turns: List<Turn>): List<List<Turn>> {
        val blocks = mutableListOf<MutableList<Turn>>()
        turns.forEach { turn ->
            if (turn is Turn.ToolResults && blocks.isNotEmpty()) blocks.last() += turn
            else blocks += mutableListOf(turn)
        }
        return blocks
    }

    private fun Turn.asLine(): String = when (this) {
        is Turn.User -> "user: $text"
        is Turn.Assistant -> buildString {
            text?.let { append("agent: ").append(it) }
            calls.forEach { append("\nagent calls ").append(it.name).append(' ').append(it.input) }
        }
        is Turn.ToolResults -> outputs.joinToString("\n") { output ->
            val text = when (val outcome = output.outcome) {
                is Outcome.Text -> outcome.text
                is Outcome.Done -> outcome.summary
                is Outcome.Image -> outcome.note
            }
            "${output.call.name} -> ${text.take(RESULT_LINE_CHARS)}"
        }
    }

    private companion object {
        const val TAG = "Compactor"
        const val CHARS_PER_TOKEN = 4
        const val IMAGE_TOKENS = 900
        const val SOFT_FRACTION = 0.6
        const val RECENT_TURNS = 6
        const val KEEP_RESULT_CHARS = 400
        const val KEEP_BLOCKS = 4
        const val MIN_BLOCKS_TO_SUMMARISE = 8
        const val MAX_SUMMARY_INPUT = 24_000
        const val RESULT_LINE_CHARS = 200
    }
}
