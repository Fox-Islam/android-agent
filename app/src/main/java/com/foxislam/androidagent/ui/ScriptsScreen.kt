package com.foxislam.androidagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxislam.androidagent.agent.SavedScript
import com.foxislam.androidagent.agent.SavedScripts
import com.foxislam.androidagent.agent.ScriptParam

/**
 * Reusable scripts made by the agent
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptsScreen(
    scripts: List<SavedScript>,
    running: Boolean,
    onUpdate: (String, String, String, String) -> Unit,
    onDelete: (String) -> Unit,
    onRun: (SavedScript, Map<String, Any?>) -> String?,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<SavedScript?>(null) }
    var launching by remember { mutableStateOf<SavedScript?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    editing?.let { script ->
        ScriptEditor(
            script = script,
            onConfirm = { name, description, source ->
                onUpdate(script.id, name, description, source)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    launching?.let { script ->
        RunDialog(
            script = script,
            onRun = { args ->
                problem = onRun(script, args)
                launching = null
            },
            onDismiss = { launching = null },
        )
    }
    problem?.let { why ->
        AlertDialog(
            onDismissRequest = { problem = null },
            title = { Text("Could not run it") },
            text = { Text(why) },
            confirmButton = { TextButton(onClick = { problem = null }) { Text("OK") } },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Saved scripts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (scripts.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("No saved scripts yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Some jobs are fiddly: a toggle that has to be checked before it is " +
                        "tapped, a list that has to be scrolled until something appears. " +
                        "When the agent works one out it can keep the script it used. They " +
                        "appear here, and you can run one again without asking for it in " +
                        "words.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(scripts, key = { it.id }) { script ->
                OutlinedCard(modifier = Modifier.fillMaxWidth().clickable { editing = script }) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(script.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = script.description.ifBlank { "no description" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { launching = script }, enabled = !running) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Run ${script.name}")
                        }
                        IconButton(onClick = { onDelete(script.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${script.name}")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Asking before it runs
 */
@Composable
private fun RunDialog(
    script: SavedScript,
    onRun: (Map<String, Any?>) -> Unit,
    onDismiss: () -> Unit,
) {
    val fields = remember(script.id) { script.fields() }
    // One entry per declared value, seeded with whatever default the script declared
    val entered = remember(script.id) {
        mutableStateMapOf<String, String>().apply {
            fields.forEach { put(it.name, it.default) }
        }
    }
    var showSource by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run ${script.name}?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(script.description, style = MaterialTheme.typography.bodyMedium)

                fields.forEach { field ->
                    val value = entered[field.name].orEmpty()
                    when (field.type) {
                        ScriptParam.BOOLEAN -> Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(field.title, style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = value.equals("true", ignoreCase = true),
                                onCheckedChange = { entered[field.name] = it.toString() },
                            )
                        }

                        else -> OutlinedTextField(
                            value = value,
                            onValueChange = { entered[field.name] = it },
                            label = { Text(field.title) },
                            singleLine = true,
                            keyboardOptions = if (field.type == ScriptParam.NUMBER) {
                                KeyboardOptions(keyboardType = KeyboardType.Number)
                            } else {
                                KeyboardOptions.Default
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Text(
                    "It will use the phone on its own. Anything it does is still checked " +
                        "against your rules and held for approval the same as always.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { showSource = !showSource },
                    contentPadding = PaddingValues(0.dp),
                ) { Text(if (showSource) "Hide the script" else "Show the script") }
                if (showSource) {
                    Text(
                        text = script.source,
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onRun(
                        fields.associate { field ->
                            val raw = entered[field.name].orEmpty()
                            field.name to when (field.type) {
                                ScriptParam.BOOLEAN -> raw.equals("true", ignoreCase = true)
                                // A text field still reads "true" as a boolean, because an
                                // undeclared value is only ever a text field
                                else -> SavedScripts.coerce(raw)
                            }
                        },
                    )
                },
            ) { Text("Run") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ScriptEditor(
    script: SavedScript,
    onConfirm: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(script.name) }
    var description by remember { mutableStateOf(script.description) }
    var source by remember { mutableStateOf(script.source) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit script") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("What it does, and what it expects in args") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text("Script") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                )
                Text(
                    "The description is all the agent sees when it is choosing which script " +
                        "to run, so it is worth being specific about what args it wants.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, description, source) },
                enabled = name.isNotBlank() && source.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
