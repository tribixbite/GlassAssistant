package dev.synople.glassassistant.performance

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.synople.glassassistant.R
import dev.synople.glassassistant.security.SecurityAuditLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Background service for handling long-running operations efficiently
 * with proper resource management and Glass hardware optimization.
 */
class BackgroundProcessingService : Service() {

    companion object {
        private const val TAG = "BackgroundProcessingService"
        private const val NOTIFICATION_CHANNEL_ID = "glass_background_processing"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_CONCURRENT_OPERATIONS = 3
        private const val OPERATION_TIMEOUT_MS = 30000L // 30 seconds
        private const val CLEANUP_INTERVAL_MS = 60000L // 1 minute
    }

    // Service binding
    private val binder = BackgroundProcessingBinder()

    // Coroutine management
    private val serviceScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("BackgroundProcessingService")
    )

    // Operation management
    private val activeOperations = ConcurrentHashMap<String, ProcessingOperation>()
    private val operationIdCounter = AtomicInteger(0)
    private val totalOperationsProcessed = AtomicLong(0)

    // Resource monitoring
    private var cleanupJob: Job? = null
    private val securityLogger by lazy { SecurityAuditLogger.getInstance(this) }

    // Performance metrics
    private var serviceStartTime = 0L
    private val operationMetrics = mutableMapOf<String, OperationMetrics>()

    data class ProcessingOperation(
        val id: String,
        val type: OperationType,
        val startTime: Long,
        val job: Job,
        val callback: ProcessingCallback?
    )

    data class OperationMetrics(
        val type: String,
        val count: Int,
        val totalTimeMs: Long,
        val averageTimeMs: Long,
        val successCount: Int,
        val errorCount: Int
    )

    enum class OperationType {
        AI_REQUEST_PROCESSING,
        IMAGE_PROCESSING,
        AUDIO_PROCESSING,
        FILE_CLEANUP,
        SECURITY_SCAN,
        CACHE_MAINTENANCE,
        DATA_SYNC
    }

    interface ProcessingCallback {
        suspend fun onProgress(operationId: String, progress: Int)
        suspend fun onComplete(operationId: String, result: Any?)
        suspend fun onError(operationId: String, error: Throwable)
    }

    inner class BackgroundProcessingBinder : Binder() {
        fun getService(): BackgroundProcessingService = this@BackgroundProcessingService
    }

    override fun onCreate() {
        super.onCreate()
        serviceStartTime = System.currentTimeMillis()

        Log.d(TAG, "BackgroundProcessingService created")

        // Create notification channel for foreground service
        createNotificationChannel()

        // Start periodic cleanup
        startPeriodicCleanup()

        // Log service start
        securityLogger.logSecurityEvent(
            SecurityAuditLogger.SecurityLevel.INFO,
            SecurityAuditLogger.SecurityEvent.APP_START,
            mapOf("service" to "BackgroundProcessingService")
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "BackgroundProcessingService started")

        // Start as foreground service with Glass-appropriate notification
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY // Restart if killed by system
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        Log.d(TAG, "BackgroundProcessingService destroying")

        try {
            // Cancel all active operations
            activeOperations.values.forEach { operation ->
                operation.job.cancel()
            }
            activeOperations.clear()

            // Cancel cleanup job
            cleanupJob?.cancel()

            // Cancel service scope
            serviceScope.cancel()

            // Log service stop
            securityLogger.logSecurityEvent(
                SecurityAuditLogger.SecurityLevel.INFO,
                SecurityAuditLogger.SecurityEvent.APP_TERMINATE,
                mapOf("service" to "BackgroundProcessingService")
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error during service destruction", e)
        }

        super.onDestroy()
    }

    /**
     * Submits a processing operation to run in the background
     */
    fun submitOperation(
        type: OperationType,
        operation: suspend () -> Any?,
        callback: ProcessingCallback? = null
    ): String? {

        // Check operation limit
        if (activeOperations.size >= MAX_CONCURRENT_OPERATIONS) {
            Log.w(TAG, "Maximum concurrent operations reached")
            return null
        }

        val operationId = generateOperationId()
        val startTime = System.currentTimeMillis()

        val job = serviceScope.launch {
            try {
                Log.d(TAG, "Starting operation $operationId of type ${type.name}")

                // Execute the operation with timeout
                val result = withTimeout(OPERATION_TIMEOUT_MS) {
                    operation()
                }

                // Record metrics
                recordOperationSuccess(type, System.currentTimeMillis() - startTime)

                // Notify callback
                callback?.onComplete(operationId, result)

                Log.d(TAG, "Operation $operationId completed successfully")

            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Operation $operationId timed out")
                recordOperationError(type, System.currentTimeMillis() - startTime)
                callback?.onError(operationId, e)

            } catch (e: Exception) {
                Log.e(TAG, "Operation $operationId failed", e)
                recordOperationError(type, System.currentTimeMillis() - startTime)
                callback?.onError(operationId, e)

            } finally {
                // Remove from active operations
                activeOperations.remove(operationId)
                totalOperationsProcessed.incrementAndGet()

                // Update notification
                updateNotification()
            }
        }

        // Track active operation
        val processingOperation = ProcessingOperation(
            id = operationId,
            type = type,
            startTime = startTime,
            job = job,
            callback = callback
        )

        activeOperations[operationId] = processingOperation

        Log.d(TAG, "Submitted operation $operationId")
        return operationId
    }

    /**
     * Cancels a specific operation
     */
    fun cancelOperation(operationId: String): Boolean {
        val operation = activeOperations.remove(operationId)
        return if (operation != null) {
            operation.job.cancel()
            Log.d(TAG, "Cancelled operation $operationId")
            true
        } else {
            Log.w(TAG, "Operation $operationId not found for cancellation")
            false
        }
    }

    /**
     * Gets current service statistics
     */
    fun getServiceStats(): Map<String, Any> {
        val currentTime = System.currentTimeMillis()
        val uptimeMs = currentTime - serviceStartTime

        return mapOf(
            "uptime_ms" to uptimeMs,
            "active_operations" to activeOperations.size,
            "total_operations_processed" to totalOperationsProcessed.get(),
            "max_concurrent_operations" to MAX_CONCURRENT_OPERATIONS,
            "operation_timeout_ms" to OPERATION_TIMEOUT_MS,
            "operation_metrics" to operationMetrics.toMap()
        )
    }

    /**
     * Gets active operation information
     */
    fun getActiveOperations(): List<Map<String, Any>> {
        val currentTime = System.currentTimeMillis()
        return activeOperations.values.map { operation ->
            mapOf(
                "id" to operation.id,
                "type" to operation.type.name,
                "duration_ms" to (currentTime - operation.startTime),
                "has_callback" to (operation.callback != null)
            )
        }
    }

    /**
     * Forces cleanup of resources
     */
    fun forceCleanup() {
        serviceScope.launch {
            performCleanup()
        }
    }

    /**
     * Starts periodic cleanup task
     */
    private fun startPeriodicCleanup() {
        cleanupJob = serviceScope.launch {
            while (isActive) {
                try {
                    delay(CLEANUP_INTERVAL_MS)
                    performCleanup()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during periodic cleanup", e)
                }
            }
        }
    }

    /**
     * Performs resource cleanup
     */
    private suspend fun performCleanup() {
        try {
            val currentTime = System.currentTimeMillis()
            val timedOutOperations = mutableListOf<String>()

            // Find timed out operations
            activeOperations.forEach { (id, operation) ->
                if (currentTime - operation.startTime > OPERATION_TIMEOUT_MS * 2) {
                    timedOutOperations.add(id)
                }
            }

            // Cancel timed out operations
            timedOutOperations.forEach { operationId ->
                cancelOperation(operationId)
                Log.w(TAG, "Cancelled timed out operation: $operationId")
            }

            // Force garbage collection if too many operations
            if (totalOperationsProcessed.get() % 50L == 0L) {
                System.gc()
                Log.d(TAG, "Performed garbage collection")
            }

            // Log cleanup event
            if (timedOutOperations.isNotEmpty()) {
                securityLogger.logSecurityEvent(
                    SecurityAuditLogger.SecurityLevel.WARNING,
                    SecurityAuditLogger.SecurityEvent.SECURITY_EXCEPTION,
                    mapOf(
                        "cleanup_type" to "timeout_operations",
                        "operations_cancelled" to timedOutOperations.size
                    )
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    /**
     * Records successful operation metrics
     */
    private fun recordOperationSuccess(type: OperationType, durationMs: Long) {
        val typeName = type.name
        val current = operationMetrics[typeName] ?: OperationMetrics(
            type = typeName,
            count = 0,
            totalTimeMs = 0,
            averageTimeMs = 0,
            successCount = 0,
            errorCount = 0
        )

        val newCount = current.count + 1
        val newTotalTime = current.totalTimeMs + durationMs
        val newSuccessCount = current.successCount + 1

        operationMetrics[typeName] = current.copy(
            count = newCount,
            totalTimeMs = newTotalTime,
            averageTimeMs = newTotalTime / newCount,
            successCount = newSuccessCount
        )
    }

    /**
     * Records failed operation metrics
     */
    private fun recordOperationError(type: OperationType, durationMs: Long) {
        val typeName = type.name
        val current = operationMetrics[typeName] ?: OperationMetrics(
            type = typeName,
            count = 0,
            totalTimeMs = 0,
            averageTimeMs = 0,
            successCount = 0,
            errorCount = 0
        )

        val newCount = current.count + 1
        val newTotalTime = current.totalTimeMs + durationMs
        val newErrorCount = current.errorCount + 1

        operationMetrics[typeName] = current.copy(
            count = newCount,
            totalTimeMs = newTotalTime,
            averageTimeMs = newTotalTime / newCount,
            errorCount = newErrorCount
        )
    }

    /**
     * Creates notification channel for foreground service
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Glass Background Processing",
                NotificationManager.IMPORTANCE_LOW // Low importance for Glass
            ).apply {
                description = "Background processing for GlassAssistant"
                setShowBadge(false) // No badge for Glass
                enableLights(false)
                enableVibration(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Creates notification for foreground service
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("GlassAssistant Processing")
            .setContentText("Processing AI requests...")
            .setSmallIcon(R.drawable.ic_glass)
            .setColor(ContextCompat.getColor(this, R.color.glass_blue))
            .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority for Glass
            .setOngoing(true)
            .setLocalOnly(true) // Keep on Glass only
            .build()
    }

    /**
     * Updates notification with current status
     */
    private fun updateNotification() {
        val activeCount = activeOperations.size
        val totalProcessed = totalOperationsProcessed.get()

        val contentText = if (activeCount > 0) {
            "Processing $activeCount operations..."
        } else {
            "Ready ($totalProcessed processed)"
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("GlassAssistant Processing")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_glass)
            .setColor(ContextCompat.getColor(this, R.color.glass_blue))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setLocalOnly(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Generates unique operation ID
     */
    private fun generateOperationId(): String {
        return "op_${operationIdCounter.incrementAndGet()}_${System.currentTimeMillis()}"
    }
}