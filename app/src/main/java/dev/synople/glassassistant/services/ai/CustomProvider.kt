package dev.synople.glassassistant.services.ai

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class CustomProvider(
    private var customBaseUrl: String = "http://localhost:11434/api"
) : AIProvider {

    companion object {
        private const val TAG = "CustomProvider"
    }

    override fun getProviderName(): String = "Local/Custom"

    override suspend fun getAvailableModels(): List<AIProvider.AIModel> {
        return withContext(Dispatchers.IO) {
            try {
                // Try to fetch models from Ollama endpoint
                val url = URL("$customBaseUrl/tags")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val modelsArray = json.optJSONArray("models")

                    val models = mutableListOf<AIProvider.AIModel>()
                    if (modelsArray != null) {
                        for (i in 0 until modelsArray.length()) {
                            val model = modelsArray.getJSONObject(i)
                            models.add(
                                AIProvider.AIModel(
                                    id = model.getString("name"),
                                    name = model.getString("name"),
                                    description = "Local Ollama model",
                                    contextLength = 8192,
                                    supportsVision = model.getString("name").contains("vision")
                                )
                            )
                        }
                    }

                    if (models.isEmpty()) {
                        getDefaultModels()
                    } else {
                        models
                    }
                } else {
                    getDefaultModels()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching models from custom endpoint", e)
                getDefaultModels()
            }
        }
    }

    private fun getDefaultModels(): List<AIProvider.AIModel> {
        return listOf(
            AIProvider.AIModel(
                id = "llama2",
                name = "Llama 2",
                description = "Meta's Llama 2 model",
                contextLength = 4096,
                supportsVision = false
            ),
            AIProvider.AIModel(
                id = "mistral",
                name = "Mistral",
                description = "Mistral 7B model",
                contextLength = 8192,
                supportsVision = false
            ),
            AIProvider.AIModel(
                id = "llava",
                name = "LLaVA",
                description = "Vision-enabled model",
                contextLength = 4096,
                supportsVision = true
            ),
            AIProvider.AIModel(
                id = "custom",
                name = "Custom Model",
                description = "User-defined model",
                contextLength = 8192,
                supportsVision = false
            )
        )
    }

    override suspend fun validateApiKey(apiKey: String): Boolean {
        // Custom endpoints may not require API keys
        return true
    }

    override suspend fun query(request: AIProvider.AIRequest, apiKey: String): AIProvider.AIResponse {
        return withContext(Dispatchers.IO) {
            try {
                // Support both Ollama and OpenAI-compatible endpoints
                val isOllama = customBaseUrl.contains("11434") || customBaseUrl.contains("ollama")

                if (isOllama) {
                    queryOllama(request)
                } else {
                    queryOpenAICompatible(request, apiKey)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying custom endpoint", e)
                AIProvider.AIResponse(text = "", error = e.message)
            }
        }
    }

    private suspend fun queryOllama(request: AIProvider.AIRequest): AIProvider.AIResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$customBaseUrl/generate")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")

                val requestBody = JSONObject().apply {
                    put("model", request.model ?: "llama2")
                    put("prompt", request.prompt)
                    put("stream", false)

                    // Add options
                    put("options", JSONObject().apply {
                        put("temperature", request.temperature)
                        put("num_predict", request.maxTokens)
                    })

                    // Handle images for vision models
                    request.imageFile?.let { imageFile ->
                        if (imageFile.exists()) {
                            val imageBytes = imageFile.readBytes()
                            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                            put("images", JSONArray().apply {
                                put(base64Image)
                            })
                        }
                    }
                }

                connection.outputStream.use { os ->
                    os.write(requestBody.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(response)
                    val content = jsonResponse.getString("response")

                    AIProvider.AIResponse(text = content)
                } else {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Error: $responseCode"
                    AIProvider.AIResponse(text = "", error = error)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying Ollama", e)
                AIProvider.AIResponse(text = "", error = e.message)
            }
        }
    }

    private suspend fun queryOpenAICompatible(request: AIProvider.AIRequest, apiKey: String): AIProvider.AIResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$customBaseUrl/chat/completions")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")

                if (apiKey.isNotEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer $apiKey")
                }

                val messages = JSONArray()

                // Add system prompt if provided
                request.systemPrompt?.let {
                    messages.put(JSONObject().apply {
                        put("role", "system")
                        put("content", it)
                    })
                }

                // Add user message
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put("content", request.prompt)
                })

                val requestBody = JSONObject().apply {
                    put("model", request.model ?: "custom")
                    put("messages", messages)
                    put("temperature", request.temperature)
                    put("max_tokens", request.maxTokens)
                }

                connection.outputStream.use { os ->
                    os.write(requestBody.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(response)
                    val choices = jsonResponse.getJSONArray("choices")
                    val content = choices.getJSONObject(0).getJSONObject("message").getString("content")

                    AIProvider.AIResponse(text = content)
                } else {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Error: $responseCode"
                    AIProvider.AIResponse(text = "", error = error)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying OpenAI-compatible endpoint", e)
                AIProvider.AIResponse(text = "", error = e.message)
            }
        }
    }

    override fun isAvailable(): Boolean = true

    override fun getConfiguration(): AIProvider.ProviderConfig {
        return AIProvider.ProviderConfig(
            baseUrl = customBaseUrl,
            requiresApiKey = false,
            supportsStreaming = false,
            maxFileSize = 10 * 1024 * 1024, // 10MB
            supportedImageFormats = listOf("jpg", "jpeg", "png")
        )
    }

    fun setBaseUrl(url: String) {
        customBaseUrl = url
    }
}