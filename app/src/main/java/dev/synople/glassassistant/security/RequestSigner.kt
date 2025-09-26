package dev.synople.glassassistant.security

import android.content.Context
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Handles request signing and validation for API security.
 * Implements HMAC-SHA256 signing with nonce and timestamp protection.
 */
class RequestSigner(private val context: Context) {

    companion object {
        private const val TAG = "RequestSigner"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val HASH_ALGORITHM = "SHA-256"
        private const val TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        private const val REQUEST_TIMEOUT_MS = 300_000L // 5 minutes
        private const val NONCE_LENGTH = 16
        private const val SIGNATURE_HEADER = "X-Glass-Signature"
        private const val TIMESTAMP_HEADER = "X-Glass-Timestamp"
        private const val NONCE_HEADER = "X-Glass-Nonce"
        private const val VERSION_HEADER = "X-Glass-Version"
        private const val CONTENT_HASH_HEADER = "X-Glass-Content-Hash"
        private const val SIGNATURE_VERSION = "1"
    }

    private val secureRandom = SecureRandom()
    private val timestampFormat = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    data class SignedRequest(
        val signature: String,
        val timestamp: String,
        val nonce: String,
        val contentHash: String,
        val headers: Map<String, String>
    )

    data class ValidationResult(
        val isValid: Boolean,
        val error: String? = null,
        val timestamp: String? = null,
        val nonce: String? = null
    )

    /**
     * Signs an API request with HMAC-SHA256 signature
     */
    fun signRequest(
        method: String,
        url: String,
        contentType: String? = null,
        body: ByteArray? = null,
        apiKey: String? = null
    ): SignedRequest {
        try {
            val timestamp = generateTimestamp()
            val nonce = generateNonce()
            val contentHash = body?.let { hashContent(it) } ?: ""

            val stringToSign = buildStringToSign(
                method = method,
                url = url,
                contentType = contentType,
                contentHash = contentHash,
                timestamp = timestamp,
                nonce = nonce
            )

            val signature = generateSignature(stringToSign, apiKey ?: "")

            val headers = mapOf(
                SIGNATURE_HEADER to signature,
                TIMESTAMP_HEADER to timestamp,
                NONCE_HEADER to nonce,
                VERSION_HEADER to SIGNATURE_VERSION,
                CONTENT_HASH_HEADER to contentHash
            )

            Log.d(TAG, "Request signed successfully - Method: $method, URL: ${sanitizeUrl(url)}")

            return SignedRequest(
                signature = signature,
                timestamp = timestamp,
                nonce = nonce,
                contentHash = contentHash,
                headers = headers
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error signing request", e)
            throw SecurityException("Failed to sign request: ${e.message}")
        }
    }

    /**
     * Validates a signed request
     */
    fun validateRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
        apiKey: String? = null
    ): ValidationResult {
        try {
            // Extract signature components from headers
            val signature = headers[SIGNATURE_HEADER]
                ?: return ValidationResult(false, "Missing signature header")

            val timestamp = headers[TIMESTAMP_HEADER]
                ?: return ValidationResult(false, "Missing timestamp header")

            val nonce = headers[NONCE_HEADER]
                ?: return ValidationResult(false, "Missing nonce header")

            val version = headers[VERSION_HEADER]
                ?: return ValidationResult(false, "Missing version header")

            val receivedContentHash = headers[CONTENT_HASH_HEADER] ?: ""

            // Validate version
            if (version != SIGNATURE_VERSION) {
                return ValidationResult(false, "Unsupported signature version: $version")
            }

            // Validate timestamp (prevent replay attacks)
            val timestampValidation = validateTimestamp(timestamp)
            if (!timestampValidation.isValid) {
                return ValidationResult(false, timestampValidation.error)
            }

            // Validate content hash if body present
            if (body != null) {
                val computedHash = hashContent(body)
                if (computedHash != receivedContentHash) {
                    return ValidationResult(false, "Content hash mismatch")
                }
            }

            // Recreate the string to sign
            val contentType = headers["Content-Type"]
            val stringToSign = buildStringToSign(
                method = method,
                url = url,
                contentType = contentType,
                contentHash = receivedContentHash,
                timestamp = timestamp,
                nonce = nonce
            )

            // Generate expected signature
            val expectedSignature = generateSignature(stringToSign, apiKey ?: "")

            // Compare signatures using constant-time comparison
            val isValid = constantTimeEquals(signature, expectedSignature)

            if (isValid) {
                Log.d(TAG, "Request validation successful - Method: $method")
                return ValidationResult(
                    isValid = true,
                    timestamp = timestamp,
                    nonce = nonce
                )
            } else {
                Log.w(TAG, "Request validation failed - Invalid signature")
                return ValidationResult(false, "Invalid signature")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error validating request", e)
            return ValidationResult(false, "Validation error: ${e.message}")
        }
    }

    /**
     * Generates a secure timestamp
     */
    private fun generateTimestamp(): String {
        return timestampFormat.format(Date())
    }

    /**
     * Generates a secure random nonce
     */
    private fun generateNonce(): String {
        val bytes = ByteArray(NONCE_LENGTH)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Hashes request content using SHA-256
     */
    private fun hashContent(content: ByteArray): String {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        val hash = digest.digest(content)
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    /**
     * Builds the canonical string to sign
     */
    private fun buildStringToSign(
        method: String,
        url: String,
        contentType: String?,
        contentHash: String,
        timestamp: String,
        nonce: String
    ): String {
        return buildString {
            append(method.uppercase())
            append("\n")
            append(normalizeUrl(url))
            append("\n")
            append(contentType ?: "")
            append("\n")
            append(contentHash)
            append("\n")
            append(timestamp)
            append("\n")
            append(nonce)
            append("\n")
            append(SIGNATURE_VERSION)
        }
    }

    /**
     * Generates HMAC-SHA256 signature
     */
    private fun generateSignature(stringToSign: String, secretKey: String): String {
        val keySpec = SecretKeySpec(
            secretKey.toByteArray(StandardCharsets.UTF_8),
            HMAC_ALGORITHM
        )

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(keySpec)

        val signature = mac.doFinal(stringToSign.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    /**
     * Validates timestamp to prevent replay attacks
     */
    private fun validateTimestamp(timestampStr: String): ValidationResult {
        try {
            val timestamp = timestampFormat.parse(timestampStr)
                ?: return ValidationResult(false, "Invalid timestamp format")

            val currentTime = System.currentTimeMillis()
            val requestTime = timestamp.time
            val timeDiff = Math.abs(currentTime - requestTime)

            if (timeDiff > REQUEST_TIMEOUT_MS) {
                return ValidationResult(
                    false,
                    "Request timestamp too old or in future (diff: ${timeDiff}ms)"
                )
            }

            return ValidationResult(true)

        } catch (e: Exception) {
            return ValidationResult(false, "Invalid timestamp: ${e.message}")
        }
    }

    /**
     * Normalizes URL for consistent signing
     */
    private fun normalizeUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val normalizedPath = uri.path ?: "/"
            val normalizedQuery = uri.query?.let { "?$it" } ?: ""
            "${uri.scheme}://${uri.host.lowercase()}${
                if (uri.port != -1) ":${uri.port}" else ""
            }$normalizedPath$normalizedQuery"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to normalize URL: $url", e)
            url
        }
    }

    /**
     * Sanitizes URL for logging (removes sensitive query parameters)
     */
    private fun sanitizeUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val sanitizedQuery = uri.query?.let { query ->
                query.split("&").joinToString("&") { param ->
                    if (param.contains("key", ignoreCase = true) ||
                        param.contains("token", ignoreCase = true) ||
                        param.contains("secret", ignoreCase = true)) {
                        "${param.split("=")[0]}=***"
                    } else {
                        param
                    }
                }
            }?.let { "?$it" } ?: ""

            "${uri.scheme}://${uri.host}${
                if (uri.port != -1) ":${uri.port}" else ""
            }${uri.path ?: "/"}$sanitizedQuery"
        } catch (e: Exception) {
            url.replace(Regex("[?&](key|token|secret)=[^&]*"), "***")
        }
    }

    /**
     * Constant-time string comparison to prevent timing attacks
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false

        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }

        return result == 0
    }
}