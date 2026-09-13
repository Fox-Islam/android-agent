package com.foxislam.androidagent.agent

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.foxislam.androidagent.MainActivity
import com.foxislam.androidagent.R

/**
 * A small floating pill shown while the agent is driving, so it is obvious from inside
 * whatever app it is working in that something is still happening - and reachable to stop.
 *
 * Two things it must never do: appear in the agent's own screenshots, or appear in its
 * accessibility tree. Otherwise the agent sees its own indicator and tries to act on it
 */
object OverlayIndicator {

    private val main = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var label: TextView? = null
    private var windowManager: WindowManager? = null
    private var lastStatus: String = ""

    private var askRoot: View? = null

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context, status: String) = main.post {
        lastStatus = status
        if (!canShow(context)) return@post
        if (root != null) {
            label?.text = status
            return@post
        }

        val manager = context.getSystemService(WindowManager::class.java) ?: return@post
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_indicator, null)

        // Keeps the pill out of ui_tree entirely, so the agent never sees it as a target
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        view.findViewById<TextView>(R.id.overlay_stop).setOnClickListener {
            AgentSession.stop()
            RunService.stop(context)
        }
        dragging(view)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // The one place FLAG_SECURE helps instead of hindering: it excludes this
                // window from screen capture, so the agent never photographs its own
                // indicator. Removing the window per-capture does not work - takeScreenshot
                // serves a frame cached from before the removal
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        runCatching { manager.addView(view, params) }
            .onSuccess {
                windowManager = manager
                root = view
                label = view.findViewById(R.id.overlay_text)
                label?.text = status
            }
    }

    /**
     * The prompt, where the user is: on top of the app the agent is driving. The shade
     * carries the same question and either can answer it, but a card already on screen is
     * noticed sooner, and the run is stopped until one of them is answered
     */
    fun ask(context: Context, request: Approvals.Pending) = showChoice(
        context = context,
        title = "Approve this step?",
        body = "${request.summary}\n${request.reason}",
        choices = listOf(
            Choice("Deny", DENY) { Approvals.resolve(request.id, Approvals.DENIED) },
            Choice("Always", MUTED) { Approvals.resolve(request.id, Approvals.ALWAYS) },
            Choice("Allow", ALLOW) { Approvals.resolve(request.id, Approvals.ONCE) },
        ),
    )

    fun askPlan(context: Context, plan: Plans.Pending) = showChoice(
        context = context,
        title = "Approve this plan?",
        body = plan.summary,
        choices = listOf(
            Choice("Reject", DENY) { Plans.resolve(plan.id, Plans.REJECTED) },
            Choice("Approve", ALLOW) { Plans.resolve(plan.id, Plans.APPROVED) },
        ),
    )

    /**
     * A question with no options is one that needs typing, which a floating card is the wrong
     * place for, so it offers the way back into the app
     */
    fun askQuestion(context: Context, question: Questions.Pending) = showChoice(
        context = context,
        title = "The agent is asking",
        body = question.text,
        choices = if (question.options.isEmpty()) {
            listOf(Choice("Answer", ALLOW) { open(context) })
        } else {
            question.options.map { option ->
                Choice(option, ALLOW) { Questions.answer(question.id, option) }
            }
        },
    )

    private class Choice(val label: String, val tint: Int, val action: () -> Unit)

    private fun open(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }

    private fun showChoice(
        context: Context,
        title: String,
        body: String,
        choices: List<Choice>,
    ) = main.post {
        clearAskNow()
        if (!canShow(context)) return@post
        val manager = context.getSystemService(WindowManager::class.java) ?: return@post
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_ask, null)
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        view.findViewById<TextView>(R.id.ask_title).text = title
        view.findViewById<TextView>(R.id.ask_body).text = body

        val row = view.findViewById<LinearLayout>(R.id.ask_buttons)
        choices.forEach { choice ->
            row.addView(
                TextView(context).apply {
                    text = choice.label
                    setTextColor(choice.tint)
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(20, 24, 20, 24)
                    setOnClickListener { clearAskNow(); choice.action() }
                },
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            y = 80
        }

        runCatching { manager.addView(view, params) }
            .onSuccess {
                windowManager = manager
                askRoot = view
            }
            .onFailure { Log.w(TAG, "could not show the prompt card", it) }
    }

    fun clearAsk() = main.post { clearAskNow() }

    private fun clearAskNow() {
        val view = askRoot ?: return
        runCatching { windowManager?.removeView(view) }
        askRoot = null
    }

    fun hide() = main.post {
        clearAskNow()
        val view = root ?: return@post
        runCatching { windowManager?.removeView(view) }
        root = null
        label = null
        windowManager = null
    }

    /** Lets the user move the pill off whatever it is covering */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragging(view: View) {
        var startX = 0f
        var startY = 0f
        var originX = 0
        var originY = 0
        view.setOnTouchListener { v, event ->
            val params = v.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY
                    originX = params.x; originY = params.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = originX + (event.rawX - startX).toInt()
                    params.y = originY + (event.rawY - startY).toInt()
                    runCatching { windowManager?.updateViewLayout(v, params) }
                    true
                }
                else -> false
            }
        }
    }

    private const val TAG = "Overlay"
    private const val DENY = 0xFFFF8A80.toInt()
    private const val ALLOW = 0xFFA5D6A7.toInt()
    private const val MUTED = 0xFFB0BEC5.toInt()
}
