package com.foxislam.androidagent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.foxislam.androidagent.MainActivity
import com.foxislam.androidagent.R

/**
 * Holds a foreground notification for the duration of a run, and owns the other three things
 * the shade has to carry: the question the agent is blocked on, the plan waiting to be
 * approved, and the news that the run has finished.
 *
 * The run itself lives in the accessibility service, which is already immune to the normal
 * background limits - so this exists for the user, not the scheduler. The agent spends the
 * whole run inside *other* apps, so the shade is the only place these are reliably reachable
 */
class RunService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AgentSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        val status = intent?.getStringExtra(EXTRA_STATUS).orEmpty().ifBlank { "Working on it" }
        val notification = build(this, status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 1
        private const val DONE_ID = 2
        private const val ASK_ID = 3
        private const val CHANNEL_ID = "agent_run"
        private const val DONE_CHANNEL_ID = "agent_done"
        private const val ASK_CHANNEL_ID = "agent_ask"
        private const val ACTION_STOP = "com.foxislam.androidagent.STOP_RUN"
        private const val EXTRA_STATUS = "status"

        /** A notification takes three actions and quietly drops the rest */
        private const val MAX_ACTIONS = 3

        fun start(context: Context, status: String) {
            val intent = Intent(context, RunService::class.java).putExtra(EXTRA_STATUS, status)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RunService::class.java))
        }

        /**
         * The run is over and the user is probably in another app. Without this, the only
         * report of what happened is a notification that silently disappears
         */
        fun finish(context: Context, succeeded: Boolean, text: String) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    DONE_CHANNEL_ID,
                    "Run finished",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "How a run ended, once it has." },
            )
            val body = text.ifBlank { if (succeeded) "Done." else "The run stopped." }
            manager.notify(
                DONE_ID,
                Notification.Builder(context, DONE_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(if (succeeded) "Task finished" else "Run stopped")
                    .setContentText(body)
                    .setStyle(Notification.BigTextStyle().bigText(body))
                    .setContentIntent(openApp(context))
                    .setAutoCancel(true)
                    .build(),
            )
        }

        fun ask(context: Context, request: Approvals.Pending) = askNotification(
            context = context,
            title = "Approve this step?",
            body = "${request.summary}\n${request.reason}",
            id = request.id,
            kind = DecisionReceiver.KIND_ASK,
            choices = listOf("Allow" to Approvals.ONCE, "Deny" to Approvals.DENIED),
        )

        fun askPlan(context: Context, plan: Plans.Pending) = askNotification(
            context = context,
            title = "Approve this plan?",
            body = plan.summary,
            id = plan.id,
            kind = DecisionReceiver.KIND_PLAN,
            choices = listOf("Approve" to Plans.APPROVED, "Reject" to Plans.REJECTED),
        )

        /**
         * A question the agent cannot answer itself. Options become buttons; a question that
         * needs typing has none, and tapping the notification opens the chat to answer in
         */
        fun askQuestion(context: Context, question: Questions.Pending) = askNotification(
            context = context,
            title = "The agent is asking",
            body = question.text,
            id = question.id,
            kind = DecisionReceiver.KIND_QUESTION,
            choices = question.options.take(MAX_ACTIONS).map { it to it },
        )

        fun clearAsk(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(ASK_ID)
        }

        private fun askNotification(
            context: Context,
            title: String,
            body: String,
            id: String,
            kind: String,
            choices: List<Pair<String, String>>,
        ) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    ASK_CHANNEL_ID,
                    "Waiting on you",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Shown when the agent has stopped and needs an answer." },
            )

            val builder = Notification.Builder(context, ASK_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body.replace('\n', ' '))
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setContentIntent(openApp(context))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_CALL)

            choices.take(MAX_ACTIONS).forEachIndexed { index, (label, decision) ->
                builder.addAction(action(context, label, id, kind, decision, 10 + index))
            }
            manager.notify(ASK_ID, builder.build())
        }

        private fun action(
            context: Context,
            label: String,
            id: String,
            kind: String,
            decision: String,
            requestCode: Int,
        ): Notification.Action = Notification.Action.Builder(
            null as android.graphics.drawable.Icon?,
            label,
            PendingIntent.getBroadcast(
                context,
                requestCode,
                DecisionReceiver.intent(context, id, kind, decision),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun build(context: Context, status: String): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Agent run", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the agent is driving your phone, so you can stop it."
                    setShowBadge(false)
                },
            )

            val stop = PendingIntent.getService(
                context,
                1,
                Intent(context, RunService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Android Agent is driving your phone")
                .setContentText(status)
                .setStyle(Notification.BigTextStyle().bigText(status))
                .setContentIntent(openApp(context))
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stop).build())
                .build()
        }
    }
}
