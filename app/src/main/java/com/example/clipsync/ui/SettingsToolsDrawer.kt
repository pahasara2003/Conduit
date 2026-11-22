package com.example.clipsync.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.example.clipsync.AiSessionSummary
import com.example.clipsync.ClipSyncRepository
import com.example.clipsync.ClipSyncState
import com.example.clipsync.SshInfo
import com.example.clipsync.ui.theme.IdeTextMutedDark
import com.example.clipsync.ui.theme.StatusError
import com.example.clipsync.ui.theme.StatusOnline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsToolsDrawer(
    state: ClipSyncState,
    sessions: List<AiSessionSummary>,
    activeSessionId: String?,
    onClose: () -> Unit,
    onNewSession: () -> Unit,
    onResumeSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onSendClipboard: () -> Unit,
    onPullClipboard: () -> Unit,
    onToggleService: (Boolean) -> Unit,
    onUpdatePcHost: (String) -> Unit,
    onUpdateApiToken: (String) -> Unit,
    onDiscoverPc: () -> Unit,
    onCopyHistory: (String) -> Unit,
    onResendHistory: (String) -> Unit
) {
    val context = LocalContext.current
    var pcIpInput by remember(state.pcHost) { mutableStateOf(state.pcHost) }
    var tokenInput by remember(state.apiToken) { mutableStateOf(state.apiToken) }
    var showToken by remember { mutableStateOf(false) }

    var expandClipboard by remember { mutableStateOf(false) }
    var expandSsh by remember { mutableStateOf(false) }
    var expandConnection by remember { mutableStateOf(false) }

    val isNotificationListenerEnabled = remember(state.isServiceRunning) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    ModalDrawerSheet(
        modifier = Modifier
            .fillMaxWidth(0.84f)
            .fillMaxHeight(),
        drawerContainerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top: + New Session CTA
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                Button(
                    onClick = {
                        onNewSession()
                        onClose()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "New Session",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Middle: Recent Conversations (scrollable)
            Text(
                text = "Recent",
                style = MaterialTheme.typography.labelSmall,
                color = IdeTextMutedDark,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (sessions.isEmpty()) {
                    item {
                        Text(
                            text = "No conversations yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = IdeTextMutedDark,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp)
                        )
                    }
                } else {
                    items(sessions, key = { it.id }) { session ->
                        val isActive = session.id == activeSessionId
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    onDeleteSession(session.id)
                                    true
                                } else {
                                    false
                                }
                            }
                        )

                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(StatusError.copy(alpha = 0.18f))
                                        .padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "Delete",
                                        tint = StatusError,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        ) {
                            Surface(
                                onClick = {
                                    onResumeSession(session.id)
                                    onClose()
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (session.status == "open") StatusOnline
                                                else IdeTextMutedDark.copy(alpha = 0.5f)
                                            )
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = session.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = session.modelLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = IdeTextMutedDark,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

            // Bottom Secondary Section: Clipboard Sync, SSH & Termius, Connection Settings
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                DrawerSectionRow(
                    icon = Icons.Rounded.ContentPaste,
                    title = "Clipboard Sync",
                    expanded = expandClipboard,
                    onClick = { expandClipboard = !expandClipboard }
                )
                AnimatedVisibility(visible = expandClipboard) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onSendClipboard,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text("Send to PC", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = onPullClipboard,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Text("Pull PC", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Background Listener",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Switch(
                                checked = state.isServiceRunning,
                                onCheckedChange = onToggleService
                            )
                        }
                        if (state.history.isNotEmpty()) {
                            state.history.take(3).forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainer)
                                        .clickable { onCopyHistory(entry.text) }
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = entry.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = "Copy",
                                        tint = IdeTextMutedDark,
                                        modifier = Modifier
                                            .padding(start = 8.dp)
                                            .size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                DrawerSectionRow(
                    icon = Icons.Rounded.Terminal,
                    title = "SSH & Termius",
                    expanded = expandSsh,
                    onClick = { expandSsh = !expandSsh }
                )
                AnimatedVisibility(visible = expandSsh) {
                    CompactSshPanel(pcHost = state.pcHost)
                }

                DrawerSectionRow(
                    icon = Icons.Rounded.Settings,
                    title = "Connection Settings",
                    expanded = expandConnection,
                    onClick = { expandConnection = !expandConnection }
                )
                AnimatedVisibility(visible = expandConnection) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = pcIpInput,
                            onValueChange = { pcIpInput = it },
                            label = { Text("PC Host") },
                            placeholder = { Text(ClipSyncRepository.DEFAULT_TAILSCALE_HOST) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            trailingIcon = {
                                Row {
                                    if (pcIpInput != state.pcHost) {
                                        IconButton(onClick = { onUpdatePcHost(pcIpInput) }) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = "Save host",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    IconButton(onClick = onDiscoverPc) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = "Auto-discover"
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            label = { Text("API Token (optional)") },
                            singleLine = true,
                            visualTransformation = if (showToken) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                Row {
                                    if (tokenInput != state.apiToken) {
                                        IconButton(onClick = {
                                            onUpdateApiToken(tokenInput)
                                            Toast.makeText(context, "Token saved", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = "Save token",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    IconButton(onClick = { showToken = !showToken }) {
                                        Icon(
                                            imageVector = if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                            contentDescription = "Toggle visibility"
                                        )
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isNotificationListenerEnabled) "Notifications: On" else "Notifications: Off",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Configure", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerSectionRow(
    icon: ImageVector,
    title: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = IdeTextMutedDark,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun CompactSshPanel(pcHost: String) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<SshInfo?>(null) }
    LaunchedEffect(pcHost) { info = ClipSyncRepository.fetchSshInfo(context).getOrNull() }
    val user = info?.user ?: "pahasara"
    val port = info?.port ?: 22
    val host = pcHost.ifBlank { info?.ip ?: ClipSyncRepository.DEFAULT_TAILSCALE_HOST }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "$user@$host:$port",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = host.isNotBlank(),
                onClick = {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse("ssh://$user@$host:$port"))
                    val termius = Intent(i).setPackage("com.server.auditor.ssh.client")
                    try {
                        if (termius.resolveActivity(context.packageManager) != null) {
                            context.startActivity(termius)
                        } else {
                            context.startActivity(Intent.createChooser(i, "Open SSH"))
                        }
                    } catch (_: Exception) {
                        Toast.makeText(context, "No SSH app found", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                Text("Termius", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                enabled = host.isNotBlank(),
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ssh", "ssh $user@$host"))
                    Toast.makeText(context, "Copied SSH command", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                Text("Copy SSH", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
