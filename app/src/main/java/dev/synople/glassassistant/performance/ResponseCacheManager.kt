package dev.synople.glassassistant.performance

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Intelligent caching strategy for API responses optimized for Glass hardware constraints.
 * Features memory and disk caching with compression and intelligent cache eviction.
 */
class ResponseCacheManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ResponseCacheManager"
        private const val CACHE_DIR_NAME = "api_response_cache"
        private const val MEMORY_CACHE_SIZE = 20 // Limited for Glass hardware
        private const val DISK_CACHE_SIZE_MB = 50L // 50MB disk cache
        private val DEFAULT_TTL_MS = TimeUnit.HOURS.toMillis(6) // 6 hours
        private val CLEANUP_INTERVAL_MS = TimeUnit.HOURS.toMillis(1) // 1 hour cleanup
        private const val MAX_RESPONSE_SIZE = 1024 * 1024 // 1MB max response size

        @Volatile
        private var INSTANCE: ResponseCacheManager? = null

        fun getInstance(context: Context): ResponseCacheManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ResponseCacheManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val memoryCache = ConcurrentHashMap<String, CacheEntry>()
    private val diskCacheDir = File(context.cacheDir, CACHE_DIR_NAME)
    private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Cache statistics
    private var memoryHits = 0L
    private var diskHits = 0L
    private var misses = 0L
    private var evictions = 0L

    data class CacheEntry(
        val key: String,
        val data: String,
        val timestamp: Long,
        val ttlMs: Long,
        val size: Int,
        val provider: String,
        val isCompressed: Boolean = false
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() > timestamp + ttlMs
        fun isValid(): Boolean = !isExpired() && data.isNotEmpty()
    }

    data class CacheStats(
        val memoryHits: Long,
        val diskHits: Long,
        val misses: Long,
        val hitRatio: Float,
        val memorySize: Int,
        val diskSizeBytes: Long,
        val evictions: Long
    )

    init {
        // Ensure cache directory exists
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs()
        }

        // Start periodic cleanup
        startPeriodicCleanup()
    }

    /**
     * Gets cached response if available and valid
     */
    suspend fun getCachedResponse(
        provider: String,
        model: String?,
        prompt: String,
        imageHash: String? = null
    ): String? = withContext(Dispatchers.IO) {

        val cacheKey = generateCacheKey(provider, model, prompt, imageHash)

        // Check memory cache first
        memoryCache[cacheKey]?.let { entry ->
            if (entry.isValid()) {
                memoryHits++
                Log.d(TAG, "Memory cache hit for key: ${cacheKey.take(16)}...")
                return@withContext entry.data
            } else {
                // Remove expired entry
                memoryCache.remove(cacheKey)
            }
        }

        // Check disk cache
        val diskEntry = getDiskCacheEntry(cacheKey)
        if (diskEntry != null && diskEntry.isValid()) {
            diskHits++

            // Promote to memory cache
            promoteToMemoryCache(diskEntry)

            Log.d(TAG, "Disk cache hit for key: ${cacheKey.take(16)}...")
            return@withContext diskEntry.data
        }

        // Cache miss
        misses++
        Log.d(TAG, "Cache miss for key: ${cacheKey.take(16)}...")
        null
    }

    /**
     * Caches API response with intelligent storage strategy
     */
    suspend fun cacheResponse(
        provider: String,
        model: String?,
        prompt: String,
        response: String,
        imageHash: String? = null,
        ttlMs: Long = DEFAULT_TTL_MS
    ) = withContext(Dispatchers.IO) {

        if (response.isEmpty() || response.length > MAX_RESPONSE_SIZE) {
            Log.w(TAG, "Response too large or empty, not caching")
            return@withContext
        }

        try {
            val cacheKey = generateCacheKey(provider, model, prompt, imageHash)
            val timestamp = System.currentTimeMillis()

            // Determine if compression is beneficial
            val shouldCompress = response.length > 1024 // Compress responses > 1KB
            val processedResponse = if (shouldCompress) {
                compressString(response)
            } else {
                response
            }

            val entry = CacheEntry(
                key = cacheKey,
                data = processedResponse,
                timestamp = timestamp,
                ttlMs = ttlMs,
                size = processedResponse.length,
                provider = provider,
                isCompressed = shouldCompress
            )

            // Always try to cache in memory first
            cacheInMemory(entry)

            // Cache to disk for persistence
            cacheToDisk(entry)

            Log.d(TAG, "Cached response: provider=$provider, size=${response.length} chars")

        } catch (e: Exception) {
            Log.e(TAG, "Error caching response", e)
        }
    }

    /**
     * Invalidates cache entries for a specific provider
     */
    suspend fun invalidateProvider(provider: String) = withContext(Dispatchers.IO) {
        try {
            // Remove from memory cache
            val memoryKeysToRemove = memoryCache.keys.filter { key ->
                memoryCache[key]?.provider == provider
            }
            memoryKeysToRemove.forEach { memoryCache.remove(it) }

            // Remove from disk cache
            diskCacheDir.listFiles()?.forEach { file ->
                try {
                    val entry = readDiskCacheFile(file)
                    if (entry?.provider == provider) {
                        file.delete()
                    }
                } catch (e: Exception) {
                    // Skip corrupted files
                    file.delete()
                }
            }

            Log.d(TAG, "Invalidated cache for provider: $provider")

        } catch (e: Exception) {
            Log.e(TAG, "Error invalidating provider cache", e)
        }
    }

    /**
     * Clears all cached data
     */
    suspend fun clearAllCache() = withContext(Dispatchers.IO) {
        try {
            // Clear memory cache
            memoryCache.clear()

            // Clear disk cache
            diskCacheDir.listFiles()?.forEach { it.delete() }

            // Reset statistics
            memoryHits = 0L
            diskHits = 0L
            misses = 0L
            evictions = 0L

            Log.d(TAG, "All cache cleared")

        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
        }
    }

    /**
     * Gets cache statistics
     */
    fun getCacheStats(): CacheStats {
        val totalRequests = memoryHits + diskHits + misses
        val hitRatio = if (totalRequests > 0) {
            (memoryHits + diskHits).toFloat() / totalRequests.toFloat()
        } else {
            0f
        }

        val diskSize = try {
            diskCacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (e: Exception) {
            0L
        }

        return CacheStats(
            memoryHits = memoryHits,
            diskHits = diskHits,
            misses = misses,
            hitRatio = hitRatio,
            memorySize = memoryCache.size,
            diskSizeBytes = diskSize,
            evictions = evictions
        )
    }

    /**
     * Generates cache key from request parameters
     */
    private fun generateCacheKey(
        provider: String,
        model: String?,
        prompt: String,
        imageHash: String?
    ): String {
        val keyData = buildString {
            append(provider)
            append("|")
            append(model ?: "default")
            append("|")
            append(prompt.hashCode())
            append("|")
            append(imageHash ?: "no_image")
        }

        return sha256Hash(keyData)
    }

    /**
     * SHA-256 hash for cache keys
     */
    private fun sha256Hash(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Caches entry in memory with LRU eviction
     */
    private fun cacheInMemory(entry: CacheEntry) {
        // Check if memory cache is full
        if (memoryCache.size >= MEMORY_CACHE_SIZE) {
            evictLeastRecentlyUsed()
        }

        memoryCache[entry.key] = entry
    }

    /**
     * Caches entry to disk
     */
    private suspend fun cacheToDisk(entry: CacheEntry) = withContext(Dispatchers.IO) {
        try {
            val cacheFile = File(diskCacheDir, entry.key)
            val cacheData = JSONObject().apply {
                put("key", entry.key)
                put("data", entry.data)
                put("timestamp", entry.timestamp)
                put("ttl", entry.ttlMs)
                put("size", entry.size)
                put("provider", entry.provider)
                put("compressed", entry.isCompressed)
            }

            cacheFile.writeText(cacheData.toString())

            // Check disk cache size and clean if needed
            cleanupDiskCache()

        } catch (e: Exception) {
            Log.e(TAG, "Error writing disk cache", e)
        }
    }

    /**
     * Gets entry from disk cache
     */
    private suspend fun getDiskCacheEntry(key: String): CacheEntry? = withContext(Dispatchers.IO) {
        try {
            val cacheFile = File(diskCacheDir, key)
            if (!cacheFile.exists()) return@withContext null

            readDiskCacheFile(cacheFile)

        } catch (e: Exception) {
            Log.w(TAG, "Error reading disk cache file", e)
            null
        }
    }

    /**
     * Reads cache entry from disk file
     */
    private fun readDiskCacheFile(file: File): CacheEntry? {
        return try {
            val jsonData = JSONObject(file.readText())
            val data = jsonData.getString("data")
            val isCompressed = jsonData.optBoolean("compressed", false)

            val processedData = if (isCompressed) {
                decompressString(data)
            } else {
                data
            }

            CacheEntry(
                key = jsonData.getString("key"),
                data = processedData,
                timestamp = jsonData.getLong("timestamp"),
                ttlMs = jsonData.getLong("ttl"),
                size = jsonData.getInt("size"),
                provider = jsonData.getString("provider"),
                isCompressed = isCompressed
            )

        } catch (e: Exception) {
            Log.w(TAG, "Corrupted cache file: ${file.name}", e)
            file.delete() // Remove corrupted file
            null
        }
    }

    /**
     * Promotes disk cache entry to memory cache
     */
    private fun promoteToMemoryCache(entry: CacheEntry) {
        if (memoryCache.size >= MEMORY_CACHE_SIZE) {
            evictLeastRecentlyUsed()
        }
        memoryCache[entry.key] = entry.copy(timestamp = System.currentTimeMillis())
    }

    /**
     * Evicts least recently used entry from memory cache
     */
    private fun evictLeastRecentlyUsed() {
        val oldestEntry = memoryCache.values.minByOrNull { it.timestamp }
        if (oldestEntry != null) {
            memoryCache.remove(oldestEntry.key)
            evictions++
            Log.d(TAG, "Evicted LRU entry: ${oldestEntry.key.take(16)}...")
        }
    }

    /**
     * Cleans up disk cache if size exceeds limit
     */
    private suspend fun cleanupDiskCache() = withContext(Dispatchers.IO) {
        try {
            val files = diskCacheDir.listFiles() ?: return@withContext
            val totalSize = files.sumOf { it.length() }

            if (totalSize > DISK_CACHE_SIZE_MB * 1024 * 1024) {
                // Sort by last modified time and delete oldest files
                files.sortedBy { it.lastModified() }
                    .take((files.size * 0.3).toInt()) // Remove 30% of files
                    .forEach {
                        it.delete()
                        Log.d(TAG, "Deleted old cache file: ${it.name}")
                    }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning disk cache", e)
        }
    }

    /**
     * Starts periodic cleanup of expired entries
     */
    private fun startPeriodicCleanup() {
        cleanupScope.launch {
            while (isActive) {
                try {
                    delay(CLEANUP_INTERVAL_MS)
                    performPeriodicCleanup()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during periodic cleanup", e)
                }
            }
        }
    }

    /**
     * Performs periodic cleanup of expired entries
     */
    private suspend fun performPeriodicCleanup(): Unit = withContext(Dispatchers.IO) {
        try {
            val currentTime = System.currentTimeMillis()

            // Clean memory cache
            val expiredMemoryKeys = memoryCache.entries
                .filter { it.value.isExpired() }
                .map { it.key }

            expiredMemoryKeys.forEach { memoryCache.remove(it) }

            // Clean disk cache
            diskCacheDir.listFiles()?.forEach { file ->
                try {
                    val entry = readDiskCacheFile(file)
                    if (entry?.isExpired() == true) {
                        file.delete()
                    }
                } catch (e: Exception) {
                    // Delete corrupted files
                    file.delete()
                }
            }

            if (expiredMemoryKeys.isNotEmpty()) {
                Log.d(TAG, "Cleaned ${expiredMemoryKeys.size} expired entries")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during periodic cleanup", e)
        }
    }

    /**
     * Simple string compression for large responses
     */
    private fun compressString(input: String): String {
        return try {
            val deflater = java.util.zip.Deflater()
            try {
                deflater.setInput(input.toByteArray())
                deflater.finish()

                val buffer = ByteArray(1024)
                val compressedData = mutableListOf<Byte>()

                while (!deflater.finished()) {
                    val count = deflater.deflate(buffer)
                    for (i in 0 until count) {
                        compressedData.add(buffer[i])
                    }
                }

                android.util.Base64.encodeToString(compressedData.toByteArray(), android.util.Base64.NO_WRAP)
            } finally {
                deflater.end()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Compression failed, using original", e)
            input
        }
    }

    /**
     * Decompresses compressed string
     */
    private fun decompressString(compressed: String): String {
        return try {
            val compressedData = android.util.Base64.decode(compressed, android.util.Base64.NO_WRAP)

            val inflater = java.util.zip.Inflater()
            try {
                inflater.setInput(compressedData)

                val buffer = ByteArray(1024)
                val decompressedData = mutableListOf<Byte>()

                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    for (i in 0 until count) {
                        decompressedData.add(buffer[i])
                    }
                }

                String(decompressedData.toByteArray())
            } finally {
                inflater.end()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Decompression failed", e)
            compressed // Return as-is if decompression fails
        }
    }

    /**
     * Shuts down the cache manager
     */
    fun shutdown() {
        cleanupScope.cancel()
        Log.d(TAG, "ResponseCacheManager shutdown")
    }
}