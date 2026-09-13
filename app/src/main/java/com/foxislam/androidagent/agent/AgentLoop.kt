package com.foxislam.androidagent.agent

import android.content.Context
import android.util.Log
import com.foxislam.androidagent.control.ControlService
import com.foxislam.androidagent.mcp.McpServers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import kotlin.coroutines.coroutineContext

/**
 * Hand-written tool loop: results carry images, every step must be cancellable mid-run,
 * and each step streams to the UI as it happens.
 *
 * The loop owns no history of its own. It starts from the chat's log, projected into
 * provider messages, and writes every step back as events, so a run resumed tomorrow sees
 * exactly what this one saw and the transcript on screen comes from the same events
 */
class AgentLoop(
    private val service: ControlService,
    private val context: Context,
    private val backend: LlmBackend,
    private val threadId: String,
    private val contextLimit: Int = DEFAULT_CONTEXT_LIMIT,
    private val planMode: Boolean = false,
    private val router: Router = Router(""),
) {

    private val tools = Tools(service, context, threadId)
    private val compactor = Compactor(backend)
    private val pipeline = ToolPipeline(tools, service, context, threadId, ::status)
    private val turns = mutableListOf<Turn>()

    /** The original ask. Skill keywords are matched against it */
    private var task = ""

    suspend fun run(prompt: String, image: String? = null) {
        task = prompt
        turns += Projection.trim(Chats.messages(threadId), REPLAY_BUDGET_CHARS)
        attach(image)
        Approvals.unreachable(context)?.let {
            Chats.record(threadId, SessionEvent.Note(Chats.nextEventId(), it))
        }
        status("Working on it")

        // Plan mode holds the acting tools back until the user has seen the plan; once they
        // have, the rest of the run is ordinary
        var planning = planMode
        var step = 0
        var recovering = false
        var mechanical = false

        while (true) {
            coroutineContext.ensureActive()
            val steered = injectSteering()
            compactIfNeeded()

            val route = router.route(
                Router.State(
                    step = step,
                    planning = planning,
                    recovering = recovering,
                    steering = steered,
                    mechanical = mechanical,
                ),
            )

            val reply = nextReply(planning, route)
            turns += reply
            reply.usage?.let { Chats.addUsage(threadId, it) }
            Chats.record(threadId, Projection.recorded(Chats.nextEventId(), reply))
            Chats.clearLive()
            step++

            if (reply.refused) {
                finish(SessionEvent.Failed(Chats.nextEventId(), "The model declined this request."))
                return
            }
            if (reply.calls.isEmpty()) {
                if (reply.isEmpty) {
                    finish(SessionEvent.Failed(Chats.nextEventId(), endedWithout(reply)))
                    return
                }

                // A model that answers in prose instead of calling done has still finished.
                // Recording it as the end keeps the chat, the notification and a resumed run
                // agreeing about how this one ended
                finish(SessionEvent.Finished(Chats.nextEventId(), reply.text.orEmpty()))
                return
            }

            val outputs = mutableListOf<ToolOutput>()
            for (call in reply.calls) {
                coroutineContext.ensureActive()

                if (call.name == "propose_plan") {
                    val verdict = decidePlan(call)
                    if (verdict == null) {
                        turns += Turn.ToolResults(
                            outputs + ToolOutput(call, Outcome.Text("The user rejected the plan.")),
                        )
                        return
                    }
                    planning = false
                    outputs += ToolOutput(call, Outcome.Text(verdict))
                    continue
                }

                val outcome = pipeline.execute(call)
                outputs += ToolOutput(call, outcome)
                if (outcome is Outcome.Done) {
                    turns += Turn.ToolResults(outputs)
                    finish(SessionEvent.Finished(Chats.nextEventId(), outcome.summary))
                    return
                }
            }

            turns += Turn.ToolResults(outputs)
            recovering = !Router.clean(outputs)
            mechanical = Router.mechanical(outputs)
        }
    }

    /**
     * A picture shared in from another app belongs to the message that arrived with it. Only
     * this run has the pixels - the log keeps the file path, not the image - so it is fitted
     * to the last user turn here instead of in the projection
     */
    private fun attach(image: String?) {
        if (image == null) return
        val last = turns.lastOrNull() as? Turn.User ?: return
        turns[turns.lastIndex] = last.copy(imageBase64 = image)
    }

    private suspend fun compactIfNeeded() {
        val note = compactor.compact(turns, contextLimit) ?: return
        Log.i(TAG, "compacted: $note")
        Chats.record(threadId, SessionEvent.Note(Chats.nextEventId(), note))
    }

    /**
     * Anything the user typed while the agent was working goes in before the next turn. The
     * event itself was recorded the moment they sent it, so this only puts it in front of the
     * model; returning true tells the router this step is not a mechanical one
     */
    private fun injectSteering(): Boolean {
        val pending = AgentSession.drainSteering()
        pending.forEach { text ->
            Log.i(TAG, "steering: $text")
            turns += Turn.User(text)
        }
        return pending.isNotEmpty()
    }


    /**
     * Models intermittently return a turn with no text and no tool call. That is not the end
     * of the task, so retry, then prod once, before treating it as the end
     */
    private suspend fun nextReply(planning: Boolean, route: String?): Turn.Assistant {
        val toolset = Tools.available(planning, Skills.all.value.isNotEmpty(), McpServers.definitions())
        val system = systemPrompt(planning)

        var last = backend.next(system, turns, toolset, route, ::onDelta)
        var attempt = 0
        while (last.isEmpty && !last.refused && attempt < EMPTY_REPLY_RETRIES) {
            attempt++
            Log.w(TAG, "empty reply (finish=${last.finishReason}), retry $attempt")
            delay(RETRY_DELAY_MS * attempt)
            // An empty turn counts as unexpected, so the good model handles it
            last = backend.next(system, turns, toolset, null, ::onDelta)
        }
        if (!last.isEmpty || last.refused) return last

        turns += Turn.User("Continue. Call a tool to make progress, or call done with a summary.")
        return backend.next(system, turns, toolset, null, ::onDelta)
    }

    private fun onDelta(delta: Delta) {
        when (delta) {
            is Delta.Reset -> Chats.stream(threadId, text = "", thinking = "")
            is Delta.Text -> Chats.stream(threadId, text = delta.chunk)
            is Delta.Thinking -> Chats.stream(threadId, thinking = delta.chunk)
        }
    }

    /** Why the run ended, in words the user can act on */
    private fun endedWithout(reply: Turn.Assistant): String = when (reply.finishReason) {
        "tool_calls" -> "The model tried to call a tool but sent an empty one, and kept doing " +
            "so on retry. This is a provider glitch, not the end of the task - say " +
            "\"continue\" to resume" + (reply.generationId?.let { " [$it]" } ?: "") + "."
        "length" -> "The model hit its output limit mid-answer. Try a shorter task, or a " +
            "model with more output headroom."
        "error" -> "The provider reported an error for this turn. Check the model is still " +
            "available on your endpoint."
        else -> "The model returned nothing to do and never called done" +
            (reply.finishReason?.let { " (finish reason: $it)" } ?: "") +
            (reply.generationId?.let { " [$it]" } ?: "") +
            ". The task is probably unfinished - say \"continue\" to pick it back up."
    }


    /** Returns what to tell the model, or null when the run is over */
    private suspend fun decidePlan(call: ToolCall): String? {
        val steps = (call.input["steps"] as? JSONArray)
            ?.let { array -> (0 until array.length()).map { array.optString(it) } }
            ?.filter { it.isNotBlank() }
            ?: listOfNotNull(call.input["steps"]?.toString()?.takeIf { it.isNotBlank() })
        val note = call.input["note"]?.toString().orEmpty()

        Chats.record(
            threadId,
            SessionEvent.ToolStarted(Chats.nextEventId(), call.id, call.name, "${steps.size} steps"),
        )
        val answer = plan(steps, note)
        Chats.record(
            threadId,
            SessionEvent.ToolFinished(Chats.nextEventId(), call.id, answer ?: "The user rejected the plan."),
        )
        return answer
    }

    private suspend fun plan(steps: List<String>, note: String): String? {
        if (steps.isEmpty()) return "The plan had no steps in it. Propose one with steps listed."

        status("Waiting for you to approve the plan")
        return when (Plans.propose(context, threadId, steps, note)) {
            Plans.APPROVED -> {
                status("Working on it")
                "The user approved the plan. Carry it out, step by step."
            }
            Plans.REJECTED -> {
                finish(SessionEvent.Failed(Chats.nextEventId(), "Plan rejected. Nothing was touched."))
                null
            }
            else -> {
                finish(
                    SessionEvent.Failed(
                        Chats.nextEventId(),
                        "Nobody answered the plan in time. Nothing was touched.",
                    ),
                )
                null
            }
        }
    }


    private fun status(text: String) {
        RunService.start(context, text)
        OverlayIndicator.show(context, text)
    }

    /**
     * The user is usually in another app when a run ends, or has the phone in a pocket, so
     * this is posted as a notification too
     */
    private fun finish(event: SessionEvent) {
        Chats.record(threadId, event)
        Chats.clearLive()
        val text = when (event) {
            is SessionEvent.Finished -> event.text
            is SessionEvent.Failed -> event.text
            else -> ""
        }
        RunService.finish(context, event is SessionEvent.Finished, text)
    }


    private fun systemPrompt(planning: Boolean): String {
        val metrics = context.resources.displayMetrics
        val packageName = ControlService.foregroundPackage
        val base = """
            You are driving an Android phone on the user's behalf, through an accessibility
            service. The screen is ${metrics.widthPixels}x${metrics.heightPixels} pixels;
            0,0 is top-left.

            How to work:
            - Start with ui_tree. It is cheap and gives you exact, tappable targets. Only take
              a screenshot when the tree is empty or too ambiguous to act on, or when the task
              is genuinely about what something looks like.
            - Prefer tap with a node index over tap with coordinates. Node indices come from the
              most recent ui_tree, so call ui_tree again after anything that changes the screen.
            - After an action that navigates or loads, wait briefly, then look again. Do not
              assume an action worked.
            - Text fields need focus first: tap the field, then type_text.
            - When several actions are certain and need no looking in between, send them as one
              run_steps call. Each separate call costs a round trip.
            - When what to do next depends on what is on screen - a toggle to tap only if it is
              off, a list to scroll until something appears, the same few steps over several
              items - write one run_script instead of looking and acting turn by turn. It
              matches on text and ids instead of saved indices, so it survives a screen
              that renumbers underneath it. Have it call phone.log to say what it decided.
            - If one of the saved scripts below already does what is being asked, call it
              instead of writing the script again. When you get a script working and the user
              would plausibly want it again, save_script it, reading the parts that vary out
              of args so the saved copy covers more than today's case.
            - When you cannot tell which of two things the user meant, call ask_user_question
              instead of guessing or stopping.
            - Call done as soon as the task is complete, or as soon as you are blocked. Say
              plainly in the summary what you did or why you stopped.

            Limits you will hit, and what they mean:
            - A black screenshot or an empty tree on a banking, payment or DRM screen means the
              window is FLAG_SECURE. You cannot see it. Stop and tell the user.
            - Runtime permission dialogs are invisible to you and cannot be tapped. If one
              appears, stop and ask the user to handle it.
            - Some apps refuse to run at all while an accessibility service is enabled.
            - You cannot act on a locked or sleeping screen.

            A small black rectangle near the top of a screenshot is this app's own activity
            indicator, excluded from capture. Ignore it - it is not part of the app you are
            looking at.

            Judgement: you are operating someone's real phone with their real accounts. Do the
            task you were given and nothing beyond it. Stop and call done if a step would send
            a message, spend money, delete data, or post publicly and the user did not clearly
            ask for it.
        """.trimIndent()

        return base +
            approvalSection() +
            planSection(planning) +
            remoteSection() +
            todoSection() +
            contextSection(packageName) +
            Memory.promptSection() +
            Skills.catalogue() +
            Skills.render(Skills.autoLoaded(packageName, task)) +
            SavedScripts.catalogue()
    }

    private fun approvalSection(): String = when (Approvals.mode) {
        Approvals.Mode.AUTO -> ""
        Approvals.Mode.RISKY ->
            "\n\nSome actions are held for the user to approve before they happen. A tool " +
                "result saying the user refused is final: do not try the same thing another way."
        Approvals.Mode.EVERYTHING ->
            "\n\nEvery action is held for the user to approve before it happens, so keep each " +
                "step small and obvious. A refusal is final."
    }

    private fun planSection(planning: Boolean): String {
        if (!planning) return ""
        return "\n\nYou are in plan mode. You can look - ui_tree, screenshot, list_apps - but " +
            "you cannot touch anything yet. Look enough to be specific, then call propose_plan " +
            "with the steps you intend to take. Acting tools are handed to you once the user " +
            "approves."
    }

    private fun remoteSection(): String {
        if (McpServers.definitions().isEmpty()) return ""
        return "\n\nTools whose names start with mcp__ run on a remote server, not on this " +
            "phone. They are often the shortest path - looking something up over an API beats " +
            "tapping through an app to find it."
    }

    private fun todoSection(): String {
        val todos = Chats.todos(threadId)
        if (todos.isEmpty()) return ""
        return "\n\nYour checklist for this task, as you last wrote it:\n" +
            todos.joinToString("\n") { "- [${it.state}] ${it.text}" } +
            "\nKeep it current with todo_write as you go."
    }

    private fun contextSection(packageName: String?): String =
        packageName?.let { "\n\nThe app in the foreground right now is $it." }.orEmpty()

    private companion object {
        const val TAG = "AgentLoop"
        const val EMPTY_REPLY_RETRIES = 2
        const val RETRY_DELAY_MS = 700L
        const val REPLAY_BUDGET_CHARS = 24_000
        const val DEFAULT_CONTEXT_LIMIT = 128_000
    }
}
