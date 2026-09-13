package com.foxislam.androidagent.agent

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompactorTest {

    private class FakeBackend(var summary: String = "Tapped through three screens.") : LlmBackend {
        var completions = 0
        override suspend fun next(
            system: String,
            turns: List<Turn>,
            tools: List<ToolSpec>,
            model: String?,
            onDelta: (Delta) -> Unit,
        ): Turn.Assistant = error("not used")

        override suspend fun complete(system: String, prompt: String): String {
            completions++
            return summary
        }
    }

    private fun step(index: Int, resultChars: Int): List<Turn> {
        val call = ToolCall("call_$index", "ui_tree", emptyMap())
        return listOf(
            Turn.Assistant("looking", null, listOf(call), null),
            Turn.ToolResults(listOf(ToolOutput(call, Outcome.Text("x".repeat(resultChars))))),
        )
    }

    private fun run(steps: Int, resultChars: Int): MutableList<Turn> =
        (mutableListOf<Turn>(Turn.User("do the thing")) + (1..steps).flatMap { step(it, resultChars) })
            .toMutableList()

    @Test
    fun `a short run is left completely alone`() = runBlocking {
        val backend = FakeBackend()
        val turns = run(steps = 2, resultChars = 500)
        val before = turns.toList()

        assertNull(Compactor(backend).compact(turns, contextLimit = 128_000))
        assertEquals(before, turns)
        assertEquals(0, backend.completions)
    }

    @Test
    fun `stale tool output is dropped before anything is summarised`() = runBlocking {
        val backend = FakeBackend()
        // Well over the soft threshold on tool output alone, but not so far that the
        // summariser runs
        val turns = run(steps = 12, resultChars = 6_000)

        val note = Compactor(backend).compact(turns, contextLimit = 20_000)

        assertNotNull(note)
        assertTrue("stale tool results" in note)
        assertEquals(0, backend.completions, "no model call should be needed to drop old output")
        // The most recent turns keep their full result; older ones do not
        val results = turns.filterIsInstance<Turn.ToolResults>()
        assertTrue((results.first().outputs.first().outcome as Outcome.Text).text.length < 6_000)
        assertEquals(6_000, (results.last().outputs.first().outcome as Outcome.Text).text.length)
    }

    @Test
    fun `when dropping is not enough the oldest steps are summarised`() = runBlocking {
        val backend = FakeBackend()
        val turns = run(steps = 40, resultChars = 4_000)

        val note = Compactor(backend).compact(turns, contextLimit = 8_000)

        assertNotNull(note)
        assertEquals(1, backend.completions)
        val opening = turns.first() as Turn.User
        assertTrue(opening.text.startsWith("do the thing"))
        assertTrue("summarised" in opening.text)
        assertTrue(turns.size < 20)
    }

    @Test
    fun `a failed summary leaves the history usable instead of half-eaten`() = runBlocking {
        val backend = object : LlmBackend {
            override suspend fun next(
                system: String,
                turns: List<Turn>,
                tools: List<ToolSpec>,
                model: String?,
                onDelta: (Delta) -> Unit,
            ): Turn.Assistant = error("not used")

            override suspend fun complete(system: String, prompt: String): String =
                throw LlmException("provider is down")
        }
        val turns = run(steps = 40, resultChars = 4_000)

        Compactor(backend).compact(turns, contextLimit = 8_000)

        assertTrue((turns.first() as Turn.User).text.startsWith("do the thing"))
        assertTrue(turns.filterIsInstance<Turn.Assistant>().isNotEmpty())
    }

    @Test
    fun `an assistant turn is never left without the results that answer it`() = runBlocking {
        val turns = run(steps = 40, resultChars = 4_000)
        Compactor(FakeBackend()).compact(turns, contextLimit = 8_000)

        // Providers reject two messages from the same speaker in a row as readily as they
        // reject a tool result with no call in front of it
        turns.zipWithNext().forEach { (first, second) ->
            assertFalse(
                first is Turn.User && second is Turn.User,
                "two user turns in a row",
            )
        }

        turns.forEachIndexed { index, turn ->
            if (turn is Turn.Assistant && turn.calls.isNotEmpty()) {
                assertTrue(
                    turns.getOrNull(index + 1) is Turn.ToolResults,
                    "a call at $index lost its result",
                )
            }
            if (turn is Turn.ToolResults) {
                assertTrue(turns.getOrNull(index - 1) is Turn.Assistant, "a result at $index lost its call")
            }
        }
    }
}
