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
 *   ... -a FOREGROUND --es model /path.gguf --ez on true|false     foreground mode for a model
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
                applyFake(ctx, intent)
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
            "FOREGROUND" -> {
                val model = intent.getStringExtra("model") ?: "/data/local/tmp/fake.gguf"
                ChatSettings.setForeground(ctx, model, intent.getBooleanExtra("on", true))
                ChatServices.client(ctx).unloadAll()
                Log.i(TAG, "foreground models: ${ChatSettings.foregroundModels(ctx)}")
            }
            "SCAN" -> {
                applyFake(ctx, intent)
                val model = intent.getStringExtra("model") ?: "/data/local/tmp/bmoe/FakeMoE-Q4_0.gguf"
                ChatServices.scan(ctx).start(
                    listOf(model), intent.getBooleanExtra("sustained", true), intent.getBooleanExtra("lossy", false),
                    intent.getIntExtra("minutes", 12),
                )
                Log.i(TAG, "scan started")
            }
            "SCAN_STOP" -> {
                ChatServices.scan(ctx).stopAll()
                ChatServices.client(ctx).scanStop()
            }
            "SCAN_DUMP" -> {
                val db = ChatServices.db(ctx)
                for (r in db.scan().observeRuns().first()) {
                    Log.i(TAG, "run=${r.id} ${r.status} rec='${r.recommendedLabel}' conf=${r.confirmed} detail='${r.detail}' verdict='${r.verdict}'")
                    for (c in db.scan().cells(r.id)) {
                        Log.i(TAG, "  cell=${c.id} ${c.stage} '${c.label}' a${c.attempt} ${c.status} dec=${"%.2f".format(c.decodeMedianTokS)} " +
                            "sus=${"%.2f".format(c.sustainedTokS)} hot=${"%.2f".format(c.throttledFrac)} gate=${"%.1f".format(c.gateWaitS)}")
                    }
                }
            }
        }
    }

    private fun applyFake(ctx: Context, intent: Intent) {
        if (intent.hasExtra("fake") || intent.hasExtra("fast")) {
            val cur = ChatSettings.load(ctx)
            cur.copy(
                fakeEngine = if (intent.hasExtra("fake")) intent.getBooleanExtra("fake", false) else cur.fakeEngine,
                fakeFast = if (intent.hasExtra("fast")) intent.getBooleanExtra("fast", false) else cur.fakeFast,
            ).save(ctx, sync = true)
        }
    }

    private companion object {
        const val TAG = "BmoeChatDebug"
    }
}
