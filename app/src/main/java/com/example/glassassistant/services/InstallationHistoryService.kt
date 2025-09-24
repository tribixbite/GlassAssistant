package com.example.glassassistant.services

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Service to log APK installation history for security auditing
 * Tracks all installation attempts, sources, and outcomes
 */
class InstallationHistoryService(private val context: Context) {

    companion object {
        private const val TAG = "InstallHistoryService"
        private const val PREFS_NAME = "installation_history"
        private const val KEY_HISTORY = "history_log"
        private const val MAX_HISTORY_ENTRIES = 100
        private const val DATE_FORMAT = "yyyy-MM-dd HH:mm:ss"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dateFormatter = SimpleDateFormat(DATE_FORMAT, Locale.US)

    data class InstallationEntry(
        val timestamp: String,
        val packageName: String,
        val versionName: String?,
        val versionCode: Int,
        val sourceUrl: String,
        val fileSize: Long,
        val sha256Hash: String?,
        val signatureValid: Boolean,
        val installationStatus: InstallationStatus,
        val errorMessage: String? = null,
        val qrCodeData: String? = null,
        val userInitiated: Boolean = true
    )

    enum class InstallationStatus {
        INITIATED,
        DOWNLOADING,
        DOWNLOAD_COMPLETE,
        VERIFYING,
        INSTALLING,
        SUCCESS,
        FAILED,
        CANCELLED,
        SIGNATURE_MISMATCH,
        MALWARE_DETECTED
    }

    /**
     * Log an installation attempt
     */
    fun logInstallation(entry: InstallationEntry) {
        try {
            val history = getHistory().toMutableList()

            // Convert entry to JSON
            val entryJson = JSONObject().apply {
                put("timestamp", entry.timestamp)
                put("package_name", entry.packageName)
                put("version_name", entry.versionName ?: "")
                put("version_code", entry.versionCode)
                put("source_url", entry.sourceUrl)
                put("file_size", entry.fileSize)
                put("sha256_hash", entry.sha256Hash ?: "")
                put("signature_valid", entry.signatureValid)
                put("status", entry.installationStatus.name)
                put("error_message", entry.errorMessage ?: "")
                put("qr_code_data", entry.qrCodeData ?: "")
                put("user_initiated", entry.userInitiated)
            }

            // Add to history
            history.add(0, entryJson)

            // Trim to max entries
            while (history.size > MAX_HISTORY_ENTRIES) {
                history.removeAt(history.lastIndex)
            }

            // Save to preferences
            saveHistory(history)

            // Log to system for debugging
            Log.i(TAG, "Installation logged: ${entry.packageName} - ${entry.installationStatus}")

            // Check for suspicious patterns
            checkSuspiciousPatterns(entry)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to log installation", e)
        }
    }

    /**
     * Create an installation entry for a download initiation
     */
    fun createDownloadEntry(
        url: String,
        qrCodeData: String? = null
    ): InstallationEntry {
        return InstallationEntry(
            timestamp = dateFormatter.format(Date()),
            packageName = "unknown",
            versionName = null,
            versionCode = 0,
            sourceUrl = url,
            fileSize = 0,
            sha256Hash = null,
            signatureValid = false,
            installationStatus = InstallationStatus.INITIATED,
            qrCodeData = qrCodeData
        )
    }

    /**
     * Update an existing entry with download completion details
     */
    fun updateDownloadComplete(
        originalEntry: InstallationEntry,
        fileSize: Long,
        sha256Hash: String?
    ): InstallationEntry {
        return originalEntry.copy(
            fileSize = fileSize,
            sha256Hash = sha256Hash,
            installationStatus = InstallationStatus.DOWNLOAD_COMPLETE
        )
    }

    /**
     * Update entry with package information after verification
     */
    fun updatePackageInfo(
        originalEntry: InstallationEntry,
        packageName: String,
        versionName: String?,
        versionCode: Int,
        signatureValid: Boolean
    ): InstallationEntry {
        return originalEntry.copy(
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            signatureValid = signatureValid,
            installationStatus = if (signatureValid) {
                InstallationStatus.VERIFYING
            } else {
                InstallationStatus.SIGNATURE_MISMATCH
            }
        )
    }

    /**
     * Mark installation as successful
     */
    fun markSuccess(originalEntry: InstallationEntry): InstallationEntry {
        return originalEntry.copy(
            installationStatus = InstallationStatus.SUCCESS
        )
    }

    /**
     * Mark installation as failed
     */
    fun markFailed(originalEntry: InstallationEntry, error: String): InstallationEntry {
        return originalEntry.copy(
            installationStatus = InstallationStatus.FAILED,
            errorMessage = error
        )
    }

    /**
     * Get installation history
     */
    fun getHistory(): List<JSONObject> {
        val historyString = prefs.getString(KEY_HISTORY, "[]") ?: "[]"
        val historyArray = JSONArray(historyString)
        val history = mutableListOf<JSONObject>()

        for (i in 0 until historyArray.length()) {
            history.add(historyArray.getJSONObject(i))
        }

        return history
    }

    /**
     * Get suspicious installations
     */
    fun getSuspiciousInstallations(): List<JSONObject> {
        return getHistory().filter { entry ->
            val status = entry.getString("status")
            val signatureValid = entry.getBoolean("signature_valid")
            val sourceUrl = entry.getString("source_url")

            // Flag as suspicious if:
            // - Signature invalid
            // - Failed with specific patterns
            // - From unknown sources
            // - Malware detected
            status == InstallationStatus.SIGNATURE_MISMATCH.name ||
            status == InstallationStatus.MALWARE_DETECTED.name ||
            !signatureValid ||
            isUnknownSource(sourceUrl)
        }
    }

    /**
     * Get installation statistics
     */
    fun getStatistics(): Map<String, Any> {
        val history = getHistory()
        val successCount = history.count {
            it.getString("status") == InstallationStatus.SUCCESS.name
        }
        val failureCount = history.count {
            it.getString("status") == InstallationStatus.FAILED.name
        }
        val suspiciousCount = getSuspiciousInstallations().size

        val uniqueSources = history.map { it.getString("source_url") }
            .map { extractDomain(it) }
            .distinct()
            .size

        return mapOf(
            "total_installations" to history.size,
            "successful" to successCount,
            "failed" to failureCount,
            "suspicious" to suspiciousCount,
            "unique_sources" to uniqueSources,
            "success_rate" to if (history.isNotEmpty()) {
                (successCount.toFloat() / history.size * 100).toInt()
            } else 0
        )
    }

    /**
     * Clear installation history
     */
    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
        Log.i(TAG, "Installation history cleared")
    }

    /**
     * Export history as JSON string
     */
    fun exportHistory(): String {
        val history = getHistory()
        val export = JSONObject().apply {
            put("export_date", dateFormatter.format(Date()))
            put("device_model", android.os.Build.MODEL)
            put("android_version", android.os.Build.VERSION.SDK_INT)
            put("total_entries", history.size)
            put("history", JSONArray(history))
        }
        return export.toString(2)
    }

    private fun saveHistory(history: List<JSONObject>) {
        val historyArray = JSONArray()
        history.forEach { historyArray.put(it) }
        prefs.edit().putString(KEY_HISTORY, historyArray.toString()).apply()
    }

    private fun checkSuspiciousPatterns(entry: InstallationEntry) {
        val suspicious = mutableListOf<String>()

        // Check for invalid signature
        if (!entry.signatureValid) {
            suspicious.add("Invalid signature")
        }

        // Check for unknown sources
        if (isUnknownSource(entry.sourceUrl)) {
            suspicious.add("Unknown source: ${extractDomain(entry.sourceUrl)}")
        }

        // Check for rapid repeated installations
        if (hasRapidRepeatedInstalls(entry.packageName)) {
            suspicious.add("Rapid repeated installation attempts")
        }

        // Log suspicious activity
        if (suspicious.isNotEmpty()) {
            Log.w(TAG, "Suspicious installation detected: ${entry.packageName}")
            Log.w(TAG, "Reasons: ${suspicious.joinToString(", ")}")

            // Could trigger additional security measures here
            // e.g., notify user, require additional confirmation, etc.
        }
    }

    private fun isUnknownSource(url: String): Boolean {
        val trustedDomains = listOf(
            "github.com",
            "play.google.com",
            "amazonaws.com",
            "googleapis.com",
            "gitlab.com",
            "bitbucket.org"
        )

        val domain = extractDomain(url).toLowerCase(Locale.US)
        return !trustedDomains.any { domain.contains(it) }
    }

    private fun extractDomain(url: String): String {
        return try {
            val uri = java.net.URI(url)
            uri.host ?: url
        } catch (e: Exception) {
            url
        }
    }

    private fun hasRapidRepeatedInstalls(packageName: String): Boolean {
        val recentHistory = getHistory().take(10)
        val packageInstalls = recentHistory.filter {
            it.getString("package_name") == packageName
        }

        // Flag if more than 3 attempts in last 10 entries
        return packageInstalls.size > 3
    }
}