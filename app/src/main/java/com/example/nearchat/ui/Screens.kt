@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.nearchat.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nearchat.core.ChatCore
import com.example.nearchat.core.ConnectionMode
import com.example.nearchat.core.FoundDevice
import com.example.nearchat.core.ImageUtil
import com.example.nearchat.core.TransportKind
import com.example.nearchat.core.previewText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Picks a photo, crops it to a profile picture and hands the bytes back. */
@Composable
private fun rememberAvatarPicker(onPicked: (ByteArray) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { ImageUtil.prepareAvatar(ctx, uri) }
            if (bytes != null) onPicked(bytes) else Toast.makeText(ctx, "לא ניתן לטעון את התמונה", Toast.LENGTH_SHORT).show()
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/** Big profile picture with a camera badge; tapping opens the picker menu. */
@Composable
private fun EditableAvatar(core: ChatCore, name: String, size: androidx.compose.ui.unit.Dp) {
    val rev = LocalRev.current
    val hash = remember(rev) { core.myAvatar }
    var menu by remember { mutableStateOf(false) }
    val pick = rememberAvatarPicker { core.setMyAvatar(it) }
    Box {
        Box(Modifier.clip(CircleShape).clickable { if (hash.isEmpty()) pick() else menu = true }) {
            Avatar(name, core.myId, size = size, avatarHash = hash, ring = true)
        }
        Box(
            Modifier.align(Alignment.BottomStart).offset(x = (-2).dp, y = (-2).dp).size(size * 0.3f).clip(CircleShape)
                .background(Color.White).padding(3.dp).clip(CircleShape).background(Brand.bubble).clickable { pick() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.CameraAlt, contentDescription = "החלף תמונה", tint = Color.White, modifier = Modifier.size(size * 0.15f)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("בחר תמונה חדשה") }, onClick = { menu = false; pick() })
            DropdownMenuItem(text = { Text("הסר תמונה", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; core.setMyAvatar(null) })
        }
    }
}

// =====================================================================
// Welcome (first run)
// =====================================================================
@Composable
fun WelcomeScreen(core: ChatCore, onDone: () -> Unit) {
    var name by remember { mutableStateOf(if (core.myName == "משתמש") "" else core.myName) }
    Box(Modifier.fillMaxSize().background(Brand.header)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Forum, contentDescription = null, tint = Color.White, modifier = Modifier.size(46.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("NearChat", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Text(
                "צ'אט בין טלפונים קרובים – בלי אינטרנט, בלי שרת, מוצפן מקצה לקצה",
                color = Color.White.copy(alpha = 0.88f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(36.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(MaterialTheme.colorScheme.surface).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("הפרופיל שלך", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text("כך יראו אותך אנשים בסביבה", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                EditableAvatar(core, name.ifBlank { "?" }, 116.dp)
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                    label = { Text("השם שלך") }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                GradientButton(
                    "בואו נתחיל", enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                    onClick = { core.myName = name; core.onboarded = true; onDone() },
                )
            }
        }
    }
}

// =====================================================================
// Home
// =====================================================================
@Composable
fun HomeScreen(
    core: ChatCore,
    onOpenChat: (String) -> Unit,
    onContacts: () -> Unit,
    onConnection: () -> Unit,
    onSettings: () -> Unit,
) {
    val rev = LocalRev.current
    val conversations = remember(rev) { core.conversations() }
    val links = remember(rev) { core.links() }
    val online = remember(rev) { core.contacts().filter { core.isOnline(it) } }
    val myAvatar = remember(rev) { core.myAvatar }
    var newGroup by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            Box(Modifier.navigationBarsPadding()) { GradientCircleButton(Icons.AutoMirrored.Filled.Chat, "שיחה חדשה", onContacts, size = 62.dp, brush = Brand.header) }
        },
    ) { _ ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
            item {
                GradientHeader {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clip(CircleShape).clickable(onClick = onSettings)) {
                            Avatar(core.myName, core.myId, size = 44.dp, avatarHash = myAvatar, ring = true)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("NearChat", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                            Text("שלום, ${core.myName}", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        }
                        HeaderIcon(if (core.mode == ConnectionMode.BLUETOOTH) Icons.Default.Bluetooth else Icons.Default.WifiTethering, "חיבור למכשירים", onConnection)
                        HeaderIcon(Icons.Default.Settings, "הגדרות", onSettings)
                    }
                    // Connection status pill
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.16f))
                            .clickable(onClick = onConnection).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (links.isEmpty()) Color(0xFFFCD34D) else Brand.Online))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (links.isEmpty()) "לא מחובר למכשירים" else "מחובר ל-${links.size} מכשירים · ${online.size} זמינים",
                                color = Color.White, fontWeight = FontWeight.SemiBold,
                            )
                            Text("ללא אינטרנט · מוצפן מקצה לקצה", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
                        }
                        Text(if (links.isEmpty()) "התחבר" else "ניהול", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    // Online now strip
                    if (online.isNotEmpty()) {
                        androidx.compose.foundation.lazy.LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.padding(bottom = 16.dp),
                        ) {
                            items(online, key = { it.id }) { c ->
                                Column(
                                    Modifier.width(62.dp).clip(RoundedCornerShape(12.dp)).clickable { onOpenChat(c.id) },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Avatar(c.name, c.id, size = 54.dp, avatarHash = c.avatar, online = true, ring = true)
                                    Spacer(Modifier.height(4.dp))
                                    Text(c.name, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickAction(Icons.Default.PersonSearch, "שיחה פרטית", Modifier.weight(1f), onContacts)
                    QuickAction(Icons.Default.GroupAdd, "קבוצה חדשה", Modifier.weight(1f)) { newGroup = true }
                }
            }
            if (conversations.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Default.Forum, "אין עדיין שיחות",
                        "התחבר למכשיר קרוב דרך Bluetooth או Wi‑Fi Direct, ואז פתח שיחה פרטית או צור קבוצה.",
                    ) { GradientButton("חיבור למכשירים", onConnection, icon = Icons.Default.Wifi) }
                }
            } else {
                item { Text("שיחות", style = sectionTitle, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            }
            items(conversations, key = { it.conversationId }) { c ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).clip(RoundedCornerShape(20.dp))
                        .clickable { onOpenChat(c.conversationId) }.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(c.title, c.conversationId, size = 56.dp, group = c.isGroup, avatarHash = c.avatar, online = if (c.isGroup) null else c.online)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            c.lastMessage?.let {
                                Text(
                                    formatListTime(it.timestamp), style = MaterialTheme.typography.labelSmall,
                                    color = if (c.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val last = c.lastMessage
                            val prefix = if (last != null && last.senderId == core.myId) "את/ה: " else if (c.isGroup && last != null) "${last.senderName}: " else ""
                            Text(
                                if (last == null) (if (c.isGroup) "קבוצה חדשה – אמרו שלום 👋" else "") else prefix + last.previewText(),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (c.unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (c.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (c.unread > 0) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    Modifier.heightIn(min = 22.dp).clip(CircleShape).background(Brand.header).padding(horizontal = 7.dp, vertical = 2.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(if (c.unread > 99) "99+" else "${c.unread}", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (newGroup) NewGroupDialog(core, onDismiss = { newGroup = false }, onCreated = { newGroup = false; onOpenChat(it) })
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Brand.bubble), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

// =====================================================================
// New group
// =====================================================================
@Composable
fun NewGroupDialog(core: ChatCore, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val rev = LocalRev.current
    val contacts = remember(rev) { core.contacts() }
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        title = { Text("קבוצה חדשה") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(50) }, label = { Text("שם הקבוצה") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(12.dp))
                Text("בחר משתתפים (${selected.size})", style = sectionTitle)
                if (contacts.isEmpty()) Text("אין עדיין אנשי קשר. התחבר קודם למכשירים קרובים.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(contacts, key = { it.id }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .clickable { if (c.id in selected) selected.remove(c.id) else selected.add(c.id) }.padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(c.name, c.id, size = 40.dp, avatarHash = c.avatar, online = core.isOnline(c))
                            Spacer(Modifier.width(10.dp))
                            Text(c.name, modifier = Modifier.weight(1f))
                            Checkbox(checked = c.id in selected, onCheckedChange = { if (it) selected.add(c.id) else selected.remove(c.id) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && selected.isNotEmpty(),
                onClick = { onCreated(core.createGroup(name, selected.toList())) },
            ) { Text("צור קבוצה", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}

// =====================================================================
// Contacts (start a private chat)
// =====================================================================
@Composable
fun ContactsScreen(core: ChatCore, onBack: () -> Unit, onOpenChat: (String) -> Unit, onConnection: () -> Unit) {
    val rev = LocalRev.current
    val contacts = remember(rev) { core.contacts() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { GradientTopBar("שיחה חדשה", onBack, subtitle = "${contacts.count { core.isOnline(it) }} זמינים עכשיו") },
    ) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (contacts.isEmpty()) {
                item {
                    EmptyState(Icons.Default.PersonSearch, "עדיין לא נמצאו משתמשים", "משתמשי NearChat מופיעים כאן אחרי שמתחברים אליהם.") {
                        GradientButton("חיבור למכשירים", onConnection, icon = Icons.Default.Wifi)
                    }
                }
            }
            items(contacts, key = { it.id }) { c ->
                val online = core.isOnline(c)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).clip(RoundedCornerShape(20.dp))
                        .clickable { onOpenChat(c.id) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(c.name, c.id, size = 52.dp, avatarHash = c.avatar, online = online)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (online) "זמין עכשיו" else if (c.lastSeen > 0) "נראה לאחרונה ${formatListTime(c.lastSeen)}" else "לא מחובר",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (online) Brand.Online else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clickable { onOpenChat(c.id) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "צ'אט", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}

// =====================================================================
// Connection
// =====================================================================
@Composable
fun ConnectionScreen(core: ChatCore, onBack: () -> Unit, onPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val rev = LocalRev.current
    val mode = remember(rev) { core.mode }
    val links = remember(rev) { core.links() }
    val bt = core.bluetooth
    val wifi = core.wifi
    val btDevices = remember(rev) { bt.foundDevices() }
    val wifiPeers = remember(rev) { wifi.peers() }

    val btIntent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { core.applyTransports() }
    fun launchBt(action: String, extra: (Intent.() -> Unit)? = null) {
        try { btIntent.launch(Intent(action).apply { extra?.invoke(this) }) } catch (e: Exception) {
            Toast.makeText(ctx, "נדרשות הרשאות Bluetooth", Toast.LENGTH_SHORT).show(); onPermissions()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { GradientTopBar("חיבור למכשירים", onBack, subtitle = if (links.isEmpty()) "לא מחובר" else "${links.size} חיבורים פעילים") },
    ) { p ->
        Column(
            Modifier.fillMaxSize().padding(p).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SoftCard {
                Text("צורת חיבור", style = sectionTitle)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ConnectionMode.AUTO to "אוטומטי",
                        ConnectionMode.WIFI_DIRECT to "Wi‑Fi Direct",
                        ConnectionMode.BLUETOOTH to "Bluetooth",
                    ).forEach { (v, label) ->
                        val check: @Composable () -> Unit = { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        FilterChip(
                            selected = v == mode, onClick = { core.mode = v }, label = { Text(label) },
                            leadingIcon = if (v == mode) check else null,
                            shape = RoundedCornerShape(12.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
                if (mode == ConnectionMode.AUTO) {
                    Text("Bluetooth ו‑Wi‑Fi Direct יחד – הכי אמין", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SoftCard {
                Text("חיבורים פעילים (${links.size})", style = sectionTitle)
                Spacer(Modifier.height(6.dp))
                if (links.isEmpty()) Text("אין חיבורים פעילים", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                links.forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                        IconBadge(if (l.transport == TransportKind.BLUETOOTH) Icons.Default.Bluetooth else Icons.Default.Wifi)
                        Spacer(Modifier.width(12.dp))
                        Text(l.remoteName ?: "מתחבר…", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(if (l.transport == TransportKind.BLUETOOTH) "Bluetooth" else "Wi‑Fi Direct", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                if (links.isNotEmpty()) TextButton(onClick = { core.disconnectAll() }) { Text("נתק הכל", color = MaterialTheme.colorScheme.error) }
            }

            if (mode != ConnectionMode.WIFI_DIRECT) {
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Default.Bluetooth); Spacer(Modifier.width(12.dp))
                        Text("Bluetooth", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    when {
                        !bt.supported -> Text("המכשיר לא תומך ב-Bluetooth")
                        !bt.hasPermissions() -> Button(onClick = onPermissions) { Text("אשר הרשאות Bluetooth") }
                        !bt.enabled -> Button(onClick = { launchBt(BluetoothAdapter.ACTION_REQUEST_ENABLE) }) { Text("הפעל Bluetooth") }
                        else -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { bt.startDiscovery() }) { Text("חפש מכשירים") }
                                OutlinedButton(onClick = {
                                    launchBt(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE) { putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300) }
                                }) { Text("הפוך לגלוי") }
                            }
                            if (bt.discovering) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(CircleShape))
                            if (bt.status.isNotEmpty()) Text(bt.status, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "במכשיר השני: פתח את NearChat ולחץ \"הפוך לגלוי\". כאן: \"חפש מכשירים\" ובחר אותו.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                            btDevices.forEach { d -> DeviceRow(d, connecting = bt.isConnecting(d.address)) { bt.connect(d.address) } }
                        }
                    }
                }
            }

            if (mode != ConnectionMode.BLUETOOTH) {
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Default.Wifi); Spacer(Modifier.width(12.dp))
                        Text("Wi‑Fi Direct", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    when {
                        !wifi.supported -> Text("המכשיר לא תומך ב-Wi‑Fi Direct")
                        !wifi.hasPermissions() -> Button(onClick = onPermissions) { Text("אשר הרשאת מכשירים בקרבת מקום") }
                        else -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { wifi.discover() }) { Text("חפש מכשירים") }
                                OutlinedButton(onClick = { wifi.createGroup() }, enabled = !wifi.groupFormed) { Text("צור רשת (מארח)") }
                            }
                            if (wifi.discovering) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(CircleShape))
                            if (wifi.groupFormed) {
                                Text(if (wifi.isGroupOwner) "אתה המארח של הרשת" else "מחובר לרשת Wi‑Fi Direct", style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { wifi.disconnect() }) { Text("התנתק מהרשת") }
                            }
                            if (wifi.status.isNotEmpty()) Text(wifi.status, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "חיבור ישיר בין הטלפונים, בלי ראוטר ובלי אינטרנט. Wi‑Fi חייב להיות דלוק. לקבוצה של 3+ מכשירים: אחד לוחץ \"צור רשת\" והשאר מתחברים אליו.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                            wifiPeers.forEach { d -> DeviceRow(d, connecting = false) { wifi.connect(d.address) } }
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Brand.bubble), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DeviceRow(d: FoundDevice, connecting: Boolean, onConnect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            if (d.detail.isNotEmpty()) Text(d.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        TextButton(onClick = onConnect, enabled = !connecting) { Text(if (connecting) "מתחבר…" else "התחבר", fontWeight = FontWeight.Bold) }
    }
}

// =====================================================================
// Settings
// =====================================================================
@Composable
fun SettingsScreen(core: ChatCore, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(core.myName) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { _ ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            GradientHeader {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה", tint = Color.White)
                    }
                    Text("הגדרות", color = Color.White, style = MaterialTheme.typography.titleLarge)
                }
                Column(Modifier.fillMaxWidth().padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EditableAvatar(core, name, 120.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(core.myName, color = Color.White, style = MaterialTheme.typography.headlineMedium)
                    Text("לחץ על התמונה כדי להחליף", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SoftCard {
                    Text("השם שלך", style = sectionTitle)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                            shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        GradientCircleButton(Icons.Default.Check, "שמור", size = 50.dp, onClick = {
                            if (name.isNotBlank()) {
                                core.myName = name
                                Toast.makeText(ctx, "השם נשמר", Toast.LENGTH_SHORT).show()
                            }
                        })
                    }
                }
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Default.Shield); Spacer(Modifier.width(12.dp))
                        Text("פרטיות והצפנה", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    listOf(
                        "הודעות פרטיות מוצפנות מקצה לקצה (ECDH P‑256 + AES‑256‑GCM). מכשיר שמעביר הודעה בדרך לא יכול לקרוא אותה.",
                        "הודעות קבוצה מוצפנות במפתח קבוצה שמחולק רק לחברי הקבוצה.",
                        "תמונת הפרופיל נשלחת מוצפנת רק למכשירים שמתחברים אליך.",
                        "כל ההודעות, התמונות וההקלטות נשמרות במכשיר מוצפנות במפתח של Android Keystore.",
                        "אין שרת ואין אינטרנט – הכל עובר ישירות בין המכשירים.",
                    ).forEach { line ->
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Text("טביעת האצבע שלך", style = sectionTitle)
                    Text(core.myFingerprint(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    "NearChat 1.2 · פרוטוקול ${ChatCore.PROTOCOL_VERSION}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding(), textAlign = TextAlign.Center,
                )
            }
        }
    }
}
