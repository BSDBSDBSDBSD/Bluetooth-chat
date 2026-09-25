@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.nearchat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.nearchat.core.ChatCore
import com.example.nearchat.core.ChatMessage
import com.example.nearchat.core.MessageStatus
import com.example.nearchat.core.MessageType
import com.example.nearchat.core.VoiceRecorder
import com.example.nearchat.core.groupIdOf
import com.example.nearchat.core.isGroupConversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatScreen(core: ChatCore, conv: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val rev = LocalRev.current
    val isGroup = isGroupConversation(conv)
    val messages = remember(rev, conv) { core.messages(conv) }
    val contact = remember(rev, conv) { if (isGroup) null else core.contact(conv) }
    val group = remember(rev, conv) { if (isGroup) core.group(groupIdOf(conv)) else null }
    val title = remember(rev, conv) { core.conversationTitle(conv) }
    val online = contact?.let { core.isOnline(it) } ?: false
    val playingId = core.voicePlayer.playingId

    var input by remember(conv) { mutableStateOf("") }
    var editing by remember(conv) { mutableStateOf<ChatMessage?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var viewImage by remember { mutableStateOf<String?>(null) }
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var recordMs by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    DisposableEffect(conv) {
        core.openConversation(conv)
        onDispose {
            core.openConversation(null)
            recorder.cancel()
        }
    }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(0) }

    fun finishRecording(send: Boolean) {
        if (send) {
            val r = recorder.stop()
            if (r != null) core.sendVoice(conv, r.first, r.second)
            else Toast.makeText(ctx, "ההקלטה קצרה מדי", Toast.LENGTH_SHORT).show()
        } else recorder.cancel()
        recording = false
    }

    LaunchedEffect(recording) {
        while (recording) {
            recordMs = recorder.elapsedMs()
            if (recordMs >= VoiceRecorder.MAX_MS) finishRecording(true)
            delay(200)
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { com.example.nearchat.core.ImageUtil.prepare(ctx, uri) }
            if (bytes != null) core.sendImage(conv, bytes) else Toast.makeText(ctx, "לא ניתן לטעון את התמונה", Toast.LENGTH_SHORT).show()
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recording = recorder.start()
        else Toast.makeText(ctx, "נדרשת הרשאת מיקרופון להקלטה", Toast.LENGTH_SHORT).show()
    }

    fun startRecording() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            core.voicePlayer.stop()
            recording = recorder.start()
            if (!recording) Toast.makeText(ctx, "לא ניתן להתחיל הקלטה", Toast.LENGTH_SHORT).show()
        } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun submit() {
        val e = editing
        if (input.isBlank()) return
        if (e != null) core.editMessage(conv, e.id, input) else core.sendText(conv, input)
        editing = null
        input = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(title, conv, group = isGroup, size = 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    isGroup -> "${group?.members?.size ?: 0} משתתפים"
                                    online -> "זמין"
                                    else -> "לא מחובר – הודעות יישלחו כשיתחבר"
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                actions = { IconButton(onClick = { showInfo = true }) { Icon(Icons.Default.Info, contentDescription = "פרטים") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, modifier = Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
                Column {
                    val e = editing
                    if (e != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("עריכת הודעה", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            IconButton(onClick = { editing = null; input = "" }) { Icon(Icons.Default.Close, contentDescription = "ביטול עריכה") }
                        }
                    }
                    if (recording) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Mic, contentDescription = null, tint = Color(0xFFD32F2F))
                            Spacer(Modifier.width(8.dp))
                            Text("מקליט… ${formatDuration(recordMs)}", modifier = Modifier.weight(1f))
                            TextButton(onClick = { finishRecording(false) }) { Text("בטל") }
                            IconButton(onClick = { finishRecording(true) }) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "שלח הקלטה", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (editing == null) {
                                IconButton(onClick = {
                                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }) { Icon(Icons.Default.Image, contentDescription = "שלח תמונה") }
                            }
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("כתוב הודעה…") },
                                maxLines = 5,
                                shape = RoundedCornerShape(24.dp),
                            )
                            if (input.isBlank() && editing == null) {
                                IconButton(onClick = { startRecording() }) { Icon(Icons.Default.Mic, contentDescription = "הקלט הודעה קולית") }
                            } else {
                                IconButton(onClick = { submit() }) {
                                    Icon(
                                        if (editing != null) Icons.Default.Done else Icons.AutoMirrored.Filled.Send,
                                        contentDescription = "שלח", tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { p ->
        Column(Modifier.fillMaxSize().padding(p)) {
            if (contact?.keyChanged == true) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).clickable { showInfo = true }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "מפתח ההצפנה של ${contact.name} השתנה. לחץ כדי לאמת את מספר הבטיחות.",
                        color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                items(messages.asReversed(), key = { it.id }) { m ->
                    MessageBubble(
                        core = core,
                        m = m,
                        mine = m.senderId == core.myId,
                        showSender = isGroup,
                        playing = playingId == m.id,
                        onOpenImage = { viewImage = m.id },
                        onEdit = { editing = m; input = m.text },
                        onDeleteForAll = { core.deleteMessage(conv, m.id) },
                        onDeleteLocal = { core.deleteLocally(conv, m.id) },
                    )
                }
            }
        }
    }

    viewImage?.let { id ->
        Dialog(onDismissRequest = { viewImage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { viewImage = null }, contentAlignment = Alignment.Center) {
                EncryptedImage(core, id, Modifier.fillMaxWidth())
            }
        }
    }

    if (showInfo) {
        ConversationInfoDialog(core, conv, onDismiss = { showInfo = false }, onDeleted = { showInfo = false; onBack() })
    }
}

@Composable
private fun MessageBubble(
    core: ChatCore,
    m: ChatMessage,
    mine: Boolean,
    showSender: Boolean,
    playing: Boolean,
    onOpenImage: () -> Unit,
    onEdit: () -> Unit,
    onDeleteForAll: () -> Unit,
    onDeleteLocal: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    // In RTL, Arrangement.Start is the right side: my messages on the right, like other Hebrew chat apps.
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.Start else Arrangement.End) {
        Box {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        onClick = {
                            if (!m.deleted && m.type == MessageType.IMAGE && m.hasMedia) onOpenImage()
                            else if (!m.deleted && m.type == MessageType.VOICE && m.hasMedia) core.voicePlayer.toggle(m.id)
                        },
                        onLongClick = { menu = true },
                    ),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    if (showSender && !mine) {
                        Text(m.senderName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    when {
                        m.deleted -> Text("🚫 ההודעה נמחקה", fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.outline)
                        m.type == MessageType.IMAGE -> EncryptedImage(
                            core, m.id,
                            Modifier.widthIn(max = 260.dp).heightIn(max = 320.dp).clip(RoundedCornerShape(10.dp)),
                        )
                        m.type == MessageType.VOICE -> VoiceContent(core, m, playing)
                        else -> Text(m.text)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        if (m.edited && !m.deleted) Text("נערך · ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        Text(formatTime(m.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        if (mine) {
                            Spacer(Modifier.width(4.dp))
                            val (icon, desc) = when (m.status) {
                                MessageStatus.PENDING -> Icons.Default.Schedule to "ממתין"
                                MessageStatus.SENT -> Icons.Default.Done to "נשלח"
                                MessageStatus.DELIVERED -> Icons.Default.DoneAll to "התקבל"
                                MessageStatus.RECEIVED -> Icons.Default.Done to ""
                            }
                            Icon(
                                icon, contentDescription = desc, modifier = Modifier.size(14.dp),
                                tint = if (m.status == MessageStatus.DELIVERED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            )
                            if (m.syncPending) Icon(Icons.Default.Schedule, contentDescription = "עדכון ממתין", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!m.deleted && m.type == MessageType.TEXT) {
                    DropdownMenuItem(text = { Text("העתק") }, onClick = { menu = false; clipboard.setText(AnnotatedString(m.text)) })
                }
                if (mine && !m.deleted && m.type == MessageType.TEXT) {
                    DropdownMenuItem(text = { Text("ערוך") }, onClick = { menu = false; onEdit() })
                }
                if (mine && !m.deleted) {
                    DropdownMenuItem(text = { Text("מחק לכולם") }, onClick = { menu = false; onDeleteForAll() })
                }
                if (!m.deleted) {
                    DropdownMenuItem(text = { Text("מחק אצלי") }, onClick = { menu = false; onDeleteLocal() })
                }
                DropdownMenuItem(text = { Text("סגור") }, onClick = { menu = false })
            }
        }
    }
}

@Composable
private fun VoiceContent(core: ChatCore, m: ChatMessage, playing: Boolean) {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        progress = 0f
        while (playing) {
            progress = if (m.durationMs > 0) (core.voicePlayer.positionMs().toFloat() / m.durationMs).coerceIn(0f, 1f) else 0f
            delay(200)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(220.dp)) {
        IconButton(onClick = { core.voicePlayer.toggle(m.id) }, enabled = m.hasMedia) {
            Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = if (playing) "עצור" else "נגן")
        }
        Column(Modifier.weight(1f)) {
            LinearProgressIndicator(progress = { if (playing) progress else 0f }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.size(4.dp))
            Text("🎤 ${formatDuration(m.durationMs)}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ConversationInfoDialog(core: ChatCore, conv: String, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val rev = LocalRev.current
    val isGroup = isGroupConversation(conv)
    val contact = remember(rev) { if (isGroup) null else core.contact(conv) }
    val group = remember(rev) { if (isGroup) core.group(groupIdOf(conv)) else null }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(core.conversationTitle(conv)) },
        text = {
            Column {
                if (contact != null) {
                    Text("מספר בטיחות", style = MaterialTheme.typography.labelLarge)
                    Text(core.safetyNumber(contact.id) ?: "", fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "השוו את המספר הזה עם המספר שמופיע אצל ${contact.name}. אם הם זהים – השיחה מוצפנת ואף אחד באמצע לא יכול לקרוא אותה.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (contact.keyChanged) {
                        Spacer(Modifier.size(8.dp))
                        TextButton(onClick = { core.acknowledgeKeyChange(contact.id) }) { Text("אימתתי – הסר אזהרה") }
                    }
                }
                if (group != null) {
                    Text("משתתפים", style = MaterialTheme.typography.labelLarge)
                    group.members.forEach { mem ->
                        val pending = mem.id in group.pendingInvites
                        Text("• ${if (mem.id == core.myId) "אני" else mem.name}${if (pending) " (הזמנה ממתינה)" else ""}")
                    }
                    Spacer(Modifier.size(8.dp))
                    Text("הודעות הקבוצה מוצפנות במפתח שמשותף רק למשתתפים.", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.size(12.dp))
                if (!confirmDelete) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(if (isGroup) "צא מהקבוצה ומחק אותה" else "מחק את השיחה", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Text("למחוק את כל ההודעות מהמכשיר הזה?", style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = { core.deleteConversation(conv); onDeleted() }) { Text("מחק", color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { confirmDelete = false }) { Text("ביטול") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("סגור") } },
    )
}
