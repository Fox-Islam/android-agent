package com.foxislam.androidagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Onboarding
 *
 * Everything here is a permission Android makes awkward to grant, since 
 * they let an app read every screen and tap anything on it. 
 * Has to be explained to the user since the settings screen doesn't provide any guidance
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeScreen(
    apiKey: String,
    onApiKey: (String) -> Unit,
    serviceEnabled: Boolean,
    onEnableService: () -> Unit,
    overlayGranted: Boolean,
    onGrantOverlay: () -> Unit,
    notificationsEnabled: Boolean,
    onEnableNotifications: () -> Unit,
    onImportBackup: () -> Unit,
    backupStatus: String?,
    onDone: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopAppBar(title = { Text("Android Agent") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "You type a task. A model reads your screen and taps, types and " +
                    "swipes to do it.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "Your screen goes to the model provider you choose, and to nobody " +
                    "else. It runs on this phone with your own API key. There is no account " +
                    "and no backend behind it. Later you can add MCP servers, which is the " +
                    "one way anything reaches a second destination.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Step(
                number = 1,
                title = "An API key",
                done = apiKey.isNotBlank(),
                detail = "OpenRouter by default. You can change the endpoint later to any " +
                    "OpenAI-compatible one, including a gateway on your own machine.",
            ) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKey,
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Someone reinstalling already has a key, in a file. Without this the only
                // way to the import button is through a setup step the backup would have
                // filled in for them
                TextButton(onClick = onImportBackup, contentPadding = PaddingValues(0.dp)) {
                    Text("Used this app before? Restore from a backup")
                }
                backupStatus?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Step(
                number = 2,
                title = "Let it see and touch the screen",
                done = serviceEnabled,
                detail = "The button below opens the right screen. Find Android Agent in " +
                    "the list and turn it on.\n\nIf the switch will not move, Android is " +
                    "blocking it because this app was sideloaded. Press and hold the app " +
                    "icon, open App info, then the three-dot menu, and choose Allow " +
                    "restricted settings. The switch works once that is enabled.",
            ) {
                TextButton(onClick = onEnableService) {
                    Text(if (serviceEnabled) "Open settings" else "Open accessibility settings")
                }
            }

            Step(
                number = 3,
                title = "Let it show a status indicator",
                done = overlayGranted,
                detail = "A small card that floats over whatever app the agent is working " +
                    "in. It says what the agent is doing, asks you before anything " +
                    "irreversible, and carries a stop button, so you can follow a run and " +
                    "halt it without returning to this app.",
            ) {
                if (!overlayGranted) TextButton(onClick = onGrantOverlay) { Text("Allow") }
            }

            Step(
                number = 4,
                title = "Let it notify you",
                done = notificationsEnabled,
                detail = "Sends the agent's questions to your notifications, and tells you " +
                    "how a run ended. Turn on either this or the status indicator: with both " +
                    "off, a question can only be answered by opening this app, and a run that " +
                    "stops to ask will wait until you do.",
            ) {
                if (!notificationsEnabled) TextButton(onClick = onEnableNotifications) { Text("Allow") }
            }

            Text(
                text = "It cannot see banking and DRM screens, cannot act while the phone is " +
                    "locked, and some apps refuse to run at all while an accessibility " +
                    "service is on. It will tell you when it hits one of those.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onDone,
                enabled = apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            ) {
                Text(
                    when {
                        apiKey.isBlank() -> "Add an API key to start"
                        !serviceEnabled -> "Start without the accessibility service"
                        else -> "Start"
                    },
                )
            }
        }
    }
}

@Composable
private fun Step(
    number: Int,
    title: String,
    done: Boolean,
    detail: String,
    content: @Composable () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (done) "✓" else "$number.",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = title, style = MaterialTheme.typography.titleSmall)
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}
