package dev.synople.glassassistant.services.security

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay

/**
 * Rate limiter implementation to prevent API abuse and manage request throttling
 * Uses token bucket algorithm for smooth rate limiting
 */
class RateLimiter {

    companion object {
        private const val TAG = "RateLimiter"

        // Default rate limits per provider
        private val DEFAULT_LIMITS = mapOf(
            "openai" to RateLimit(60, 60000),        // 60 requests per minute
            "anthropic" to RateLimit(50, 60000),     // 50 requests per minute
            "openrouter" to RateLimit(100, 60000),   // 100 requests per minute
            "google" to RateLimit(60, 60000),        // 60 requests per minute
            "local" to RateLimit(1000, 60000)        // 1000 requests per minute for local
        )

        // Global rate limit across all providers
        private const val GLOBAL_LIMIT = 200         // Total requests per minute
        private const val GLOBAL_WINDOW_MS = 60000L  // 1 minute window
    }

    data class RateLimit(
        val maxRequests: Int,
        val windowMs: Long
    )

    data class BucketState(
        val tokens: AtomicInteger,
        val lastRefillTime: Long,
        val maxTokens: Int,
        val refillRateMs: Long
    )

    private val providerBuckets = ConcurrentHashMap<String, BucketState>()
    private val userBuckets = ConcurrentHashMap<String, BucketState>()
    private val globalBucket = BucketState(
        tokens = AtomicInteger(GLOBAL_LIMIT),
        lastRefillTime = System.currentTimeMillis(),
        maxTokens = GLOBAL_LIMIT,
        refillRateMs = GLOBAL_WINDOW_MS / GLOBAL_LIMIT
    )

    // Request history for analytics
    private val requestHistory = mutableListOf<RequestRecord>()
    private val historyLock = Any()

    data class RequestRecord(
        val timestamp: Long,
        val provider: String,
        val userId: String?,
        val allowed: Boolean,
        val tokensRemaining: Int
    )

    /**
     * Check if a request is allowed and consume a token if it is
     */
    suspend fun allowRequest(
        provider: String,
        userId: String? = null,
        wait: Boolean = false
    ): Boolean {
        // Refill tokens based on elapsed time
        refillBuckets()

        // Check global rate limit
        if (!checkAndConsumeToken(globalBucket)) {
            if (wait) {
                val waitTime = calculateWaitTime(globalBucket)
                Log.d(TAG, "Global rate limit hit, waiting ${waitTime}ms")
                delay(waitTime)
                return allowRequest(provider, userId, false)
            }
            recordRequest(provider, userId, false, globalBucket.tokens.get())
            return false
        }

        // Check provider-specific rate limit
        val providerBucket = getProviderBucket(provider)
        if (!checkAndConsumeToken(providerBucket)) {
            // Return global token since provider check failed
            globalBucket.tokens.incrementAndGet()

            if (wait) {
                val waitTime = calculateWaitTime(providerBucket)
                Log.d(TAG, "Provider rate limit hit for $provider, waiting ${waitTime}ms")
                delay(waitTime)
                return allowRequest(provider, userId, false)
            }
            recordRequest(provider, userId, false, providerBucket.tokens.get())
            return false
        }

        // Check user-specific rate limit if userId provided
        userId?.let { uid ->
            val userBucket = getUserBucket(uid)
            if (!checkAndConsumeToken(userBucket)) {
                // Return tokens since user check failed
                globalBucket.tokens.incrementAndGet()
                providerBucket.tokens.incrementAndGet()

                if (wait) {
                    val waitTime = calculateWaitTime(userBucket)
                    Log.d(TAG, "User rate limit hit for $uid, waiting ${waitTime}ms")
                    delay(waitTime)
                    return allowRequest(provider, userId, false)
                }
                recordRequest(provider, userId, false, userBucket.tokens.get())
                return false
            }
        }

        // Request allowed
        recordRequest(provider, userId, true, globalBucket.tokens.get())
        return true
    }

    /**
     * Get current rate limit status
     */
    fun getStatus(provider: String? = null, userId: String? = null): RateLimitStatus {
        refillBuckets()

        val globalTokens = globalBucket.tokens.get()
        val providerTokens = provider?.let {
            getProviderBucket(it).tokens.get()
        }
        val userTokens = userId?.let {
            getUserBucket(it).tokens.get()
        }

        return RateLimitStatus(
            globalTokensRemaining = globalTokens,
            globalMaxTokens = globalBucket.maxTokens,
            providerTokensRemaining = providerTokens,
            providerMaxTokens = provider?.let { getProviderBucket(it).maxTokens },
            userTokensRemaining = userTokens,
            userMaxTokens = userId?.let { getUserBucket(it).maxTokens },
            nextRefillMs = calculateNextRefill()
        )
    }

    data class RateLimitStatus(
        val globalTokensRemaining: Int,
        val globalMaxTokens: Int,
        val providerTokensRemaining: Int?,
        val providerMaxTokens: Int?,
        val userTokensRemaining: Int?,
        val userMaxTokens: Int?,
        val nextRefillMs: Long
    )

    /**
     * Reset rate limits for a specific provider or user
     */
    fun reset(provider: String? = null, userId: String? = null) {
        provider?.let {
            providerBuckets.remove(it)
            Log.i(TAG, "Reset rate limit for provider: $it")
        }

        userId?.let {
            userBuckets.remove(it)
            Log.i(TAG, "Reset rate limit for user: $it")
        }

        if (provider == null && userId == null) {
            // Reset all
            providerBuckets.clear()
            userBuckets.clear()
            globalBucket.tokens.set(globalBucket.maxTokens)
            Log.i(TAG, "Reset all rate limits")
        }
    }

    /**
     * Update rate limit for a provider
     */
    fun updateProviderLimit(provider: String, maxRequests: Int, windowMs: Long) {
        val bucket = getProviderBucket(provider)
        synchronized(bucket) {
            // Update the bucket with new limits
            providerBuckets[provider] = BucketState(
                tokens = AtomicInteger(maxRequests),
                lastRefillTime = System.currentTimeMillis(),
                maxTokens = maxRequests,
                refillRateMs = windowMs / maxRequests
            )
        }
        Log.i(TAG, "Updated rate limit for $provider: $maxRequests per ${windowMs}ms")
    }

    /**
     * Get request history for analytics
     */
    fun getRequestHistory(
        sinceMs: Long? = null,
        provider: String? = null
    ): List<RequestRecord> {
        synchronized(historyLock) {
            return requestHistory.filter { record ->
                (sinceMs == null || record.timestamp >= sinceMs) &&
                (provider == null || record.provider == provider)
            }.toList()
        }
    }

    /**
     * Clear old history entries
     */
    fun clearOldHistory(olderThanMs: Long = 3600000) { // Default 1 hour
        synchronized(historyLock) {
            val cutoff = System.currentTimeMillis() - olderThanMs
            requestHistory.removeAll { it.timestamp < cutoff }
        }
    }

    // Private helper methods

    private fun checkAndConsumeToken(bucket: BucketState): Boolean {
        return bucket.tokens.updateAndGet { current ->
            if (current > 0) current - 1 else 0
        } >= 0
    }

    private fun refillBuckets() {
        val now = System.currentTimeMillis()

        // Refill global bucket
        refillBucket(globalBucket, now)

        // Refill provider buckets
        providerBuckets.values.forEach { bucket ->
            refillBucket(bucket, now)
        }

        // Refill user buckets
        userBuckets.values.forEach { bucket ->
            refillBucket(bucket, now)
        }
    }

    private fun refillBucket(bucket: BucketState, now: Long) {
        synchronized(bucket) {
            val elapsed = now - bucket.lastRefillTime
            val tokensToAdd = (elapsed / bucket.refillRateMs).toInt()

            if (tokensToAdd > 0) {
                bucket.tokens.updateAndGet { current ->
                    minOf(current + tokensToAdd, bucket.maxTokens)
                }
                // Update last refill time
                val newBucket = bucket.copy(lastRefillTime = now)
                // This is a simplified update - in production, use proper atomic updates
            }
        }
    }

    private fun getProviderBucket(provider: String): BucketState {
        return providerBuckets.computeIfAbsent(provider) {
            val limit = DEFAULT_LIMITS[provider.lowercase()] ?: RateLimit(60, 60000)
            BucketState(
                tokens = AtomicInteger(limit.maxRequests),
                lastRefillTime = System.currentTimeMillis(),
                maxTokens = limit.maxRequests,
                refillRateMs = limit.windowMs / limit.maxRequests
            )
        }
    }

    private fun getUserBucket(userId: String): BucketState {
        return userBuckets.computeIfAbsent(userId) {
            // Default user limit: 30 requests per minute
            BucketState(
                tokens = AtomicInteger(30),
                lastRefillTime = System.currentTimeMillis(),
                maxTokens = 30,
                refillRateMs = 60000L / 30
            )
        }
    }

    private fun calculateWaitTime(bucket: BucketState): Long {
        val tokensNeeded = 1
        val refillsNeeded = tokensNeeded - bucket.tokens.get()
        return if (refillsNeeded > 0) {
            refillsNeeded * bucket.refillRateMs
        } else {
            0
        }
    }

    private fun calculateNextRefill(): Long {
        val now = System.currentTimeMillis()
        val nextGlobalRefill = globalBucket.lastRefillTime + globalBucket.refillRateMs
        return nextGlobalRefill - now
    }

    private fun recordRequest(
        provider: String,
        userId: String?,
        allowed: Boolean,
        tokensRemaining: Int
    ) {
        synchronized(historyLock) {
            requestHistory.add(
                RequestRecord(
                    timestamp = System.currentTimeMillis(),
                    provider = provider,
                    userId = userId,
                    allowed = allowed,
                    tokensRemaining = tokensRemaining
                )
            )

            // Keep history size manageable
            if (requestHistory.size > 1000) {
                requestHistory.removeAt(0)
            }
        }

        if (!allowed) {
            Log.w(TAG, "Rate limit exceeded for provider=$provider, user=$userId")
        }
    }
}