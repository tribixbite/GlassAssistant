package dev.synople.glassassistant.services.ai

import android.util.Base64
import android.util.Log
import dev.synople.glassassistant.services.security.CertificatePinner
import dev.synople.glassassistant.services.security.RateLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class OpenRouterProvider(
    private var customBaseUrl: String? = null
) : StreamingAIProvider {

    companion object {
        private const val TAG = "OpenRouterProvider"
        private const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        private const val MODELS_ENDPOINT = "/models"
        private const val CHAT_ENDPOINT = "/chat/completions"
    }

    private val baseUrl: String
        get() = customBaseUrl ?: DEFAULT_BASE_URL

    private val certificatePinner = CertificatePinner()
    private val rateLimiter = RateLimiter()

    init {
        // Enable certificate pinning for security
        certificatePinner.setPinningEnabled(true)
        certificatePinner.setStrictMode(false) // Allow connections if no pins configured

        // Configure rate limiting for OpenRouter
        rateLimiter.updateProviderLimit("openrouter", 100, 60000) // 100 requests per minute
    }

    /**
     * Create a secure connection with certificate pinning
     */
    private fun createSecureConnection(url: URL): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection

        // Apply certificate pinning for HTTPS connections
        if (connection is HttpsURLConnection) {
            certificatePinner.applyPinning(connection)
        }

        return connection
    }

    override fun getProviderName(): String = "OpenRouter"

    override suspend fun getAvailableModels(): List<AIProvider.AIModel> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl$MODELS_ENDPOINT")
                val connection = createSecureConnection(url)
                connection.requestMethod = "GET"
                connection.setRequestProperty("Content-Type", "application/json")

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val modelsArray = json.getJSONArray("data")

                val models = mutableListOf<AIProvider.AIModel>()
                for (i in 0 until modelsArray.length()) {
                    val model = modelsArray.getJSONObject(i)
                    models.add(
                        AIProvider.AIModel(
                            id = model.getString("id"),
                            name = model.optString("name", model.getString("id")),
                            description = model.optString("description"),
                            contextLength = model.optInt("context_length", 4096),
                            supportsVision = model.optBoolean("vision", false),
                            supportsAudio = false, // OpenRouter doesn't directly support audio
                            costPerToken = model.optJSONObject("pricing")?.optDouble("prompt", 0.0) ?: 0.0
                        )
                    )
                }
                models
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching models", e)
                // Return some popular default models
                listOf(
                    AIProvider.AIModel(
                        id = "openai/gpt-4-turbo-preview",
                        name = "GPT-4 Turbo",
                        description = "Latest GPT-4 Turbo with vision",
                        contextLength = 128000,
                        supportsVision = true
                    ),
                    AIProvider.AIModel(
                        id = "anthropic/claude-3-opus",
                        name = "Claude 3 Opus",
                        description = "Most capable Claude model",
                        contextLength = 200000,
                        supportsVision = true
                    ),
                    AIProvider.AIModel(
                        id = "google/gemini-pro-vision",
                        name = "Gemini Pro Vision",
                        description = "Google's multimodal model",
                        contextLength = 32000,
                        supportsVision = true
                    ),
                    AIProvider.AIModel(
                        id = "meta-llama/llama-3-70b-instruct",
                        name = "Llama 3 70B",
                        description = "Open source large model",
                        contextLength = 8192,
                        supportsVision = false
                    )
                )
            }
        }
    }

    override suspend fun validateApiKey(apiKey: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Try to fetch models with the API key
                val url = URL("$baseUrl$MODELS_ENDPOINT")
                val connection = createSecureConnection(url)
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.setRequestProperty("Content-Type", "application/json")

                val responseCode = connection.responseCode
                responseCode == HttpURLConnection.HTTP_OK
            } catch (e: Exception) {
                Log.e(TAG, "Error validating API key", e)
                false
            }
        }
    }

    override suspend fun query(request: AIProvider.AIRequest, apiKey: String): AIProvider.AIResponse {
        return withContext(Dispatchers.IO) {
            try {
                // Check rate limit
                if (!rateLimiter.allowRequest("openrouter", null, true)) {
                    Log.w(TAG, "Rate limit exceeded, waiting...")
                }
                val url = URL("$baseUrl$CHAT_ENDPOINT")
                val connection = createSecureConnection(url)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("HTTP-Referer", "https://glassassistant.app")
                connection.setRequestProperty("X-Title", "Glass Assistant")

                val messages = JSONArray()

                // Add system prompt if provided
                request.systemPrompt?.let {
                    messages.put(JSONObject().apply {
                        put("role", "system")
                        put("content", it)
                    })
                }

                // Build user message
                val userMessage = JSONObject().apply {
                    put("role", "user")

                    val content = JSONArray()

                    // Add text prompt
                    content.put(JSONObject().apply {
                        put("type", "text")
                        put("text", request.prompt)
                    })

                    // Add image if provided
                    request.imageFile?.let { imageFile: File ->
                        if (imageFile.exists()) {
                            val imageBytes = imageFile.readBytes()
                            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                            val mimeType = when (imageFile.extension.lowercase()) {
                                "png" -> "image/png"
                                "jpg", "jpeg" -> "image/jpeg"
                                "gif" -> "image/gif"
                                "webp" -> "image/webp"
                                else -> "image/jpeg"
                            }

                            content.put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:$mimeType;base64,$base64Image")
                                })
                            })
                        }
                    }

                    // Handle audio by transcribing it first (if needed)
                    request.audioFile?.let { audioFile ->
                        if (audioFile.exists()) {
                            // For now, add a note that audio was provided
                            // In production, you'd transcribe this first
                            content.put(JSONObject().apply {
                                put("type", "text")
                                put("text", "[Audio file provided: ${audioFile.name}]")
                            })
                        }
                    }

                    put("content", content)
                }

                messages.put(userMessage)

                val requestBody = JSONObject().apply {
                    put("model", request.model ?: "openai/gpt-4-vision-preview")
                    put("messages", messages)
                    put("temperature", request.temperature)
                    put("max_tokens", request.maxTokens)
                }

                connection.outputStream.use { os ->
                    os.write(requestBody.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Error: $responseCode"
                }

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val jsonResponse = JSONObject(response)
                    val choices = jsonResponse.getJSONArray("choices")
                    val message = choices.getJSONObject(0).getJSONObject("message")
                    val content = message.getString("content")

                    val usage = jsonResponse.optJSONObject("usage")?.let {
                        AIProvider.Usage(
                            promptTokens = it.optInt("prompt_tokens", 0),
                            completionTokens = it.optInt("completion_tokens", 0),
                            totalTokens = it.optInt("total_tokens", 0),
                            estimatedCost = calculateCost(
                                it.optInt("prompt_tokens", 0),
                                it.optInt("completion_tokens", 0),
                                request.model ?: "openai/gpt-4-vision-preview"
                            )
                        )
                    }

                    AIProvider.AIResponse(text = content, usage = usage)
                } else {
                    AIProvider.AIResponse(text = "", error = "Error: $response")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error querying OpenRouter", e)
                AIProvider.AIResponse(text = "", error = e.message)
            }
        }
    }

    override fun isAvailable(): Boolean = true

    override fun getConfiguration(): AIProvider.ProviderConfig {
        return AIProvider.ProviderConfig(
            baseUrl = baseUrl,
            requiresApiKey = true,
            supportsStreaming = true,
            maxFileSize = 20 * 1024 * 1024, // 20MB
            supportedImageFormats = listOf("jpg", "jpeg", "png", "gif", "webp"),
            supportedAudioFormats = listOf() // Audio needs transcription first
        )
    }

    private fun calculateCost(promptTokens: Int, completionTokens: Int, model: String): Double {
        // Approximate costs per 1K tokens (these should be fetched from API)
        val costs = mapOf(
            "openai/gpt-4-turbo-preview" to Pair(0.01, 0.03),
            "openai/gpt-4-vision-preview" to Pair(0.01, 0.03),
            "anthropic/claude-3-opus" to Pair(0.015, 0.075),
            "anthropic/claude-3-sonnet" to Pair(0.003, 0.015),
            "google/gemini-pro-vision" to Pair(0.00025, 0.0005),
            "meta-llama/llama-3-70b-instruct" to Pair(0.0007, 0.0009)
        )

        val (promptCost, completionCost) = costs[model] ?: Pair(0.001, 0.002)
        return (promptTokens * promptCost / 1000.0) + (completionTokens * completionCost / 1000.0)
    }

    // Streaming implementation
    override suspend fun queryStream(
        request: AIProvider.AIRequest,
        apiKey: String,
        streamingCallback: StreamingCallback
    ): Flow<String> = flow {
        withContext(Dispatchers.IO) {
            try {
                streamingCallback.onStreamStart()

                val url = URL("$baseUrl$CHAT_ENDPOINT")
                val connection = createSecureConnection(url)
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.setRequestProperty("HTTP-Referer", "https://glassassistant.dev")
                connection.setRequestProperty("X-Title", "GlassAssistant")
                connection.doOutput = true
                connection.doInput = true

                // Build request with streaming enabled
                val messages = JSONArray()
                val systemMessage = JSONObject().apply {
                    put("role", "system")
                    put("content", request.systemPrompt ?: "You are a helpful AI assistant for Google Glass.")
                }
                messages.put(systemMessage)

                val userMessage = JSONObject().apply {
                    put("role", "user")
                    val content = JSONArray()

                    // Add text prompt
                    content.put(JSONObject().apply {
                        put("type", "text")
                        put("text", request.prompt)
                    })

                    // Add image if provided
                    request.imageFile?.let { imageFile: File ->
                        if (imageFile.exists()) {
                            val imageBytes = imageFile.readBytes()
                            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                            val mimeType = when (imageFile.extension.lowercase()) {
                                "png" -> "image/png"
                                "jpg", "jpeg" -> "image/jpeg"
                                "gif" -> "image/gif"
                                "webp" -> "image/webp"
                                else -> "image/jpeg"
                            }

                            content.put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", "data:$mimeType;base64,$base64Image")
                                })
                            })
                        }
                    }

                    put("content", content)
                }
                messages.put(userMessage)

                val requestBody = JSONObject().apply {
                    put("model", request.model ?: "openai/gpt-4-vision-preview")
                    put("messages", messages)
                    put("temperature", request.temperature)
                    put("max_tokens", request.maxTokens)
                    put("stream", true)  // Enable streaming
                }

                connection.outputStream.use { os ->
                    os.write(requestBody.toString().toByteArray())
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    var line: String?
                    val fullResponse = StringBuilder()
                    var bytesReceived = 0L

                    while (reader.readLine().also { line = it } != null) {
                        val trimmedLine = line!!.trim()

                        // SSE format: data: {json}
                        if (trimmedLine.startsWith("data: ")) {
                            val data = trimmedLine.substring(6)

                            // Check for end of stream
                            if (data == "[DONE]") {
                                break
                            }

                            try {
                                val json = JSONObject(data)
                                val choices = json.optJSONArray("choices")

                                if (choices != null && choices.length() > 0) {
                                    val choice = choices.getJSONObject(0)
                                    val delta = choice.optJSONObject("delta")
                                    val content = delta?.optString("content")

                                    if (!content.isNullOrEmpty()) {
                                        fullResponse.append(content)
                                        bytesReceived += content.length

                                        // Emit chunk
                                        emit(content)
                                        streamingCallback.onChunkReceived(content)
                                        streamingCallback.onProgress(bytesReceived)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error parsing stream chunk: $data", e)
                            }
                        }
                    }

                    reader.close()
                    streamingCallback.onComplete(fullResponse.toString())

                } else {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                    throw Exception("OpenRouter streaming error: $error")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Streaming error", e)
                streamingCallback.onError(e)
                throw e
            }
        }
    }

    override fun getStreamingConfig(): StreamingConfig {
        return StreamingConfig(
            chunkSize = 256,
            bufferSize = 8192,
            timeoutMs = 60000,  // 60 seconds for streaming
            reconnectAttempts = 2,
            reconnectDelayMs = 1000,
            supportsSentinel = true,
            sentinelToken = "[DONE]"
        )
    }
}