package com.foxislam.androidagent.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouterTest {

    private val router = Router("cheap/model")

    private fun state(
        step: Int = 3,
        planning: Boolean = false,
        recovering: Boolean = false,
        steering: Boolean = false,
        mechanical: Boolean = true,
    ) = Router.State(step, planning, recovering, steering, mechanical)

    @Test
    fun `a cruising run uses the cheap model`() {
        assertEquals("cheap/model", router.route(state()))
    }

    @Test
    fun `the first step, planning, and anything unexpected use the good one`() {
        assertNull(router.route(state(step = 0)))
        assertNull(router.route(state(planning = true)))
        assertNull(router.route(state(recovering = true)))
        assertNull(router.route(state(steering = true)))
        assertNull(router.route(state(mechanical = false)))
    }

    @Test
    fun `with no cheap model configured everything goes to the chosen one`() {
        assertNull(Router("").route(state()))
    }

    @Test
    fun `a step is mechanical only when every call in it was`() {
        val tap = ToolOutput(ToolCall("1", "tap", emptyMap()), Outcome.Text("Tapped."))
        val ask = ToolOutput(ToolCall("2", "ask_user_question", emptyMap()), Outcome.Text("yes"))

        val open = ToolOutput(ToolCall("3", "open_app", emptyMap()), Outcome.Text("Launched Clock."))

        assertEquals(true, Router.mechanical(listOf(tap)))
        assertEquals(true, Router.mechanical(listOf(tap, open)))
        assertEquals(false, Router.mechanical(listOf(tap, ask)))
        assertEquals(false, Router.mechanical(emptyList()))
    }

    @Test
    fun `a step that hit trouble is not clean`() {
        val ok = ToolOutput(ToolCall("1", "tap", emptyMap()), Outcome.Text("Tapped node 3."))
        val stale = ToolOutput(ToolCall("2", "tap", emptyMap()), Outcome.Text("No node 7 in the current tree."))

        assertEquals(true, Router.clean(listOf(ok)))
        assertEquals(false, Router.clean(listOf(ok, stale)))
    }
}
