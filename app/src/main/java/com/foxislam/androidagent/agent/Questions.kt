package com.foxislam.androidagent.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * The agent asking the user something.
 *
 * Without this the only way out of "which of these three Dans did you mean" is to stop the
 * run and report the ambiguity. An approval is a yes/no about an action the agent has
 * already chosen; this is the other direction - the agent has no answer and only the user
 * does
 */
object Questions {

    data class Pending(
        val id: String,
        val threadId: String,
        val text: String,
        val options: List<String>,
    )

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private var waiting: CompletableDeferred<String>? = null

    /** Blocks the run until it is answered. The answer is what the tool returns */
    suspend fun ask(
        context: Context,
        threadId: String,
        text: String,
        options: List<String>,
    ): String {
        val question = Pending(UUID.randomUUID().toString(), threadId, text, options.take(MAX_OPTIONS))
        val answer = CompletableDeferred<String>()
        waiting = answer
        _pending.value = question

        Chats.record(
            threadId,
            SessionEvent.QuestionAsked(Chats.nextEventId(), question.id, text, question.options),
        )
        OverlayIndicator.askQuestion(context, question)
        RunService.askQuestion(context, question)

        val reply = try {
            withTimeout(TIMEOUT_MS) { answer.await() }
        } catch (e: TimeoutCancellationException) {
            Log.i(TAG, "question ${question.id} went unanswered")
            UNANSWERED
        } catch (e: CancellationException) {
            Chats.record(
                threadId,
                SessionEvent.QuestionAnswered(Chats.nextEventId(), question.id, "Stopped."),
            )
            throw e
        } finally {
            waiting = null
            _pending.value = null
            OverlayIndicator.clearAsk()
            RunService.clearAsk(context)
        }

        Chats.record(threadId, SessionEvent.QuestionAnswered(Chats.nextEventId(), question.id, reply))
        return reply
    }

    fun answer(id: String, text: String) {
        if (_pending.value?.id != id) return
        waiting?.complete(text.trim().ifEmpty { UNANSWERED })
    }

    fun answerCurrent(text: String) {
        _pending.value?.let { answer(it.id, text) }
    }

    const val UNANSWERED = "The user did not answer. Do not ask again - decide on your own or call done."

    private const val TAG = "Questions"
    private const val TIMEOUT_MS = 10 * 60 * 1000L
    private const val MAX_OPTIONS = 5
}
