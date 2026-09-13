package com.foxislam.androidagent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxislam.androidagent.mcp.CachedTool
import com.foxislam.androidagent.mcp.McpServer
import kotlinx.coroutines.launch

/**
 * Remote tools
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(
    servers: List<McpServer>,
    tools: Map<String, List<CachedTool>>,
    status: Map<String, String>,
    onAdd: (String, String, String) -> Unit,
    onUpdate: (String, String, String, String) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onRefresh: suspend (String) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<McpServer?>(null) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (adding) {
        ServerEditor(
            initial = null,
            onConfirm = { name, url, token -> onAdd(name, url, token); adding = false },
            onDismiss = { adding = false },
        )
    }
    editing?.let { server ->
        ServerEditor(
            initial = server,
            onConfirm = { name, url, token ->
                onUpdate(server.id, name, url, token)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("MCP servers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add a server")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (servers.isEmpty()) {
                item {
                    Text("No MCP servers yet", style = MaterialTheme.typography.titleMedium)
                }
                item {
                    Text(
                        "Add a server URL and, if it needs one, a bearer token. Its tools then " +
                            "sit alongside the phone's own, named mcp__server__tool. Sign-in " +
                            "flows (OAuth) are not supported, but a token is.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(servers, key = { it.id }) { server ->
                val discovered = tools[server.id].orEmpty()
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f).clickable { editing = server }) {
                                Text(server.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = server.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Switch(
                                checked = server.enabled,
                                onCheckedChange = { onEnabled(server.id, it) },
                            )
                        }

                        Text(
                            text = status[server.id]
                                ?: "${discovered.size} tools cached. Refresh to check.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (discovered.isNotEmpty()) {
                            Text(
                                text = discovered.joinToString(", ") { it.name },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp, end = 16.dp),
                            )
                        }

                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            IconButton(onClick = { scope.launch { onRefresh(server.id) } }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh tools")
                            }
                            IconButton(onClick = { onDelete(server.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove server")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerEditor(
    initial: McpServer?,
    onConfirm: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var token by remember { mutableStateOf(initial?.token.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add MCP server" else "Edit MCP server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    placeholder = { Text("https://example.com/mcp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bearer token (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, url, token) },
                enabled = name.isNotBlank() && url.startsWith("http"),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
