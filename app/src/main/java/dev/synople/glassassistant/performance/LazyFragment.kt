package dev.synople.glassassistant.performance

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Base class for implementing lazy-loaded fragments that optimize memory usage
 * and startup time by deferring heavy operations until the fragment is actually visible.
 */
abstract class LazyFragment : Fragment() {

    private var isViewCreated = AtomicBoolean(false)
    private var isDataInitialized = AtomicBoolean(false)
    private var isPendingInitialization = AtomicBoolean(false)

    /**
     * Called to create the view. Override this instead of onCreateView for lazy fragments.
     */
    protected abstract fun onCreateLazyView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View?

    /**
     * Called when the fragment's data should be initialized.
     * This is called only when the fragment becomes visible for the first time.
     */
    protected abstract suspend fun initializeData()

    /**
     * Called to perform any cleanup when the fragment is no longer visible.
     * Override this to release resources when fragment is not visible.
     */
    protected open fun onVisibilityChanged(isVisible: Boolean) {
        // Default implementation does nothing
    }

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = onCreateLazyView(inflater, container, savedInstanceState)
        isViewCreated.set(true)

        // Initialize data if fragment is already visible
        if (isVisible && !isDataInitialized.get() && !isPendingInitialization.get()) {
            performLazyInitialization()
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set up lifecycle observer for visibility changes
        viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                onVisibilityChanged(true)
                if (!isDataInitialized.get() && !isPendingInitialization.get()) {
                    performLazyInitialization()
                }
            }

            override fun onPause(owner: LifecycleOwner) {
                onVisibilityChanged(false)
            }
        })
    }

    override fun setUserVisibleHint(isVisibleToUser: Boolean) {
        super.setUserVisibleHint(isVisibleToUser)

        if (isVisibleToUser && isViewCreated.get() &&
            !isDataInitialized.get() && !isPendingInitialization.get()) {
            performLazyInitialization()
        }

        onVisibilityChanged(isVisibleToUser)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)

        val isVisible = !hidden
        if (isVisible && isViewCreated.get() &&
            !isDataInitialized.get() && !isPendingInitialization.get()) {
            performLazyInitialization()
        }

        onVisibilityChanged(isVisible)
    }

    private fun performLazyInitialization() {
        if (isPendingInitialization.compareAndSet(false, true)) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    initializeData()
                    isDataInitialized.set(true)
                } catch (e: Exception) {
                    android.util.Log.e(
                        this@LazyFragment::class.simpleName,
                        "Error during lazy initialization",
                        e
                    )
                } finally {
                    isPendingInitialization.set(false)
                }
            }
        }
    }

    /**
     * Check if the fragment's data has been initialized
     */
    protected fun isDataInitialized(): Boolean = isDataInitialized.get()

    /**
     * Force initialization of data (useful for testing)
     */
    protected fun forceInitialization() {
        if (!isDataInitialized.get()) {
            performLazyInitialization()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isViewCreated.set(false)
        isDataInitialized.set(false)
        isPendingInitialization.set(false)
    }
}