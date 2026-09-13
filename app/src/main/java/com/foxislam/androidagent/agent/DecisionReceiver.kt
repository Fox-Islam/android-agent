package com.foxislam.androidagent.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Answers an approval from the notification shade.
 *
 * A broadcast instead of a service start: the answer has to work whether or not the
 * foreground service happens to be up, and nothing here needs a lifecycle of its own
 */
class DecisionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val decision = intent.getStringExtra(EXTRA_DECISION) ?: return
        when (intent.getStringExtra(EXTRA_KIND)) {
            KIND_ASK -> Approvals.resolve(id, decision)
            KIND_PLAN -> Plans.resolve(id, decision)
            KIND_QUESTION -> Questions.answer(id, decision)
        }
        RunService.clearAsk(context)
    }

    companion object {
        const val KIND_ASK = "ask"
        const val KIND_PLAN = "plan"
        const val KIND_QUESTION = "question"

        private const val ACTION = "com.foxislam.androidagent.DECIDE"
        private const val EXTRA_ID = "id"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_DECISION = "decision"

        fun intent(context: Context, id: String, kind: String, decision: String): Intent =
            Intent(context, DecisionReceiver::class.java)
                .setAction("$ACTION.$kind.$decision.$id")
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_DECISION, decision)
    }
}
