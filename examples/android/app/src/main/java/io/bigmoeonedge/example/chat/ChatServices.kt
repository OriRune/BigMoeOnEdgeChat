package io.bigmoeonedge.example.chat

import android.content.Context
import io.bigmoeonedge.example.chat.data.ChatDb
import io.bigmoeonedge.example.chat.data.ChatRepository
import io.bigmoeonedge.example.chat.engine.EngineClient

/** Process-wide handles to the chat store and the engine process. */
object ChatServices {
    @Volatile private var repo: ChatRepository? = null

    fun db(ctx: Context): ChatDb = ChatDb.get(ctx)

    fun repository(ctx: Context): ChatRepository = repo ?: synchronized(this) {
        repo ?: ChatRepository(ChatDb.get(ctx), EngineClient(ctx.applicationContext)).also { repo = it }
    }

    @Volatile private var scanRepo: io.bigmoeonedge.example.scan.ScanRepository? = null

    fun scan(ctx: Context): io.bigmoeonedge.example.scan.ScanRepository = scanRepo ?: synchronized(this) {
        scanRepo ?: io.bigmoeonedge.example.scan.ScanRepository(ChatDb.get(ctx), kick = { client(ctx).scanStart() }).also { scanRepo = it }
    }

    fun client(ctx: Context): EngineClient = EngineClient(ctx.applicationContext)
}
