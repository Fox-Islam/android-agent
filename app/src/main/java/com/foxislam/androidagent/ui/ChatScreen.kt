package com.foxislam.androidagent.ui

import android.graphics.BitmapFactory
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.foxislam.androidagent.agent.Approvals
import com.foxislam.androidagent.agent.Entry
import com.foxislam.androidagent.agent.Live
import com.foxislam.androidagent.agent.TodoItem
import com.foxislam.androidagent.agent.Plans
import com.foxislam.androidagent.agent.Reasoning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    title: String,
    initialPrompt: String,
    onRename: (String) -> Unit,
    onSavePrompt: (String) -> Unit,
    transcript: List<Entry>,
    live: Live?,
    running: Boolean,
    interrupted: Boolean,
    onResume: () -> Unit,
    onDismissResume: () -> Unit,
    planMode: Boolean,
    onPlanMode: (Boolean) -> Unit,
    contextTokens: Int,
    contextLimit: Int,
    costUsd: Double,
    onDecideAsk: (String, String) -> Unit,
    onDecidePlan: (String, String) -> Unit,
    onSteer: (String) -> Unit,
    onAnswer: (String, String) -> Unit,
    onFork: (Long) -> Unit,
    models: List<String>,
    selectedModel: String,
    onSelectModel: (String) -> Unit,
    reasoning: Reasoning,
    onSelectReasoning: (Reasoning) -> Unit,
    serviceEnabled: Boolean,
    onEnableService: () -> Unit,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    // Keyed on the seed so opening a saved task drops its text straight into the chat input
    var prompt by remember(initialPrompt) { mutableStateOf(initialPrompt) }
    var renaming by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(transcript.size, live != null) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.lastIndex)
    }

    if (renaming) {
        TextPromptDialog(
            title = "Rename chat",
            initial = title,
            confirm = "Rename",
            label = "Chat name",
            singleLine = true,
            onConfirm = { onRename(it); renaming = false },
            onDismiss = { renaming = false },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { renaming = true },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to chats")
                    }
                },
                actions = {
                    IconButton(onClick = { renaming = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename chat")
                    }
                    IconButton(
                        onClick = { onSavePrompt(prompt.trim()) },
                        enabled = prompt.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Star, contentDescription = "Save this as a task")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!serviceEnabled) ServiceWarning(onEnableService)

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(transcript, key = { it.id }) { entry ->
                    Message(
                        entry = entry,
                        onDecideAsk = onDecideAsk,
                        onDecidePlan = onDecidePlan,
                        onAnswer = onAnswer,
                        onFork = onFork,
                    )
                }

                // The turn in flight, painted as it arrives and replaced by the real message
                // when it arrives
                live?.let { item(key = "live") { LiveMessage(it) } }
            }

            if (interrupted && !running) ResumeCard(onResume, onDismissResume)

            Row(verticalAlignment = Alignment.CenterVertically) {
                ModelPicker(models, selectedModel, onSelectModel)
                ReasoningPicker(reasoning, onSelectReasoning)
                PlanToggle(planMode, onPlanMode)
            }

            CostLine(contextTokens, contextLimit, costUsd)

            ChatInput(
                value = prompt,
                onValueChange = { prompt = it },
                running = running,
                canSend = prompt.isNotBlank() && selectedModel.isNotBlank(),
                // Sending mid-run is a correction, not a new task: it joins the run in
                // progress instead of queueing behind it or replacing it
                onSend = {
                    val text = prompt.trim()
                    prompt = ""
                    if (running) onSteer(text) else onSend(text)
                },
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun Message(
    entry: Entry,
    onDecideAsk: (String, String) -> Unit = { _, _ -> },
    onDecidePlan: (String, String) -> Unit = { _, _ -> },
    onAnswer: (String, String) -> Unit = { _, _ -> },
    onFork: (Long) -> Unit = {},
) = when (entry) {
    is Entry.Prompt -> UserBubble(entry.text, entry.imagePath) { onFork(entry.id) }
    is Entry.Said -> SaidBlock(entry) { onFork(entry.id) }
    is Entry.Finished -> AssistantBubble(entry.text) { onFork(entry.id) }
    is Entry.Tool -> ToolPill(entry)
    is Entry.Note -> NoteLine(entry.text)
    is Entry.Ask -> AskCard(entry, onDecideAsk)
    is Entry.Plan -> PlanCard(entry, onDecidePlan)
    is Entry.Question -> QuestionCard(entry, onAnswer)
    is Entry.Todos -> TodosCard(entry)
    is Entry.Failed -> Text(
        text = entry.text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.copyOnLongPress(entry.text),
    )
}

@Composable
private fun SaidBlock(entry: Entry.Said, onFork: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entry.thinking?.takeIf { it.isNotBlank() }?.let { ThinkingBlock(it, startOpen = false) }
        if (entry.text.isNotBlank()) AssistantBubble(entry.text, onFork)
    }
}

/** The reply as it arrives, with thinking shown open while the text is still coming */
@Composable
private fun LiveMessage(live: Live) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (live.thinking.isNotBlank()) ThinkingBlock(live.thinking, startOpen = true)
        if (live.text.isNotBlank()) AssistantBubble(live.text) {}
    }
}

/**
 * Long-press to copy, as in any chat app. The text copied is what the model wrote, Markdown
 * and all - the rendered version would lose the structure the moment it was pasted
 * anywhere
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.copyOnLongPress(text: String): Modifier {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return combinedClickable(
        onLongClickLabel = "Copy",
        onLongClick = {
            clipboard.setText(AnnotatedString(text))
            // Android 13 and up shows its own copy confirmation; showing it twice looks broken
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
        },
        onClick = {},
    )
}

@Composable
private fun UserBubble(text: String, imagePath: String? = null, onFork: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        MessageMenu(text, onFork) { menu ->
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.widthIn(max = 300.dp).then(menu),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    imagePath?.let { SharedThumbnail(it) }
                    // Rendered as typed: the user wrote this, so their asterisks stay
                    if (text.isNotBlank()) {
                        Text(text = text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedThumbnail(path: String) {
    val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() } ?: return
    Image(
        bitmap = bitmap,
        contentDescription = "Shared image",
        modifier = Modifier.heightIn(max = 220.dp).clip(MaterialTheme.shapes.medium),
    )
}

@Composable
private fun AssistantBubble(text: String, onFork: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        MessageMenu(text, onFork) { menu ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.widthIn(max = 320.dp).then(menu),
            ) {
                MarkdownText(
                    text = text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/**
 * Long-press a message to copy it, or to start again from just before it. Forking keeps
 * everything the agent had worked out up to that point
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageMenu(
    text: String,
    onFork: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Box {
        content(
            Modifier.combinedClickable(
                onLongClickLabel = "Message actions",
                onLongClick = { open = true },
                onClick = {},
            ),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Copy") },
                onClick = {
                    clipboard.setText(AnnotatedString(text))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text("Fork from here") },
                onClick = { onFork(); open = false },
            )
        }
    }
}

/**
 * A tool call is activity, not conversation, so it renders as a compact pill that fills in
 * its own result, expandable for the detail
 */
@Composable
private fun ToolPill(entry: Entry.Tool) {
    var expanded by remember { mutableStateOf(false) }
    val label = listOf(entry.name.replace('_', ' '), entry.detail)
        .filter { it.isNotBlank() }
        .joinToString(" ")

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = CircleShape,
            modifier = Modifier.clickable(enabled = entry.result != null) { expanded = !expanded },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulsingDot(active = entry.running)
                Text(text = label, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (expanded && entry.result != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = entry.result,
                    modifier = Modifier
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun ThinkingBlock(text: String, startOpen: Boolean) {
    var open by remember(startOpen) { mutableStateOf(startOpen) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PulsingDot(active = false)
            Text(
                text = if (open) "Thinking" else "Thinking…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.copyOnLongPress(text),
            ) {
                MarkdownText(
                    text = text,
                    modifier = Modifier
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * A run the system killed. The log survived it, so picking it back up keeps everything the
 * agent had worked out
 */
@Composable
private fun ResumeCard(onResume: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "This run was interrupted",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
            TextButton(onClick = onResume) { Text("Resume") }
        }
    }
}

@Composable
private fun NoteLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/**
 * The held action. Everything about it is blunt - what is about to happen, why it stopped,
 * and three buttons - because it is read in a hurry, on top of another app, by someone who
 * did not expect to be interrupted
 */
@Composable
private fun AskCard(entry: Entry.Ask, onDecide: (String, String) -> Unit) {
    val pending = entry.decision == null
    Surface(
        color = if (pending) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (pending) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (pending) "Approve this step?" else "Approval: ${entry.decision}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = listOf(entry.tool.replace('_', ' '), entry.detail)
                    .filter { it.isNotBlank() }
                    .joinToString(" "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(text = entry.reason, style = MaterialTheme.typography.bodySmall)

            if (pending) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onDecide(entry.askId, Approvals.DENIED) }) { Text("Deny") }
                    TextButton(onClick = { onDecide(entry.askId, Approvals.ALWAYS) }) { Text("Always") }
                    TextButton(onClick = { onDecide(entry.askId, Approvals.ONCE) }) { Text("Allow") }
                }
            }
        }
    }
}

@Composable
private fun PlanCard(entry: Entry.Plan, onDecide: (String, String) -> Unit) {
    val pending = entry.decision == null
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (pending) "Plan" else "Plan: ${entry.decision}",
                style = MaterialTheme.typography.titleSmall,
            )
            entry.steps.forEachIndexed { index, step ->
                Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
            }
            if (entry.note.isNotBlank()) {
                Text(entry.note, style = MaterialTheme.typography.bodySmall)
            }
            if (pending) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onDecide(entry.planId, Plans.REJECTED) }) { Text("Reject") }
                    TextButton(onClick = { onDecide(entry.planId, Plans.APPROVED) }) { Text("Approve") }
                }
            }
        }
    }
}

/**
 * The agent's own question. Options are buttons; anything else needs typing, and the answer
 * goes back into the run that is sitting still waiting for it
 */
@Composable
private fun QuestionCard(entry: Entry.Question, onAnswer: (String, String) -> Unit) {
    val pending = entry.answer == null
    var typed by remember(entry.id) { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("The agent is asking", style = MaterialTheme.typography.titleSmall)
            Text(entry.text, style = MaterialTheme.typography.bodyMedium)

            if (!pending) {
                Text(
                    text = "You said: ${entry.answer}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                return@Column
            }

            if (entry.options.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    entry.options.forEach { option ->
                        TextButton(onClick = { onAnswer(entry.questionId, option) }) { Text(option) }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = { Text("Answer") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onAnswer(entry.questionId, typed.trim()) },
                    enabled = typed.isNotBlank(),
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun TodosCard(entry: Entry.Todos) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            entry.items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = when (item.state) {
                            TodoItem.DONE -> "✓"
                            TodoItem.DOING -> "▸"
                            else -> "◦"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (item.state == TodoItem.DONE) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (item.state == TodoItem.DONE) TextDecoration.LineThrough else null,
                    )
                }
            }
        }
    }
}

/**
 * How full the window is and what the chat has cost. Both are invisible otherwise, and
 * useful before a long run instead of after it
 */
@Composable
private fun CostLine(contextTokens: Int, contextLimit: Int, costUsd: Double) {
    if (contextTokens == 0 && costUsd == 0.0) return
    val used = if (contextLimit > 0) (100.0 * contextTokens / contextLimit).toInt() else 0
    val cost = if (costUsd > 0) " · $" + String.format("%.4f", costUsd) else ""
    Text(
        text = "context ${tokens(contextTokens)} of ${tokens(contextLimit)} ($used%)$cost",
        style = MaterialTheme.typography.labelSmall,
        color = if (used >= 80) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp),
    )
}

private fun tokens(count: Int): String =
    if (count >= 1000) "${count / 1000}k" else count.toString()

@Composable
private fun PlanToggle(enabled: Boolean, onChange: (Boolean) -> Unit) {
    TextButton(onClick = { onChange(!enabled) }) {
        Text(
            text = if (enabled) "Plan: on" else "Plan",
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun PulsingDot(active: Boolean) {
    val alpha = if (active) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "pulseAlpha",
        ).value
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(7.dp)
            .alpha(alpha)
            .background(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                CircleShape,
            ),
    )
}

@Composable
private fun ReasoningPicker(selected: Reasoning, onSelect: (Reasoning) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(
                text = if (selected == Reasoning.DEFAULT) "Reasoning" else selected.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Reasoning.entries.forEach { level ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = level.label,
                            fontWeight = if (level == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = { onSelect(level); open = false },
                )
            }
        }
    }
}

@Composable
private fun ModelPicker(models: List<String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(start = 12.dp)) {
        TextButton(onClick = { open = true }) {
            Text(
                text = selected.ifBlank { "No models configured" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = model,
                            fontWeight = if (model == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = { onSelect(model); open = false },
                )
            }
        }
    }
}

@Composable
private fun ServiceWarning(onEnable: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onEnable),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Accessibility service is off", style = MaterialTheme.typography.titleSmall)
            Text(
                "Tap to open settings. If the toggle is greyed out, use App info → ⋮ → " +
                    "Allow restricted settings first.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(if (running) "Steer it" else "Message") },
            shape = MaterialTheme.shapes.extraLarge,
            maxLines = 4,
            modifier = Modifier.weight(1f),
        )
        FilledIconButton(
            // Stop when the chat input is empty, send when it is not, so the one button is
            // unambiguous
            onClick = { if (running && !canSend) onStop() else onSend() },
            enabled = running || canSend,
            colors = if (running && !canSend) {
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                IconButtonDefaults.filledIconButtonColors()
            },
            modifier = Modifier.padding(bottom = 4.dp).size(52.dp),
        ) {
            if (running && !canSend) {
                // The core icon set has no Stop glyph, and pulling in
                // material-icons-extended for one square costs ~30MB in the debug APK
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(LocalContentColor.current, RoundedCornerShape(3.dp))
                        .semantics { contentDescription = "Stop" },
                )
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
