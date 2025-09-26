package dev.synople.glassassistant.security

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.collections.HashMap

/**
 * Security audit logging system for tracking security events and potential threats.
 * Provides structured logging with automatic rotation and threat detection.
 */
class SecurityAuditLogger private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SecurityAuditLogger"
        private const val LOG_FILE_NAME = "security_audit.log"
        private const val MAX_LOG_FILE_SIZE = 10 * 1024 * 1024 // 10MB
        private const val MAX_LOG_FILES = 5
        private const val BATCH_SIZE = 50
        private const val FLUSH_INTERVAL_MS = 30_000L // 30 seconds
        private const val MAX_QUEUE_SIZE = 1000

        @Volatile
        private var INSTANCE: SecurityAuditLogger? = null

        fun getInstance(context: Context): SecurityAuditLogger {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurityAuditLogger(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val logQueue = ConcurrentLinkedQueue<AuditLogEntry>()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val loggerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isShutdown = false

    data class AuditLogEntry(
        val timestamp: String,
        val level: SecurityLevel,
        val event: SecurityEvent,
        val details: Map<String, Any>,
        val deviceInfo: Map<String, String>,
        val sessionId: String
    )

    enum class SecurityLevel(val priority: Int) {
        INFO(0),
        WARNING(1),
        CRITICAL(2),
        EMERGENCY(3)
    }

    enum class SecurityEvent {
        // Authentication Events
        API_KEY_VALIDATION_SUCCESS,
        API_KEY_VALIDATION_FAILURE,
        API_KEY_STORAGE_ACCESS,
        API_KEY_ROTATION,

        // Request Security Events
        REQUEST_SIGNING_SUCCESS,
        REQUEST_SIGNING_FAILURE,
        REQUEST_SIGNATURE_VALIDATION_SUCCESS,
        REQUEST_SIGNATURE_VALIDATION_FAILURE,
        CERTIFICATE_PINNING_SUCCESS,
        CERTIFICATE_PINNING_FAILURE,

        // File Security Events
        SECURE_FILE_CREATED,
        SECURE_FILE_DELETED,
        TEMP_FILE_CLEANUP,
        METADATA_STRIPPED,

        // Memory Security Events
        SENSITIVE_DATA_CLEARED,
        MEMORY_CLEANUP_PERFORMED,
        OUT_OF_MEMORY_DETECTED,

        // Network Security Events
        RATE_LIMIT_TRIGGERED,
        SUSPICIOUS_REQUEST_BLOCKED,
        TLS_HANDSHAKE_FAILURE,
        NETWORK_TIMEOUT,

        // Application Security Events
        APP_START,
        APP_TERMINATE,
        SECURITY_POLICY_VIOLATION,
        UNAUTHORIZED_ACCESS_ATTEMPT,

        // System Security Events
        DEVICE_INTEGRITY_CHECK,
        ROOT_DETECTION,
        DEBUG_MODE_DETECTED,
        MALWARE_SCAN_RESULT,

        // Error Events
        SECURITY_EXCEPTION,
        CRYPTOGRAPHIC_ERROR,
        FILE_SYSTEM_ERROR,
        CONFIGURATION_ERROR
    }

    private val sessionId = UUID.randomUUID().toString().take(8)

    init {
        startLoggingService()
        logSecurityEvent(SecurityLevel.INFO, SecurityEvent.APP_START, mapOf(
            "version" to getAppVersion(),
            "session_id" to sessionId
        ))
    }

    /**
     * Logs a security event
     */
    fun logSecurityEvent(
        level: SecurityLevel,
        event: SecurityEvent,
        details: Map<String, Any> = emptyMap(),
        exception: Throwable? = null
    ) {
        if (isShutdown) return

        try {
            val enhancedDetails = details.toMutableMap()

            // Add exception details if present
            exception?.let { ex ->
                enhancedDetails["exception_class"] = ex::class.java.simpleName
                enhancedDetails["exception_message"] = ex.message ?: "No message"
                enhancedDetails["stack_trace"] = ex.stackTraceToString().take(1000) // Limit size
            }

            val entry = AuditLogEntry(
                timestamp = timestampFormat.format(Date()),
                level = level,
                event = event,
                details = enhancedDetails,
                deviceInfo = getDeviceInfo(),
                sessionId = sessionId
            )

            // Add to queue
            if (logQueue.size < MAX_QUEUE_SIZE) {
                logQueue.offer(entry)
            } else {
                // Queue full - drop oldest entries
                logQueue.poll()
                logQueue.offer(entry)
                Log.w(TAG, "Log queue full, dropping old entries")
            }

            // Log to Android Log for immediate visibility
            val androidLevel = when (level) {
                SecurityLevel.INFO -> Log.INFO
                SecurityLevel.WARNING -> Log.WARN
                SecurityLevel.CRITICAL -> Log.ERROR
                SecurityLevel.EMERGENCY -> Log.ERROR
            }

            Log.println(androidLevel, TAG, "SECURITY_EVENT: ${event.name} - ${enhancedDetails}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to log security event", e)
        }
    }

    /**
     * Logs API request security information
     */
    fun logApiRequest(
        provider: String,
        success: Boolean,
        responseTime: Long,
        requestSize: Long = 0,
        responseSize: Long = 0,
        errorMessage: String? = null
    ) {
        val event = if (success) {
            SecurityEvent.REQUEST_SIGNING_SUCCESS
        } else {
            SecurityEvent.REQUEST_SIGNING_FAILURE
        }

        val level = if (success) SecurityLevel.INFO else SecurityLevel.WARNING

        logSecurityEvent(level, event, mapOf(
            "provider" to provider,
            "response_time_ms" to responseTime,
            "request_size_bytes" to requestSize,
            "response_size_bytes" to responseSize,
            "error_message" to (errorMessage ?: "")
        ))
    }

    /**
     * Logs file security operations
     */
    fun logFileOperation(
        operation: String,
        fileName: String,
        success: Boolean,
        fileSize: Long = 0,
        sensitiveData: Boolean = false
    ) {
        val event = when (operation.lowercase()) {
            "create" -> SecurityEvent.SECURE_FILE_CREATED
            "delete" -> SecurityEvent.SECURE_FILE_DELETED
            "cleanup" -> SecurityEvent.TEMP_FILE_CLEANUP
            else -> SecurityEvent.FILE_SYSTEM_ERROR
        }

        val level = if (success) SecurityLevel.INFO else SecurityLevel.WARNING

        logSecurityEvent(level, event, mapOf(
            "operation" to operation,
            "file_name" to sanitizeFileName(fileName),
            "file_size_bytes" to fileSize,
            "sensitive_data" to sensitiveData
        ))
    }

    /**
     * Logs memory security operations
     */
    fun logMemoryOperation(
        operation: String,
        dataType: String,
        dataSize: Int,
        success: Boolean
    ) {
        val event = when (operation.lowercase()) {
            "clear" -> SecurityEvent.SENSITIVE_DATA_CLEARED
            "cleanup" -> SecurityEvent.MEMORY_CLEANUP_PERFORMED
            else -> SecurityEvent.SECURITY_EXCEPTION
        }

        val level = if (success) SecurityLevel.INFO else SecurityLevel.WARNING

        logSecurityEvent(level, event, mapOf(
            "operation" to operation,
            "data_type" to dataType,
            "data_size" to dataSize
        ))
    }

    /**
     * Logs threat detection events
     */
    fun logThreatDetection(
        threatType: String,
        severity: SecurityLevel,
        details: Map<String, Any>
    ) {
        logSecurityEvent(severity, SecurityEvent.SUSPICIOUS_REQUEST_BLOCKED, details.plus(
            mapOf("threat_type" to threatType)
        ))
    }

    /**
     * Gets security statistics
     */
    fun getSecurityStats(): Map<String, Any> {
        return try {
            val logDir = getLogDirectory()
            val logFiles = logDir.listFiles { file -> file.name.contains("security_audit") }

            mapOf(
                "total_log_files" to (logFiles?.size ?: 0),
                "queue_size" to logQueue.size,
                "session_id" to sessionId,
                "app_version" to getAppVersion(),
                "log_directory_size" to (logFiles?.sumOf { it.length() } ?: 0L)
            )
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "Unknown error"))
        }
    }

    /**
     * Exports security logs for analysis
     */
    suspend fun exportSecurityLogs(): File? {
        return withContext(Dispatchers.IO) {
            try {
                flushLogs() // Ensure all logs are written

                val exportFile = File(getLogDirectory(), "security_audit_export_${System.currentTimeMillis()}.json")
                val logArray = JSONArray()

                // Read all log files
                val logDir = getLogDirectory()
                val logFiles = logDir.listFiles { file ->
                    file.name.contains("security_audit") && file.extension == "log"
                }?.sortedBy { it.lastModified() }

                logFiles?.forEach { logFile ->
                    logFile.readLines().forEach { line ->
                        try {
                            val logEntry = JSONObject(line)
                            logArray.put(logEntry)
                        } catch (e: Exception) {
                            // Skip malformed log lines
                        }
                    }
                }

                // Add metadata
                val exportData = JSONObject().apply {
                    put("export_timestamp", timestampFormat.format(Date()))
                    put("session_id", sessionId)
                    put("device_info", JSONObject(getDeviceInfo()))
                    put("logs", logArray)
                    put("stats", JSONObject(getSecurityStats()))
                }

                exportFile.writeText(exportData.toString(2))
                Log.i(TAG, "Security logs exported to: ${exportFile.name}")
                exportFile

            } catch (e: Exception) {
                Log.e(TAG, "Failed to export security logs", e)
                null
            }
        }
    }

    /**
     * Starts the background logging service
     */
    private fun startLoggingService() {
        loggerScope.launch {
            while (!isShutdown) {
                try {
                    flushLogs()
                    delay(FLUSH_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in logging service", e)
                    delay(FLUSH_INTERVAL_MS * 2) // Back off on error
                }
            }
        }
    }

    /**
     * Flushes pending logs to file
     */
    private suspend fun flushLogs() {
        if (logQueue.isEmpty()) return

        withContext<Unit>(Dispatchers.IO) {
            try {
                val logFile = getCurrentLogFile()
                val entries = mutableListOf<AuditLogEntry>()

                // Drain queue up to batch size
                repeat(minOf(BATCH_SIZE, logQueue.size)) {
                    logQueue.poll()?.let { entries.add(it) }
                }

                if (entries.isNotEmpty()) {
                    logFile.appendText(entries.joinToString("\n") { entry ->
                        JSONObject().apply {
                            put("timestamp", entry.timestamp)
                            put("level", entry.level.name)
                            put("event", entry.event.name)
                            put("details", JSONObject(entry.details))
                            put("device_info", JSONObject(entry.deviceInfo))
                            put("session_id", entry.sessionId)
                        }.toString()
                    } + "\n")

                    Log.d(TAG, "Flushed ${entries.size} security log entries")
                }

                // Check if log rotation is needed
                if (logFile.length() > MAX_LOG_FILE_SIZE) {
                    rotateLogFiles()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush logs", e)
            }
        }
    }

    /**
     * Gets or creates the current log file
     */
    private fun getCurrentLogFile(): File {
        val logDir = getLogDirectory()
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        return File(logDir, LOG_FILE_NAME)
    }

    /**
     * Gets the log directory
     */
    private fun getLogDirectory(): File {
        return File(context.filesDir, "security_logs")
    }

    /**
     * Rotates log files when they get too large
     */
    private fun rotateLogFiles() {
        try {
            val logDir = getLogDirectory()
            val currentFile = File(logDir, LOG_FILE_NAME)

            if (currentFile.exists()) {
                // Rotate existing files
                for (i in MAX_LOG_FILES - 1 downTo 1) {
                    val oldFile = File(logDir, "$LOG_FILE_NAME.$i")
                    val newFile = File(logDir, "$LOG_FILE_NAME.${i + 1}")

                    if (oldFile.exists()) {
                        if (i == MAX_LOG_FILES - 1) {
                            oldFile.delete() // Delete oldest
                        } else {
                            oldFile.renameTo(newFile)
                        }
                    }
                }

                // Move current to .1
                currentFile.renameTo(File(logDir, "$LOG_FILE_NAME.1"))
            }

            Log.d(TAG, "Log files rotated")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log files", e)
        }
    }

    /**
     * Gets device information for audit context
     */
    private fun getDeviceInfo(): Map<String, String> {
        return mapOf(
            "android_version" to Build.VERSION.RELEASE,
            "api_level" to Build.VERSION.SDK_INT.toString(),
            "device_model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "app_version" to getAppVersion(),
            "build_type" to try {
                val buildConfigClass = Class.forName("${context.packageName}.BuildConfig")
                val debugField = buildConfigClass.getField("DEBUG")
                if (debugField.getBoolean(null)) "debug" else "release"
            } catch (e: Exception) { "unknown" }
        )
    }

    /**
     * Gets app version information
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.versionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Sanitizes file names for logging
     */
    private fun sanitizeFileName(fileName: String): String {
        return fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(50)
    }

    /**
     * Shuts down the logger
     */
    fun shutdown() {
        isShutdown = true

        // Final flush
        runBlocking {
            flushLogs()
        }

        loggerScope.cancel()

        logSecurityEvent(SecurityLevel.INFO, SecurityEvent.APP_TERMINATE, mapOf(
            "shutdown_timestamp" to timestampFormat.format(Date())
        ))

        Log.i(TAG, "SecurityAuditLogger shutdown completed")
    }
}