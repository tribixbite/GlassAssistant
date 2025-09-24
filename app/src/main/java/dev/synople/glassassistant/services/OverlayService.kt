package dev.synople.glassassistant.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.synople.glassassistant.R

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val OVERLAY_WIDTH = 600
        private const val OVERLAY_HEIGHT = 600

        // Display brightness levels (simulated)
        const val BRIGHTNESS_MIN = 30    // 30 nits equivalent
        const val BRIGHTNESS_MAX = 5000  // 5000 nits equivalent
        const val BRIGHTNESS_DEFAULT = 1000

        // Refresh rate target
        const val REFRESH_RATE_TARGET = 90f // 90Hz

        // Actions
        const val ACTION_SHOW_TEXT = "show_text"
        const val ACTION_SHOW_NAVIGATION = "show_navigation"
        const val ACTION_SHOW_TRANSLATION = "show_translation"
        const val ACTION_SHOW_NOTIFICATION = "show_notification"
        const val ACTION_HIDE = "hide"
        const val ACTION_UPDATE_BRIGHTNESS = "update_brightness"

        // Extras
        const val EXTRA_TEXT = "text"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_BRIGHTNESS = "brightness"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_POSITION = "position"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isOverlayShown = false

    // Different overlay containers
    private var textContainer: FrameLayout? = null
    private var navigationContainer: FrameLayout? = null
    private var translationContainer: FrameLayout? = null
    private var notificationContainer: FrameLayout? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIntent(it) }
        return START_STICKY
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_SHOW_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                val duration = intent.getLongExtra(EXTRA_DURATION, 3000)
                showTextOverlay(text, duration)
            }
            ACTION_SHOW_NAVIGATION -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                val subtitle = intent.getStringExtra(EXTRA_SUBTITLE) ?: ""
                showNavigationOverlay(title, subtitle)
            }
            ACTION_SHOW_TRANSLATION -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                val subtitle = intent.getStringExtra(EXTRA_SUBTITLE) ?: ""
                showTranslationOverlay(text, subtitle)
            }
            ACTION_SHOW_NOTIFICATION -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                showNotificationOverlay(title, text)
            }
            ACTION_HIDE -> {
                hideOverlay()
            }
            ACTION_UPDATE_BRIGHTNESS -> {
                val brightness = intent.getIntExtra(EXTRA_BRIGHTNESS, BRIGHTNESS_DEFAULT)
                updateBrightness(brightness)
            }
        }
    }

    private fun setupOverlay() {
        // Create overlay layout
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_display, null)

        // Initialize containers
        textContainer = overlayView?.findViewById(R.id.textContainer)
        navigationContainer = overlayView?.findViewById(R.id.navigationContainer)
        translationContainer = overlayView?.findViewById(R.id.translationContainer)
        notificationContainer = overlayView?.findViewById(R.id.notificationContainer)

        // Setup layout params for overlay window
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
        }

        layoutParams = WindowManager.LayoutParams(
            OVERLAY_WIDTH,
            OVERLAY_HEIGHT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 50 // Offset from edge
            y = 100 // Offset from top

            // Set refresh rate if supported
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                preferredRefreshRate = REFRESH_RATE_TARGET
            }
        }
    }

    private fun showTextOverlay(text: String, duration: Long) {
        hideAllContainers()
        textContainer?.visibility = View.VISIBLE

        val textView = textContainer?.findViewById<TextView>(R.id.overlayText)
            ?: TextView(this).also { textContainer?.addView(it) }

        textView.text = text
        textView.textSize = 24f
        textView.setTextColor(ContextCompat.getColor(this, android.R.color.white))

        showOverlay()

        // Auto-hide after duration
        if (duration > 0) {
            textView.postDelayed({
                hideOverlay()
            }, duration)
        }
    }

    private fun showNavigationOverlay(direction: String, distance: String) {
        hideAllContainers()
        navigationContainer?.visibility = View.VISIBLE

        // Update navigation UI
        navigationContainer?.findViewById<TextView>(R.id.navDirection)?.text = direction
        navigationContainer?.findViewById<TextView>(R.id.navDistance)?.text = distance

        showOverlay()
    }

    private fun showTranslationOverlay(originalText: String, translatedText: String) {
        hideAllContainers()
        translationContainer?.visibility = View.VISIBLE

        // Update translation UI
        translationContainer?.findViewById<TextView>(R.id.originalText)?.text = originalText
        translationContainer?.findViewById<TextView>(R.id.translatedText)?.text = translatedText

        showOverlay()
    }

    private fun showNotificationOverlay(title: String, message: String) {
        hideAllContainers()
        notificationContainer?.visibility = View.VISIBLE

        // Update notification UI
        notificationContainer?.findViewById<TextView>(R.id.notificationTitle)?.text = title
        notificationContainer?.findViewById<TextView>(R.id.notificationMessage)?.text = message

        showOverlay()

        // Auto-hide after 5 seconds
        notificationContainer?.postDelayed({
            hideOverlay()
        }, 5000)
    }

    private fun showOverlay() {
        if (!isOverlayShown && overlayView != null && layoutParams != null) {
            try {
                windowManager?.addView(overlayView, layoutParams)
                isOverlayShown = true
            } catch (e: Exception) {
                Log.e(TAG, "Error showing overlay", e)
            }
        }
    }

    private fun hideOverlay() {
        if (isOverlayShown && overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
                isOverlayShown = false
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding overlay", e)
            }
        }
    }

    private fun hideAllContainers() {
        textContainer?.visibility = View.GONE
        navigationContainer?.visibility = View.GONE
        translationContainer?.visibility = View.GONE
        notificationContainer?.visibility = View.GONE
    }

    private fun updateBrightness(brightness: Int) {
        layoutParams?.let { params ->
            // Simulate brightness by adjusting alpha
            val normalizedBrightness = brightness.coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX)
            val alpha = normalizedBrightness / BRIGHTNESS_MAX.toFloat()

            params.alpha = alpha

            if (isOverlayShown) {
                windowManager?.updateViewLayout(overlayView, params)
            }
        }
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}