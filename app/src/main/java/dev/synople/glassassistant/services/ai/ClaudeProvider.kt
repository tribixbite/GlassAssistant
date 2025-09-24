package dev.synople.glassassistant.services.ai

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ClaudeProvider : AIProvider {

    companion object {
        private const val TAG = "ClaudeProvider"
        private const val BASE_URL = "https://api.anthropic.com/v1"
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }

    override fun getProviderName(): String = "Claude"

    override suspend fun getAvailableModels(): List<AIProvider.AIModel> {
        return listOf(
            AIProvider.AIModel(
                id = "claude-3-opus-20240229",
                name = "Claude 3 Opus",
                description = "Most capable Claude model",
                contextLength = 200000,
                supportsVision = true
            ),
            AIProvider.AIModel(
                id = "claude-3-sonnet-20240229",
                name = "Claude 3 Sonnet",
                description = "Balanced performance",
                contextLength = 200000,
                supportsVision = true
            ),
            AIProvider.AIModel(
                id = "claude-3-haiku-20240307",
                name = "Claude 3 Haiku",
                description = "Fast and efficient",
                contextLength = 200000,
                supportsVision = true
            )
        )
    }

    override suspend fun validateApiKey(apiKey: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL/messages")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("x-api-key", apiKey)
                connection.setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
                connection.setRequestProperty("Content-Type", "application/json")

                // Minimal test message
                val requestBody = JSONObject().apply {
                    put("model", "claude-3-haiku-20240307")
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "Hi")
                        })
                    })
                    put("max_tokens", 10)
                }

                connection.outputStream.use { os ->
                    os.write(requestBody.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                responseCode == HttpURLConnection.HTTP_OK || responseCode == 401
            } catch (e: Exception) {
                Log.e(TAG, "Error validating API key", e)
                false
            }
        }
    }

    override suspend fun query(request: AIProvider.AIRequest, apiKey: String): AIProvider.AIResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL/messages")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("x-api-key", apiKey)
                connection.setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
                connection.setRequestProperty("Content-Type", "application/json")

                val messages = JSONArray()

                // Build user message with multimodal content if needed
                val userMessage = JSONObject().apply {
                    put("role", "user")

                    if (request.imageFile != null && request.imageFile.exists()) {
                        // Multimodal message
                        val content = JSONArray()

                        // Add image first (Claude prefers images before text)
                        val imageBytes = request.imageFile.readBytes()
                        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                        val mimeType = when (request.imageFile.extension.lowercase()) {
                            "png" -> "image/png"
                            "jpg", "jpeg" -> "image/jpeg"
                            "gif" -> "image/gif"
                            "webp" -> "image/webp"
                            else -> "image/jpeg"
                        }

                        content.put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", mimeType)
                                put("data", base64Image)
                            })
                        })

                        // Add text
                        content.put(JSONObject().apply {
                            put("type", "text")
                            put("text", request.prompt)
                        })

                        put("content", content)
                    } else {
                        // Text-only message
                        put("content", request.prompt)
                    }
                }

                messages.put(userMessage)

                val requestBody = JSONObject().apply {
                    put("model", request.model ?: "claude-3-haiku-20240307")
                    put("messages", messages)
                    put("max_tokens", request.maxTokens)
                    put("temperature", request.temperature)

                    // Add system prompt if provided
                    request.systemPrompt?.let {
                        put("system", it)
                    }
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
                        val content = jsonResponse.getJSONArray("content").getJSONObject(0).getString("text")

                        val usage = jsonResponse.optJSONObject("usage")?.let {
                            AIProvider.Usage(
                                promptTokens = it.optInt("input_tokens", 0),
                                completionTokens = it.optInt("output_tokens", 0),
                                totalTokens = it.optInt("input_tokens", 0) + it.optInt("output_tokens", 0),
                                estimatedCost = calculateCost(
                                    it.optInt("input_tokens", 0),
                                    it.optInt("output_tokens", 0),
                                    request.model ?: "claude-3-haiku-20240307"
                                )
                            )
                        }

                        AIProvider.AIResponse(text = content, usage = usage)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing Claude response", e)
                        AIProvider.AIResponse(text = "", error = "Failed to parse response")
                    }
                } else {
                    Log.e(TAG, "Claude API error: $response")
                    AIProvider.AIResponse(text = "", error = "API Error: $response")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error querying Claude", e)
                AIProvider.AIResponse(text = "", error = e.message)
            }
        }
    }

    private fun calculateCost(inputTokens: Int, outputTokens: Int, model: String): Double {
        // Claude pricing per 1M tokens (as of 2024)
        val costs = mapOf(
            "claude-3-opus-20240229" to Pair(15.0, 75.0),
            "claude-3-sonnet-20240229" to Pair(3.0, 15.0),
            "claude-3-haiku-20240307" to Pair(0.25, 1.25)
        )

        val (inputCost, outputCost) = costs[model] ?: Pair(0.25, 1.25)
        return (inputTokens * inputCost / 1_000_000.0) + (outputTokens * outputCost / 1_000_000.0)
    }

    override fun isAvailable(): Boolean = true

    override fun getConfiguration(): AIProvider.ProviderConfig {
        return AIProvider.ProviderConfig(
            baseUrl = BASE_URL,
            requiresApiKey = true,
            supportsStreaming = true,
            maxFileSize = 5 * 1024 * 1024, // 5MB per image
            supportedImageFormats = listOf("jpg", "jpeg", "png", "gif", "webp")
        )
    }
}