@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.nearchat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
            // With animated transitions the next screen may already have opened another chat.
            if (core.activeConversation == conv) core.openConversation(null)
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            GradientTopBar(
                title = title,
                onBack = onBack,
                subtitle = when {
                    isGroup -> "${group?.members?.size ?: 0} משתתפים"
                    online -> "זמין עכשיו"
                    else -> "לא מחובר – ההודעות יישלחו כשיתחבר"
                },
                leading = {
                    Avatar(title, conv, size = 40.dp, group = isGroup, avatarHash = contact?.avatar ?: "", online = if (isGroup) null else online, ring = true)
                },
                onTitleClick = { showInfo = true },
            ) { HeaderIcon(Icons.Default.Info, "פרטים") { showInfo = true } }
        },
        bottomBar = {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)).padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                val e = editing
                if (e != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer).padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("עריכת הודעה", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(e.text, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { editing = null; input = "" }) { Icon(Icons.Default.Close, contentDescription = "ביטול עריכה") }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Row(
                        Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp))
                            .background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (recording) {
                            val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
                                0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse",
                            )
                            Spacer(Modifier.width(12.dp))
                            Box(Modifier.size(12.dp).alpha(pulse).clip(CircleShape).background(Color(0xFFEF4444)))
                            Spacer(Modifier.width(10.dp))
                            Text("מקליט  ${formatDuration(recordMs)}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { finishRecording(false) }) { Text("בטל", color = MaterialTheme.colorScheme.error) }
                        } else {
                            if (editing == null) {
                                IconButton(onClick = {
                                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }) { Icon(Icons.Default.AddPhotoAlternate, contentDescription = "שלח תמונה", tint = MaterialTheme.colorScheme.primary) }
                            } else Spacer(Modifier.width(14.dp))
                            TextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("הודעה…") },
                                maxLines = 5,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                ),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    when {
                        recording -> GradientCircleButton(Icons.AutoMirrored.Filled.Send, "שלח הקלטה", { finishRecording(true) }, size = 52.dp)
                        input.isBlank() && editing == null -> GradientCircleButton(Icons.Default.Mic, "הקלט הודעה קולית", { startRecording() }, size = 52.dp)
                        else -> GradientCircleButton(
                            if (editing != null) Icons.Default.Done else Icons.AutoMirrored.Filled.Send, "שלח", { submit() }, size = 52.dp,
                        )
                    }
                }
            }
        },
    ) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).background(
                Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))),
            ),
        ) {
            if (contact?.keyChanged == true) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { showInfo = true }.padding(12.dp),
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
            if (messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Avatar(title, conv, size = 96.dp, group = isGroup, avatarHash = contact?.avatar ?: "")
                        Spacer(Modifier.height(14.dp))
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text("השיחה מוצפנת מקצה לקצה", style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("שלחו הודעה ראשונה 👋", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    // Reversed layout: items listed from newest to oldest, drawn bottom-up.
                    for (i in messages.indices.reversed()) {
                        val m = messages[i]
                        val prev = messages.getOrNull(i - 1)
                        val next = messages.getOrNull(i + 1)
                        val firstOfRun = prev == null || prev.senderId != m.senderId || m.timestamp - prev.timestamp > RUN_GAP_MS || !sameDay(prev.timestamp, m.timestamp)
                        val lastOfRun = next == null || next.senderId != m.senderId || next.timestamp - m.timestamp > RUN_GAP_MS || !sameDay(next.timestamp, m.timestamp)
                        item(key = m.id) {
                            val mine = m.senderId == core.myId
                            MessageBubble(
                                core = core,
                                m = m,
                                mine = mine,
                                group = isGroup,
                                firstOfRun = firstOfRun,
                                lastOfRun = lastOfRun,
                                senderAvatar = if (isGroup && !mine && lastOfRun) (core.contact(m.senderId)?.avatar ?: "") else null,
                                playing = playingId == m.id,
                                onOpenImage = { viewImage = m.id },
                                onEdit = { editing = m; input = m.text },
                                onDeleteForAll = { core.deleteMessage(conv, m.id) },
                                onDeleteLocal = { core.deleteLocally(conv, m.id) },
                            )
                        }
                        if (prev == null || !sameDay(prev.timestamp, m.timestamp)) {
                            item(key = "day-" + m.id) { DayChip(formatDayLabel(m.timestamp)) }
                        }
                    }
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

private const val RUN_GAP_MS = 3 * 60 * 1000L

@Composable
private fun DayChip(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun MessageBubble(
    core: ChatCore,
    m: ChatMessage,
    mine: Boolean,
    group: Boolean,
    firstOfRun: Boolean,
    lastOfRun: Boolean,
    senderAvatar: String?,
    playing: Boolean,
    onOpenImage: () -> Unit,
    onEdit: () -> Unit,
    onDeleteForAll: () -> Unit,
    onDeleteLocal: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val big = 20.dp
    val small = 6.dp
    // RTL: "start" is the right edge. Mine sit on the right with the tail bottom-right; theirs mirror that.
    val shape = if (mine) RoundedCornerShape(
        topStart = if (firstOfRun) big else small, topEnd = big, bottomEnd = big, bottomStart = small,
    ) else RoundedCornerShape(
        topStart = big, topEnd = if (firstOfRun) big else small, bottomEnd = small, bottomStart = big,
    )
    val isImage = !m.deleted && m.type == MessageType.IMAGE
    val onColor = if (mine) Color.White else MaterialTheme.colorScheme.onSurface
    val metaColor = if (mine) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.outline

    Row(
        Modifier.fillMaxWidth().padding(top = if (firstOfRun) 6.dp else 1.5.dp, bottom = 1.5.dp),
        horizontalArrangement = if (mine) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        Box {
            Column(
                Modifier
                    .widthIn(max = 300.dp)
                    .shadow(if (mine) 3.dp else 1.dp, shape, spotColor = if (mine) Brand.Violet else Color.Black)
                    .clip(shape)
                    .then(if (mine) Modifier.background(Brand.bubble) else Modifier.background(MaterialTheme.colorScheme.surface))
                    .combinedClickable(
                        onClick = {
                            if (!m.deleted && m.type == MessageType.IMAGE && m.hasMedia) onOpenImage()
                            else if (!m.deleted && m.type == MessageType.VOICE && m.hasMedia) core.voicePlayer.toggle(m.id)
                        },
                        onLongClick = { menu = true },
                    )
                    .padding(if (isImage) 4.dp else 0.dp),
            ) {
                Column(Modifier.padding(horizontal = if (isImage) 6.dp else 12.dp, vertical = if (isImage) 2.dp else 8.dp)) {
                    if (group && !mine && firstOfRun) {
                        Text(
                            m.senderName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                            color = senderColor(m.senderId), modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                    when {
                        m.deleted -> Text("🚫 ההודעה נמחקה", fontStyle = FontStyle.Italic, color = metaColor)
                        m.type == MessageType.IMAGE -> EncryptedImage(
                            core, m.id,
                            Modifier.widthIn(max = 280.dp).heightIn(max = 340.dp).clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        m.type == MessageType.VOICE -> VoiceContent(core, m, playing, mine)
                        else -> Text(m.text, color = onColor, style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.End).padding(top = 3.dp),
                    ) {
                        if (m.edited && !m.deleted) Text("נערך · ", style = MaterialTheme.typography.labelSmall, color = metaColor)
                        Text(formatTime(m.timestamp), style = MaterialTheme.typography.labelSmall, color = metaColor)
                        if (mine) {
                            Spacer(Modifier.width(4.dp))
                            val (icon, desc) = when (m.status) {
                                MessageStatus.PENDING -> Icons.Default.Schedule to "ממתין"
                                MessageStatus.SENT -> Icons.Default.Done to "נשלח"
                                MessageStatus.DELIVERED -> Icons.Default.DoneAll to "התקבל"
                                MessageStatus.RECEIVED -> Icons.Default.Done to ""
                            }
                            Icon(
                                icon, contentDescription = desc, modifier = Modifier.size(15.dp),
                                tint = if (m.status == MessageStatus.DELIVERED) Brand.Delivered else metaColor,
                            )
                            if (m.syncPending) Icon(Icons.Default.Schedule, contentDescription = "עדכון ממתין", modifier = Modifier.size(14.dp), tint = metaColor)
                        }
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!m.deleted && m.type == MessageType.TEXT) {
                    DropdownMenuItem(
                        text = { Text("העתק") }, leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = { menu = false; clipboard.setText(AnnotatedString(m.text)) },
                    )
                }
                if (mine && !m.deleted && m.type == MessageType.TEXT) {
                    DropdownMenuItem(
                        text = { Text("ערוך") }, leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menu = false; onEdit() },
                    )
                }
                if (mine && !m.deleted) {
                    DropdownMenuItem(
                        text = { Text("מחק לכולם", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDeleteForAll() },
                    )
                }
                if (!m.deleted) {
                    DropdownMenuItem(
                        text = { Text("מחק אצלי") }, leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menu = false; onDeleteLocal() },
                    )
                }
            }
        }
        // Sender avatar for other people's messages in groups (only on the last bubble of a run).
        if (group && !mine) {
            Spacer(Modifier.width(6.dp))
            if (senderAvatar != null) Avatar(m.senderName, m.senderId, size = 30.dp, avatarHash = senderAvatar)
            else Spacer(Modifier.width(30.dp))
        }
    }
}

private val senderColors = listOf(
    Color(0xFF6366F1), Color(0xFF0EA5E9), Color(0xFFF97316), Color(0xFF10B981),
    Color(0xFFEC4899), Color(0xFF8B5CF6), Color(0xFF14B8A6), Color(0xFFEF4444),
)

private fun senderColor(id: String) = senderColors[(id.hashCode() and 0x7fffffff) % senderColors.size]

@Composable
private fun VoiceContent(core: ChatCore, m: ChatMessage, playing: Boolean, mine: Boolean) {
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        progress = 0f
        while (playing) {
            progress = if (m.durationMs > 0) (core.voicePlayer.positionMs().toFloat() / m.durationMs).coerceIn(0f, 1f) else 0f
            delay(100)
        }
    }
    // A stable pseudo-waveform derived from the message id, so every note looks distinct.
    val bars = remember(m.id) {
        val rnd = java.util.Random(m.id.hashCode().toLong())
        List(28) { 0.25f + rnd.nextFloat() * 0.75f }
    }
    val active = if (mine) Color.White else Brand.Violet
    val inactive = if (mine) Color.White.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(236.dp)) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (mine) Color.White.copy(alpha = 0.22f) else MaterialTheme.colorScheme.primaryContainer)
                .clickable(enabled = m.hasMedia) { core.voicePlayer.toggle(m.id) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (playing) "עצור" else "נגן",
                tint = if (mine) Color.White else MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                bars.forEachIndexed { i, h ->
                    val on = playing && i.toFloat() / bars.size <= progress
                    Box(Modifier.width(3.dp).fillMaxHeight(h).clip(CircleShape).background(if (on) active else inactive))
                }
            }
            Text(
                formatDuration(if (playing) (progress * m.durationMs).toLong() else m.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = if (mine) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.outline,
            )
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
        shape = RoundedCornerShape(28.dp),
        title = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(core.conversationTitle(conv), conv, size = 88.dp, group = isGroup, avatarHash = contact?.avatar ?: "")
                Spacer(Modifier.height(10.dp))
                Text(core.conversationTitle(conv))
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                        val me = mem.id == core.myId
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(
                                mem.name, mem.id, size = 36.dp,
                                avatarHash = if (me) core.myAvatar else core.contact(mem.id)?.avatar ?: "",
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(if (me) "אני" else mem.name, style = MaterialTheme.typography.titleSmall)
                                if (pending) Text("הזמנה ממתינה", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
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
