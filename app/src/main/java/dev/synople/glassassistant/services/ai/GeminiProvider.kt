package dev.synople.glassassistant.services.ai

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiProvider : AIProvider {

    companion object {
        private const val TAG = "GeminiProvider"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }

    override fun getProviderName(): String = "Gemini"

    override suspend fun getAvailableModels(): List<AIProvider.AIModel> {
        return listOf(
            AIProvider.AIModel(
                id = "gemini-pro-vision",
                name = "Gemini Pro Vision",
                description = "Multimodal model",
                contextLength = 32000,
                supportsVision = true
            ),
            AIProvider.AIModel(
                id = "gemini-pro",
                name = "Gemini Pro",
                description = "Text-only model",
                contextLength = 32000,
                supportsVision = false
            )
        )
    }

    override suspend fun validateApiKey(apiKey: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL/models?key=$apiKey")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"

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
                // Determine model and endpoint
                val model = request.model ?: "gemini-pro"
                val endpoint = when {
                    request.imageFile != null -> "$BASE_URL/models/gemini-pro-vision:generateContent"
                    else -> "$BASE_URL/models/$model:generateContent"
                }

                val url = URL("$endpoint?key=$apiKey")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")

                // Build request body
                val contents = JSONArray()
                val parts = JSONArray()

                // Add text part
                parts.put(JSONObject().apply {
                    put("text", request.prompt)
                })

                // Add image part if provided
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

                        parts.put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", mimeType)
                                put("data", base64Image)
                            })
                        })
                    }
                }

                contents.put(JSONObject().apply {
                    put("parts", parts)
                })

                val requestBody = JSONObject().apply {
                    put("contents", contents)

                    // Add generation config
                    put("generationConfig", JSONObject().apply {
                        put("temperature", request.temperature)
                        put("maxOutputTokens", request.maxTokens)
                    })

                    // Add safety settings (optional)
                    put("safetySettings", JSONArray().apply {
                        listOf("HARM_CATEGORY_HARASSMENT",
                               "HARM_CATEGORY_HATE_SPEECH",
                               "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                               "HARM_CATEGORY_DANGEROUS_CONTENT").forEach { category ->
                            put(JSONObject().apply {
                                put("category", category)
                                put("threshold", "BLOCK_ONLY_HIGH")
                            })
                        }
                    })
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
                        val candidates = jsonResponse.getJSONArray("candidates")
                        val content = candidates.getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")

                        // Gemini doesn't provide token usage in response
                        val usage = AIProvider.Usage(
                            promptTokens = 0,
                            completionTokens = 0,
                            totalTokens = 0,
                            estimatedCost = 0.0
                        )

                        AIProvider.AIResponse(text = content, usage = usage)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing Gemini response", e)
                        AIProvider.AIResponse(text = "", error = "Failed to parse response")
                    }
                } else {
                    Log.e(TAG, "Gemini API error: $response")
                    AIProvider.AIResponse(text = "", error = "API Error: $response")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error querying Gemini", e)
                AIProvider.AIResponse(text = "", error = e.message)
            }
        }
    }

    override fun isAvailable(): Boolean = true

    override fun getConfiguration(): AIProvider.ProviderConfig {
        return AIProvider.ProviderConfig(
            baseUrl = BASE_URL,
            requiresApiKey = true,
            supportsStreaming = true,
            maxFileSize = 20 * 1024 * 1024, // 20MB
            supportedImageFormats = listOf("jpg", "jpeg", "png", "gif", "webp")
        )
    }
}