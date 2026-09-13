package com.foxislam.androidagent.agent

import android.content.Context
import com.foxislam.androidagent.control.ControlService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The single in-flight run. It belongs to the accessibility service, not the Activity: the
 * agent spends most of a run inside other apps, and the user may switch chats while it
 * works, so the run remembers which thread it is writing to
 */
object AgentSession {

    private val _runningThreadId = MutableStateFlow<String?>(null)

    val runningThreadId: StateFlow<String?> = _runningThreadId.asStateFlow()

    private var job: Job? = null

    /** Typed at the agent mid-run. Picked up at the next step boundary */
    private val steering = ConcurrentLinkedQueue<String>()

    fun isRunning(threadId: String?): Boolean =
        threadId != null && _runningThreadId.value == threadId

    fun start(
        context: Context,
        threadId: String,
        prompt: String,
        backend: LlmBackend,
        image: SharedImage? = null,
        contextLimit: Int = 128_000,
        fastModel: String = "",
    ) {
        val service = ControlService.instance
        if (service == null) {
            Chats.record(
                threadId,
                SessionEvent.Failed(
                    Chats.nextEventId(),
                    "Accessibility service is not enabled. Turn it on in Settings first.",
                ),
            )
            return
        }
        if (_runningThreadId.value != null) return

        _runningThreadId.value = threadId
        steering.clear()

        Chats.ensureLoaded(threadId)
        Chats.markRunning(threadId, true)
        val planMode = Chats.thread(threadId)?.planMode ?: false
        // The prompt is an event like any other, so the loop reads it back out of the log
        // along with everything before it instead of being handed it separately
        Chats.record(threadId, SessionEvent.UserMessage(Chats.nextEventId(), prompt, image?.path))

        job = service.scope.launch {
            try {
                AgentLoop(
                    service = service,
                    context = context.applicationContext,
                    backend = backend,
                    threadId = threadId,
                    contextLimit = contextLimit,
                    planMode = planMode,
                    router = Router(fastModel),
                ).run(prompt, image?.base64)
            } catch (e: CancellationException) {
                Chats.record(threadId, SessionEvent.Failed(Chats.nextEventId(), "Stopped."))
                throw e
            } catch (e: Throwable) {
                Chats.record(
                    threadId,
                    SessionEvent.Failed(Chats.nextEventId(), e.message ?: e.javaClass.simpleName),
                )
                RunService.finish(context.applicationContext, false, e.message ?: "The run failed.")
            } finally {
                _runningThreadId.value = null
                steering.clear()
                Chats.clearLive()
                Chats.settle(threadId)
                Chats.markRunning(threadId, false)
                RunService.stop(context.applicationContext)
                OverlayIndicator.hide()
            }
        }
    }

    /**
     * A saved script, run at the user's request instead of at a model's.
     *
     * There is no model in this: the script already holds every step, so a round trip to be
     * told to run it would add cost and nothing else. It still goes through
     * [ToolPipeline], so every action inside is gated and recorded exactly as it would be
     * mid-conversation, and it goes into a chat the user can read afterwards.
     *
     * Returns null when it started, or why it could not
     */
    fun runSaved(context: Context, script: SavedScript, args: Map<String, Any?>): String? {
        val service = ControlService.instance
            ?: return "Accessibility service is not enabled. Turn it on in Settings first."
        if (_runningThreadId.value != null) return "Something is already running."

        val app = context.applicationContext
        val threadId = Chats.newChat()
        _runningThreadId.value = threadId
        steering.clear()
        Chats.ensureLoaded(threadId)
        Chats.markRunning(threadId, true)
        Chats.record(
            threadId,
            SessionEvent.UserMessage(Chats.nextEventId(), "Run the saved script \"${script.name}\"."),
        )

        job = service.scope.launch {
            val status: (String) -> Unit = { text ->
                RunService.start(app, text)
                OverlayIndicator.show(app, text)
            }
            try {
                status("Running ${script.name}")
                val pipeline = ToolPipeline(
                    tools = Tools(service, app, threadId),
                    service = service,
                    context = app,
                    threadId = threadId,
                    onStatus = status,
                )
                val outcome = pipeline.execute(
                    ToolCall(
                        id = "user_${System.currentTimeMillis()}",
                        name = Tools.callScript.name,
                        input = mapOf("name" to script.name, "args" to JSONObject(args)),
                    ),
                )
                val text = Projection.resultText(outcome)
                Chats.record(threadId, SessionEvent.Finished(Chats.nextEventId(), text))
                RunService.finish(app, true, text.lines().firstOrNull().orEmpty())
            } catch (e: CancellationException) {
                Chats.record(threadId, SessionEvent.Failed(Chats.nextEventId(), "Stopped."))
                throw e
            } catch (e: Throwable) {
                val why = e.message ?: e.javaClass.simpleName
                Chats.record(threadId, SessionEvent.Failed(Chats.nextEventId(), why))
                RunService.finish(app, false, why)
            } finally {
                _runningThreadId.value = null
                steering.clear()
                Chats.clearLive()
                Chats.settle(threadId)
                Chats.markRunning(threadId, false)
                RunService.stop(app)
                OverlayIndicator.hide()
            }
        }
        return null
    }

    /**
     * A correction, mid-run. The alternative is stopping the agent and starting again, which
     * throws away everything it has worked out about where it is
     */
    fun steer(threadId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !isRunning(threadId)) return
        // Recorded the moment it is typed, so it is in the chat even if the run ends before
        // the loop gets to it. A question on screen takes the answer instead
        if (Questions.pending.value?.threadId == threadId) {
            Questions.answerCurrent(trimmed)
            Chats.record(threadId, SessionEvent.UserMessage(Chats.nextEventId(), trimmed, steering = true))
            return
        }
        Chats.record(threadId, SessionEvent.UserMessage(Chats.nextEventId(), trimmed, steering = true))
        steering += trimmed
    }

    fun drainSteering(): List<String> {
        val drained = mutableListOf<String>()
        while (true) drained += steering.poll() ?: return drained
    }

    fun stop() {
        // A run stopped mid-question leaves a prompt on screen nobody will ever answer
        Approvals.resolveCurrent(Approvals.DENIED)
        Plans.resolveCurrent(Plans.REJECTED)
        Questions.answerCurrent(Questions.UNANSWERED)
        job?.cancel()
        job = null
        _runningThreadId.value = null
    }
}
