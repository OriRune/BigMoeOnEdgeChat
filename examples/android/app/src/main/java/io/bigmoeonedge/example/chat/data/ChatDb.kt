package io.bigmoeonedge.example.chat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        EngineStatusEntity::class,
        UiPresenceEntity::class,
        ScanRunEntity::class,
        ScanCellEntity::class,
        ModelProfileEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class ChatDb : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun engineStatus(): EngineStatusDao
    abstract fun presence(): PresenceDao
    abstract fun scan(): ScanDao

    companion object {
        @Volatile private var instance: ChatDb? = null

        /**
         * One instance per process. The UI process and the :engine process both open the file, so
         * multi-instance invalidation is mandatory: it is what makes a write in one process wake the
         * Flow collectors of the other.
         */
        fun get(ctx: Context): ChatDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, ChatDb::class.java, "chat.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .enableMultiInstanceInvalidation()
                .build()
                .also { instance = it }
        }

        /** In-memory database for tests. */
        fun inMemory(ctx: Context): ChatDb =
            Room.inMemoryDatabaseBuilder(ctx, ChatDb::class.java).allowMainThreadQueries().build()
    }
}
