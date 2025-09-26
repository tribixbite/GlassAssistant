package dev.synople.glassassistant.services.ai

import android.content.Context
import android.util.Log
import dev.synople.glassassistant.security.MemoryCleaner
import dev.synople.glassassistant.security.RequestSigner
import dev.synople.glassassistant.security.SecureFileManager
import dev.synople.glassassistant.security.SecureImageProcessor
import dev.synople.glassassistant.security.SecurityAuditLogger
import kotlinx.coroutines.delay
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException

abstract class BaseProvider(protected val context: Context) : AIProvider {

    companion object {
        const val TAG = "BaseProvider"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        private const val CONNECTION_TIMEOUT = 30000
        private const val READ_TIMEOUT = 60000
    }

    protected val requestSigner: RequestSigner by lazy { RequestSigner(context) }
    protected val secureFileManager: SecureFileManager by lazy { SecureFileManager.getInstance(context) }
    protected val imageProcessor: SecureImageProcessor by lazy { SecureImageProcessor(context) }
    protected val securityLogger: SecurityAuditLogger by lazy { SecurityAuditLogger.getInstance(context) }

    protected suspend fun <T> withRetry(
        maxRetries: Int = MAX_RETRIES,
        delayMs: Long = RETRY_DELAY_MS,
        operation: suspend () -> T
    ): T {
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                return operation()
            } catch (e: Exception) {
                lastException = e

                // Don't retry on certain errors
                when (e) {
                    is IllegalArgumentException -> throw e
                    is SecurityException -> throw e
                }

                // Check if it's a retryable error
                val isRetryable = when (e) {
                    is SocketTimeoutException -> true
                    is UnknownHostException -> attempt < maxRetries - 1
                    else -> {
                        val message = e.message ?: ""
                        message.contains("timeout", ignoreCase = true) ||
                        message.contains("connection", ignoreCase = true) ||
                        message.contains("502") || // Bad Gateway
                        message.contains("503") || // Service Unavailable
                        message.contains("504")    // Gateway Timeout
                    }
                }

                if (!isRetryable || attempt == maxRetries - 1) {
                    throw e
                }

                Log.w(TAG, "Request failed (attempt ${attempt + 1}/$maxRetries), retrying...", e)
                delay(delayMs * (attempt + 1)) // Exponential backoff
            }
        }

        throw lastException ?: Exception("Operation failed after $maxRetries attempts")
    }

    protected fun setupConnection(connection: HttpURLConnection) {
        connection.connectTimeout = CONNECTION_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.useCaches = false
    }

    /**
     * Applies request signing to HTTP connection
     */
    protected fun applyRequestSigning(
        connection: HttpURLConnection,
        requestBody: ByteArray? = null,
        apiKey: String? = null,
        enableSigning: Boolean = true
    ) {
        if (!enableSigning) return

        try {
            val signedRequest = requestSigner.signRequest(
                method = connection.requestMethod,
                url = connection.url.toString(),
                contentType = connection.getRequestProperty("Content-Type"),
                body = requestBody,
                apiKey = apiKey
            )

            // Apply signature headers
            signedRequest.headers.forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }

            // Add additional security headers
            connection.setRequestProperty("X-Glass-Client", "GlassAssistant/1.0")
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            connection.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")

        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply request signing, continuing without", e)
            // Don't fail the request if signing fails, but log it
        }
    }

    /**
     * Validates response signature if present
     */
    protected fun validateResponseSignature(
        connection: HttpURLConnection,
        responseBody: ByteArray?,
        apiKey: String?
    ): Boolean {
        try {
            val responseHeaders = mutableMapOf<String, String>()
            connection.headerFields.forEach { (name, values) ->
                if (name != null && values.isNotEmpty()) {
                    responseHeaders[name] = values.first()
                }
            }

            // Only validate if signature headers are present
            if (responseHeaders.containsKey("X-Glass-Signature")) {
                val validation = requestSigner.validateRequest(
                    method = "RESPONSE",
                    url = connection.url.toString(),
                    headers = responseHeaders,
                    body = responseBody,
                    apiKey = apiKey
                )

                if (!validation.isValid) {
                    Log.w(TAG, "Response signature validation failed: ${validation.error}")
                    return false
                }
            }

            return true
        } catch (e: Exception) {
            Log.w(TAG, "Error validating response signature", e)
            return false
        }
    }

    protected fun handleErrorResponse(
        responseCode: Int,
        errorBody: String?
    ): AIProvider.AIResponse {
        val errorMessage = when (responseCode) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> "Invalid API key or unauthorized access"
            HttpURLConnection.HTTP_FORBIDDEN -> "Access forbidden - check API permissions"
            HttpURLConnection.HTTP_NOT_FOUND -> "Endpoint not found - check base URL"
            429 -> "Rate limit exceeded - please try again later"
            HttpURLConnection.HTTP_INTERNAL_ERROR -> "Server error - please try again"
            HttpURLConnection.HTTP_BAD_GATEWAY -> "Gateway error - service temporarily unavailable"
            HttpURLConnection.HTTP_UNAVAILABLE -> "Service unavailable - please try again later"
            HttpURLConnection.HTTP_GATEWAY_TIMEOUT -> "Gateway timeout - request took too long"
            else -> parseErrorBody(errorBody) ?: "HTTP $responseCode: ${getStatusMessage(responseCode)}"
        }

        // Log security events for authentication and authorization errors
        when (responseCode) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> {
                securityLogger.logSecurityEvent(
                    SecurityAuditLogger.SecurityLevel.WARNING,
                    SecurityAuditLogger.SecurityEvent.API_KEY_VALIDATION_FAILURE,
                    mapOf(
                        "response_code" to responseCode,
                        "provider" to getProviderName(),
                        "error_body" to (errorBody?.take(200) ?: "")
                    )
                )
            }
            HttpURLConnection.HTTP_FORBIDDEN -> {
                securityLogger.logSecurityEvent(
                    SecurityAuditLogger.SecurityLevel.WARNING,
                    SecurityAuditLogger.SecurityEvent.UNAUTHORIZED_ACCESS_ATTEMPT,
                    mapOf(
                        "response_code" to responseCode,
                        "provider" to getProviderName()
                    )
                )
            }
            429 -> {
                securityLogger.logSecurityEvent(
                    SecurityAuditLogger.SecurityLevel.WARNING,
                    SecurityAuditLogger.SecurityEvent.RATE_LIMIT_TRIGGERED,
                    mapOf(
                        "response_code" to responseCode,
                        "provider" to getProviderName()
                    )
                )
            }
        }

        return AIProvider.AIResponse(
            text = "",
            error = errorMessage
        )
    }

    private fun parseErrorBody(errorBody: String?): String? {
        if (errorBody.isNullOrEmpty()) return null

        return try {
            // Try to extract error message from JSON
            val pattern = "\"(error|message|detail)\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            pattern.find(errorBody)?.groupValues?.get(2) ?: errorBody.take(200)
        } catch (e: Exception) {
            errorBody.take(200)
        }
    }

    private fun getStatusMessage(code: Int): String {
        return when (code) {
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> "Unknown Error"
        }
    }

    protected fun sanitizeInput(input: String): String {
        // Remove control characters except for newlines and tabs
        return input.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
    }

    protected fun validateImageFile(file: java.io.File?, maxSize: Long = 20 * 1024 * 1024): Boolean {
        if (file == null) return true
        if (!file.exists()) {
            Log.w(TAG, "Image file does not exist: ${file.path}")
            return false
        }
        if (file.length() > maxSize) {
            Log.w(TAG, "Image file too large: ${file.length()} > $maxSize")
            return false
        }

        val validExtensions = listOf("jpg", "jpeg", "png", "gif", "webp")
        val extension = file.extension.lowercase()
        if (extension !in validExtensions) {
            Log.w(TAG, "Invalid image format: $extension")
            return false
        }

        return true
    }

    /**
     * Creates a secure temporary file for processing
     */
    protected fun createSecureTempFile(
        prefix: String = "ai_temp_",
        suffix: String = ".tmp",
        sensitiveData: Boolean = true
    ) = secureFileManager.createSecureTempFile(prefix, suffix, sensitiveData)

    /**
     * Creates a secure temporary file with content
     */
    protected fun createSecureTempFileWithContent(
        content: ByteArray,
        prefix: String = "ai_temp_",
        suffix: String = ".tmp",
        sensitiveData: Boolean = true
    ) = secureFileManager.createSecureTempFileWithContent(content, prefix, suffix, sensitiveData)

    /**
     * Securely processes an image file with memory-safe operations and metadata stripping
     */
    protected fun processImageSecurely(imageFile: File?): dev.synople.glassassistant.security.SecureImageProcessor.ProcessedImage? {
        if (imageFile == null) return null

        return try {
            imageProcessor.processImageSecurely(
                imageFile,
                SecureImageProcessor.ImageProcessingOptions(
                    maxWidth = 2048,
                    maxHeight = 2048,
                    quality = 85,
                    stripMetadata = true,
                    sanitizeFileName = true
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image securely", e)
            null
        }
    }

    /**
     * Converts image to Base64 string with secure processing
     */
    protected fun imageToBase64Securely(imageFile: File?): String? {
        if (imageFile == null) return null

        return try {
            imageProcessor.imageToBase64(
                imageFile,
                SecureImageProcessor.ImageProcessingOptions(
                    maxWidth = 1024,
                    maxHeight = 1024,
                    quality = 80,
                    stripMetadata = true
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert image to Base64", e)
            null
        }
    }

    /**
     * Securely processes an audio file by copying it to secure temp location
     */
    protected fun processAudioSecurely(audioFile: File?): dev.synople.glassassistant.security.SecureFile? {
        if (audioFile == null || !audioFile.exists()) return null

        try {
            // Validate audio file size (max 25MB)
            if (audioFile.length() > 25 * 1024 * 1024) {
                Log.w(TAG, "Audio file too large: ${audioFile.length()}")
                return null
            }

            val audioData = audioFile.readBytes()
            val extension = ".${audioFile.extension}"

            return createSecureTempFileWithContent(
                content = audioData,
                prefix = "audio_",
                suffix = extension,
                sensitiveData = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process audio securely", e)
            return null
        }
    }

    /**
     * Forces cleanup of temporary files
     */
    protected fun cleanupTempFiles() {
        try {
            secureFileManager.forceCleanup()
        } catch (e: Exception) {
            Log.w(TAG, "Error during temp file cleanup", e)
        }
    }

    /**
     * Securely clears sensitive strings from memory
     */
    protected fun clearSensitiveString(sensitiveString: String?) {
        if (sensitiveString != null) {
            MemoryCleaner.clearString(sensitiveString)
        }
    }

    /**
     * Securely clears sensitive byte arrays from memory
     */
    protected fun clearSensitiveBytes(sensitiveBytes: ByteArray?) {
        if (sensitiveBytes != null) {
            MemoryCleaner.clearByteArray(sensitiveBytes)
        }
    }

    /**
     * Performs comprehensive cleanup after AI request processing
     */
    protected fun performSecurityCleanup() {
        try {
            // Clear tracked sensitive data
            MemoryCleaner.clearAllTrackedData()

            // Cleanup temporary files
            cleanupTempFiles()

            // Force garbage collection
            MemoryCleaner.forceCleanup()

            Log.d(TAG, "Security cleanup completed")
        } catch (e: Exception) {
            Log.w(TAG, "Error during security cleanup", e)
        }
    }

    /**
     * Wrapper for processing requests with automatic sensitive data cleanup
     */
    protected suspend inline fun <T> withSecureProcessing(
        sensitiveInputs: List<Any> = emptyList(),
        crossinline block: suspend () -> T
    ): T {
        return MemoryCleaner.withSensitiveData {
            // Track sensitive inputs
            sensitiveInputs.forEachIndexed { index, input ->
                track(input, "AIRequest-Input-$index")
            }

            try {
                block()
            } finally {
                // Cleanup happens automatically via AutoCloseable
                Log.d(TAG, "Secure processing completed with automatic cleanup")
            }
        }
    }

    /**
     * Logs successful API request for security audit
     */
    protected fun logSuccessfulRequest(
        provider: String,
        model: String?,
        responseTime: Long,
        requestSize: Long = 0,
        responseSize: Long = 0
    ) {
        securityLogger.logApiRequest(
            provider = provider,
            success = true,
            responseTime = responseTime,
            requestSize = requestSize,
            responseSize = responseSize
        )

        securityLogger.logSecurityEvent(
            SecurityAuditLogger.SecurityLevel.INFO,
            SecurityAuditLogger.SecurityEvent.API_KEY_VALIDATION_SUCCESS,
            mapOf(
                "provider" to provider,
                "model" to (model ?: "unknown"),
                "response_time_ms" to responseTime
            )
        )
    }

    /**
     * Logs failed API request for security audit
     */
    protected fun logFailedRequest(
        provider: String,
        error: String,
        responseTime: Long = 0,
        exception: Throwable? = null
    ) {
        securityLogger.logApiRequest(
            provider = provider,
            success = false,
            responseTime = responseTime,
            errorMessage = error
        )

        securityLogger.logSecurityEvent(
            SecurityAuditLogger.SecurityLevel.WARNING,
            SecurityAuditLogger.SecurityEvent.SECURITY_EXCEPTION,
            mapOf(
                "provider" to provider,
                "error" to error
            ),
            exception
        )
    }

    /**
     * Logs security-related operations
     */
    protected fun logSecurityOperation(
        operation: String,
        success: Boolean,
        details: Map<String, Any> = emptyMap()
    ) {
        val level = if (success) {
            SecurityAuditLogger.SecurityLevel.INFO
        } else {
            SecurityAuditLogger.SecurityLevel.WARNING
        }

        val event = when (operation.lowercase()) {
            "file_create" -> SecurityAuditLogger.SecurityEvent.SECURE_FILE_CREATED
            "file_delete" -> SecurityAuditLogger.SecurityEvent.SECURE_FILE_DELETED
            "memory_clear" -> SecurityAuditLogger.SecurityEvent.SENSITIVE_DATA_CLEARED
            "request_sign" -> if (success) {
                SecurityAuditLogger.SecurityEvent.REQUEST_SIGNING_SUCCESS
            } else {
                SecurityAuditLogger.SecurityEvent.REQUEST_SIGNING_FAILURE
            }
            else -> SecurityAuditLogger.SecurityEvent.SECURITY_EXCEPTION
        }

        securityLogger.logSecurityEvent(level, event, details.plus(
            mapOf("operation" to operation, "provider" to getProviderName())
        ))
    }
}