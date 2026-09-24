@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.nearchat.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nearchat.core.ChatCore
import com.example.nearchat.core.ConnectionMode
import com.example.nearchat.core.FoundDevice
import com.example.nearchat.core.TransportKind
import com.example.nearchat.core.previewText

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה") }
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
    val onlineCount = remember(rev) { core.contacts().count { core.isOnline(it) } }
    var newGroup by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NearChat") },
                actions = {
                    IconButton(onClick = onConnection) {
                        BadgedBox(badge = { if (links.isNotEmpty()) Badge { Text("${links.size}") } }) {
                            Icon(Icons.Default.Wifi, contentDescription = "חיבור למכשירים")
                        }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "הגדרות") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onContacts,
                icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                text = { Text("שיחה חדשה") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Card(
                    Modifier.fillMaxWidth().padding(12.dp).clickable(onClick = onConnection),
                    colors = CardDefaults.cardColors(
                        containerColor = if (links.isNotEmpty()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (core.mode == ConnectionMode.BLUETOOTH) Icons.Default.Bluetooth else Icons.Default.Wifi, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (links.isEmpty()) "לא מחובר למכשירים" else "מחובר ל-${links.size} מכשירים · $onlineCount משתמשים זמינים",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text("ללא שרת וללא אינטרנט · מוצפן מקצה לקצה", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = onConnection) { Text(if (links.isEmpty()) "התחבר" else "ניהול") }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onContacts, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Person, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("שיחה פרטית")
                    }
                    OutlinedButton(onClick = { newGroup = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.GroupAdd, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("קבוצה חדשה")
                    }
                }
            }
            if (conversations.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("אין עדיין שיחות", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "התחבר למכשיר קרוב דרך Bluetooth או Wi‑Fi Direct, ואז פתח שיחה פרטית או צור קבוצה.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            items(conversations, key = { it.conversationId }) { c ->
                ListItem(
                    modifier = Modifier.clickable { onOpenChat(c.conversationId) },
                    leadingContent = { Avatar(c.title, c.conversationId, group = c.isGroup) },
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (!c.isGroup) { Spacer(Modifier.width(6.dp)); OnlineDot(c.online) }
                        }
                    },
                    supportingContent = {
                        Text(c.lastMessage?.previewText() ?: if (c.isGroup) "קבוצה חדשה" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            c.lastMessage?.let { Text(formatListTime(it.timestamp), style = MaterialTheme.typography.labelSmall) }
                            if (c.unread > 0) Badge { Text("${c.unread}") }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }

    if (newGroup) NewGroupDialog(core, onDismiss = { newGroup = false }, onCreated = { newGroup = false; onOpenChat(it) })
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
        title = { Text("קבוצה חדשה") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it.take(50) }, label = { Text("שם הקבוצה") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
                Text("בחר משתתפים", style = MaterialTheme.typography.labelLarge)
                if (contacts.isEmpty()) Text("אין עדיין אנשי קשר. התחבר קודם למכשירים קרובים.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(contacts, key = { it.id }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { if (c.id in selected) selected.remove(c.id) else selected.add(c.id) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = c.id in selected, onCheckedChange = { if (it) selected.add(c.id) else selected.remove(c.id) })
                            Text(c.name, modifier = Modifier.weight(1f))
                            OnlineDot(core.isOnline(c))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && selected.isNotEmpty(),
                onClick = { onCreated(core.createGroup(name, selected.toList())) },
            ) { Text("צור") }
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
    Scaffold(topBar = { TopAppBar(title = { Text("שיחות פרטיות") }, navigationIcon = { BackButton(onBack) }) }) { p ->
        LazyColumn(Modifier.fillMaxSize().padding(p)) {
            if (contacts.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("עדיין לא נמצאו משתמשים", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(8.dp))
                        Text("משתמשי NearChat מופיעים כאן אחרי שמתחברים אליהם.")
                        Spacer(Modifier.size(16.dp))
                        Button(onClick = onConnection) { Text("חיבור למכשירים") }
                    }
                }
            }
            items(contacts, key = { it.id }) { c ->
                val online = core.isOnline(c)
                ListItem(
                    modifier = Modifier.clickable { onOpenChat(c.id) },
                    leadingContent = { Avatar(c.name, c.id) },
                    headlineContent = { Text(c.name) },
                    supportingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OnlineDot(online); Spacer(Modifier.width(6.dp))
                            Text(if (online) "זמין" else if (c.lastSeen > 0) "נראה לאחרונה ${formatListTime(c.lastSeen)}" else "לא מחובר")
                        }
                    },
                    trailingContent = { TextButton(onClick = { onOpenChat(c.id) }) { Text("צ'אט") } },
                )
                HorizontalDivider()
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

    Scaffold(topBar = { TopAppBar(title = { Text("חיבור למכשירים") }, navigationIcon = { BackButton(onBack) }) }) { p ->
        Column(Modifier.fillMaxSize().padding(p).verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text("צורת חיבור", style = MaterialTheme.typography.titleMedium)
            listOf(
                ConnectionMode.AUTO to "אוטומטי (Bluetooth + Wi‑Fi Direct)",
                ConnectionMode.WIFI_DIRECT to "Wi‑Fi Direct בלבד",
                ConnectionMode.BLUETOOTH to "Bluetooth בלבד",
            ).forEach { (v, label) ->
                Row(Modifier.fillMaxWidth().clickable { core.mode = v }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = v == mode, onClick = { core.mode = v }); Text(label)
                }
            }

            SectionCard("חיבורים פעילים (${links.size})") {
                if (links.isEmpty()) Text("אין חיבורים פעילים", style = MaterialTheme.typography.bodySmall)
                links.forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Icon(if (l.transport == TransportKind.BLUETOOTH) Icons.Default.Bluetooth else Icons.Default.Wifi, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(l.remoteName ?: "מתחבר…", modifier = Modifier.weight(1f))
                        Text(if (l.transport == TransportKind.BLUETOOTH) "Bluetooth" else "Wi‑Fi Direct", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (links.isNotEmpty()) TextButton(onClick = { core.disconnectAll() }) { Text("נתק הכל") }
            }

            if (mode != ConnectionMode.WIFI_DIRECT) {
                SectionCard("Bluetooth") {
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
                            if (bt.discovering) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
                            if (bt.status.isNotEmpty()) Text(bt.status, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "במכשיר השני: פתח את NearChat ולחץ \"הפוך לגלוי\". כאן: \"חפש מכשירים\" ובחר אותו.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                            )
                            btDevices.forEach { d -> DeviceRow(d, connecting = bt.isConnecting(d.address)) { bt.connect(d.address) } }
                        }
                    }
                }
            }

            if (mode != ConnectionMode.BLUETOOTH) {
                SectionCard("Wi‑Fi Direct") {
                    when {
                        !wifi.supported -> Text("המכשיר לא תומך ב-Wi‑Fi Direct")
                        !wifi.hasPermissions() -> Button(onClick = onPermissions) { Text("אשר הרשאת מכשירים בקרבת מקום") }
                        else -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { wifi.discover() }) { Text("חפש מכשירים") }
                                OutlinedButton(onClick = { wifi.createGroup() }, enabled = !wifi.groupFormed) { Text("צור רשת (מארח)") }
                            }
                            if (wifi.discovering) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
                            if (wifi.groupFormed) {
                                Text(if (wifi.isGroupOwner) "אתה המארח של הרשת" else "מחובר לרשת Wi‑Fi Direct", style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { wifi.disconnect() }) { Text("התנתק מהרשת") }
                            }
                            if (wifi.status.isNotEmpty()) Text(wifi.status, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "חיבור ישיר בין הטלפונים, בלי ראוטר ובלי אינטרנט. Wi‑Fi חייב להיות דלוק. לקבוצה של 3+ מכשירים: אחד לוחץ \"צור רשת\" והשאר מתחברים אליו.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                            )
                            wifiPeers.forEach { d -> DeviceRow(d, connecting = false) { wifi.connect(d.address) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(6.dp))
            content()
        }
    }
}

@Composable
private fun DeviceRow(d: FoundDevice, connecting: Boolean, onConnect: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d.detail.isNotEmpty()) Text(d.detail, style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = onConnect, enabled = !connecting) { Text(if (connecting) "מתחבר…" else "התחבר") }
    }
}

// =====================================================================
// Settings
// =====================================================================
@Composable
fun SettingsScreen(core: ChatCore, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(core.myName) }
    Scaffold(topBar = { TopAppBar(title = { Text("הגדרות") }, navigationIcon = { BackButton(onBack) }) }) { p ->
        Column(Modifier.fillMaxSize().padding(p).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("השם שלך", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions.Default,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    core.myName = name
                    Toast.makeText(ctx, "השם נשמר", Toast.LENGTH_SHORT).show()
                }, enabled = name.isNotBlank()) { Text("שמור") }
            }
            Spacer(Modifier.size(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null); Spacer(Modifier.width(8.dp))
                Text("פרטיות והצפנה", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "• הודעות פרטיות מוצפנות מקצה לקצה (ECDH P‑256 + AES‑256‑GCM). מכשיר שמעביר הודעה בדרך לא יכול לקרוא אותה.\n" +
                    "• הודעות קבוצה מוצפנות במפתח קבוצה שמחולק רק לחברי הקבוצה.\n" +
                    "• כל ההודעות, התמונות וההקלטות נשמרות במכשיר מוצפנות במפתח של Android Keystore.\n" +
                    "• אין שרת ואין אינטרנט – הכל עובר ישירות בין המכשירים.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(12.dp))
            Text("טביעת האצבע שלך", style = MaterialTheme.typography.labelLarge)
            Text(core.myFingerprint(), fontFamily = FontFamily.Monospace)
            Spacer(Modifier.size(24.dp))
            Text("גרסה", style = MaterialTheme.typography.titleMedium)
            Text("NearChat 1.1 · פרוטוקול ${ChatCore.PROTOCOL_VERSION}")
        }
    }
}
