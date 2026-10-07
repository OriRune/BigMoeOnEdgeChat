package io.bigmoeonedge.example.chat.engine

import io.bigmoeonedge.example.AppSettings
import io.bigmoeonedge.example.DenseWeights
import io.bigmoeonedge.example.chat.ChatSettings
import io.bigmoeonedge.example.chat.ThinkLevel

/**
 * What a chat job asks of the engine: the argv that opens the session and its identity. Pure, so the
 * footprint rules are unit-tested.
 */
data class EngineConfig(
    val app: AppSettings,
    val argvSig: String,
) {
    companion object {
        /**
         * The :engine process is held to ~3.1 GiB of memory (measured, see CHAT.md): anonymous
         * RAM plus swap above that and the kernel throttles the process to a stop. A model whose file
         * is bigger than this fraction of the budget gets the small footprint.
         */
        const val SMALL_FOOTPRINT_CACHE_MB = 500
        const val SMALL_FOOTPRINT_CTX = 2048
        private const val BIG_MODEL_BYTES = 6L shl 30

        /**
         * Settings for chatting with a model: the user's engine settings (the lab's) with the chat
         * settings laid over them, then clamped to the memory budget of the process the engine lives in.
         */
        fun resolve(
            base: AppSettings,
            chat: ChatSettings,
            modelBytes: Long,
            override: AppSettings? = null,
            // Foreground mode runs in the main process while the app is in front, which gets about twice the
            // memory of :engine, so the small footprint is not applied.
            foreground: Boolean = false,
        ): AppSettings {
            // A scan-tuned profile replaces the global engine settings wholesale, and stands on its own.
            if (override != null) {
                return override.copy(nPredict = chat.nPredict, sessionCtx = autoCtx(chat, modelBytes, override.mmap, foreground))
            }
            var s = base.copy(
                nPredict = chat.nPredict,
                sessionCtx = autoCtx(chat, modelBytes, base.mmap, foreground),
                // Lossless unless the user set it in the engine settings screen.
                metricsCsv = false,
            )
            if (modelBytes >= BIG_MODEL_BYTES && !s.mmap && !foreground) {
                // Cache + dense weights + KV have to fit the background budget. 500 MiB is below the
                // engine's floor, hence --force-cache (sessionArgv adds it).
                if (s.cacheMb == AppSettings.CACHE_AUTO || s.cacheMb > SMALL_FOOTPRINT_CACHE_MB) {
                    s = s.copy(cacheMb = SMALL_FOOTPRINT_CACHE_MB)
                }
                if (s.denseWeights == DenseWeights.AHWB) s = s.copy(denseWeights = DenseWeights.ANON)
            }
            return s
        }

        private fun autoCtx(chat: ChatSettings, modelBytes: Long, mmap: Boolean, foreground: Boolean): Int = when {
            chat.sessionCtx != ChatSettings.CTX_AUTO -> chat.sessionCtx
            modelBytes >= BIG_MODEL_BYTES && !mmap && !foreground -> SMALL_FOOTPRINT_CTX
            else -> AppSettings.SESSION_CTX
        }

        /** What one reply asks the engine for: the total n_predict, and the part of it thinking may use. */
        data class TurnBudget(val nPredict: Int, val thinkBudget: Int?)

        /**
         * The reply length [reply] is the answer's; thinking comes on top, so a long think cannot eat
         * the answer. The context is shared with the history, so thinking never takes more than a
         * quarter of it (on top of a reply that is already at most half).
         */
        fun turnBudget(reply: Int, ctx: Int, thinking: Boolean, level: ThinkLevel): TurnBudget {
            if (!thinking) return TurnBudget(reply, null)
            val think = minOf(level.tokens, (ctx / 4).coerceAtLeast(64))
            return TurnBudget(reply + think, think)
        }

        /** Sampling flags for the session argv; greedy when the temperature is 0. */
        fun samplingArgs(chat: ChatSettings): List<String> =
            if (chat.temperature <= 0f) emptyList()
            else listOf(
                "--temp", chat.temperature.toString(),
                "--top-p", chat.topP.toString(),
                "--top-k", chat.topK.toString(),
            )

        fun argv(s: AppSettings, chat: ChatSettings, cliPath: String, modelPath: String): List<String> =
            s.sessionArgv(cliPath, modelPath, csvPath = null) + samplingArgs(chat)

        /** Identity of the session: the argv without the cli path. A change reopens the session. */
        fun signature(s: AppSettings, chat: ChatSettings, modelPath: String): String =
            s.sessionSignature(modelPath) + "|" + samplingArgs(chat).joinToString("|")
    }
}
