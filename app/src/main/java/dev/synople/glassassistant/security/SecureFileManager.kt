package dev.synople.glassassistant.security

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.*
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Secure file manager for handling temporary files with proper cleanup and security.
 * Provides secure file creation, access control, and automatic cleanup.
 */
class SecureFileManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SecureFileManager"
        private const val TEMP_DIR_NAME = "glass_secure_temp"
        private const val MAX_TEMP_FILE_AGE_MS = 10 * 60 * 1000L // 10 minutes
        private const val CLEANUP_INTERVAL_MINUTES = 5L
        private const val MAX_TEMP_FILES = 100
        private const val SECURE_FILE_PREFIX = "glass_"
        private const val SECURE_FILE_SUFFIX = ".tmp"

        @Volatile
        private var INSTANCE: SecureFileManager? = null

        fun getInstance(context: Context): SecureFileManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureFileManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val secureRandom = SecureRandom()
    private val tempFileRegistry = ConcurrentHashMap<String, TempFileInfo>()
    private val cleanupExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "SecureFileManager-Cleanup").apply {
            isDaemon = true
        }
    }

    private data class TempFileInfo(
        val file: File,
        val createdAt: Long,
        val sensitiveData: Boolean,
        val autoDelete: Boolean
    )

    init {
        // Start periodic cleanup
        cleanupExecutor.scheduleAtFixedRate(
            ::performCleanup,
            CLEANUP_INTERVAL_MINUTES,
            CLEANUP_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )

        // Cleanup on app start
        performInitialCleanup()
    }

    /**
     * Creates a secure temporary file
     */
    fun createSecureTempFile(
        prefix: String = SECURE_FILE_PREFIX,
        suffix: String = SECURE_FILE_SUFFIX,
        sensitiveData: Boolean = true,
        autoDelete: Boolean = true
    ): SecureFile {
        try {
            val tempDir = getTempDirectory()
            ensureDirectoryExists(tempDir)

            // Generate secure filename
            val secureFileName = generateSecureFileName(prefix, suffix)
            val file = File(tempDir, secureFileName)

            // Ensure file doesn't exist (should be impossible with secure random)
            if (file.exists()) {
                throw SecurityException("Temporary file already exists: ${file.name}")
            }

            // Create file with secure permissions
            if (!file.createNewFile()) {
                throw IOException("Failed to create temporary file")
            }

            // Set restrictive permissions (owner read/write only)
            setSecureFilePermissions(file)

            // Register for tracking
            val tempFileInfo = TempFileInfo(
                file = file,
                createdAt = System.currentTimeMillis(),
                sensitiveData = sensitiveData,
                autoDelete = autoDelete
            )
            tempFileRegistry[file.absolutePath] = tempFileInfo

            // Check if we have too many temp files
            if (tempFileRegistry.size > MAX_TEMP_FILES) {
                Log.w(TAG, "Too many temporary files, triggering cleanup")
                performCleanup()
            }

            Log.d(TAG, "Created secure temporary file: ${file.name}")
            return SecureFile(file, this, sensitiveData)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create secure temporary file", e)
            throw SecurityException("Failed to create secure temporary file: ${e.message}")
        }
    }

    /**
     * Creates a secure temporary file with specific content
     */
    fun createSecureTempFileWithContent(
        content: ByteArray,
        prefix: String = SECURE_FILE_PREFIX,
        suffix: String = SECURE_FILE_SUFFIX,
        sensitiveData: Boolean = true
    ): SecureFile {
        val secureFile = createSecureTempFile(prefix, suffix, sensitiveData)

        try {
            secureFile.writeBytes(content)
            return secureFile
        } catch (e: Exception) {
            // Clean up on failure
            secureFile.delete()
            throw e
        }
    }

    /**
     * Securely deletes a file
     */
    internal fun secureDelete(file: File, isSensitive: Boolean) {
        try {
            if (!file.exists()) {
                Log.d(TAG, "File does not exist for deletion: ${file.name}")
                return
            }

            if (isSensitive) {
                // Overwrite file contents multiple times for sensitive data
                overwriteFileSecurely(file)
            }

            // Delete the file
            if (!file.delete()) {
                Log.w(TAG, "Failed to delete file: ${file.absolutePath}")
                // Try to delete on exit as fallback
                file.deleteOnExit()
            } else {
                Log.d(TAG, "Securely deleted file: ${file.name}")
            }

            // Remove from registry
            tempFileRegistry.remove(file.absolutePath)

        } catch (e: Exception) {
            Log.e(TAG, "Error during secure file deletion", e)
            // Still remove from registry to prevent memory leaks
            tempFileRegistry.remove(file.absolutePath)
        }
    }

    /**
     * Get temp directory for this app
     */
    private fun getTempDirectory(): File {
        val tempDir = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            // Use external cache if available for better performance
            File(context.externalCacheDir, TEMP_DIR_NAME)
        } else {
            // Fall back to internal cache
            File(context.cacheDir, TEMP_DIR_NAME)
        }

        return tempDir
    }

    /**
     * Ensures directory exists and has secure permissions
     */
    private fun ensureDirectoryExists(dir: File) {
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                throw IOException("Failed to create temp directory: ${dir.absolutePath}")
            }
        }

        // Set directory permissions
        try {
            dir.setReadable(true, true)   // Owner read only
            dir.setWritable(true, true)   // Owner write only
            dir.setExecutable(true, true) // Owner execute only
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set directory permissions", e)
        }
    }

    /**
     * Generates a cryptographically secure filename
     */
    private fun generateSecureFileName(prefix: String, suffix: String): String {
        val timestamp = System.currentTimeMillis()
        val randomBytes = ByteArray(16)
        secureRandom.nextBytes(randomBytes)

        val randomHex = randomBytes.joinToString("") {
            "%02x".format(it)
        }

        return "${prefix}${timestamp}_${randomHex}${suffix}"
    }

    /**
     * Sets secure file permissions (owner read/write only)
     */
    private fun setSecureFilePermissions(file: File) {
        try {
            file.setReadable(false, false) // Remove all read permissions
            file.setWritable(false, false) // Remove all write permissions
            file.setExecutable(false, false) // Remove all execute permissions

            file.setReadable(true, true)   // Owner read only
            file.setWritable(true, true)   // Owner write only

        } catch (e: Exception) {
            Log.w(TAG, "Failed to set file permissions for ${file.name}", e)
        }
    }

    /**
     * Securely overwrites file contents multiple times
     */
    private fun overwriteFileSecurely(file: File) {
        try {
            val fileSize = file.length()
            if (fileSize == 0L) return

            file.outputStream().use { out ->
                val buffer = ByteArray(8192)

                // Overwrite with random data 3 times
                repeat(3) {
                    secureRandom.nextBytes(buffer)

                    var bytesWritten = 0L
                    while (bytesWritten < fileSize) {
                        val bytesToWrite = minOf(buffer.size, (fileSize - bytesWritten).toInt())
                        out.write(buffer, 0, bytesToWrite)
                        bytesWritten += bytesToWrite
                    }

                    out.flush()
                    out.fd.sync() // Force write to disk
                }
            }

        } catch (e: Exception) {
            Log.w(TAG, "Failed to overwrite file securely: ${file.name}", e)
        }
    }

    /**
     * Performs periodic cleanup of old temporary files
     */
    private fun performCleanup() {
        try {
            val currentTime = System.currentTimeMillis()
            val filesToDelete = mutableListOf<String>()

            // Find files to delete
            tempFileRegistry.forEach { (path, info) ->
                val age = currentTime - info.createdAt

                if (info.autoDelete && (age > MAX_TEMP_FILE_AGE_MS || !info.file.exists())) {
                    filesToDelete.add(path)
                }
            }

            // Delete old files
            filesToDelete.forEach { path ->
                tempFileRegistry[path]?.let { info ->
                    secureDelete(info.file, info.sensitiveData)
                }
            }

            if (filesToDelete.isNotEmpty()) {
                Log.d(TAG, "Cleaned up ${filesToDelete.size} temporary files")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    /**
     * Performs initial cleanup on manager creation
     */
    private fun performInitialCleanup() {
        try {
            val tempDir = getTempDirectory()
            if (tempDir.exists()) {
                tempDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.startsWith(SECURE_FILE_PREFIX)) {
                        val age = System.currentTimeMillis() - file.lastModified()
                        if (age > MAX_TEMP_FILE_AGE_MS) {
                            // Assume sensitive for safety
                            overwriteFileSecurely(file)
                            file.delete()
                            Log.d(TAG, "Cleaned up stale temp file: ${file.name}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during initial cleanup", e)
        }
    }

    /**
     * Manually trigger cleanup (for testing or forced cleanup)
     */
    fun forceCleanup() {
        performCleanup()
    }

    /**
     * Get number of tracked temporary files
     */
    fun getTempFileCount(): Int = tempFileRegistry.size

    /**
     * Cleanup all resources
     */
    fun shutdown() {
        try {
            // Perform final cleanup
            performCleanup()

            // Shutdown cleanup executor
            cleanupExecutor.shutdown()
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow()
            }

            Log.d(TAG, "SecureFileManager shutdown completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
    }
}

/**
 * Secure wrapper for temporary files with automatic cleanup
 */
class SecureFile internal constructor(
    private val file: File,
    private val manager: SecureFileManager,
    private val isSensitive: Boolean
) : Closeable {

    val name: String get() = file.name
    val absolutePath: String get() = file.absolutePath
    val length: Long get() = if (file.exists()) file.length() else 0L

    /**
     * Checks if the file exists
     */
    fun exists(): Boolean = file.exists()

    /**
     * Reads all bytes from the file
     */
    fun readBytes(): ByteArray {
        return file.readBytes()
    }

    /**
     * Writes bytes to the file
     */
    fun writeBytes(bytes: ByteArray) {
        file.writeBytes(bytes)
    }

    /**
     * Gets input stream for the file
     */
    fun inputStream(): FileInputStream {
        return file.inputStream()
    }

    /**
     * Gets output stream for the file
     */
    fun outputStream(): FileOutputStream {
        return file.outputStream()
    }

    /**
     * Gets the underlying file (use with caution)
     */
    fun getFile(): File = file

    /**
     * Securely deletes the file
     */
    fun delete() {
        manager.secureDelete(file, isSensitive)
    }

    /**
     * Auto-cleanup on close
     */
    override fun close() {
        delete()
    }
}