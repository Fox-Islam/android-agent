package com.foxislam.androidagent.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** A reply as it is arriving. Shown live, and written to the log only once it settles */
data class Live(val threadId: String, val text: String = "", val thinking: String = "")

/**
 * Every conversation, as an append-only log of events per chat.
 *
 * Nothing here stores what the screen shows or what the model is sent: both are projections
 * of the log ([Projection]), so they cannot drift apart. Streaming is the one thing that does
 * not belong in the log - a token is not an event - so it lives in [live] until the turn
 * settles and is written as a single message.
 *
 * Only metadata is read at startup. A log is read when its chat is opened or run, because
 * otherwise the cost of launching the app grows with every chat ever held on the phone
 */
object Chats {

    private val ids = AtomicLong(System.currentTimeMillis())

    private val _threads = MutableStateFlow<List<ChatThread>>(emptyList())
    val threads: StateFlow<List<ChatThread>> = _threads.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()

    private var store: SessionStore? = null
    private var files: File? = null

    fun load(context: Context) {
        if (store != null) return
        val dir = context.applicationContext.filesDir
        files = dir
        val sessions = SessionStore(File(dir, "chats"))
        store = sessions

        sessions.migrate(File(dir, "chats.json"))
        _threads.value = sessions.load().map { ChatThread(it) }

        // Metadata still marked running means the process went away mid-run, so the chat is
        // offered back to be picked up
        _threads.value.filter { it.meta.running }.forEach { interrupted(it.id) }

        sweepSharedImages()
    }

    fun nextEventId(): Long = ids.incrementAndGet()

    fun thread(id: String?): ChatThread? = _threads.value.firstOrNull { it.id == id }

    fun reload() {
        val sessions = store ?: return
        _threads.value = sessions.load().map { ChatThread(it) }
    }

    fun active(): ChatThread? = thread(_activeId.value)

    fun ensureLoaded(threadId: String): ChatThread? {
        val thread = thread(threadId) ?: return null
        if (thread.loaded) return thread

        val sessions = store ?: return thread
        val events = sessions.trim(threadId, KEEP_EVENTS) ?: sessions.events(threadId)
        val loaded = ChatThread(thread.meta, events, loaded = true)
        _threads.update { list -> list.map { if (it.id == threadId) loaded else it } }
        return loaded
    }

    fun newChat(): String {
        val meta = ChatMeta(id = UUID.randomUUID().toString(), updatedAt = System.currentTimeMillis())
        store?.writeMeta(meta)
        _threads.update { listOf(ChatThread(meta, loaded = true)) + it }
        _activeId.value = meta.id
        return meta.id
    }

    /**
     * A new chat holding everything this one had up to [beforeEventId], and nothing after.
     *
     * For recovery: a run that went wrong at step twelve can restart from step ten with a
     * word of correction, keeping everything the agent had worked out about where it was
     */
    fun fork(threadId: String, beforeEventId: Long): String? {
        val source = ensureLoaded(threadId) ?: return null
        val kept = source.events.takeWhile { it.id != beforeEventId }
        if (kept.isEmpty()) return null

        val meta = ChatMeta(
            id = UUID.randomUUID().toString(),
            title = source.displayTitle.take(50) + " (fork)",
            updatedAt = System.currentTimeMillis(),
            planMode = source.planMode,
            parent = threadId,
        )
        store?.writeMeta(meta)
        store?.copyInto(meta.id, kept)
        _threads.update { listOf(ChatThread(meta, kept, loaded = true)) + it }
        _activeId.value = meta.id
        record(meta.id, SessionEvent.Note(nextEventId(), "Forked from an earlier chat at this point."))
        return meta.id
    }

    fun rename(id: String, title: String) {
        mutateMeta(id) { it.copy(title = title.trim()) }
    }

    fun open(id: String) {
        _activeId.value = id
        ensureLoaded(id)
    }

    fun delete(id: String) {
        store?.delete(id)
        _threads.update { list -> list.filterNot { it.id == id } }
        if (_activeId.value == id) _activeId.value = null
    }

    fun setPlanMode(id: String, enabled: Boolean) {
        mutateMeta(id) { it.copy(planMode = enabled) }
    }


    fun markRunning(threadId: String, running: Boolean) {
        mutateMeta(threadId) { it.copy(running = running, interrupted = if (running) false else it.interrupted) }
    }

    /** Called at startup for a chat whose run never got to finish */
    private fun interrupted(threadId: String) {
        ensureLoaded(threadId)
        settle(threadId, "The app closed before this finished.")
        mutateMeta(threadId) { it.copy(running = false, interrupted = true) }
        record(
            threadId,
            SessionEvent.Note(
                nextEventId(),
                "This run stopped when the app closed. It may have left an app open " +
                    "part-way through something, so check the phone before you use Resume.",
            ),
        )
    }

    fun clearInterrupted(threadId: String) {
        mutateMeta(threadId) { it.copy(interrupted = false) }
    }


    fun record(threadId: String, event: SessionEvent) {
        store?.append(threadId, event)
        _threads.update { list ->
            list.map { thread ->
                if (thread.id != threadId) thread
                else ChatThread(
                    summarise(thread.meta, event).copy(updatedAt = System.currentTimeMillis()),
                    if (thread.loaded) thread.events + event else thread.events,
                    thread.loaded,
                )
            }.sortedByDescending { it.updatedAt }
        }
        writeMeta(threadId)
    }

    /**
     * Keeps the two lines the chat list draws - what was asked, and the last reply - on the
     * metadata, so the list never has to open a single log to render itself
     */
    private fun summarise(meta: ChatMeta, event: SessionEvent): ChatMeta = when {
        event is SessionEvent.UserMessage && meta.title.isBlank() ->
            meta.copy(title = event.text.take(60))
        event is SessionEvent.Finished -> meta.copy(preview = event.text.oneLine())
        event is SessionEvent.Failed -> meta.copy(preview = event.text.oneLine())
        event is SessionEvent.AssistantMessage && !event.text.isNullOrBlank() ->
            meta.copy(preview = event.text.oneLine())
        else -> meta
    }

    private fun String.oneLine(): String = replace('\n', ' ').take(200)

    fun messages(threadId: String): List<Turn> =
        Projection.messages(ensureLoaded(threadId)?.events.orEmpty())

    fun todos(threadId: String): List<TodoItem> = ensureLoaded(threadId)?.todos.orEmpty()

    /**
     * Closes off everything left hanging, so a chat never renders a question that nothing is
     * listening for and never replays a tool call that nothing answered
     */
    fun settle(threadId: String, why: String = "Stopped.") {
        val pending = Projection.unsettled(ensureLoaded(threadId)?.events.orEmpty())
        pending.forEach { event ->
            when (event) {
                is SessionEvent.ToolStarted ->
                    record(threadId, SessionEvent.ToolFinished(nextEventId(), event.callId, why))
                is SessionEvent.AskRaised ->
                    record(threadId, SessionEvent.AskDecided(nextEventId(), event.askId, Approvals.EXPIRED))
                is SessionEvent.PlanProposed ->
                    record(threadId, SessionEvent.PlanDecided(nextEventId(), event.planId, Plans.EXPIRED))
                is SessionEvent.QuestionAsked ->
                    record(threadId, SessionEvent.QuestionAnswered(nextEventId(), event.questionId, why))
                else -> Unit
            }
        }
        writeMeta(threadId)
    }


    fun stream(threadId: String, text: String? = null, thinking: String? = null) {
        _live.update { current ->
            val base = current?.takeIf { it.threadId == threadId } ?: Live(threadId)
            base.copy(text = text ?: base.text, thinking = thinking ?: base.thinking)
        }
    }

    fun clearLive() {
        _live.value = null
    }


    fun addUsage(threadId: String, usage: Usage) {
        if (usage.total == 0 && usage.costUsd == 0.0) return
        mutateMeta(threadId) {
            it.copy(
                promptTokens = it.promptTokens + usage.promptTokens,
                completionTokens = it.completionTokens + usage.completionTokens,
                costUsd = it.costUsd + usage.costUsd,
                contextTokens = if (usage.promptTokens > 0) usage.promptTokens else it.contextTokens,
            )
        }
    }

    private fun mutateMeta(threadId: String, block: (ChatMeta) -> ChatMeta) {
        var written: ChatMeta? = null
        _threads.update { list ->
            list.map { thread ->
                if (thread.id != threadId) thread
                else ChatThread(block(thread.meta).also { written = it }, thread.events, thread.loaded)
            }.sortedByDescending { it.updatedAt }
        }
        written?.let { store?.writeMeta(it) }
    }

    private fun writeMeta(threadId: String) {
        thread(threadId)?.let { store?.writeMeta(it.meta) }
    }

    /**
     * Images shared in from other apps, long after the chat that used them has been read.
     * Nothing else ever deletes them, and they are the largest thing this app writes
     */
    private fun sweepSharedImages() {
        val dir = File(files ?: return, "shared")
        if (!dir.isDirectory) return
        val cutoff = System.currentTimeMillis() - SHARED_IMAGE_TTL_MS
        val stale = dir.listFiles()?.filter { it.lastModified() < cutoff }.orEmpty()
        stale.forEach { it.delete() }
        if (stale.isNotEmpty()) Log.i(TAG, "cleared ${stale.size} shared images")
    }

    private const val TAG = "Chats"
    private const val KEEP_EVENTS = 2_000
    private const val SHARED_IMAGE_TTL_MS = 30L * 24 * 60 * 60 * 1000
}
