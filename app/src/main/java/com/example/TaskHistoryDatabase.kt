package com.example

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val templatePath: String? = null,
    val templateName: String? = null,
    val sourcePath: String? = null,
    val sourceName: String? = null,
    val latestOutputDocxPath: String? = null
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String, // "user", "assistant", "tool", "error"
    val content: String,
    val timestamp: Long,
    val thoughtText: String? = null,
    val thoughtDurationMs: Long = 0L,
    val toolName: String? = null,
    val toolArgsJson: String? = null,
    val toolOutputText: String? = null,
    val outputDocxPath: String? = null,
    val attachmentsJson: String = "[]",
    val tokensPerSec: Float = 0f
)

@Entity(tableName = "model_config")
data class ModelConfigEntity(
    @PrimaryKey val id: Int = 1,
    val executionProvider: String = ExecutionProviderMode.AUTO_NPU_GPU.name,
    val includeVisionEncoder: Boolean = false,
    val maxNewTokens: Int = 512,
    val autoSyncTelemetryGitHub: Boolean = true
)

@Entity(tableName = "telemetry_runs")
data class TelemetryRunEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val timestamp: Long,
    val socBadge: String,
    val executionProvider: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val batchTokenizeMs: Float,
    val gpuPrefetchMs: Float,
    val prefillTokPerSec: Float,
    val decodeTokPerSec: Float,
    val avgNpuStepMs: Float,
    val peakNpuUtilPercent: Int,
    val thermalStatusLabel: String,
    val isThermalThrottling: Boolean,
    val nativeHeapMb: Int,
    val availRamMb: Int,
    val inspectDurationMs: Long,
    val cloneDurationMs: Long,
    val compactedTurns: Int,
    val status: String
)

@Dao
interface CopyFlowHistoryDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun observeAllSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): ChatSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ChatSessionEntity)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAllSessions()

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAllMessages()

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSessionOnce(sessionId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM model_config WHERE id = 1 LIMIT 1")
    suspend fun getModelConfig(): ModelConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveModelConfig(config: ModelConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetryRun(run: TelemetryRunEntity)

    @Query("SELECT * FROM telemetry_runs ORDER BY timestamp DESC LIMIT 50")
    fun observeRecentTelemetryRuns(): Flow<List<TelemetryRunEntity>>

    @Query("SELECT * FROM telemetry_runs ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecentTelemetryRunsOnce(): List<TelemetryRunEntity>
}

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        ModelConfigEntity::class,
        TelemetryRunEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class TaskHistoryDatabase : RoomDatabase() {
    abstract fun dao(): CopyFlowHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: TaskHistoryDatabase? = null

        fun getInstance(context: Context): TaskHistoryDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TaskHistoryDatabase::class.java,
                    "copyflow_qwen_sessions.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
