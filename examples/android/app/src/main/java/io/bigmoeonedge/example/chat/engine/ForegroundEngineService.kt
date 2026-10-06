package io.bigmoeonedge.example.chat.engine

/**
 * The engine for models set to foreground mode, hosted in the main process. Only a process that is
 * `top-app` gets the larger memory budget (about 6 GiB against the :engine process's 3.1 GiB, measured), so
 * this service works while the app is in front, freezes the reply when it is left and thaws it on return.
 * Everything else is [EngineService].
 */
class ForegroundEngineService : EngineService() {
    override val foregroundMode: Boolean = true
}
