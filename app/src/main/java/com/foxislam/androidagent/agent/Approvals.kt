package com.foxislam.androidagent.agent

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.foxislam.androidagent.mcp.McpServers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Decides whether an action gets to happen.
 *
 * Order of authority: a [Hooks] rule, then the user's mode, then a prompt. The prompt has to
 * reach someone who is probably looking at another app, so it appears three times over
 * - in the chat, as a floating card, and as a notification - and any of them can answer it
 */
object Approvals {

    enum class Mode(val label: String, val description: String) {
        AUTO("Never ask", "The agent acts without stopping. Hooks still apply."),
        RISKY("Ask before risky steps", "Sending, paying, deleting or posting, and anything in a money or password app."),
        EVERYTHING("Ask before every step", "Every tap, swipe and keystroke. Slow, and occasionally what you want."),
    }

    /** Held here instead of being read from settings on each call: this is on the hot path */
    @Volatile
    var mode: Mode = Mode.RISKY

    data class Pending(
        val id: String,
        val threadId: String,
        val tool: String,
        val detail: String,
        val reason: String,
    ) {
        val summary: String get() = listOf(tool.replace('_', ' '), detail).filter { it.isNotBlank() }.joinToString(" ")
    }

    data class Verdict(val allowed: Boolean, val message: String)

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    private var waiting: CompletableDeferred<String>? = null

    /**
     * Decides, asking if it has to. [detail] is what the user will read, so it must state
     * what is about to happen in their words - "tap Send £40 to Dan", not "tap node 17"
     */
    suspend fun gate(
        context: Context,
        threadId: String,
        tool: String,
        detail: String,
        packageName: String?,
    ): Verdict {
        val rule = Hooks.decide(tool, detail, packageName)
        if (rule?.action == Hooks.Action.DENY) {
            return Verdict(false, "Refused by a standing rule of the user's: \"${rule.source}\". Do not retry it.")
        }
        if (rule?.action == Hooks.Action.ALLOW) return Verdict(true, "")

        val reason = when {
            rule?.action == Hooks.Action.ASK -> "Your rule asks about this: ${rule.source}"
            !mutating(tool) -> return Verdict(true, "")
            mode == Mode.AUTO -> return Verdict(true, "")
            mode == Mode.EVERYTHING -> "You asked to approve every step."
            else -> riskOf(tool, detail, packageName) ?: return Verdict(true, "")
        }

        return ask(context, threadId, tool, detail, reason, packageName)
    }

    /**
     * Whether a prompt can reach someone who is not looking at the app. Both surfaces off
     * means a run that stops to ask sits until it times out
     */
    fun unreachable(context: Context): String? {
        if (mode == Mode.AUTO) return null
        val notifications = context.getSystemService(NotificationManager::class.java)
            ?.areNotificationsEnabled() ?: false
        if (notifications || OverlayIndicator.canShow(context)) return null
        return "Approvals can only be answered inside the app: the status indicator and " +
            "notifications are both switched off. A prompt raised while you are in another " +
            "app will wait three minutes and then refuse."
    }

    /**
     * What counts as risky when no rule has decided. Kept blunt: an unnecessary prompt
     * costs a tap, a missed one can cost a payment
     */
    fun riskOf(tool: String, detail: String, packageName: String?): String? {
        if (tool.startsWith(McpServers.PREFIX)) {
            return "This calls out to an MCP server, not the phone."
        }
        if (packageName != null && SENSITIVE_PACKAGE.containsMatchIn(packageName)) {
            return "This is a money or security app ($packageName)."
        }
        val hit = RISKY_WORDS.find(detail) ?: return null
        return "This looks irreversible. It mentions \"${hit.value}\"."
    }

    private fun mutating(tool: String): Boolean =
        tool in MUTATING_TOOLS || tool.startsWith(McpServers.PREFIX)

    private suspend fun ask(
        context: Context,
        threadId: String,
        tool: String,
        detail: String,
        reason: String,
        packageName: String?,
    ): Verdict {
        val request = Pending(UUID.randomUUID().toString(), threadId, tool, detail, reason)
        val answer = CompletableDeferred<String>()
        waiting = answer
        _pending.value = request

        Chats.record(
            threadId,
            SessionEvent.AskRaised(Chats.nextEventId(), request.id, tool, detail, reason),
        )
        OverlayIndicator.ask(context, request)
        RunService.ask(context, request)

        val decision = try {
            withTimeout(TIMEOUT_MS) { answer.await() }
        } catch (e: TimeoutCancellationException) {
            Log.i(TAG, "approval ${request.id} expired")
            EXPIRED
        } catch (e: CancellationException) {
            // Stopped while it was asking. Settle the card on the way past, or it sits there
            // offering buttons that answer nothing
            Chats.record(threadId, SessionEvent.AskDecided(Chats.nextEventId(), request.id, STOPPED))
            throw e
        } finally {
            waiting = null
            _pending.value = null
            OverlayIndicator.clearAsk()
            RunService.clearAsk(context)
        }

        Chats.record(threadId, SessionEvent.AskDecided(Chats.nextEventId(), request.id, decision))
        if (decision == ALWAYS) Hooks.allowAlways(tool, packageName)

        return when (decision) {
            ONCE, ALWAYS -> Verdict(true, "")
            EXPIRED -> Verdict(
                false,
                "Nobody answered the approval prompt in time, so this was not done. Stop and " +
                    "call done, saying what is left.",
            )
            else -> Verdict(
                false,
                "The user refused this action. Do not retry it - find another way or call done.",
            )
        }
    }

    fun resolve(id: String, decision: String) {
        if (_pending.value?.id != id) return
        waiting?.complete(decision)
    }

    fun resolveCurrent(decision: String) {
        _pending.value?.let { resolve(it.id, decision) }
    }

    const val ONCE = "allowed"
    const val ALWAYS = "always"
    const val DENIED = "denied"
    const val EXPIRED = "expired"
    const val STOPPED = "stopped"

    private const val TAG = "Approvals"
    private const val TIMEOUT_MS = 3 * 60 * 1000L

    private val MUTATING_TOOLS = setOf("tap", "swipe", "type_text", "press", "open_app")

    private val RISKY_WORDS = Regex(
        "(?i)\\b(send|sending|pay|paying|payment|buy|purchase|order|checkout|transfer|" +
            "withdraw|delete|remove|erase|wipe|uninstall|confirm|submit|publish|post|share|" +
            "call|dial|block|unfriend|unfollow|sign out|log out|factory reset|accept|agree)\\b",
    )

    private val SENSITIVE_PACKAGE = Regex(
        "(?i)(bank|monzo|revolut|starling|barclays|hsbc|lloyds|natwest|santander|chase|" +
            "paypal|wise|coinbase|binance|wallet|\\.pay|payments|authenticator|password|" +
            "keepass|bitwarden|lastpass|1password)",
    )
}
