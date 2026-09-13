package com.foxislam.androidagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.foxislam.androidagent.SettingsStore
import com.foxislam.androidagent.agent.Approvals

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    overlayGranted: Boolean,
    onGrantOverlay: () -> Unit,
    apiUrl: String,
    onApiUrl: (String) -> Unit,
    onResetApiUrl: () -> Unit,
    apiKey: String,
    onApiKey: (String) -> Unit,
    modelsRaw: String,
    onModels: (String) -> Unit,
    memory: String,
    onMemory: (String) -> Unit,
    approvalMode: Approvals.Mode,
    onApprovalMode: (Approvals.Mode) -> Unit,
    hooks: String,
    onHooks: (String) -> Unit,
    contextLimit: Int,
    onContextLimit: (Int) -> Unit,
    fastModel: String,
    onFastModel: (String) -> Unit,
    onExportDiagnostics: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    backupSecrets: Boolean,
    onBackupSecrets: (Boolean) -> Unit,
    backupStatus: String?,
    skillCount: Int,
    onOpenSkills: () -> Unit,
    serverCount: Int,
    onOpenMcp: () -> Unit,
    scriptCount: Int,
    onOpenScripts: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Endpoint")
                OutlinedTextField(
                    value = apiUrl,
                    onValueChange = onApiUrl,
                    label = { Text("API URL") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    // Reset belongs to the field, not floating beside the section heading
                    trailingIcon = {
                        if (apiUrl.trim() != SettingsStore.DEFAULT_API_URL) {
                            IconButton(onClick = onResetApiUrl) {
                                Icon(Icons.Default.Refresh, contentDescription = "Reset to default")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(
                    "Any OpenAI-compatible chat-completions endpoint. Defaults to OpenRouter; " +
                        "point it at a local or self-hosted gateway if you prefer.",
                )
                // Plain http to a machine on your own network is the normal case for a
                // self-hosted gateway. Plain http to the internet sends the key unencrypted
                if (apiUrl.startsWith("http://") && !isLocal(apiUrl)) {
                    Text(
                        text = "This address is http, and it does not look like one on " +
                            "your own network. Your API key will travel unencrypted, so " +
                            "anyone between here and there can read it and spend against " +
                            "it. Use https, or an address on your own network if that is " +
                            "what this is.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKey,
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("Stored on this device. Sent to the endpoint above.")
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Approvals")
                ApprovalModePicker(approvalMode, onApprovalMode)
                Hint(approvalMode.description)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Rules")
                OutlinedTextField(
                    value = hooks,
                    onValueChange = onHooks,
                    label = { Text("One rule per line") },
                    placeholder = { Text("deny * in com.*bank*\nask type_text matching (?i)password\nallow tap in com.android.settings") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
                Hint(
                    "Checked before every action, first match wins: allow, ask or deny, then " +
                        "a tool name (* allowed), optionally `in <package>` and `matching " +
                        "<regex>`. Answering \"Always\" to a prompt writes a line here.",
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Playbooks and tools")
                SettingRow {
                    Text(
                        text = counted(skillCount, "app playbook", "No app playbooks"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onOpenSkills) { Text("Edit") }
                }
                SettingRow {
                    Text(
                        text = counted(serverCount, "MCP server", "No MCP servers"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onOpenMcp) { Text("Edit") }
                }
                SettingRow {
                    Text(
                        text = counted(scriptCount, "saved script", "No saved scripts"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onOpenScripts) { Text("Edit") }
                }
                Hint(
                    "Playbooks teach the agent one app at a time. MCP servers give it tools " +
                        "that are not on this phone at all. Saved scripts are jobs it has " +
                        "already worked out, which you can run yourself without a model.",
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Memory")
                OutlinedTextField(
                    value = memory,
                    onValueChange = onMemory,
                    label = { Text("What the agent should remember") },
                    placeholder = { Text("- My work phone is the one called Pixel\n- Never message the #general channel") },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                )
                Hint(
                    "Added to the end of every system prompt. The agent can add to this itself " +
                        "with its remember and forget tools, and you can edit it here.",
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Status indicator")
                SettingRow {
                    Text(
                        text = if (overlayGranted) "Allowed" else "Not allowed",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!overlayGranted) {
                        TextButton(onClick = onGrantOverlay) { Text("Allow") }
                    }
                }
                Hint(
                    "A small card that floats over whatever app the agent is working in, " +
                        "showing what it is doing, with a stop button. Turn on either this " +
                        "or notifications: with both off, the agent can only reach you " +
                        "inside this app.",
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Models")
                OutlinedTextField(
                    value = modelsRaw,
                    onValueChange = onModels,
                    label = { Text("One model per line") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                )
                Hint(
                    "These fill the picker on the chat screen. Each must support both tool " +
                        "calling and image input, or the agent cannot see the screen.",
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Context window")
                OutlinedTextField(
                    value = contextLimit.toString(),
                    onValueChange = { raw -> raw.filter(Char::isDigit).toIntOrNull()?.let(onContextLimit) },
                    label = { Text("Tokens") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(
                    "The token limit of the model you are using; check its documentation if " +
                        "you are not sure. A long conversation compacts itself before it " +
                        "reaches this, so a run is not cut short. Set it higher than the " +
                        "model allows and the provider will start rejecting requests.",
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Cheap model")
                OutlinedTextField(
                    value = fastModel,
                    onValueChange = onFastModel,
                    label = { Text("Model slug, or blank") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(
                    "Most of a task is tapping and looking, which does not need the good " +
                        "model. When this is set the run uses it for those steps and for its " +
                        "own summaries, and goes back to the chosen model to plan, to recover " +
                        "from anything unexpected, and whenever you say something.",
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Back up and restore")
                SettingRow {
                    Text("Save a backup file", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onExportBackup) { Text("Export") }
                }
                SettingRow {
                    Text("Open a backup file", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onImportBackup) { Text("Import") }
                }
                SettingRow {
                    Text(
                        "Include the API key and server tokens",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = backupSecrets, onCheckedChange = onBackupSecrets)
                }
                backupStatus?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Hint(
                    "Everything you have: chats, scripts, saved tasks, playbooks, rules, " +
                        "memory, servers and settings. There is no account behind this app, " +
                        "so a backup file is the only way to move to another phone or to " +
                        "come back after reinstalling. Importing adds what is missing and " +
                        "updates anything it shares an id with, so opening a backup never " +
                        "throws away what is only on this phone. Leave the key out if the " +
                        "file is going somewhere you would not put a password.",
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Diagnostics")
                SettingRow {
                    Text("Export a report", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onExportDiagnostics) { Text("Export") }
                }
                Hint(
                    "The current chat, your settings and the state of the permissions, as a " +
                        "file you can send to someone. It never includes your API key, any " +
                        "server token, or anything typed into a password field.",
                )
            }

        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApprovalModePicker(selected: Approvals.Mode, onSelect: (Approvals.Mode) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Approvals.Mode.entries.forEach { mode ->
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                label = { Text(mode.label) },
            )
        }
    }
}

/** Good enough to tell "my own machine" from "the internet" for a warning */
private fun isLocal(url: String): Boolean {
    val host = url.removePrefix("http://").substringBefore('/').substringBefore(':')
    return host == "localhost" || host.endsWith(".local") || host == "10.0.2.2" ||
        host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("127.") ||
        Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
}

/** "1 saved script", not "1 saved scripts" */
private fun counted(n: Int, noun: String, none: String): String =
    if (n == 0) none else "$n $noun" + if (n == 1) "" else "s"

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
