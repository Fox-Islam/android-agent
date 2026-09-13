package com.foxislam.androidagent.agent

/**
 * Which model answers this step.
 *
 * Most of a phone task is mechanical - tap the thing, wait, read the tree, tap the next
 * thing - and a cheap model does that as well as an expensive one. The expensive one is used
 * at the start, when planning, whenever the user says something, and the moment anything is
 * unexpected: a tool that failed, an approval refused, a screen that was not what it expected
 */
class Router(private val fastModel: String) {

    /** Null means "the backend's own model" - the good one */
    fun route(state: State): String? {
        if (fastModel.isBlank()) return null
        if (state.step == 0 || state.planning || state.recovering || state.steering) return null
        if (!state.mechanical) return null
        return fastModel
    }

    data class State(
        val step: Int,
        val planning: Boolean,
        /** The previous step failed, was refused, or came back empty */
        val recovering: Boolean,
        /** The user just said something, which is never a mechanical step */
        val steering: Boolean,
        /** Every call in the previous step was a tap, a look or a wait */
        val mechanical: Boolean,
    )

    companion object {
        private val MECHANICAL = setOf(
            "tap", "swipe", "press", "wait", "ui_tree", "screenshot", "list_apps", "run_steps",
            // Launching an app by name is as mechanical as tapping one: leaving it out puts
            // the good model on every turn that follows opening anything
            "open_app",
        )

        fun mechanical(outputs: List<ToolOutput>): Boolean =
            outputs.isNotEmpty() && outputs.all { it.call.name in MECHANICAL }

        /**
         * Whether the last step went as expected. The same blunt reading the batch runner
         * uses: every tool here reports trouble in words
         */
        fun clean(outputs: List<ToolOutput>): Boolean = outputs.none { output ->
            val text = Projection.resultText(output.outcome)
            TROUBLE.any { text.contains(it, ignoreCase = true) }
        }

        private val TROUBLE = listOf(
            "No node", "Failed", "was cancelled", "not connected", "refused", "rejected",
            "empty", "could not", "too long", "Unknown tool",
        )
    }
}
