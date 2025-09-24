package dev.synople.glassassistant.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStorage(private val context: Context) {

    companion object {
        private const val TAG = "SecureStorage"
        private const val KEYSTORE_ALIAS = "GlassAssistantKeyAlias"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREFS_NAME = "encrypted_prefs"
        private const val LEGACY_PREFS_NAME = "glass_assistant_prefs"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SEPARATOR = ":"
        private const val GCM_TAG_LENGTH = 128
    }

    private val sharedPreferences: SharedPreferences by lazy {
        // For Glass (API 19), we use regular SharedPreferences with custom encryption
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveApiKey(provider: String, apiKey: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Use Android Keystore for additional encryption layer
                val encryptedKey = encryptWithKeystore(apiKey)
                sharedPreferences.edit()
                    .putString("${provider}_api_key", encryptedKey)
                    .apply()
            } else {
                // For older devices, use basic obfuscation
                val obfuscated = Base64.encodeToString(apiKey.toByteArray(), Base64.NO_WRAP)
                sharedPreferences.edit()
                    .putString("${provider}_api_key", obfuscated)
                    .apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving API key for $provider", e)
        }
    }

    fun getApiKey(provider: String): String? {
        return try {
            val stored = sharedPreferences.getString("${provider}_api_key", null)
            if (stored.isNullOrEmpty()) return null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                decryptWithKeystore(stored)
            } else {
                // Deobfuscate for older devices
                String(Base64.decode(stored, Base64.NO_WRAP))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving API key for $provider", e)
            null
        }
    }

    fun removeApiKey(provider: String) {
        sharedPreferences.edit()
            .remove("${provider}_api_key")
            .apply()
    }

    fun saveProviderSettings(provider: String, model: String?, baseUrl: String?) {
        sharedPreferences.edit().apply {
            putString("current_provider", provider)
            model?.let { putString("${provider}_model", it) }
            baseUrl?.let { putString("${provider}_base_url", it) }
            apply()
        }
    }

    fun getCurrentProvider(): String {
        return sharedPreferences.getString("current_provider", "OpenRouter") ?: "OpenRouter"
    }

    fun getProviderModel(provider: String): String? {
        return sharedPreferences.getString("${provider}_model", null)
    }

    fun getProviderBaseUrl(provider: String): String? {
        return sharedPreferences.getString("${provider}_base_url", null)
    }

    fun clearAllData() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // Clear keystore entries
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
                keyStore.load(null)
                keyStore.deleteEntry(KEYSTORE_ALIAS)
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing keystore", e)
            }
        }

        // Clear all shared preferences
        sharedPreferences.edit().clear().apply()
    }

    // Keystore encryption for API 23+
    private fun encryptWithKeystore(plainText: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return plainText
        }

        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
                generateKey()
            }

            val secretKey = keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainText.toByteArray())

            // Combine IV and encrypted data
            val ivString = Base64.encodeToString(iv, Base64.NO_WRAP)
            val encryptedString = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)

            return "$ivString$IV_SEPARATOR$encryptedString"
        } catch (e: Exception) {
            Log.e(TAG, "Error encrypting with keystore", e)
            return Base64.encodeToString(plainText.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun decryptWithKeystore(encryptedText: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return encryptedText
        }

        try {
            val parts = encryptedText.split(IV_SEPARATOR)
            if (parts.size != 2) {
                // Might be legacy non-encrypted data
                return encryptedText
            }

            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encryptedBytes = Base64.decode(parts[1], Base64.NO_WRAP)

            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            val secretKey = keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            return String(decryptedBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting with keystore", e)
            // Try to decode as base64 in case it's legacy data
            return try {
                String(Base64.decode(encryptedText, Base64.NO_WRAP))
            } catch (e2: Exception) {
                ""
            }
        }
    }

    private fun generateKey() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        try {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "Error generating keystore key", e)
        }
    }

    // Migration helper for existing unencrypted data
    fun migrateUnencryptedData() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val allEntries = legacyPrefs.all

        for ((key, value) in allEntries) {
            if (key.endsWith("_api_key") && value is String) {
                val provider = key.removeSuffix("_api_key")
                if (!sharedPreferences.contains(key)) {
                    // Migrate the key
                    saveApiKey(provider, value)
                    Log.d(TAG, "Migrated API key for $provider")
                }
            } else if (value is String) {
                sharedPreferences.edit().putString(key, value).apply()
            }
        }

        // Clear legacy prefs after migration
        if (allEntries.isNotEmpty()) {
            legacyPrefs.edit().clear().apply()
            Log.d(TAG, "Cleared legacy preferences after migration")
        }
    }
}