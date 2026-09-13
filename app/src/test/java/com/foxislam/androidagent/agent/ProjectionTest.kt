package com.foxislam.androidagent.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProjectionTest {

    private var next = 0L
    private fun id() = ++next

    private fun call(name: String, callId: String) = RecordedCall(callId, name, "{}")

    @Test
    fun `a finished step becomes an assistant turn and its results`() {
        val events = listOf(
            SessionEvent.UserMessage(id(), "open settings"),
            SessionEvent.AssistantMessage(id(), text = "looking", calls = listOf(call("ui_tree", "c1"))),
            SessionEvent.ToolStarted(id(), "c1", "ui_tree", ""),
            SessionEvent.ToolFinished(id(), "c1", "[0] Button"),
        )

        val turns = Projection.messages(events)

        assertEquals(3, turns.size)
        assertIs<Turn.User>(turns[0])
        assertIs<Turn.Assistant>(turns[1])
        val results = assertIs<Turn.ToolResults>(turns[2])
        assertEquals("c1", results.outputs.single().call.id)
    }

    @Test
    fun `a run that ended in prose shows the answer once, as the summary`() {
        val answer = "The first item is Network & internet."
        val events = listOf(
            SessionEvent.UserMessage(id(), "what is the first item"),
            SessionEvent.AssistantMessage(id(), text = answer),
            SessionEvent.Finished(id(), answer),
        )

        val entries = Projection.view(events)

        assertEquals(2, entries.size)
        assertIs<Entry.Prompt>(entries[0])
        assertEquals(answer, assertIs<Entry.Finished>(entries[1]).text)
    }

    @Test
    fun `a summary that says something else is kept alongside the message`() {
        val events = listOf(
            SessionEvent.AssistantMessage(id(), text = "tapping through the list"),
            SessionEvent.Finished(id(), "Turned Wi-Fi on."),
        )

        val entries = Projection.view(events)

        assertEquals(2, entries.size)
        assertIs<Entry.Said>(entries[0])
        assertIs<Entry.Finished>(entries[1])
    }

    @Test
    fun `a step the process died in the middle of is not replayed`() {
        val events = listOf(
            SessionEvent.UserMessage(id(), "open settings"),
            SessionEvent.AssistantMessage(id(), calls = listOf(call("tap", "c1"))),
            SessionEvent.ToolStarted(id(), "c1", "tap", "node 3"),
        )

        // An assistant turn whose call nothing answered is what providers reject
        assertEquals(1, Projection.messages(events).size)
    }

    @Test
    fun `a step with only some of its results answered is dropped whole`() {
        val events = listOf(
            SessionEvent.UserMessage(id(), "do it"),
            SessionEvent.AssistantMessage(id(), calls = listOf(call("tap", "c1"), call("tap", "c2"))),
            SessionEvent.ToolFinished(id(), "c1", "Tapped."),
        )

        assertEquals(1, Projection.messages(events).size)
    }

    @Test
    fun `a chat migrated from before the log reads as things that were done`() {
        val events = listOf(
            SessionEvent.UserMessage(id(), "open settings"),
            SessionEvent.AssistantMessage(id(), text = "on it"),
            // No owning call: this is what an old transcript converts to
            SessionEvent.ToolFinished(id(), "legacy", "Tapped node 3."),
            SessionEvent.UserMessage(id(), "and again"),
        )

        val turns = Projection.messages(events)

        assertTrue(turns.any { it is Turn.User && it.text.startsWith("(did:") })
        assertTrue(turns.last() is Turn.User)
    }

    @Test
    fun `the view fills a tool pill in from its own result`() {
        val events = listOf(
            SessionEvent.ToolStarted(id(), "c1", "tap", "node 3"),
            SessionEvent.ToolFinished(id(), "c1", "Tapped node 3."),
        )

        val tool = Projection.view(events).single() as Entry.Tool
        assertEquals("Tapped node 3.", tool.result)
        assertEquals(false, tool.running)
    }

    @Test
    fun `done is logged for replay but never rendered as a pill`() {
        val events = listOf(
            SessionEvent.AssistantMessage(id(), calls = listOf(call("done", "c1"))),
            SessionEvent.ToolStarted(id(), "c1", "done", ""),
            SessionEvent.ToolFinished(id(), "c1", "All finished."),
            SessionEvent.Finished(id(), "All finished."),
        )

        val view = Projection.view(events)
        assertTrue(view.none { it is Entry.Tool })
        assertIs<Entry.Finished>(view.single())
        // The model still sees a complete exchange, or the chat cannot be continued
        assertEquals(2, Projection.messages(events).size)
    }

    @Test
    fun `only the latest checklist is shown`() {
        val events = listOf(
            SessionEvent.TodosWritten(id(), listOf(TodoItem("one"))),
            SessionEvent.UserMessage(id(), "go on"),
            SessionEvent.TodosWritten(id(), listOf(TodoItem("one", TodoItem.DONE), TodoItem("two"))),
        )

        val todos = Projection.view(events).filterIsInstance<Entry.Todos>()
        assertEquals(1, todos.size)
        assertEquals(2, todos.single().items.size)
    }

    @Test
    fun `a superseded checklist does not shift the results that follow it`() {
        val events = listOf(
            SessionEvent.TodosWritten(id(), listOf(TodoItem("one"))),
            SessionEvent.ToolStarted(id(), "c1", "tap", "node 3"),
            SessionEvent.TodosWritten(id(), listOf(TodoItem("one", TodoItem.DONE))),
            SessionEvent.ToolFinished(id(), "c1", "Tapped node 3."),
        )

        val view = Projection.view(events)

        assertEquals("Tapped node 3.", view.filterIsInstance<Entry.Tool>().single().result)
        assertEquals(1, view.filterIsInstance<Entry.Todos>().single().items.size)
    }

    @Test
    fun `decisions land on the entry they belong to`() {
        val events = listOf(
            SessionEvent.AskRaised(id(), "a1", "tap", "\"Send\"", "looks irreversible"),
            SessionEvent.AskDecided(id(), "a1", Approvals.DENIED),
            SessionEvent.QuestionAsked(id(), "q1", "Which Dan?", listOf("Dan A", "Dan B")),
            SessionEvent.QuestionAnswered(id(), "q1", "Dan B"),
        )

        val view = Projection.view(events)
        assertEquals(Approvals.DENIED, (view[0] as Entry.Ask).decision)
        assertEquals("Dan B", (view[1] as Entry.Question).answer)
    }

    @Test
    fun `what is still waiting on the user is findable after a restart`() {
        val events = listOf(
            SessionEvent.AskRaised(id(), "a1", "tap", "", ""),
            SessionEvent.AskDecided(id(), "a1", Approvals.ONCE),
            SessionEvent.QuestionAsked(id(), "q1", "Which one?"),
            SessionEvent.ToolStarted(id(), "c1", "tap", ""),
        )

        val unsettled = Projection.unsettled(events)

        assertEquals(2, unsettled.size)
        assertTrue(unsettled.any { it is SessionEvent.QuestionAsked })
        assertTrue(unsettled.any { it is SessionEvent.ToolStarted })
    }

    @Test
    fun `trimming keeps whole steps and always opens on the user`() {
        val events = mutableListOf<SessionEvent>(SessionEvent.UserMessage(id(), "start"))
        repeat(20) { index ->
            val callId = "c$index"
            events += SessionEvent.AssistantMessage(id(), calls = listOf(call("ui_tree", callId)))
            events += SessionEvent.ToolFinished(id(), callId, "x".repeat(2_000))
        }

        val trimmed = Projection.trim(Projection.messages(events), budgetChars = 6_000)

        assertTrue(trimmed.isNotEmpty())
        // The task itself is always at the front, however little of the tail fits
        val opening = assertIs<Turn.User>(trimmed.first())
        assertTrue(opening.text.startsWith("start"))
        assertTrue(trimmed.size < 41, "the whole conversation was kept")
        trimmed.forEachIndexed { index, turn ->
            if (turn is Turn.ToolResults) {
                assertIs<Turn.Assistant>(trimmed[index - 1], "a result lost its call")
            }
        }
    }
}
