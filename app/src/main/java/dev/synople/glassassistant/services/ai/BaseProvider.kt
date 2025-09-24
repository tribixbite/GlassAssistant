package dev.synople.glassassistant.services.ai

import android.util.Log
import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException

abstract class BaseProvider : AIProvider {

    companion object {
        private const val TAG = "BaseProvider"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        private const val CONNECTION_TIMEOUT = 30000
        private const val READ_TIMEOUT = 60000
    }

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
}