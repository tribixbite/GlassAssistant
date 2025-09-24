package dev.synople.glassassistant.services.ai

/**
 * Callback interface for streaming AI responses
 * Allows real-time text updates as the AI generates content
 */
interface StreamingCallback {
    /**
     * Called when a new text chunk is received
     * @param chunk The text fragment received from the stream
     */
    fun onChunkReceived(chunk: String)

    /**
     * Called when the complete response has been received
     * @param fullResponse The complete accumulated response
     */
    fun onComplete(fullResponse: String)

    /**
     * Called when an error occurs during streaming
     * @param error The exception that occurred
     */
    fun onError(error: Exception)

    /**
     * Called when streaming starts
     */
    fun onStreamStart()

    /**
     * Called to report streaming progress
     * @param bytesReceived Number of bytes received so far
     * @param estimatedTotal Estimated total bytes (if known)
     */
    fun onProgress(bytesReceived: Long, estimatedTotal: Long = -1)
}