package com.foxislam.androidagent.control

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * One on-screen element the agent can reason about, flattened out of the accessibility tree.
 *
 * [node] is kept so a tap can go through ACTION_CLICK (more reliable than a synthetic
 * gesture on small or oddly-shaped targets); [bounds] is the fallback when the reference has
 * gone stale because the UI changed in the meantime
 */
class UiNode(
    val index: Int,
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val focused: Boolean,
    val node: AccessibilityNodeInfo?,
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    /** One compact line. Text costs less than an image, so this is the preferred view */
    fun render(): String = buildString {
        append('[').append(index).append("] ").append(className)
        viewId?.let { append(" #").append(it) }
        text?.let { append(' ').append('"').append(it.ellipsise()).append('"') }
        contentDescription
            ?.takeIf { it != text }
            ?.let { append(" desc=\"").append(it.ellipsise()).append('"') }

        val flags = buildList {
            if (clickable) add("clickable")
            if (editable) add("editable")
            if (scrollable) add("scrollable")
            if (focused) add("focused")
            if (checkable) add(if (checked) "checked" else "unchecked")
        }
        if (flags.isNotEmpty()) flags.joinTo(this, ",", " (", ")")

        append(" @").append(centerX).append(',').append(centerY)
        append(" box=").append(bounds.left).append(',').append(bounds.top)
        append('-').append(bounds.right).append(',').append(bounds.bottom)
    }

    private fun String.ellipsise(limit: Int = 120): String =
        if (length <= limit) replace('\n', ' ') else take(limit).replace('\n', ' ') + "..."
}
