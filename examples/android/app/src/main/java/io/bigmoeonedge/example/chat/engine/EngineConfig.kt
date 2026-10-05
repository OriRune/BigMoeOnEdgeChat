package io.bigmoeonedge.example.chat.engine

import io.bigmoeonedge.example.AppSettings
import io.bigmoeonedge.example.DenseWeights
import io.bigmoeonedge.example.chat.ChatSettings

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
        ): AppSettings {
            // A scan-tuned profile replaces the global engine settings wholesale, and stands on its own.
            if (override != null) {
                return override.copy(nPredict = chat.nPredict, sessionCtx = autoCtx(chat, modelBytes, override.mmap))
            }
            var s = base.copy(
                nPredict = chat.nPredict,
                sessionCtx = autoCtx(chat, modelBytes, base.mmap),
                // Lossless unless the user set it in the engine settings screen.
                metricsCsv = false,
            )
            if (modelBytes >= BIG_MODEL_BYTES && !s.mmap) {
                // Cache + dense weights + KV have to fit the background budget. 500 MiB is below the
                // engine's floor, hence --force-cache (sessionArgv adds it).
                if (s.cacheMb == AppSettings.CACHE_AUTO || s.cacheMb > SMALL_FOOTPRINT_CACHE_MB) {
                    s = s.copy(cacheMb = SMALL_FOOTPRINT_CACHE_MB)
                }
                if (s.denseWeights == DenseWeights.AHWB) s = s.copy(denseWeights = DenseWeights.ANON)
            }
            return s
        }

        private fun autoCtx(chat: ChatSettings, modelBytes: Long, mmap: Boolean): Int = when {
            chat.sessionCtx != ChatSettings.CTX_AUTO -> chat.sessionCtx
            modelBytes >= BIG_MODEL_BYTES && !mmap -> SMALL_FOOTPRINT_CTX
            else -> AppSettings.SESSION_CTX
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
