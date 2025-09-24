package dev.synople.glassassistant.services

import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.synople.glassassistant.security.SecureStorage

class NotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"

        // Supported apps for notification display
        private val SUPPORTED_APPS = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b", // WhatsApp Business
            "com.facebook.orca", // Messenger
            "com.instagram.android",
            "com.google.android.apps.messaging", // Messages
            "com.google.android.gm", // Gmail
            "com.twitter.android",
            "com.snapchat.android",
            "com.telegram.messenger",
            "com.discord",
            "com.slack"
        )

        // Priority apps that always show notifications
        private val PRIORITY_APPS = setOf(
            "com.whatsapp",
            "com.facebook.orca",
            "com.google.android.apps.messaging"
        )
    }

    private lateinit var secureStorage: SecureStorage
    private var isEnabled = true
    private var filterLevel = FilterLevel.PRIORITY

    enum class FilterLevel {
        ALL,      // Show all notifications
        PRIORITY, // Show only priority app notifications
        CUSTOM    // Show custom filtered notifications
    }

    override fun onCreate() {
        super.onCreate()
        secureStorage = SecureStorage(this)
        loadSettings()
        Log.d(TAG, "NotificationListenerService created")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isEnabled) return

        val packageName = sbn.packageName
        val notification = sbn.notification

        // Check if we should process this notification
        if (!shouldProcessNotification(packageName)) return

        // Extract notification details
        val title = notification.extras.getString("android.title") ?: ""
        val text = notification.extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText = notification.extras.getCharSequence("android.bigText")?.toString()
        val subText = notification.extras.getString("android.subText")

        // Determine app name
        val appName = getAppName(packageName)

        // Format the message
        val displayText = bigText ?: text
        val displayTitle = "$appName: $title"

        // Send to overlay service
        showNotificationOverlay(displayTitle, displayText)

        // Handle specific app behaviors
        handleSpecificApp(packageName, title, displayText)

        Log.d(TAG, "Notification from $packageName: $title - $text")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Optionally hide overlay when notification is removed
        val packageName = sbn.packageName
        if (PRIORITY_APPS.contains(packageName)) {
            // You might want to hide the overlay after a delay
            Log.d(TAG, "Notification removed from $packageName")
        }
    }

    private fun shouldProcessNotification(packageName: String): Boolean {
        return when (filterLevel) {
            FilterLevel.ALL -> SUPPORTED_APPS.contains(packageName)
            FilterLevel.PRIORITY -> PRIORITY_APPS.contains(packageName)
            FilterLevel.CUSTOM -> isCustomFilterMatch(packageName)
        }
    }

    private fun isCustomFilterMatch(packageName: String): Boolean {
        // Load custom filters from secure storage
        val enabledApps = secureStorage.getProviderModel("notification_apps")
            ?.split(",")
            ?.map { it.trim() }
            ?: PRIORITY_APPS.toList()

        return enabledApps.contains(packageName)
    }

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            "com.whatsapp" -> "WhatsApp"
            "com.whatsapp.w4b" -> "WhatsApp Business"
            "com.facebook.orca" -> "Messenger"
            "com.instagram.android" -> "Instagram"
            "com.google.android.apps.messaging" -> "Messages"
            "com.google.android.gm" -> "Gmail"
            "com.twitter.android" -> "Twitter"
            "com.snapchat.android" -> "Snapchat"
            "com.telegram.messenger" -> "Telegram"
            "com.discord" -> "Discord"
            "com.slack" -> "Slack"
            else -> {
                try {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    packageManager.getApplicationLabel(appInfo).toString()
                } catch (e: Exception) {
                    packageName.substringAfterLast(".")
                }
            }
        }
    }

    private fun showNotificationOverlay(title: String, message: String) {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW_NOTIFICATION
            putExtra(OverlayService.EXTRA_TITLE, title)
            putExtra(OverlayService.EXTRA_TEXT, message)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun handleSpecificApp(packageName: String, title: String, text: String) {
        when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> {
                handleWhatsAppNotification(title, text)
            }
            "com.facebook.orca" -> {
                handleMessengerNotification(title, text)
            }
            "com.instagram.android" -> {
                handleInstagramNotification(title, text)
            }
            "com.google.android.apps.messaging" -> {
                handleSMSNotification(title, text)
            }
        }
    }

    private fun handleWhatsAppNotification(sender: String, message: String) {
        // Special handling for WhatsApp notifications
        if (message.contains("📷") || message.contains("Photo")) {
            showNotificationOverlay("WhatsApp: $sender", "📷 Photo received")
        } else if (message.contains("🎥") || message.contains("Video")) {
            showNotificationOverlay("WhatsApp: $sender", "🎥 Video received")
        } else if (message.contains("🎤") || message.contains("Voice message")) {
            showNotificationOverlay("WhatsApp: $sender", "🎤 Voice message")
        } else if (message.contains("📞") || message.contains("calling")) {
            // Handle calls differently - maybe show a persistent overlay
            showCallOverlay("WhatsApp", sender)
        }
    }

    private fun handleMessengerNotification(sender: String, message: String) {
        // Special handling for Messenger
        if (message.contains("is calling") || message.contains("missed call")) {
            showCallOverlay("Messenger", sender)
        }
    }

    private fun handleInstagramNotification(title: String, text: String) {
        // Handle Instagram DMs and notifications
        if (title.contains("Direct") || text.contains("sent you a message")) {
            showNotificationOverlay("Instagram DM", text)
        }
    }

    private fun handleSMSNotification(sender: String, message: String) {
        // Handle SMS/MMS messages
        showNotificationOverlay("SMS: $sender", message)
    }

    private fun showCallOverlay(app: String, caller: String) {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW_NOTIFICATION
            putExtra(OverlayService.EXTRA_TITLE, "📞 $app Call")
            putExtra(OverlayService.EXTRA_TEXT, "$caller is calling...")
            putExtra(OverlayService.EXTRA_DURATION, 10000L) // Show for 10 seconds
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun loadSettings() {
        // Load notification settings from secure storage
        isEnabled = secureStorage.getProviderModel("notifications_enabled") != "false"

        val filterSetting = secureStorage.getProviderModel("notification_filter") ?: "PRIORITY"
        filterLevel = try {
            FilterLevel.valueOf(filterSetting)
        } catch (e: Exception) {
            FilterLevel.PRIORITY
        }
    }

    fun updateSettings(enabled: Boolean, filter: FilterLevel) {
        isEnabled = enabled
        filterLevel = filter

        // Save to secure storage
        secureStorage.saveProviderSettings(
            "notifications",
            if (enabled) "true" else "false",
            filter.name
        )
    }
}