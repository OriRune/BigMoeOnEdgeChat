package io.bigmoeonedge.example.chat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        EngineStatusEntity::class,
        UiPresenceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ChatDb : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun engineStatus(): EngineStatusDao
    abstract fun presence(): PresenceDao

    companion object {
        @Volatile private var instance: ChatDb? = null

        /**
         * One instance per process. The UI process and the :engine process both open the file, so
         * multi-instance invalidation is mandatory: it is what makes a write in one process wake the
         * Flow collectors of the other.
         */
        fun get(ctx: Context): ChatDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, ChatDb::class.java, "chat.db")
                .enableMultiInstanceInvalidation()
                .build()
                .also { instance = it }
        }

        /** In-memory database for tests. */
        fun inMemory(ctx: Context): ChatDb =
            Room.inMemoryDatabaseBuilder(ctx, ChatDb::class.java).allowMainThreadQueries().build()
    }
}
