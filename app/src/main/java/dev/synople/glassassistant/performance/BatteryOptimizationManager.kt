package dev.synople.glassassistant.performance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.synople.glassassistant.security.SecurityAuditLogger
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Battery optimization manager designed specifically for Glass hardware constraints.
 * Implements intelligent power management and adaptive performance scaling.
 */
class BatteryOptimizationManager private constructor(
    private val context: Context
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "BatteryOptimizationManager"

        // Glass-specific battery thresholds
        private const val CRITICAL_BATTERY_LEVEL = 10 // 10%
        private const val LOW_BATTERY_LEVEL = 25      // 25%
        private const val GOOD_BATTERY_LEVEL = 50     // 50%

        // Power management intervals
        private const val MONITORING_INTERVAL_MS = 30000L // 30 seconds
        private const val BACKGROUND_CLEANUP_INTERVAL_MS = 300000L // 5 minutes
        private const val AGGRESSIVE_CLEANUP_INTERVAL_MS = 60000L // 1 minute when low battery

        // Performance scaling factors
        private const val CRITICAL_PERFORMANCE_SCALE = 0.5f
        private const val LOW_PERFORMANCE_SCALE = 0.7f
        private const val NORMAL_PERFORMANCE_SCALE = 1.0f

        @Volatile
        private var INSTANCE: BatteryOptimizationManager? = null

        fun getInstance(context: Context): BatteryOptimizationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BatteryOptimizationManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    // Power management
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    // Monitoring scope
    private val monitoringScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isMonitoring = AtomicBoolean(false)

    // Battery state
    private var currentBatteryLevel = 100
    private var isCharging = false
    private var batteryTemperature = 0
    private var powerSaveMode = false

    // Performance scaling
    private var currentPerformanceScale = NORMAL_PERFORMANCE_SCALE
    private val performanceScaleListeners = mutableSetOf<PerformanceScaleListener>()

    // Optimization counters
    private val optimizationActions = AtomicInteger(0)
    private val powerSavingsEstimateMs = AtomicInteger(0)

    // Security logging
    private val securityLogger by lazy { SecurityAuditLogger.getInstance(context) }

    enum class BatteryState {
        CRITICAL,
        LOW,
        GOOD,
        CHARGING
    }

    enum class OptimizationLevel {
        AGGRESSIVE,  // Critical battery
        MODERATE,    // Low battery
        MINIMAL,     // Good battery
        DISABLED     // Charging
    }

    interface PerformanceScaleListener {
        fun onPerformanceScaleChanged(scale: Float, batteryState: BatteryState)
    }

    data class BatteryStats(
        val level: Int,
        val isCharging: Boolean,
        val temperature: Int,
        val voltage: Int,
        val powerSaveMode: Boolean,
        val batteryState: BatteryState,
        val optimizationLevel: OptimizationLevel,
        val performanceScale: Float,
        val optimizationActions: Int,
        val estimatedPowerSavingsMs: Int
    )

    // Battery change receiver
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    updateBatteryState(intent)
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    handlePowerConnected()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    handlePowerDisconnected()
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    handlePowerSaveModeChanged()
                }
            }
        }
    }

    init {
        // Register battery receivers
        registerBatteryReceivers()

        // Initial battery state update
        updateInitialBatteryState()

        Log.d(TAG, "BatteryOptimizationManager initialized")
    }

    override fun onResume(owner: LifecycleOwner) {
        startMonitoring()
    }

    override fun onPause(owner: LifecycleOwner) {
        // Continue monitoring in background but reduce frequency
        adjustMonitoringFrequency(background = true)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stopMonitoring()
    }

    /**
     * Starts battery monitoring and optimization
     */
    fun startMonitoring() {
        if (isMonitoring.compareAndSet(false, true)) {
            monitoringScope.launch {
                performBatteryMonitoring()
            }

            Log.d(TAG, "Battery monitoring started")

            securityLogger.logSecurityEvent(
                SecurityAuditLogger.SecurityLevel.INFO,
                SecurityAuditLogger.SecurityEvent.APP_START,
                mapOf("component" to "BatteryOptimizationManager")
            )
        }
    }

    /**
     * Stops battery monitoring
     */
    fun stopMonitoring() {
        isMonitoring.set(false)
        monitoringScope.cancel()

        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering battery receiver", e)
        }

        Log.d(TAG, "Battery monitoring stopped")
    }

    /**
     * Adds performance scale listener
     */
    fun addPerformanceScaleListener(listener: PerformanceScaleListener) {
        performanceScaleListeners.add(listener)
    }

    /**
     * Removes performance scale listener
     */
    fun removePerformanceScaleListener(listener: PerformanceScaleListener) {
        performanceScaleListeners.remove(listener)
    }

    /**
     * Gets current battery statistics
     */
    fun getBatteryStats(): BatteryStats {
        val voltage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } else {
            0
        }

        return BatteryStats(
            level = currentBatteryLevel,
            isCharging = isCharging,
            temperature = batteryTemperature,
            voltage = voltage,
            powerSaveMode = powerSaveMode,
            batteryState = getCurrentBatteryState(),
            optimizationLevel = getCurrentOptimizationLevel(),
            performanceScale = currentPerformanceScale,
            optimizationActions = optimizationActions.get(),
            estimatedPowerSavingsMs = powerSavingsEstimateMs.get()
        )
    }

    /**
     * Forces immediate optimization
     */
    fun forceOptimization() {
        monitoringScope.launch {
            performOptimization(force = true)
        }
    }

    /**
     * Gets current performance scale factor
     */
    fun getCurrentPerformanceScale(): Float = currentPerformanceScale

    /**
     * Checks if device should reduce operations
     */
    fun shouldReduceOperations(): Boolean {
        return getCurrentBatteryState() in listOf(BatteryState.CRITICAL, BatteryState.LOW)
    }

    /**
     * Registers battery-related broadcast receivers
     */
    private fun registerBatteryReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }

        context.registerReceiver(batteryReceiver, filter)
    }

    /**
     * Updates initial battery state
     */
    private fun updateInitialBatteryState() {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryIntent?.let { updateBatteryState(it) }

        // Check power save mode
        powerSaveMode = powerManager.isPowerSaveMode
    }

    /**
     * Updates battery state from intent
     */
    private fun updateBatteryState(intent: Intent) {
        currentBatteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        currentBatteryLevel = (currentBatteryLevel * 100) / scale

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

        batteryTemperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)

        // Trigger optimization check
        monitoringScope.launch {
            checkAndOptimize()
        }

        Log.d(TAG, "Battery updated: ${currentBatteryLevel}%, charging: $isCharging")
    }

    /**
     * Handles power connected event
     */
    private fun handlePowerConnected() {
        isCharging = true
        updatePerformanceScale(NORMAL_PERFORMANCE_SCALE, BatteryState.CHARGING)

        Log.d(TAG, "Power connected - optimization relaxed")
    }

    /**
     * Handles power disconnected event
     */
    private fun handlePowerDisconnected() {
        isCharging = false
        monitoringScope.launch {
            checkAndOptimize()
        }

        Log.d(TAG, "Power disconnected - optimization activated")
    }

    /**
     * Handles power save mode change
     */
    private fun handlePowerSaveModeChanged() {
        powerSaveMode = powerManager.isPowerSaveMode

        monitoringScope.launch {
            checkAndOptimize()
        }

        Log.d(TAG, "Power save mode changed: $powerSaveMode")
    }

    /**
     * Main battery monitoring loop
     */
    private suspend fun performBatteryMonitoring() {
        while (isMonitoring.get()) {
            try {
                checkAndOptimize()

                val interval = when (getCurrentBatteryState()) {
                    BatteryState.CRITICAL -> AGGRESSIVE_CLEANUP_INTERVAL_MS
                    BatteryState.LOW -> BACKGROUND_CLEANUP_INTERVAL_MS / 2
                    else -> MONITORING_INTERVAL_MS
                }

                delay(interval)

            } catch (e: Exception) {
                Log.e(TAG, "Error in battery monitoring loop", e)
                delay(MONITORING_INTERVAL_MS)
            }
        }
    }

    /**
     * Checks battery state and applies optimizations
     */
    private suspend fun checkAndOptimize() {
        val batteryState = getCurrentBatteryState()
        val optimizationLevel = getCurrentOptimizationLevel()

        // Update performance scale
        val newScale = when (batteryState) {
            BatteryState.CRITICAL -> CRITICAL_PERFORMANCE_SCALE
            BatteryState.LOW -> LOW_PERFORMANCE_SCALE
            BatteryState.GOOD, BatteryState.CHARGING -> NORMAL_PERFORMANCE_SCALE
        }

        if (newScale != currentPerformanceScale) {
            updatePerformanceScale(newScale, batteryState)
        }

        // Apply optimizations
        performOptimization(force = false)
    }

    /**
     * Performs battery optimizations
     */
    private suspend fun performOptimization(force: Boolean) {
        val optimizationLevel = getCurrentOptimizationLevel()

        if (optimizationLevel == OptimizationLevel.DISABLED && !force) {
            return
        }

        try {
            when (optimizationLevel) {
                OptimizationLevel.AGGRESSIVE -> {
                    performAggressiveOptimization()
                }
                OptimizationLevel.MODERATE -> {
                    performModerateOptimization()
                }
                OptimizationLevel.MINIMAL -> {
                    performMinimalOptimization()
                }
                OptimizationLevel.DISABLED -> {
                    // Only if forced
                    if (force) performMinimalOptimization()
                }
            }

            optimizationActions.incrementAndGet()

        } catch (e: Exception) {
            Log.e(TAG, "Error during optimization", e)
        }
    }

    /**
     * Aggressive optimization for critical battery
     */
    private suspend fun performAggressiveOptimization() {
        // Force garbage collection
        System.gc()

        // Clear all non-essential caches
        ResponseCacheManager.getInstance(context).clearAllCache()

        // Reduce background operations
        // (This would integrate with BackgroundProcessingService)

        // Log power savings
        powerSavingsEstimateMs.addAndGet(300000) // Estimate 5 minutes saved

        Log.d(TAG, "Aggressive optimization performed")

        securityLogger.logSecurityEvent(
            SecurityAuditLogger.SecurityLevel.WARNING,
            SecurityAuditLogger.SecurityEvent.SECURITY_POLICY_VIOLATION,
            mapOf(
                "optimization_type" to "aggressive",
                "battery_level" to currentBatteryLevel
            )
        )
    }

    /**
     * Moderate optimization for low battery
     */
    private suspend fun performModerateOptimization() {
        // Selective garbage collection
        System.gc()

        // Clear expired caches only
        val cacheManager = ResponseCacheManager.getInstance(context)
        // This would call a method to clear only expired entries

        powerSavingsEstimateMs.addAndGet(120000) // Estimate 2 minutes saved

        Log.d(TAG, "Moderate optimization performed")
    }

    /**
     * Minimal optimization for good battery
     */
    private suspend fun performMinimalOptimization() {
        // Light cleanup
        System.runFinalization()

        powerSavingsEstimateMs.addAndGet(30000) // Estimate 30 seconds saved

        Log.d(TAG, "Minimal optimization performed")
    }

    /**
     * Updates performance scale and notifies listeners
     */
    private fun updatePerformanceScale(scale: Float, batteryState: BatteryState) {
        currentPerformanceScale = scale

        performanceScaleListeners.forEach { listener ->
            try {
                listener.onPerformanceScaleChanged(scale, batteryState)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying performance scale listener", e)
            }
        }

        Log.d(TAG, "Performance scale updated to: $scale")
    }

    /**
     * Gets current battery state
     */
    private fun getCurrentBatteryState(): BatteryState {
        return when {
            isCharging -> BatteryState.CHARGING
            currentBatteryLevel <= CRITICAL_BATTERY_LEVEL -> BatteryState.CRITICAL
            currentBatteryLevel <= LOW_BATTERY_LEVEL -> BatteryState.LOW
            else -> BatteryState.GOOD
        }
    }

    /**
     * Gets current optimization level
     */
    private fun getCurrentOptimizationLevel(): OptimizationLevel {
        return when (getCurrentBatteryState()) {
            BatteryState.CHARGING -> OptimizationLevel.DISABLED
            BatteryState.CRITICAL -> OptimizationLevel.AGGRESSIVE
            BatteryState.LOW -> OptimizationLevel.MODERATE
            BatteryState.GOOD -> OptimizationLevel.MINIMAL
        }
    }

    /**
     * Adjusts monitoring frequency based on app state
     */
    private fun adjustMonitoringFrequency(background: Boolean) {
        // This would adjust the monitoring interval
        Log.d(TAG, "Monitoring frequency adjusted for background: $background")
    }
}