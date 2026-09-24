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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.example.nearchat.core.ChatCore
import com.example.nearchat.ui.ChatScreen
import com.example.nearchat.ui.ConnectionScreen
import com.example.nearchat.ui.ContactsScreen
import com.example.nearchat.ui.HomeScreen
import com.example.nearchat.ui.LocalCore
import com.example.nearchat.ui.LocalRev
import com.example.nearchat.ui.NearChatTheme
import com.example.nearchat.ui.WelcomeScreen
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
                    LocalCore provides core,
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

private sealed interface Screen {
    data object Welcome : Screen
    data object Home : Screen
    data object Contacts : Screen
    data object Connection : Screen
    data object Settings : Screen
    data class Chat(val conversationId: String) : Screen
}

@Composable
fun App(core: ChatCore, pendingConversation: MutableState<String?>) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(if (core.onboarded) Screen.Home else Screen.Welcome) }

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

    BackHandler(enabled = screen != Screen.Home && screen != Screen.Welcome) { screen = Screen.Home }

    AnimatedContent(
        targetState = screen,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        transitionSpec = {
            // Going deeper slides in from the leading edge (RTL: from the left); going home reverses it.
            val forward = targetState != Screen.Home
            val dir = if (forward) -1 else 1
            (slideInHorizontally { w -> dir * w / 4 } + fadeIn()) togetherWith (slideOutHorizontally { w -> -dir * w / 4 } + fadeOut())
        },
        label = "screens",
    ) { target -> Box(Modifier.fillMaxSize()) { ScreenContent(core, target, { screen = it }, { permissionLauncher.launch(it) }) } }
}

@Composable
private fun ScreenContent(core: ChatCore, s: Screen, go: (Screen) -> Unit, requestPermissions: (Array<String>) -> Unit) {
    when (s) {
        Screen.Welcome -> WelcomeScreen(core, onDone = { go(Screen.Home) })
        Screen.Home -> HomeScreen(
            core = core,
            onOpenChat = { go(Screen.Chat(it)) },
            onContacts = { go(Screen.Contacts) },
            onConnection = { go(Screen.Connection) },
            onSettings = { go(Screen.Settings) },
        )
        Screen.Contacts -> ContactsScreen(core, onBack = { go(Screen.Home) }, onOpenChat = { go(Screen.Chat(it)) }, onConnection = { go(Screen.Connection) })
        Screen.Connection -> ConnectionScreen(core, onBack = { go(Screen.Home) }, onPermissions = { requestPermissions(basePermissions) })
        Screen.Settings -> SettingsScreen(core, onBack = { go(Screen.Home) })
        is Screen.Chat -> ChatScreen(core, s.conversationId, onBack = { go(Screen.Home) })
    }
}
