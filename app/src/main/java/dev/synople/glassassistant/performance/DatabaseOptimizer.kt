package dev.synople.glassassistant.performance

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.*

/**
 * Database optimization and indexing for Glass Assistant.
 * Provides efficient storage and retrieval for conversation history and cache data.
 */
class DatabaseOptimizer private constructor(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {

    companion object {
        private const val TAG = "DatabaseOptimizer"
        private const val DATABASE_NAME = "glass_assistant.db"
        private const val DATABASE_VERSION = 1

        // Table definitions
        private const val CONVERSATION_HISTORY_TABLE = "conversation_history"
        private const val RESPONSE_CACHE_TABLE = "response_cache"
        private const val USAGE_ANALYTICS_TABLE = "usage_analytics"

        @Volatile
        private var INSTANCE: DatabaseOptimizer? = null

        fun getInstance(context: Context): DatabaseOptimizer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseOptimizer(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        createTables(db)
        createIndexes(db)
        Log.d(TAG, "Database created with optimized indexes")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Handle database upgrades
        db.execSQL("DROP TABLE IF EXISTS $CONVERSATION_HISTORY_TABLE")
        db.execSQL("DROP TABLE IF EXISTS $RESPONSE_CACHE_TABLE")
        db.execSQL("DROP TABLE IF EXISTS $USAGE_ANALYTICS_TABLE")
        onCreate(db)
    }

    /**
     * Creates database tables
     */
    private fun createTables(db: SQLiteDatabase) {
        // Conversation history table
        db.execSQL("""
            CREATE TABLE $CONVERSATION_HISTORY_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                provider TEXT NOT NULL,
                model TEXT,
                prompt TEXT NOT NULL,
                response TEXT NOT NULL,
                image_hash TEXT,
                audio_duration_ms INTEGER,
                response_time_ms INTEGER NOT NULL,
                token_count INTEGER,
                cost_estimate REAL,
                user_rating INTEGER,
                created_at INTEGER DEFAULT (strftime('%s', 'now'))
            )
        """.trimIndent())

        // Response cache table (for persistence)
        db.execSQL("""
            CREATE TABLE $RESPONSE_CACHE_TABLE (
                cache_key TEXT PRIMARY KEY,
                provider TEXT NOT NULL,
                model TEXT,
                prompt_hash TEXT NOT NULL,
                image_hash TEXT,
                response_data TEXT NOT NULL,
                compressed INTEGER DEFAULT 0,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                access_count INTEGER DEFAULT 1,
                last_accessed INTEGER NOT NULL
            )
        """.trimIndent())

        // Usage analytics table
        db.execSQL("""
            CREATE TABLE $USAGE_ANALYTICS_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                provider TEXT,
                timestamp INTEGER NOT NULL,
                duration_ms INTEGER,
                success INTEGER NOT NULL,
                error_message TEXT,
                battery_level INTEGER,
                memory_usage_mb REAL,
                network_type TEXT,
                metadata TEXT
            )
        """.trimIndent())
    }

    /**
     * Creates optimized indexes for fast queries
     */
    private fun createIndexes(db: SQLiteDatabase) {
        // Conversation history indexes
        db.execSQL("CREATE INDEX idx_conversation_timestamp ON $CONVERSATION_HISTORY_TABLE(timestamp DESC)")
        db.execSQL("CREATE INDEX idx_conversation_provider ON $CONVERSATION_HISTORY_TABLE(provider)")
        db.execSQL("CREATE INDEX idx_conversation_created_at ON $CONVERSATION_HISTORY_TABLE(created_at DESC)")
        db.execSQL("CREATE INDEX idx_conversation_rating ON $CONVERSATION_HISTORY_TABLE(user_rating DESC)")

        // Composite index for provider + time queries
        db.execSQL("CREATE INDEX idx_conversation_provider_time ON $CONVERSATION_HISTORY_TABLE(provider, timestamp DESC)")

        // Response cache indexes
        db.execSQL("CREATE INDEX idx_cache_expires_at ON $RESPONSE_CACHE_TABLE(expires_at)")
        db.execSQL("CREATE INDEX idx_cache_provider ON $RESPONSE_CACHE_TABLE(provider)")
        db.execSQL("CREATE INDEX idx_cache_access_count ON $RESPONSE_CACHE_TABLE(access_count DESC)")
        db.execSQL("CREATE INDEX idx_cache_last_accessed ON $RESPONSE_CACHE_TABLE(last_accessed DESC)")

        // Usage analytics indexes
        db.execSQL("CREATE INDEX idx_analytics_event_type ON $USAGE_ANALYTICS_TABLE(event_type)")
        db.execSQL("CREATE INDEX idx_analytics_timestamp ON $USAGE_ANALYTICS_TABLE(timestamp DESC)")
        db.execSQL("CREATE INDEX idx_analytics_provider ON $USAGE_ANALYTICS_TABLE(provider)")
        db.execSQL("CREATE INDEX idx_analytics_success ON $USAGE_ANALYTICS_TABLE(success, timestamp DESC)")

        // Composite indexes for common query patterns
        db.execSQL("CREATE INDEX idx_analytics_provider_time ON $USAGE_ANALYTICS_TABLE(provider, timestamp DESC)")
        db.execSQL("CREATE INDEX idx_analytics_type_success ON $USAGE_ANALYTICS_TABLE(event_type, success)")

        Log.d(TAG, "Database indexes created successfully")
    }

    /**
     * Performs database optimization and maintenance
     */
    suspend fun optimizeDatabase() = withContext(Dispatchers.IO) {
        try {
            writableDatabase.use { db ->
                // Analyze query patterns to update statistics
                db.execSQL("ANALYZE")

                // Vacuum to reclaim space and optimize file structure
                db.execSQL("VACUUM")

                // Reindex to ensure optimal performance
                db.execSQL("REINDEX")

                Log.d(TAG, "Database optimization completed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error optimizing database", e)
        }
    }

    /**
     * Cleans up old records to maintain performance
     */
    suspend fun cleanupOldRecords(
        conversationRetentionDays: Int = 30,
        cacheCleanupThreshold: Int = 1000,
        analyticsRetentionDays: Int = 7
    ) = withContext(Dispatchers.IO) {
        try {
            writableDatabase.use { db ->
                val currentTime = System.currentTimeMillis() / 1000 // Unix timestamp

                // Clean conversation history older than retention period
                val conversationCutoff = currentTime - (conversationRetentionDays * 24 * 60 * 60)
                val conversationDeleted = db.delete(
                    CONVERSATION_HISTORY_TABLE,
                    "created_at < ?",
                    arrayOf(conversationCutoff.toString())
                )

                // Clean expired cache entries
                val cacheDeleted = db.delete(
                    RESPONSE_CACHE_TABLE,
                    "expires_at < ?",
                    arrayOf(currentTime.toString())
                )

                // Clean old analytics data
                val analyticsCutoff = currentTime - (analyticsRetentionDays * 24 * 60 * 60)
                val analyticsDeleted = db.delete(
                    USAGE_ANALYTICS_TABLE,
                    "timestamp < ?",
                    arrayOf(analyticsCutoff.toString())
                )

                Log.d(TAG, "Cleanup completed: $conversationDeleted conversations, $cacheDeleted cache entries, $analyticsDeleted analytics records")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old records", e)
        }
    }

    /**
     * Gets database statistics for monitoring
     */
    suspend fun getDatabaseStats(): Map<String, Any> = withContext(Dispatchers.IO) {
        try {
            readableDatabase.use { db ->
                val stats = mutableMapOf<String, Any>()

                // Get table row counts
                db.rawQuery("SELECT COUNT(*) FROM $CONVERSATION_HISTORY_TABLE", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        stats["conversation_count"] = cursor.getInt(0)
                    }
                }

                db.rawQuery("SELECT COUNT(*) FROM $RESPONSE_CACHE_TABLE", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        stats["cache_entries"] = cursor.getInt(0)
                    }
                }

                db.rawQuery("SELECT COUNT(*) FROM $USAGE_ANALYTICS_TABLE", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        stats["analytics_entries"] = cursor.getInt(0)
                    }
                }

                // Get database file size
                val dbFile = db.path?.let { java.io.File(it) }
                stats["database_size_bytes"] = dbFile?.length() ?: 0L

                // Get cache hit ratio from recent data
                db.rawQuery("""
                    SELECT
                        AVG(access_count) as avg_access_count,
                        MAX(access_count) as max_access_count
                    FROM $RESPONSE_CACHE_TABLE
                    WHERE last_accessed > ?
                """.trimIndent(), arrayOf((System.currentTimeMillis() / 1000 - 3600).toString())).use { cursor ->
                    if (cursor.moveToFirst()) {
                        stats["avg_cache_hits"] = cursor.getDouble(0)
                        stats["max_cache_hits"] = cursor.getInt(1)
                    }
                }

                stats
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting database stats", e)
            mapOf("error" to (e.message ?: "Unknown error"))
        }
    }
}