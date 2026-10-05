package io.bigmoeonedge.example.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.bigmoeonedge.example.chat.data.EngineStatusEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * adb-driven entry points, compiled into debug builds only:
 *
 *   am broadcast -n <pkg>/io.bigmoeonedge.example.chat.DebugChatReceiver -a SEND \
 *       --es text "hello" [--el conv N] [--es model /path.gguf] [--ez fake true] [--ez thinking true]
 *   ... -a DUMP [--el conv N]      prints the messages and the engine status to logcat (tag BmoeChatDebug)
 *   ... -a UNLOAD | -a KILL_ENGINE
 */
class DebugChatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle(app, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(ctx: Context, intent: Intent) {
        val repo = ChatServices.repository(ctx)
        when (intent.action) {
            "SEND" -> {
                if (intent.hasExtra("fake")) {
                    ChatSettings.load(ctx).copy(fakeEngine = intent.getBooleanExtra("fake", false)).save(ctx, sync = true)
                }
                var conv = intent.getLongExtra("conv", -1)
                if (conv < 0) {
                    val model = intent.getStringExtra("model") ?: "/data/local/tmp/fake.gguf"
                    conv = repo.createConversation(
                        model, intent.getStringExtra("system") ?: "", intent.getBooleanExtra("thinking", false),
                    )
                }
                repo.send(conv, intent.getStringExtra("text") ?: "hello")
                Log.i(TAG, "sent conv=$conv")
            }
            "DUMP" -> {
                val db = ChatServices.db(ctx)
                val st: EngineStatusEntity? = db.engineStatus().get()
                Log.i(TAG, "engine: ${st?.state} ${st?.detail} err=${st?.lastError} paused=${st?.pausedReason}")
                val one = intent.getLongExtra("conv", -1)
                val ids = if (one >= 0) listOf(one) else db.conversations().observeAll().first().map { it.id }
                for (id in ids) for (m in db.messages().listFor(id)) {
                    Log.i(TAG, "conv=$id msg=${m.id} ${m.role} ${m.status} att=${m.attempt} ooc=${m.outOfContext} " +
                        "tok=${m.tokens} text=${m.text.replace('\n', ' ').take(120)} err=${m.error}")
                }
            }
            "UNLOAD" -> ChatServices.client(ctx).unload()
        }
    }

    private companion object {
        const val TAG = "BmoeChatDebug"
    }
}
