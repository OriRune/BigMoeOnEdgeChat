package io.bigmoeonedge.example.chat.engine

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.bigmoeonedge.example.chat.AppForeground
import io.bigmoeonedge.example.chat.ChatSettings
import io.bigmoeonedge.example.chat.data.EngineKicker

/**
 * The UI side of the engine process: every call is an intent to [EngineService], which lives in the
 * `:engine` process. Starting it as a foreground service is what lets a reply finish with the
 * screen off.
 */
class EngineClient(private val ctx: Context) : EngineKicker {
    override fun kick() = sendBoth(EngineService.ACTION_KICK)

    fun cancel(messageId: Long) = sendBoth(EngineService.ACTION_CANCEL) { putExtra(EngineService.EXTRA_MESSAGE_ID, messageId) }

    /** A scan run was queued or resumed in the database: start working on it. */
    fun scanStart() = send(EngineService.ACTION_SCAN_START)

    fun scanStop() = send(EngineService.ACTION_SCAN_STOP)

    fun unload() = sendBoth(EngineService.ACTION_UNLOAD)

    /** Both engine services, whatever the settings say: a model just changed mode and its old copy must go. */
    fun unloadAll() {
        send(EngineService.ACTION_UNLOAD)
        if (AppForeground.isForeground) send(EngineService.ACTION_UNLOAD, service = ForegroundEngineService::class.java)
    }

    /** The lab screen is about to need the engine's memory. */
    fun suspend() = sendBoth(EngineService.ACTION_SUSPEND)

    fun resume() = sendBoth(EngineService.ACTION_RESUME)

    /**
     * The :engine service always; the main-process one too when some model is set to foreground mode and
     * the app is in front (a background start is refused, and the queue waits for the next kick when the
     * app returns).
     */
    private fun sendBoth(action: String, extras: Intent.() -> Unit = {}) {
        send(action, extras)
        if (AppForeground.isForeground && ChatSettings.foregroundModels(ctx).isNotEmpty()) {
            send(action, extras, ForegroundEngineService::class.java)
        }
    }

    private fun send(action: String, extras: Intent.() -> Unit = {}, service: Class<*> = EngineService::class.java) {
        val i = Intent(ctx, service).setAction(action).apply(extras)
        // Refused when the app is in the background with no user action behind it (Android 12+). The
        // queue is in the database either way, so the work waits for the next kick.
        runCatching { ContextCompat.startForegroundService(ctx, i) }
            .onFailure { android.util.Log.w("BmoeEngineClient", "could not start the engine service: $it") }
    }
}
