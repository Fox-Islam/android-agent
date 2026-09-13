package com.foxislam.androidagent.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.PowerManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import com.foxislam.androidagent.SettingsStore
import com.foxislam.androidagent.agent.Approvals
import com.foxislam.androidagent.agent.Chats
import com.foxislam.androidagent.agent.Hooks
import com.foxislam.androidagent.agent.Memory
import com.foxislam.androidagent.agent.SavedPrompts
import com.foxislam.androidagent.agent.SavedScripts
import com.foxislam.androidagent.agent.Skills
import com.foxislam.androidagent.mcp.McpServers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The privileged half of the app. An enabled accessibility service is system-bound and
 * high-priority, so it is also the natural host for a long agent run: no foreground
 * service, and background-start restrictions do not apply to it
 */
class ControlService : AccessibilityService() {

    /**
     * Recreated on each connect. [onUnbind] cancels it, and a cancelled scope never runs
     * anything again - so without this a rebound service silently does nothing for the rest
     * of the process's life
     */
    var scope = CoroutineScope(SupervisorJob())
        private set

    private var lastCaptureAt = 0L
    private var lastSnapshot: List<UiNode> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob())
        // The Activity is not the only way into a run, and the system can restart this
        // process on its own - so everything a run reads is loaded here too. Each of these
        // is a no-op the second time
        Chats.load(this)
        Memory.load(this)
        Skills.load(this)
        Hooks.load(this)
        SavedPrompts.load(this)
        SavedScripts.load(this)
        McpServers.load(this)
        Approvals.mode = SettingsStore.get(this).approvalMode
        Log.i(TAG, "control service connected")
    }

    /**
     * Android binds a replacement before it tears the old one down, so for a moment two
     * instances exist. Only the instance still registered may clear the shared state -
     * clearing it unconditionally wipes what the live one has set, leaving the app reporting
     * the service off while it runs and [instance] null for every run that tries to start
     */
    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
            _connected.value = false
        }
        scope.cancel()
        Log.i(TAG, "control service unbound")
        return super.onUnbind(intent)
    }

    /**
     * Node indices are only meaningful for the screen they were read from. When the window
     * changes, drop the snapshot so a stale `tap {node: N}` fails loudly and the agent
     * re-reads the tree, instead of silently tapping where that node used to be
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastSnapshot = emptyList()
            // Which app is in front decides whether an action needs approval, so it is
            // tracked continuously: by the time the question is asked the window may
            // already have changed
            event.packageName?.toString()
                ?.takeUnless { it == packageName || it in IGNORED_PACKAGES || it in imePackages }
                ?.let { foregroundPackage = it }
        }
    }

    /**
     * The keyboard is a window like any other and announces itself the moment it opens, so
     * without this the agent believes it is working "in" the IME - and an approval granted
     * for an app would be recorded against the keyboard instead
     */
    private val imePackages: Set<String> by lazy {
        getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.mapTo(mutableSetOf()) { it.packageName }
            .orEmpty()
    }

    /**
     * Whether what is about to be typed into would be a secret. The text itself is the tool's
     * argument and the model needs it, but nothing else does: not the transcript, not the log
     * on disk, and not the approval prompt someone may be reading in public
     */
    fun isSecretTarget(nodeIndex: Int?): Boolean {
        val node = if (nodeIndex != null) nodeAt(nodeIndex)?.node else focusedEditable()
        return runCatching { node?.isPassword == true }.getOrDefault(false)
    }

    /**
     * Why the screen cannot be acted on, or null when it can. Gestures dispatched at a dark
     * or locked screen are silently dropped, so without this the agent taps into nothing and
     * reports success
     */
    fun screenProblem(): String? {
        val power = getSystemService(PowerManager::class.java)
        if (power?.isInteractive == false) {
            return "The screen is off, so nothing can be tapped. Ask the user to wake the phone."
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            return "The phone is locked, so nothing can be tapped. Ask the user to unlock it."
        }
        return null
    }

    /** What the user would call the thing about to be touched, for the approval prompt */
    fun describeNode(index: Int): String? = nodeAt(index)?.let { node ->
        listOfNotNull(node.text, node.contentDescription, node.viewId)
            .firstOrNull { it.isNotBlank() }
            ?.take(60)
    }

    override fun onInterrupt() = Unit


    /**
     * takeScreenshot() is rate-limited; calling it too soon fails with
     * ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT, so wait out the interval instead of burning
     * a turn on the error
     */
    suspend fun capture(): Bitmap {
        val sinceLast = SystemClock.uptimeMillis() - lastCaptureAt
        if (sinceLast < MIN_CAPTURE_INTERVAL_MS) delay(MIN_CAPTURE_INTERVAL_MS - sinceLast)
        lastCaptureAt = SystemClock.uptimeMillis()

        return suspendCancellableCoroutine { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        try {
                            // The hardware bitmap is only valid while the buffer is open,
                            // so copy into a software bitmap before releasing it. Leaking
                            // the buffer breaks every subsequent capture
                            val copied = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            if (copied == null) {
                                cont.resume(blankFallback())
                            } else {
                                cont.resume(copied)
                            }
                        } finally {
                            buffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resume(blankFallback())
                        Log.w(TAG, "screenshot failed: $errorCode")
                    }
                },
            )
        }
    }

    private fun blankFallback(): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)


    /**
     * Flattens every interactive or labelled node across all visible windows. Walking
     * `windows` instead of just `rootInActiveWindow` keeps the IME and any overlay in view,
     * which matters when the agent is trying to find a keyboard key
     */
    fun snapshot(): List<UiNode> {
        val collected = mutableListOf<UiNode>()
        val roots = LinkedHashSet<AccessibilityNodeInfo>()
        // Our own floating indicator is a real window and shows up here like any other.
        // importantForAccessibility on the view is not enough - the window's own node
        // survives - so drop the whole window by its root id, or the agent can end up
        // tapping its own STOP button
        windows.mapNotNullTo(roots) { window ->
            window.root?.takeUnless { it.viewIdResourceName == OVERLAY_ROOT_ID }
        }
        rootInActiveWindow?.let(roots::add)
        roots.forEach { collect(it, collected, 0) }
        lastSnapshot = collected
        return collected
    }

    private fun collect(node: AccessibilityNodeInfo?, into: MutableList<UiNode>, depth: Int) {
        if (node == null || depth > MAX_TREE_DEPTH || into.size >= MAX_NODES) return
        if (node.isVisibleToUser) interesting(node, into.size)?.let(into::add)
        for (i in 0 until node.childCount) collect(node.getChild(i), into, depth + 1)
    }

    private fun interesting(node: AccessibilityNodeInfo, index: Int): UiNode? {
        val text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val desc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val actionable = node.isClickable || node.isLongClickable || node.isEditable ||
            node.isCheckable || node.isScrollable
        if (!actionable && text == null && desc == null) return null

        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.width() <= 0 || bounds.height() <= 0) return null

        return UiNode(
            index = index,
            className = node.className?.toString()?.substringAfterLast('.') ?: "View",
            text = text,
            contentDescription = desc,
            viewId = node.viewIdResourceName?.substringAfterLast('/'),
            bounds = bounds,
            clickable = node.isClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            focused = node.isFocused,
            node = node,
        )
    }

    fun nodeAt(index: Int): UiNode? = lastSnapshot.getOrNull(index)?.takeIf { it.index == index }

    /**
     * What the last look found, unrendered. A script tests `checked` and reads `text`, which
     * the line-per-node text the model reads is the wrong shape for
     */
    val nodes: List<UiNode> get() = lastSnapshot


    suspend fun tap(x: Int, y: Int): Boolean = gesture(
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(pathOf(x, y), 0, TAP_DURATION_MS))
            .build(),
    )

    /** Prefers ACTION_CLICK on the live node, falling back to a gesture at its centre */
    suspend fun tapNode(index: Int): String {
        val target = nodeAt(index)
            ?: return "No node $index in the current tree. The screen changed, so call ui_tree again."
        val live = target.node
        if (live != null && live.refresh()) {
            val clickable = generateSequence(live) { it.parent }.take(CLICK_ANCESTOR_LIMIT)
                .firstOrNull { it.isClickable }
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "Clicked node $index."
            }
        }
        val ok = tap(target.centerX, target.centerY)
        return if (ok) "Tapped node $index at ${target.centerX},${target.centerY}."
        else "Failed to tap node $index."
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return gesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50, 10_000)))
                .build(),
        )
    }

    private suspend fun gesture(description: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                description,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!dispatched && cont.isActive) cont.resume(false)
        }

    private fun pathOf(x: Int, y: Int) = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

    /**
     * Types into the input-focused field. Password and custom input views frequently reject
     * ACTION_SET_TEXT, so the failure is reported instead of silently swallowed
     */
    fun typeText(text: String, nodeIndex: Int?): String {
        val target = resolveTextTarget(nodeIndex)
            ?: return "No editable field is focused. Tap the field first, then type."

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) "Typed into ${target.className?.toString()?.substringAfterLast('.')}."
        else "The field rejected programmatic text entry (likely a password or custom input)."
    }

    private fun resolveTextTarget(nodeIndex: Int?): AccessibilityNodeInfo? {
        if (nodeIndex != null) {
            val node = nodeAt(nodeIndex)?.node?.takeIf { it.refresh() } ?: return null
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return node
        }
        return focusedEditable()
    }

    /**
     * Tapping a text field opens the IME, and the IME then *is* the active window - so
     * rootInActiveWindow.findFocus() searches the keyboard and finds nothing editable.
     * The service-level findFocus searches every window, which is what is needed here
     */
    private fun focusedEditable(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?.takeIf { it.isEditable }
            ?.let { return it }

        val roots = LinkedHashSet<AccessibilityNodeInfo>()
        rootInActiveWindow?.let(roots::add)
        windows.mapNotNullTo(roots) { it.root }
        return roots.firstNotNullOfOrNull { root ->
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        }
    }

    fun press(key: String): String {
        val action = when (key.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return "Unknown key '$key'. Use back, home, recents or notifications."
        }
        return if (performGlobalAction(action)) "Pressed $key." else "Failed to press $key."
    }

    companion object {
        private const val TAG = "ControlService"

        /** takeScreenshot() throttles below roughly this interval */
        private const val MIN_CAPTURE_INTERVAL_MS = 1_000L
        private const val TAP_DURATION_MS = 60L
        private const val MAX_TREE_DEPTH = 60
        private const val MAX_NODES = 300
        private const val CLICK_ANCESTOR_LIMIT = 5
        private const val OVERLAY_ROOT_ID = "com.foxislam.androidagent:id/overlay_root"

        /** The shade and the launcher are not "an app the agent is working in" */
        private val IGNORED_PACKAGES = setOf("com.android.systemui", "android")

        /**
         * The app the agent is currently standing in. Read by the approval layer, which runs
         * on the agent's coroutine instead of the main thread
         */
        @Volatile
        var foregroundPackage: String? = null
            private set

        @Volatile
        var instance: ControlService? = null
            private set

        private val _connected = MutableStateFlow(false)

        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        val isConnected: Boolean get() = _connected.value && instance != null
    }
}
