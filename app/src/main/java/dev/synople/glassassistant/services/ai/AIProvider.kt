package dev.synople.glassassistant.services.ai

import java.io.File

interface AIProvider {

    data class AIResponse(
        val text: String,
        val error: String? = null,
        val usage: Usage? = null
    )

    data class Usage(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0,
        val estimatedCost: Double = 0.0
    )

    data class AIRequest(
        val prompt: String,
        val imageFile: File? = null,
        val audioFile: File? = null,
        val model: String? = null,
        val temperature: Double = 0.7,
        val maxTokens: Int = 2000,
        val systemPrompt: String? = null
    )

    /**
     * Get provider name for display
     */
    fun getProviderName(): String

    /**
     * Get list of available models
     */
    suspend fun getAvailableModels(): List<AIModel>

    /**
     * Validate API key
     */
    suspend fun validateApiKey(apiKey: String): Boolean

    /**
     * Send a query to the AI provider
     */
    suspend fun query(request: AIRequest, apiKey: String): AIResponse

    /**
     * Check if provider is available
     */
    fun isAvailable(): Boolean

    /**
     * Get provider configuration
     */
    fun getConfiguration(): ProviderConfig

    data class AIModel(
        val id: String,
        val name: String,
        val description: String? = null,
        val contextLength: Int = 0,
        val supportsVision: Boolean = false,
        val supportsAudio: Boolean = false,
        val costPerToken: Double = 0.0
    )

    data class ProviderConfig(
        val baseUrl: String,
        val requiresApiKey: Boolean = true,
        val supportsStreaming: Boolean = false,
        val maxFileSize: Long = 10 * 1024 * 1024, // 10MB default
        val supportedImageFormats: List<String> = listOf("jpg", "png", "gif", "webp"),
        val supportedAudioFormats: List<String> = listOf("mp3", "wav", "m4a", "webm")
    )
}