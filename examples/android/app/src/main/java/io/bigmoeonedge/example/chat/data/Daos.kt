package io.bigmoeonedge.example.chat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert
    suspend fun insert(c: ConversationEntity): Long

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("UPDATE conversations SET lastReadAt = :at WHERE id = :id")
    suspend fun markRead(id: Long, at: Long)

    @Query("UPDATE conversations SET modelPath = :modelPath WHERE id = :id")
    suspend fun setModel(id: Long, modelPath: String)

    @Query("UPDATE conversations SET thinking = :thinking, thinkLevel = :level WHERE id = :id")
    suspend fun setThinking(id: Long, thinking: Boolean, level: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    /** Title or any message text contains [like] (already wrapped in %...%). */
    @Query(
        "SELECT * FROM conversations WHERE title LIKE :like ESCAPE '\\' OR id IN " +
            "(SELECT conversationId FROM messages WHERE text LIKE :like ESCAPE '\\') " +
            "ORDER BY updatedAt DESC, id DESC",
    )
    fun search(like: String): Flow<List<ConversationEntity>>

    @Query("SELECT modelPath FROM conversations ORDER BY updatedAt DESC, id DESC LIMIT 1")
    suspend fun lastUsedModel(): String?
}

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(m: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt, id")
    fun observeFor(cid: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :cid ORDER BY createdAt, id")
    suspend fun listFor(cid: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE id = :id")
    fun observe(id: Long): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE role = 'assistant' AND status = 'QUEUED' ORDER BY queuedAt, id")
    suspend fun queued(): List<MessageEntity>

    /** Queued replies, oldest first, for the "N ahead" label. */
    @Query("SELECT id FROM messages WHERE role = 'assistant' AND status = 'QUEUED' ORDER BY queuedAt, id")
    fun observeQueuedIds(): Flow<List<Long>>

    @Query("SELECT * FROM messages WHERE role = 'assistant' AND status IN ('PREFILLING','GENERATING') LIMIT 1")
    fun observeActive(): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE role = 'assistant' AND status IN ('PREFILLING','GENERATING')")
    suspend fun active(): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE role = 'assistant' AND status = 'QUEUED' AND conversationId = :cid")
    suspend fun queuedCount(cid: Long): Int

    @Query(
        "SELECT * FROM messages WHERE conversationId = :cid AND role = 'assistant' AND status = 'QUEUED' " +
            "ORDER BY id LIMIT 1",
    )
    suspend fun queuedFor(cid: Long): MessageEntity?

    @Query("UPDATE messages SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String? = null)

    @Query("UPDATE messages SET createdAt = :at WHERE id = :id")
    suspend fun setCreatedAt(id: Long, at: Long)

    @Query("UPDATE messages SET text = :text WHERE id = :id")
    suspend fun setText(id: Long, text: String)

    /** Streaming write: the in-flight text and reasoning, at most every 500 ms. */
    @Query(
        "UPDATE messages SET text = :text, reasoning = :reasoning, status = :status, tokens = :tokens, " +
            "tokPerSec = :tokPerSec WHERE id = :id",
    )
    suspend fun updateProgress(
        id: Long, text: String, reasoning: String, status: String, tokens: Int, tokPerSec: Double,
    ): Int

    @Query(
        "UPDATE messages SET text = :text, reasoning = :reasoning, status = :status, error = :error, " +
            "tokens = :tokens, tokPerSec = :tokPerSec, prefillS = :prefillS, metrics = :metrics, " +
            "finishedAt = :finishedAt WHERE id = :id",
    )
    suspend fun finish(
        id: Long, text: String, reasoning: String, status: String, error: String?, tokens: Int,
        tokPerSec: Double, prefillS: Double, metrics: String, finishedAt: Long,
    ): Int

    @Query("UPDATE messages SET thinkingCut = :cut, thinkingTokens = :tokens WHERE id = :id")
    suspend fun setThinkingInfo(id: Long, cut: Boolean, tokens: Int)

    @Query(
        "UPDATE messages SET status = 'QUEUED', error = NULL, text = '', reasoning = '', attempt = :attempt, " +
            "queuedAt = :queuedAt WHERE id = :id",
    )
    suspend fun requeue(id: Long, attempt: Int, queuedAt: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM messages WHERE conversationId = :cid AND (createdAt > :at OR (createdAt = :at AND id > :id))")
    suspend fun deleteAfter(cid: Long, at: Long, id: Long)

    @Query("UPDATE messages SET outOfContext = 0 WHERE conversationId = :cid AND outOfContext = 1")
    suspend fun clearOutOfContext(cid: Long)

    @Query("UPDATE messages SET outOfContext = 1 WHERE id IN (:ids)")
    suspend fun setOutOfContext(ids: List<Long>)

    @Transaction
    suspend fun replaceOutOfContext(cid: Long, ids: List<Long>) {
        clearOutOfContext(cid)
        if (ids.isNotEmpty()) setOutOfContext(ids)
    }

    @Query("SELECT MAX(createdAt) FROM messages WHERE conversationId = :cid")
    suspend fun latestCreatedAt(cid: Long): Long?

    /** The last message of each conversation, for the list preview. */
    @Query(
        "SELECT * FROM messages WHERE id IN (SELECT MAX(id) FROM messages WHERE status != 'QUEUED' OR role = 'user' " +
            "GROUP BY conversationId)",
    )
    fun observeLastMessages(): Flow<List<MessageEntity>>

    /** Newest finished reply per conversation, to compare with lastReadAt. */
    @Query(
        "SELECT conversationId, MAX(finishedAt) AS finishedAt FROM messages " +
            "WHERE role = 'assistant' AND status = 'DONE' GROUP BY conversationId",
    )
    fun observeLastReplyTimes(): Flow<List<ReplyTime>>

    /** Conversations that have a reply queued or running, for the list chips. */
    @Query(
        "SELECT conversationId, status FROM messages WHERE role = 'assistant' AND " +
            "status IN ('QUEUED','PREFILLING','GENERATING','FAILED','INTERRUPTED') " +
            "AND id IN (SELECT MAX(id) FROM messages WHERE role = 'assistant' GROUP BY conversationId)",
    )
    fun observeLatestReplyStatus(): Flow<List<ReplyStatus>>
}

data class ReplyTime(val conversationId: Long, val finishedAt: Long?)
data class ReplyStatus(val conversationId: Long, val status: String)

@Dao
interface EngineStatusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(s: EngineStatusEntity)

    @Query("SELECT * FROM engine_status WHERE id = 0")
    fun observe(): Flow<EngineStatusEntity?>

    @Query("SELECT * FROM engine_status WHERE id = 0")
    suspend fun get(): EngineStatusEntity?
}

@Dao
interface PresenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(p: UiPresenceEntity)

    @Query("SELECT * FROM ui_presence WHERE id = 0")
    suspend fun get(): UiPresenceEntity?
}

@Dao
interface ScanDao {
    @Insert
    suspend fun insertRun(r: ScanRunEntity): Long

    @androidx.room.Update
    suspend fun updateRun(r: ScanRunEntity)

    @Query("SELECT * FROM scan_runs WHERE id = :id")
    suspend fun run(id: Long): ScanRunEntity?

    @Query("SELECT * FROM scan_runs WHERE id = :id")
    fun observeRun(id: Long): Flow<ScanRunEntity?>

    @Query("SELECT * FROM scan_runs ORDER BY startedAt DESC")
    fun observeRuns(): Flow<List<ScanRunEntity>>

    @Query("SELECT * FROM scan_runs WHERE status = 'RUNNING' ORDER BY startedAt LIMIT 1")
    suspend fun runningRun(): ScanRunEntity?

    @Query("SELECT * FROM scan_runs WHERE status = 'RUNNING' ORDER BY startedAt LIMIT 1")
    fun observeRunning(): Flow<ScanRunEntity?>

    @Query("SELECT * FROM scan_runs WHERE modelPath = :path ORDER BY startedAt DESC LIMIT 1")
    suspend fun latestFor(path: String): ScanRunEntity?

    @Query("DELETE FROM scan_runs WHERE id = :id")
    suspend fun deleteRun(id: Long)

    @Insert
    suspend fun insertCell(c: ScanCellEntity): Long

    @androidx.room.Update
    suspend fun updateCell(c: ScanCellEntity)

    @Query("SELECT * FROM scan_cells WHERE runId = :runId ORDER BY id")
    suspend fun cells(runId: Long): List<ScanCellEntity>

    @Query("SELECT * FROM scan_cells WHERE runId = :runId ORDER BY id")
    fun observeCells(runId: Long): Flow<List<ScanCellEntity>>

    @Query("SELECT * FROM scan_cells WHERE id = :id")
    suspend fun cell(id: Long): ScanCellEntity?

    // Cells a dead engine process left mid-run are run again.
    @Query("UPDATE scan_cells SET status = 'PENDING' WHERE runId = :runId AND status = 'RUNNING'")
    suspend fun resetRunningCells(runId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putProfile(p: ModelProfileEntity)

    @Query("SELECT * FROM model_profiles WHERE modelPath = :path")
    suspend fun profile(path: String): ModelProfileEntity?

    @Query("SELECT * FROM model_profiles WHERE modelPath = :path")
    fun observeProfile(path: String): Flow<ModelProfileEntity?>

    @Query("DELETE FROM model_profiles WHERE modelPath = :path")
    suspend fun clearProfile(path: String)
}
