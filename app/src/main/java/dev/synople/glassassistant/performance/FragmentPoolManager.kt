package dev.synople.glassassistant.performance

import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.reflect.KClass

/**
 * Fragment pool manager for recycling fragments and reducing memory allocations.
 * Implements object pooling pattern for fragments to improve performance.
 */
class FragmentPoolManager private constructor() {

    companion object {
        private const val TAG = "FragmentPoolManager"
        private const val MAX_POOL_SIZE = 5
        private const val MAX_POOLS = 10

        @Volatile
        private var INSTANCE: FragmentPoolManager? = null

        fun getInstance(): FragmentPoolManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FragmentPoolManager().also { INSTANCE = it }
            }
        }
    }

    private val fragmentPools = ConcurrentHashMap<String, ConcurrentLinkedQueue<Fragment>>()
    private val fragmentFactories = ConcurrentHashMap<String, () -> Fragment>()
    private val activeFragments = ConcurrentHashMap<Fragment, String>()

    /**
     * Registers a fragment factory for pooling
     */
    fun <T : Fragment> registerFragmentFactory(
        fragmentClass: KClass<T>,
        factory: () -> T
    ) {
        val className = fragmentClass.java.name
        fragmentFactories[className] = factory
        fragmentPools[className] = ConcurrentLinkedQueue()

        Log.d(TAG, "Registered fragment factory for $className")
    }

    /**
     * Acquires a fragment from the pool or creates a new one
     */
    fun <T : Fragment> acquireFragment(fragmentClass: KClass<T>): T? {
        val className = fragmentClass.java.name
        val pool = fragmentPools[className]
        val factory = fragmentFactories[className]

        if (pool == null || factory == null) {
            Log.w(TAG, "No pool registered for fragment class: $className")
            return null
        }

        return try {
            // Try to get from pool first
            val pooledFragment = pool.poll()

            val fragment = if (pooledFragment != null && isFragmentReusable(pooledFragment)) {
                pooledFragment
            } else {
                // Create new fragment if pool is empty or fragment is not reusable
                factory.invoke()
            }

            // Track active fragment
            activeFragments[fragment] = className

            @Suppress("UNCHECKED_CAST")
            fragment as T

        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring fragment for $className", e)
            null
        }
    }

    /**
     * Returns a fragment to the pool
     */
    fun releaseFragment(fragment: Fragment) {
        val className = activeFragments.remove(fragment)

        if (className == null) {
            Log.w(TAG, "Trying to release untracked fragment: ${fragment::class.java.name}")
            return
        }

        val pool = fragmentPools[className]

        if (pool == null) {
            Log.w(TAG, "No pool found for fragment class: $className")
            return
        }

        // Clean up fragment before returning to pool
        if (prepareFragmentForReuse(fragment)) {
            if (pool.size < MAX_POOL_SIZE) {
                pool.offer(fragment)
                Log.d(TAG, "Fragment returned to pool: $className (pool size: ${pool.size})")
            } else {
                Log.d(TAG, "Pool full, discarding fragment: $className")
            }
        } else {
            Log.d(TAG, "Fragment not suitable for reuse, discarding: $className")
        }
    }

    /**
     * Prepares a fragment for reuse by cleaning up its state
     */
    private fun prepareFragmentForReuse(fragment: Fragment): Boolean {
        return try {
            // Only reuse fragments that are properly detached
            if (fragment.isAdded || fragment.activity != null) {
                return false
            }

            // Clear fragment arguments to prevent state leakage
            fragment.arguments?.clear()

            // Reset fragment state if it supports it
            if (fragment is FragmentReusable) {
                fragment.resetForReuse()
            }

            true

        } catch (e: Exception) {
            Log.w(TAG, "Error preparing fragment for reuse", e)
            false
        }
    }

    /**
     * Checks if a fragment can be reused
     */
    private fun isFragmentReusable(fragment: Fragment): Boolean {
        return try {
            // Fragment should not be added to any activity
            !fragment.isAdded &&
            fragment.activity == null &&
            fragment.view == null &&
            fragment.context == null

        } catch (e: Exception) {
            Log.w(TAG, "Error checking fragment reusability", e)
            false
        }
    }

    /**
     * Clears all pools (useful for memory pressure scenarios)
     */
    fun clearAllPools() {
        try {
            val totalCleared = fragmentPools.values.sumOf { it.size }
            fragmentPools.values.forEach { it.clear() }
            activeFragments.clear()

            Log.d(TAG, "Cleared all fragment pools, freed $totalCleared fragments")

        } catch (e: Exception) {
            Log.e(TAG, "Error clearing fragment pools", e)
        }
    }

    /**
     * Gets pool statistics for monitoring
     */
    fun getPoolStats(): Map<String, Int> {
        return fragmentPools.mapValues { it.value.size }
    }

    /**
     * Gets the number of active fragments
     */
    fun getActiveFragmentCount(): Int = activeFragments.size

    /**
     * Smart fragment replacement that uses pooling
     */
    fun replaceFragment(
        fragmentManager: FragmentManager,
        containerId: Int,
        newFragmentClass: KClass<out Fragment>,
        tag: String? = null,
        addToBackStack: Boolean = false
    ) {
        try {
            val transaction = fragmentManager.beginTransaction()

            // Get current fragment to potentially release it
            val currentFragment = fragmentManager.findFragmentById(containerId)

            // Acquire new fragment from pool
            val newFragment = acquireFragment(newFragmentClass)

            if (newFragment != null) {
                transaction.replace(containerId, newFragment, tag)

                if (addToBackStack) {
                    transaction.addToBackStack(tag)
                }

                transaction.commit()

                // Release old fragment to pool if applicable
                currentFragment?.let { releaseFragment(it) }

                Log.d(TAG, "Fragment replaced using pool: ${newFragmentClass.java.simpleName}")

            } else {
                Log.w(TAG, "Failed to acquire fragment from pool: ${newFragmentClass.java.simpleName}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during smart fragment replacement", e)
        }
    }

    /**
     * Memory pressure handler
     */
    fun onMemoryPressure(level: Int) {
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                // Trim some pools
                fragmentPools.values.forEach { pool ->
                    while (pool.size > MAX_POOL_SIZE / 2) {
                        pool.poll()
                    }
                }
                Log.d(TAG, "Trimmed fragment pools due to moderate memory pressure")
            }

            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // Clear all pools
                clearAllPools()
                Log.d(TAG, "Cleared all fragment pools due to high memory pressure")
            }
        }
    }
}

/**
 * Interface for fragments that support reuse
 */
interface FragmentReusable {
    /**
     * Called when the fragment is being prepared for reuse from the pool
     */
    fun resetForReuse()
}