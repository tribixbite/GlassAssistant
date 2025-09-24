package dev.synople.glassassistant.services.ai

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class OpenAIProvider : AIProvider {

    companion object {
        private const val TAG = "OpenAIProvider"
        private const val BASE_URL = "https://api.openai.com/v1"
    }

    override fun getProviderName(): String = "OpenAI"

    override suspend fun getAvailableModels(): List<AIProvider.AIModel> {
        return listOf(
            AIProvider.AIModel(
                id = "gpt-4-vision-preview",
                name = "GPT-4 Vision",
                description = "GPT-4 with vision capabilities",
                contextLength = 128000,
                supportsVision = true,
                supportsAudio = false
            ),
            AIProvider.AIModel(
                id = "gpt-4-turbo-preview",
                name = "GPT-4 Turbo",
                description = "Latest GPT-4 Turbo model",
                contextLength = 128000,
                supportsVision = false,
                supportsAudio = false
            ),
            AIProvider.AIModel(
                id = "gpt-3.5-turbo",
                name = "GPT-3.5 Turbo",
                description = "Fast and efficient model",
                contextLength = 16385,
                supportsVision = false,
                supportsAudio = false
            )
        )
    }

    override suspend fun validateApiKey(apiKey: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL/models")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.responseCode == HttpURLConnection.HTTP_OK
            } catch (e: Exception) {
                Log.e(TAG, "Error validating API key", e)
                false
            }
        }
    }

    override suspend fun query(request: AIProvider.AIRequest, apiKey: String): AIProvider.AIResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL/chat/completions")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                connection.setRequestProperty("Content-Type", "application/json")

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

                    // Handle multimodal content for vision models
                    if (request.imageFile != null && request.model?.contains("vision") == true) {
                        val content = JSONArray()

                        // Add text
                        content.put(JSONObject().apply {
                            put("type", "text")
                            put("text", request.prompt)
                        })

                        // Add image
                        request.imageFile?.let { imageFile ->
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
                    } else {
                        // Text-only message
                        put("content", request.prompt)
                    }
                }

                messages.put(userMessage)

                val requestBody = JSONObject().apply {
                    put("model", request.model ?: "gpt-3.5-turbo")
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
                    try {
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
                                    request.model ?: "gpt-3.5-turbo"
                                )
                            )
                        }

                        AIProvider.AIResponse(text = content, usage = usage)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing OpenAI response", e)
                        AIProvider.AIResponse(text = "", error = "Failed to parse response")
                    }
                } else {
                    Log.e(TAG, "OpenAI API error: $response")
                    AIProvider.AIResponse(text = "", error = "API Error: $response")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error querying OpenAI", e)
                AIProvider.AIResponse(text = "", error = e.message)
            }
        }
    }

    private fun calculateCost(promptTokens: Int, completionTokens: Int, model: String): Double {
        // OpenAI pricing per 1K tokens (as of 2024)
        val costs = mapOf(
            "gpt-4-vision-preview" to Pair(0.01, 0.03),
            "gpt-4-turbo-preview" to Pair(0.01, 0.03),
            "gpt-4" to Pair(0.03, 0.06),
            "gpt-3.5-turbo" to Pair(0.0005, 0.0015),
            "gpt-3.5-turbo-16k" to Pair(0.003, 0.004)
        )

        val (promptCost, completionCost) = costs[model] ?: Pair(0.001, 0.002)
        return (promptTokens * promptCost / 1000.0) + (completionTokens * completionCost / 1000.0)
    }

    override fun isAvailable(): Boolean = true

    override fun getConfiguration(): AIProvider.ProviderConfig {
        return AIProvider.ProviderConfig(
            baseUrl = BASE_URL,
            requiresApiKey = true,
            supportsStreaming = true
        )
    }
}