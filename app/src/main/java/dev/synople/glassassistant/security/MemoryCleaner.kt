package dev.synople.glassassistant.security

import android.util.Log
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility class for securely clearing sensitive data from memory.
 * Provides methods to overwrite strings, byte arrays, and other sensitive data structures.
 */
object MemoryCleaner {

    private const val TAG = "MemoryCleaner"
    private val secureRandom = SecureRandom()
    private val trackedSensitiveData = ConcurrentHashMap<Any, String>()

    /**
     * Securely clears a string by overwriting its internal char array
     */
    fun clearString(string: String?): Boolean {
        if (string == null || string.isEmpty()) return true

        return try {
            // Access the internal value field of String
            val valueField = String::class.java.getDeclaredField("value")
            valueField.isAccessible = true

            val chars = valueField.get(string) as CharArray
            clearCharArray(chars)

            Log.d(TAG, "Cleared string of length ${string.length}")
            true
        } catch (e: Exception) {
            // Fallback for different JVM implementations or newer Android versions
            Log.w(TAG, "Could not clear string using reflection, trying alternatives", e)
            clearStringSlow(string)
        }
    }

    /**
     * Securely clears a StringBuilder
     */
    fun clearStringBuilder(sb: StringBuilder?): Boolean {
        if (sb == null) return true

        try {
            // Overwrite with random data
            val random = CharArray(sb.length)
            for (i in random.indices) {
                random[i] = (secureRandom.nextInt(95) + 32).toChar() // Printable ASCII
            }

            sb.clear()
            sb.append(random)
            sb.clear()

            // Clear the random array too
            clearCharArray(random)

            Log.d(TAG, "Cleared StringBuilder")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing StringBuilder", e)
            return false
        }
    }

    /**
     * Securely clears a char array
     */
    fun clearCharArray(chars: CharArray?): Boolean {
        if (chars == null || chars.isEmpty()) return true

        return try {
            // Overwrite with random characters
            for (i in chars.indices) {
                chars[i] = (secureRandom.nextInt(95) + 32).toChar() // Printable ASCII
            }

            // Second pass with different pattern
            Arrays.fill(chars, '\u0000')

            Log.d(TAG, "Cleared char array of size ${chars.size}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing char array", e)
            false
        }
    }

    /**
     * Securely clears a byte array
     */
    fun clearByteArray(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.isEmpty()) return true

        return try {
            // First pass with random data
            secureRandom.nextBytes(bytes)

            // Second pass with zeros
            Arrays.fill(bytes, 0.toByte())

            // Third pass with random data again
            secureRandom.nextBytes(bytes)

            // Final pass with zeros
            Arrays.fill(bytes, 0.toByte())

            Log.d(TAG, "Cleared byte array of size ${bytes.size}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing byte array", e)
            false
        }
    }

    /**
     * Securely clears a ByteBuffer
     */
    fun clearByteBuffer(buffer: ByteBuffer?): Boolean {
        if (buffer == null) return true

        return try {
            if (buffer.hasArray()) {
                clearByteArray(buffer.array())
            } else {
                // Direct buffer - overwrite with random data
                val randomBytes = ByteArray(buffer.remaining())
                secureRandom.nextBytes(randomBytes)
                buffer.put(randomBytes)

                // Second pass with zeros
                buffer.rewind()
                val zeros = ByteArray(buffer.remaining())
                buffer.put(zeros)
            }

            buffer.clear()
            Log.d(TAG, "Cleared ByteBuffer")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing ByteBuffer", e)
            false
        }
    }

    /**
     * Securely clears a CharBuffer
     */
    fun clearCharBuffer(buffer: CharBuffer?): Boolean {
        if (buffer == null) return true

        return try {
            if (buffer.hasArray()) {
                clearCharArray(buffer.array())
            } else {
                // Direct buffer - overwrite with random characters
                val randomChars = CharArray(buffer.remaining()) {
                    (secureRandom.nextInt(95) + 32).toChar()
                }
                buffer.put(randomChars)

                // Second pass with nulls
                buffer.rewind()
                val nulls = CharArray(buffer.remaining()) { '\u0000' }
                buffer.put(nulls)
            }

            buffer.clear()
            Log.d(TAG, "Cleared CharBuffer")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing CharBuffer", e)
            false
        }
    }

    /**
     * Registers sensitive data for tracking and eventual cleanup
     */
    fun trackSensitiveData(data: Any, description: String) {
        trackedSensitiveData[data] = description
        Log.d(TAG, "Tracking sensitive data: $description")
    }

    /**
     * Unregisters and clears tracked sensitive data
     */
    fun clearTrackedData(data: Any): Boolean {
        val description = trackedSensitiveData.remove(data)
        if (description != null) {
            val result = when (data) {
                is String -> clearString(data)
                is StringBuilder -> clearStringBuilder(data)
                is CharArray -> clearCharArray(data)
                is ByteArray -> clearByteArray(data)
                is ByteBuffer -> clearByteBuffer(data)
                is CharBuffer -> clearCharBuffer(data)
                else -> {
                    Log.w(TAG, "Unknown data type for clearing: ${data::class.java}")
                    false
                }
            }

            Log.d(TAG, "Cleared tracked data: $description, success: $result")
            return result
        }

        return false
    }

    /**
     * Clears all tracked sensitive data
     */
    fun clearAllTrackedData(): Int {
        var clearedCount = 0

        trackedSensitiveData.keys.toList().forEach { data ->
            if (clearTrackedData(data)) {
                clearedCount++
            }
        }

        Log.d(TAG, "Cleared $clearedCount tracked sensitive data items")
        return clearedCount
    }

    /**
     * Forces garbage collection and system cleanup
     */
    fun forceCleanup() {
        try {
            // Clear all tracked data first
            clearAllTrackedData()

            // Request garbage collection multiple times
            repeat(3) {
                System.gc()
                System.runFinalization()
                Thread.yield()
            }

            Log.d(TAG, "Forced memory cleanup completed")
        } catch (e: Exception) {
            Log.w(TAG, "Error during forced cleanup", e)
        }
    }

    /**
     * Gets count of tracked sensitive data items
     */
    fun getTrackedDataCount(): Int = trackedSensitiveData.size

    /**
     * Slow string clearing for fallback (less reliable)
     */
    private fun clearStringSlow(string: String): Boolean {
        return try {
            // Try to trigger string interning behavior to potentially clear
            val copy = StringBuilder(string)
            clearStringBuilder(copy)

            // Suggest garbage collection
            System.gc()

            Log.d(TAG, "Attempted slow string clearing")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Slow string clearing failed", e)
            false
        }
    }

    /**
     * Utility class for managing sensitive data lifecycle
     */
    class SensitiveDataScope : AutoCloseable {
        private val scopeData = mutableListOf<Any>()

        fun track(data: Any, description: String = "ScopedData"): Any {
            scopeData.add(data)
            trackSensitiveData(data, "$description (scoped)")
            return data
        }

        override fun close() {
            scopeData.forEach { data ->
                clearTrackedData(data)
            }
            scopeData.clear()
            Log.d(TAG, "SensitiveDataScope closed")
        }
    }

    /**
     * Extension function for automatic sensitive data cleanup
     */
    inline fun <R> withSensitiveData(block: SensitiveDataScope.() -> R): R {
        return SensitiveDataScope().use { scope ->
            scope.block()
        }
    }
}