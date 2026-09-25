package com.example.nearchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.example.nearchat.core.ChatCore
import com.example.nearchat.ui.ChatScreen
import com.example.nearchat.ui.ConnectionScreen
import com.example.nearchat.ui.ContactsScreen
import com.example.nearchat.ui.HomeScreen
import com.example.nearchat.ui.LocalRev
import com.example.nearchat.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private var rev by mutableIntStateOf(0)
    private val onCoreChanged: () -> Unit = { rev++ }
    private val pendingConversation = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        core.addListener(onCoreChanged)
        pendingConversation.value = intent?.getStringExtra(Notifications.EXTRA_CONV)
        setContent {
            NearChatTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                    LocalRev provides rev,
                ) {
                    App(core, pendingConversation)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifications.EXTRA_CONV)?.let { pendingConversation.value = it }
    }

    override fun onResume() {
        super.onResume()
        core.uiVisible = true
        rev++
    }

    override fun onPause() {
        core.uiVisible = false
        core.flush()
        super.onPause()
    }

    override fun onDestroy() {
        core.removeListener(onCoreChanged)
        super.onDestroy()
    }
}

private val basePermissions = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.NEARBY_WIFI_DEVICES,
    Manifest.permission.POST_NOTIFICATIONS,
)

@Composable
fun NearChatTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF9DB0FF), secondary = Color(0xFF9DB0FF))
    else lightColorScheme(primary = Color(0xFF405DE6), secondary = Color(0xFF5B6CC9))
    MaterialTheme(colorScheme = scheme, content = content)
}

private sealed interface Screen {
    data object Home : Screen
    data object Contacts : Screen
    data object Connection : Screen
    data object Settings : Screen
    data class Chat(val conversationId: String) : Screen
}

@Composable
fun App(core: ChatCore, pendingConversation: MutableState<String?>) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        core.applyTransports()
        NearChatService.start(ctx)
    }
    LaunchedEffect(Unit) {
        val missing = basePermissions.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else { core.applyTransports(); NearChatService.start(ctx) }
    }

    val pending = pendingConversation.value
    LaunchedEffect(pending) {
        if (pending != null) { screen = Screen.Chat(pending); pendingConversation.value = null }
    }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            core = core,
            onOpenChat = { screen = Screen.Chat(it) },
            onContacts = { screen = Screen.Contacts },
            onConnection = { screen = Screen.Connection },
            onSettings = { screen = Screen.Settings },
        )
        Screen.Contacts -> ContactsScreen(core, onBack = { screen = Screen.Home }, onOpenChat = { screen = Screen.Chat(it) }, onConnection = { screen = Screen.Connection })
        Screen.Connection -> ConnectionScreen(core, onBack = { screen = Screen.Home }, onPermissions = { permissionLauncher.launch(basePermissions) })
        Screen.Settings -> SettingsScreen(core, onBack = { screen = Screen.Home })
        is Screen.Chat -> ChatScreen(core, s.conversationId, onBack = { screen = Screen.Home })
    }
}
