package dev.synople.glassassistant.services.ai

import kotlinx.coroutines.flow.Flow

/**
 * Extended AI Provider interface with streaming support
 * Providers can implement this to support real-time response streaming
 */
interface StreamingAIProvider : AIProvider {

    /**
     * Query the AI provider with streaming support
     * @param request The AI request with image/audio and prompt
     * @param apiKey The API key for authentication
     * @param streamingCallback Callback for real-time updates
     * @return Flow of text chunks as they arrive
     */
    suspend fun queryStream(
        request: AIProvider.AIRequest,
        apiKey: String,
        streamingCallback: StreamingCallback
    ): Flow<String>

    /**
     * Check if streaming is supported by this provider
     * @return True if streaming is supported, false otherwise
     */
    fun isStreamingSupported(): Boolean = true

    /**
     * Get streaming configuration for this provider
     * @return Configuration specific to streaming
     */
    fun getStreamingConfig(): StreamingConfig = StreamingConfig()
}

/**
 * Configuration for streaming behavior
 */
data class StreamingConfig(
    val chunkSize: Int = 256,              // Preferred chunk size in characters
    val bufferSize: Int = 4096,            // Buffer size for stream reading
    val timeoutMs: Long = 30000,           // Stream timeout in milliseconds
    val reconnectAttempts: Int = 3,        // Number of reconnect attempts on failure
    val reconnectDelayMs: Long = 1000,     // Delay between reconnect attempts
    val supportsSentinel: Boolean = false,  // Whether provider sends end-of-stream sentinel
    val sentinelToken: String? = null       // End-of-stream sentinel if supported
)