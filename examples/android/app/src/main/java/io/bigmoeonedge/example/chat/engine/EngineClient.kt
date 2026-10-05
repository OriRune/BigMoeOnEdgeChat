package io.bigmoeonedge.example.chat.engine

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.bigmoeonedge.example.chat.data.EngineKicker

/**
 * The UI side of the engine process: every call is an intent to [EngineService], which lives in the
 * `:engine` process. Starting it as a foreground service is what lets a reply finish with the
 * screen off.
 */
class EngineClient(private val ctx: Context) : EngineKicker {
    override fun kick() = send(EngineService.ACTION_KICK)

    fun cancel(messageId: Long) = send(EngineService.ACTION_CANCEL) { putExtra(EngineService.EXTRA_MESSAGE_ID, messageId) }

    /** A scan run was queued or resumed in the database: start working on it. */
    fun scanStart() = send(EngineService.ACTION_SCAN_START)

    fun scanStop() = send(EngineService.ACTION_SCAN_STOP)

    fun unload() = send(EngineService.ACTION_UNLOAD)

    /** The lab screen is about to need the engine's memory. */
    fun suspend() = send(EngineService.ACTION_SUSPEND)

    fun resume() = send(EngineService.ACTION_RESUME)

    private fun send(action: String, extras: Intent.() -> Unit = {}) {
        val i = Intent(ctx, EngineService::class.java).setAction(action).apply(extras)
        // Refused when the app is in the background with no user action behind it (Android 12+). The
        // queue is in the database either way, so the work waits for the next kick.
        runCatching { ContextCompat.startForegroundService(ctx, i) }
            .onFailure { android.util.Log.w("BmoeEngineClient", "could not start the engine service: $it") }
    }
}
