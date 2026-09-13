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
 * Plan mode: look first, say what you are about to do, and wait to be told to go ahead.
 *
 * Until the plan is approved the agent physically cannot act: it is handed a tool list with
 * no way to tap, type or launch anything
 */
object Plans {

    data class Pending(
        val id: String,
        val threadId: String,
        val steps: List<String>,
        val note: String,
    ) {
        val summary: String get() = steps.mapIndexed { i, step -> "${i + 1}. $step" }.joinToString("\n")
    }

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private var waiting: CompletableDeferred<String>? = null

    /** Blocks the run until the user answers, or until waiting stops being reasonable */
    suspend fun propose(
        context: Context,
        threadId: String,
        steps: List<String>,
        note: String,
    ): String {
        val plan = Pending(UUID.randomUUID().toString(), threadId, steps, note)
        val answer = CompletableDeferred<String>()
        waiting = answer
        _pending.value = plan

        Chats.record(threadId, SessionEvent.PlanProposed(Chats.nextEventId(), plan.id, steps, note))
        OverlayIndicator.askPlan(context, plan)
        RunService.askPlan(context, plan)

        val decision = try {
            withTimeout(TIMEOUT_MS) { answer.await() }
        } catch (e: TimeoutCancellationException) {
            Log.i(TAG, "plan ${plan.id} expired")
            EXPIRED
        } catch (e: CancellationException) {
            Chats.record(threadId, SessionEvent.PlanDecided(Chats.nextEventId(), plan.id, REJECTED))
            throw e
        } finally {
            waiting = null
            _pending.value = null
            OverlayIndicator.clearAsk()
            RunService.clearAsk(context)
        }

        Chats.record(threadId, SessionEvent.PlanDecided(Chats.nextEventId(), plan.id, decision))
        return decision
    }

    fun resolve(id: String, decision: String) {
        if (_pending.value?.id != id) return
        waiting?.complete(decision)
    }

    fun resolveCurrent(decision: String) {
        _pending.value?.let { resolve(it.id, decision) }
    }

    const val APPROVED = "approved"
    const val REJECTED = "rejected"
    const val EXPIRED = "expired"

    private const val TAG = "Plans"
    private const val TIMEOUT_MS = 10 * 60 * 1000L
}
