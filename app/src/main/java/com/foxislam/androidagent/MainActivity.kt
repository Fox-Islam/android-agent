package com.foxislam.androidagent

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.foxislam.androidagent.agent.AgentSession
import com.foxislam.androidagent.agent.Approvals
import com.foxislam.androidagent.agent.Backup
import com.foxislam.androidagent.agent.ChatThread
import com.foxislam.androidagent.agent.Chats
import com.foxislam.androidagent.agent.Diagnostics
import com.foxislam.androidagent.agent.Hooks
import com.foxislam.androidagent.agent.Images
import com.foxislam.androidagent.agent.Live
import com.foxislam.androidagent.agent.Memory
import com.foxislam.androidagent.agent.OpenRouterBackend
import com.foxislam.androidagent.agent.OverlayIndicator
import com.foxislam.androidagent.agent.Plans
import com.foxislam.androidagent.agent.Questions
import com.foxislam.androidagent.agent.SavedPrompts
import com.foxislam.androidagent.agent.SavedScripts
import com.foxislam.androidagent.agent.SharedImage
import com.foxislam.androidagent.agent.Skills
import com.foxislam.androidagent.control.ControlService
import com.foxislam.androidagent.mcp.McpServers
import com.foxislam.androidagent.ui.AgentTheme
import com.foxislam.androidagent.ui.ChatListScreen
import com.foxislam.androidagent.ui.ChatScreen
import com.foxislam.androidagent.ui.McpScreen
import com.foxislam.androidagent.ui.SavedPromptsScreen
import com.foxislam.androidagent.ui.ScriptsScreen
import com.foxislam.androidagent.ui.SettingsScreen
import com.foxislam.androidagent.ui.SkillsScreen
import com.foxislam.androidagent.ui.WelcomeScreen
import kotlinx.coroutines.launch

private enum class Screen { WELCOME, LIST, CHAT, SETTINGS, SAVED, SKILLS, SCRIPTS, MCP }

private fun accessibilitySettings(): Intent =
    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun overlaySettings(context: Context): Intent =
    Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private data class Handoff(val prompt: String, val image: SharedImage?)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // imePadding()/safeDrawing only track the keyboard once the app stops letting the
        // system fit insets for it; without this the chat input sits under the IME
        enableEdgeToEdge()
        Chats.load(this)
        Memory.load(this)
        SavedPrompts.load(this)
        Skills.load(this)
        SavedScripts.load(this)
        Hooks.load(this)
        McpServers.load(this)
        Approvals.mode = SettingsStore.get(this).approvalMode

        // Remote tool lists go stale, and a run that discovers it mid-turn has already lost
        // the turn. Refreshing at launch keeps the cache current without blocking
        lifecycleScope.launch { McpServers.refreshAll() }

        // Asked for during the walkthrough, not at first launch, where the dialog would
        // arrive with nothing to explain it
        val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        if (SettingsStore.get(this).onboarded) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        askForNotifications = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }

        // A chat of its own, made before the first composition instead of during it
        val handoff = handoff(intent)?.also { Chats.newChat() }
        setContent { AgentTheme { App(handoff) } }
    }

    /**
     * Share sheet, text selection and the assist gesture all arrive here: the entry points
     * from inside another app, where the task usually occurs to you
     */
    private fun handoff(intent: Intent?): Handoff? {
        if (intent == null) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        val image = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?.let { Images.import(this, it) }

        return when {
            image != null -> Handoff(text.orEmpty(), image)
            !text.isNullOrBlank() -> Handoff(text, null)
            intent.action == Intent.ACTION_ASSIST || intent.action == ACTION_NEW_CHAT ->
                Handoff("", null)
            else -> null
        }
    }

    companion object {
        const val ACTION_NEW_CHAT = "com.foxislam.androidagent.NEW_CHAT"

        /**
         * Set once the Activity has a launcher to ask with. The walkthrough calls it, and
         * falls back to the app's notification settings when the system will no longer show
         * the dialog - which it stops doing after the second refusal
         */
        private var askForNotifications: () -> Unit = {}

        fun askNotifications(context: Context) {
            askForNotifications()
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.areNotificationsEnabled() == false) {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

@Composable
private fun App(handoff: Handoff?) {
    val context = LocalContext.current
    val store = remember { SettingsStore.get(context) }
    val scope = rememberCoroutineScope()

    var screen by remember {
        mutableStateOf(
            when {
                !store.onboarded -> Screen.WELCOME
                handoff != null -> Screen.CHAT
                else -> Screen.LIST
            },
        )
    }
    val settings = remember { SettingsState(store) }

    fun backend() = OpenRouterBackend(
        endpoint = store.apiUrl,
        apiKey = store.apiKey,
        model = settings.selectedModel,
        reasoning = settings.reasoning,
        fastModel = settings.fastModel,
    )
    val memory by Memory.notes.collectAsStateWithLifecycle()
    val hooks by Hooks.text.collectAsStateWithLifecycle()
    val savedPrompts by SavedPrompts.all.collectAsStateWithLifecycle()
    val skills by Skills.all.collectAsStateWithLifecycle()
    val scripts by SavedScripts.all.collectAsStateWithLifecycle()
    val servers by McpServers.all.collectAsStateWithLifecycle()
    val serverTools by McpServers.tools.collectAsStateWithLifecycle()
    val serverStatus by McpServers.status.collectAsStateWithLifecycle()

    // There is no backend, so these two are the only route between one install and the next
    var backupSecrets by remember { mutableStateOf(true) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupStatus = writeBackup(context, uri, backupSecrets)
    }
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        backupStatus = readBackup(context, uri)
        // Without this a restored key is on disk and invisible, and Start stays disabled
        settings.reload()
    }

    // Seeds the chat input when a saved task is chosen; cleared once that chat is left
    var pendingPrompt by remember { mutableStateOf(handoff?.prompt.orEmpty()) }
    // An image only rides along with the first thing sent in the chat it arrived in
    var pendingImage by remember { mutableStateOf(handoff?.image) }

    val threads by Chats.threads.collectAsStateWithLifecycle()
    val activeId by Chats.activeId.collectAsStateWithLifecycle()
    val runningThreadId by AgentSession.runningThreadId.collectAsStateWithLifecycle()
    val live by Chats.live.collectAsStateWithLifecycle()
    val serviceEnabled by ControlService.connected.collectAsStateWithLifecycle()

    // Re-read on every recomposition: the user grants these in system settings and comes back
    val overlayGranted = OverlayIndicator.canShow(context)
    val notificationsEnabled = context.getSystemService(NotificationManager::class.java)
        ?.areNotificationsEnabled() ?: false

    BackHandler(enabled = screen != Screen.LIST) {
        pendingPrompt = ""
        screen = if (screen in setOf(Screen.SKILLS, Screen.SCRIPTS, Screen.MCP)) {
            Screen.SETTINGS
        } else {
            Screen.LIST
        }
    }

    when (screen) {
        Screen.WELCOME -> WelcomeScreen(
            apiKey = settings.apiKey,
            onApiKey = settings::setApiKey,
            serviceEnabled = serviceEnabled,
            onEnableService = { context.startActivity(accessibilitySettings()) },
            overlayGranted = overlayGranted,
            onGrantOverlay = { context.startActivity(overlaySettings(context)) },
            notificationsEnabled = notificationsEnabled,
            onEnableNotifications = { MainActivity.askNotifications(context) },
            onImportBackup = {
                backupStatus = null
                openBackup.launch(arrayOf("application/json", "*/*"))
            },
            backupStatus = backupStatus,
            onDone = { store.onboarded = true; screen = Screen.LIST },
        )

        Screen.SETTINGS -> SettingsScreen(
            overlayGranted = overlayGranted,
            onGrantOverlay = { context.startActivity(overlaySettings(context)) },
            apiUrl = settings.apiUrl,
            onApiUrl = settings::setApiUrl,
            onResetApiUrl = settings::resetApiUrl,
            apiKey = settings.apiKey,
            onApiKey = settings::setApiKey,
            memory = memory,
            onMemory = Memory::replace,
            approvalMode = settings.approvalMode,
            onApprovalMode = settings::setApprovalMode,
            hooks = hooks,
            onHooks = Hooks::replace,
            contextLimit = settings.contextLimit,
            onContextLimit = settings::setContextLimit,
            onExportBackup = { backupStatus = null; saveBackup.launch(Backup.filename()) },
            onImportBackup = { backupStatus = null; openBackup.launch(arrayOf("application/json", "*/*")) },
            backupSecrets = backupSecrets,
            onBackupSecrets = { backupSecrets = it },
            backupStatus = backupStatus,
            onExportDiagnostics = {
                val file = Diagnostics.write(context, activeId)
                if (file == null) {
                    Toast.makeText(context, "Could not write the report", Toast.LENGTH_SHORT).show()
                } else {
                    Diagnostics.share(context, file)
                }
            },
            fastModel = settings.fastModel,
            onFastModel = settings::setFastModel,
            skillCount = skills.size,
            onOpenSkills = { screen = Screen.SKILLS },
            serverCount = servers.size,
            onOpenMcp = { screen = Screen.MCP },
            scriptCount = scripts.size,
            onOpenScripts = { screen = Screen.SCRIPTS },
            modelsRaw = settings.modelsRaw,
            onModels = settings::setModels,
            onBack = { screen = Screen.LIST },
        )

        Screen.SKILLS -> SkillsScreen(
            skills = skills,
            onAdd = Skills::add,
            onUpdate = Skills::update,
            onDelete = Skills::delete,
            onBack = { screen = Screen.SETTINGS },
        )

        Screen.SCRIPTS -> ScriptsScreen(
            scripts = scripts,
            // One run at a time, whoever started it
            running = runningThreadId != null,
            onUpdate = SavedScripts::update,
            onDelete = SavedScripts::delete,
            onRun = { script, args -> AgentSession.runSaved(context, script, args) },
            onBack = { screen = Screen.SETTINGS },
        )

        Screen.MCP -> McpScreen(
            servers = servers,
            tools = serverTools,
            status = serverStatus,
            // A server with no tools discovered yet does nothing, so saving one asks it
            // what it can do
            onAdd = { name, url, token ->
                val id = McpServers.add(name, url, token)
                scope.launch { McpServers.refresh(id) }
            },
            onUpdate = { id, name, url, token ->
                McpServers.update(id, name, url, token)
                scope.launch { McpServers.refresh(id) }
            },
            onEnabled = McpServers::setEnabled,
            onDelete = McpServers::delete,
            onRefresh = { McpServers.refresh(it) },
            onBack = { screen = Screen.SETTINGS },
        )

        Screen.LIST -> ChatListScreen(
            threads = threads,
            runningThreadId = runningThreadId,
            onOpen = { Chats.open(it); screen = Screen.CHAT },
            onNew = { Chats.newChat(); screen = Screen.CHAT },
            onDelete = Chats::delete,
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenSaved = { screen = Screen.SAVED },
        )

        Screen.SAVED -> SavedPromptsScreen(
            prompts = savedPrompts,
            onUse = { text ->
                Chats.newChat()
                pendingPrompt = text
                screen = Screen.CHAT
            },
            onAdd = SavedPrompts::add,
            onUpdate = SavedPrompts::update,
            onDelete = SavedPrompts::delete,
            onBack = { screen = Screen.LIST },
        )

        Screen.CHAT -> {
            // Read the thread out of the collected list, not the flow: the chat screen has
            // to recompose when an event arrives in it, and reading Chats.thread() directly
            // subscribes to nothing - the transcript then only moves when something else
            // happens to change
            val thread = threads.firstOrNull { it.id == activeId }
            if (thread == null) {
                screen = Screen.LIST
            } else {
                ChatRoute(
                    thread = thread,
                    settings = settings,
                    backend = ::backend,
                    live = live?.takeIf { it.threadId == thread.id },
                    running = runningThreadId == thread.id,
                    serviceEnabled = serviceEnabled,
                    initialPrompt = pendingPrompt,
                    takeImage = {
                        val image = pendingImage
                        pendingImage = null
                        image
                    },
                    onBack = { pendingPrompt = ""; screen = Screen.LIST },
                )
            }
        }
    }
}

@Composable
private fun ChatRoute(
    thread: ChatThread,
    settings: SettingsState,
    backend: () -> OpenRouterBackend,
    live: Live?,
    running: Boolean,
    serviceEnabled: Boolean,
    initialPrompt: String,
    takeImage: () -> SharedImage?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    ChatScreen(
        title = thread.displayTitle,
        initialPrompt = initialPrompt,
        onRename = { Chats.rename(thread.id, it) },
        onSavePrompt = SavedPrompts::add,
        transcript = thread.entries,
        live = live,
        running = running,
        interrupted = thread.interrupted,
        onResume = {
            Chats.clearInterrupted(thread.id)
            AgentSession.start(
                context = context,
                threadId = thread.id,
                prompt = "Pick up where you left off. Check the screen first - it " +
                    "may have moved on - then carry on with the task, or call done " +
                    "if it is no longer worth continuing.",
                backend = backend(),
                contextLimit = settings.contextLimit,
                fastModel = settings.fastModel,
            )
        },
        onDismissResume = { Chats.clearInterrupted(thread.id) },
        planMode = thread.planMode,
        onPlanMode = { Chats.setPlanMode(thread.id, it) },
        contextTokens = thread.contextTokens,
        contextLimit = settings.contextLimit,
        costUsd = thread.costUsd,
        onDecideAsk = { id, decision -> Approvals.resolve(id, decision) },
        onDecidePlan = { id, decision -> Plans.resolve(id, decision) },
        onSteer = { AgentSession.steer(thread.id, it) },
        onAnswer = { id, answer -> Questions.answer(id, answer) },
        onFork = { eventId ->
            if (Chats.fork(thread.id, eventId) == null) {
                Toast.makeText(context, "Nothing before this message", Toast.LENGTH_SHORT).show()
            }
        },
        models = settings.models,
        selectedModel = settings.selectedModel,
        onSelectModel = settings::setSelectedModel,
        reasoning = settings.reasoning,
        onSelectReasoning = settings::setReasoning,
        serviceEnabled = serviceEnabled,
        onEnableService = { context.startActivity(accessibilitySettings()) },
        onBack = onBack,
        onSend = { prompt ->
            AgentSession.start(
                context = context,
                threadId = thread.id,
                prompt = prompt,
                backend = backend(),
                image = takeImage(),
                contextLimit = settings.contextLimit,
                fastModel = settings.fastModel,
            )
        },
        onStop = AgentSession::stop,
    )
}


/** The backup written, or why it could not be */
private fun writeBackup(context: Context, uri: Uri, withSecrets: Boolean): String = runCatching {
    val text = Backup.export(context, withSecrets)
    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        ?: error("could not open the file")
    val kb = (text.length / 1024).coerceAtLeast(1)
    "Saved a backup of ${kb}KB" + if (withSecrets) ", including your API key." else "."
}.getOrElse { "Could not write the backup: ${it.message ?: "unknown error"}" }

/** What the import merged, or why it could not read the file */
private fun readBackup(context: Context, uri: Uri): String = runCatching {
    val text = context.contentResolver.openInputStream(uri)?.use {
        it.readBytes().decodeToString()
    } ?: error("could not open the file")
    Backup.import(context, text).summary
}.getOrElse { it.message ?: "Could not read that file." }
