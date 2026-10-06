package io.bigmoeonedge.example.chat.ui

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.bigmoeonedge.example.AppSettings
import io.bigmoeonedge.example.MainActivity
import io.bigmoeonedge.example.ModelManager
import io.bigmoeonedge.example.SettingsScreen
import io.bigmoeonedge.example.chat.ChatServices
import io.bigmoeonedge.example.chat.ChatSettings
import io.bigmoeonedge.example.scan.ScanResultsScreen
import io.bigmoeonedge.example.scan.ScanResultsViewModel
import io.bigmoeonedge.example.scan.ScanScreen
import io.bigmoeonedge.example.scan.ScanViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The app's front door: saved chats, a thread per conversation, settings. The engine itself lives in
 * the `:engine` process; this activity only reads and writes the database and sends intents.
 */
class ChatActivity : ComponentActivity() {
    // Set by a notification tap; consumed by the NavHost.
    private val openConversation = mutableStateOf<Long?>(null)
    private val openScan = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openConversation.value = conversationOf(intent)
        openScan.value = scanOf(intent)
        setContent {
            ChatTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ChatRoot(
                        openConversation = openConversation.value,
                        openScan = openScan.value,
                        onOpened = { openConversation.value = null; openScan.value = null },
                        onLab = ::openLab,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        conversationOf(intent)?.let { openConversation.value = it }
        scanOf(intent)?.let { openScan.value = it }
    }

    override fun onResume() {
        super.onResume()
        val client = ChatServices.client(this)
        // Back from the lab: give the engine process its queue back.
        if (prefs().getBoolean(KEY_LAB, false)) {
            prefs().edit().putBoolean(KEY_LAB, false).apply()
            client.resume()
        }
        // Work is waiting but the engine process is gone (killed, or the phone restarted).
        lifecycleScope.launch(Dispatchers.IO) {
            val db = ChatServices.db(this@ChatActivity)
            val waiting = db.messages().queued().isNotEmpty() || db.messages().active().isNotEmpty() ||
                db.scan().runningRun() != null
            if (waiting && !EngineLiveness.isAlive(this@ChatActivity)) client.kick()
        }
    }

    /** The lab screen runs its own engine; hold the chat queue and free the memory while it is open. */
    private fun openLab() {
        prefs().edit().putBoolean(KEY_LAB, true).apply()
        ChatServices.client(this).suspend()
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun prefs() = getSharedPreferences("chat_ui", Context.MODE_PRIVATE)

    private fun scanOf(i: Intent?): Long? =
        i?.getLongExtra(EXTRA_OPEN_SCAN, -1L)?.takeIf { it >= 0 }

    private fun conversationOf(i: Intent?): Long? =
        i?.getLongExtra(EXTRA_CONVERSATION_ID, -1L)?.takeIf { it >= 0 }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversationId"
        const val EXTRA_OPEN_SCAN = "openScanRun"
        private const val KEY_LAB = "lab_open"

        /** Cancels a conversation's reply notification once the user is looking at it. */
        fun cancelNotification(ctx: Context, conversationId: Long) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(conversationId.toInt())
        }
    }
}

@Composable
fun ChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}

@Composable
private fun ChatRoot(openConversation: Long?, openScan: Long?, onOpened: () -> Unit, onLab: () -> Unit) {
    val ctx = LocalContext.current
    val nav = rememberNavController()
    var chatSettings by remember { mutableStateOf(ChatSettings.load(ctx)) }
    NotificationPrompt()

    LaunchedEffect(openConversation) {
        if (openConversation != null) {
            nav.navigate("thread/$openConversation") { launchSingleTop = true }
            onOpened()
        }
    }

    LaunchedEffect(openScan) {
        if (openScan != null) {
            nav.navigate("scan/$openScan") { launchSingleTop = true }
            onOpened()
        }
    }

    NavHost(navController = nav, startDestination = "conversations") {
        composable("conversations") {
            val vm: ConversationListViewModel = viewModel()
            ConversationListScreen(
                vm = vm,
                onOpen = { nav.navigate("thread/$it") },
                onNew = { nav.navigate("new") },
                onSettings = { nav.navigate("settings") },
                onLab = onLab,
                onScan = { nav.navigate("scan") },
                onUnload = { ChatServices.client(ctx).unload() },
            )
        }
        composable("new") {
            val vm: NewChatViewModel = viewModel()
            NewChatScreen(
                vm = vm,
                onBack = { nav.popBackStack() },
                onCreated = { id -> nav.navigate("thread/$id") { popUpTo("conversations") } },
                onLab = onLab,
            )
        }
        composable("thread/{id}") { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
            val app = ctx.applicationContext as android.app.Application
            val vm: ThreadViewModel = viewModel(
                key = "thread$id",
                factory = viewModelFactory { initializer { ThreadViewModel(app, id) } },
            )
            LaunchedEffect(id) { ChatActivity.cancelNotification(ctx, id) }
            ThreadScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable("scan") {
            val vm: ScanViewModel = viewModel()
            ScanScreen(vm = vm, onBack = { nav.popBackStack() }, onOpenRun = { nav.navigate("scan/$it") })
        }
        composable("scan/{id}") { entry ->
            val id = entry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
            val app = ctx.applicationContext as android.app.Application
            val vm: ScanResultsViewModel = viewModel(
                key = "scan$id",
                factory = viewModelFactory { initializer { ScanResultsViewModel(app, id) } },
            )
            ScanResultsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable("settings") {
            ChatSettingsScreen(
                current = chatSettings,
                onChange = { chatSettings = it; it.save(ctx) },
                onEngineSettings = { nav.navigate("engine_settings") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("engine_settings") {
            val npuUnavailable = remember { ModelManager.npuUnavailableReason(ctx) }
            var settings by remember {
                mutableStateOf(AppSettings.load(ctx).let { if (npuUnavailable == null) it else it.copy(npuPrefill = false) })
            }
            SettingsScreen(
                current = settings,
                npuUnavailable = npuUnavailable,
                onChange = { settings = it; it.save(ctx) },
                onBack = { nav.popBackStack() },
            )
        }
    }
}

/**
 * Asks once, with a reason, for the permission replies need to reach the user while the app is
 * closed. Without it the chat still works; the reply is just waiting when the app is opened.
 */
@Composable
private fun NotificationPrompt() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("chat_ui", Context.MODE_PRIVATE) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var ask by remember {
        mutableStateOf(
            !prefs.getBoolean("asked_notifications", false) &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED,
        )
    }
    if (!ask) return
    AlertDialog(
        onDismissRequest = { ask = false; prefs.edit().putBoolean("asked_notifications", true).apply() },
        title = { Text("Get replies as notifications?") },
        text = { Text("A reply can take minutes. With notifications on, it reaches you when the app is closed, and you can answer from the notification.") },
        confirmButton = {
            TextButton(onClick = {
                ask = false
                prefs.edit().putBoolean("asked_notifications", true).apply()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text("Allow") }
        },
        dismissButton = {
            TextButton(onClick = { ask = false; prefs.edit().putBoolean("asked_notifications", true).apply() }) { Text("Not now") }
        },
    )
}
