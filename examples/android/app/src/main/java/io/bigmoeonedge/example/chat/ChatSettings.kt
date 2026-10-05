package io.bigmoeonedge.example.chat

import android.content.Context
import android.content.SharedPreferences

/**
 * Chat-only settings. The UI process writes them; the :engine process reads them at the start of
 * every job. Engine knobs (cache, threads, dense mode...) stay in the lab's [io.bigmoeonedge.example.AppSettings].
 */
data class ChatSettings(
    val nPredict: Int = 1024,
    // Prompt plus reply for the whole conversation. Memory too: the KV is sized for it once, and a
    // bigger one takes RAM from the expert cache. 0 = automatic: 4096, or 2048 for a model too big
    // for the background memory budget (see EngineConfig).
    val sessionCtx: Int = CTX_AUTO,
    val temperature: Float = 0.7f, // 0 = greedy
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val keepLoadedMinutes: Int = 30, // 0 = unload at once, -1 = never
    val pauseOnLowBattery: Boolean = true,
    // Show the reply text on the lock screen, which is what makes reading and answering from it possible.
    val lockScreenText: Boolean = true,
    val fakeEngine: Boolean = false, // debug builds only
    val fakeFast: Boolean = false, // debug builds only: the fake phone runs 40x faster, so a scan takes seconds
) {
    /** [sync] writes to disk before returning, for a caller that kicks the engine process right after. */
    fun save(ctx: Context, sync: Boolean = false) {
        val e = ctx.prefs().edit()
            .putInt("nPredict", nPredict).putInt("sessionCtx", sessionCtx)
            .putFloat("temperature", temperature).putFloat("topP", topP).putInt("topK", topK)
            .putInt("keepLoadedMinutes", keepLoadedMinutes)
            .putBoolean("pauseOnLowBattery", pauseOnLowBattery)
            .putBoolean("lockScreenText", lockScreenText)
            .putBoolean("fakeEngine", fakeEngine)
            .putBoolean("fakeFast", fakeFast)
        if (sync) e.commit() else e.apply()
    }

    companion object {
        const val CTX_AUTO = 0
        val CTX_CHOICES = intArrayOf(CTX_AUTO, 2048, 4096, 8192, 16384)
        val NPREDICT_CHOICES = intArrayOf(256, 512, 1024, 2048)
        val KEEP_CHOICES = intArrayOf(0, 5, 15, 30, 60, -1)

        fun load(ctx: Context): ChatSettings {
            val p = ctx.prefs()
            val d = ChatSettings()
            return ChatSettings(
                nPredict = p.getInt("nPredict", d.nPredict),
                sessionCtx = p.getInt("sessionCtx", d.sessionCtx),
                temperature = p.getFloat("temperature", d.temperature),
                topP = p.getFloat("topP", d.topP),
                topK = p.getInt("topK", d.topK),
                keepLoadedMinutes = p.getInt("keepLoadedMinutes", d.keepLoadedMinutes),
                pauseOnLowBattery = p.getBoolean("pauseOnLowBattery", d.pauseOnLowBattery),
                lockScreenText = p.getBoolean("lockScreenText", d.lockScreenText),
                fakeEngine = p.getBoolean("fakeEngine", d.fakeEngine),
                fakeFast = p.getBoolean("fakeFast", d.fakeFast),
            )
        }

        const val PREFS = "chat_settings"

        /**
         * The engine process reads settings the UI process wrote after this process cached the file.
         * MULTI_PROCESS makes getSharedPreferences re-read a file changed behind its back; it is
         * deprecated for writers, but a read-only consumer of a file one process writes is exactly
         * the case it still serves.
         */
        @Suppress("DEPRECATION")
        fun refreshFromDisk(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)
            ctx.getSharedPreferences("bmoe_settings", Context.MODE_MULTI_PROCESS)
        }

        private fun Context.prefs(): SharedPreferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
