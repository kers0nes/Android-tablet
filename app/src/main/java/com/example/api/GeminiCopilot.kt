package com.example.api

import android.util.Log
import com.example.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object GeminiCopilot {
    private const val TAG = "GeminiCopilot"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getTerminalAssistance(prompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext "Error: Gemini API Key is missing. Please set GEMINI_API_KEY in the AI Studio environment or Secrets Panel."
        }

        val url = "$BASE_URL?key=$apiKey"

        val systemInstruction = "You are 'copilot', a super smart Terminal Assistant built into the Termux Android app. " +
                "Help the user formulate Linux/Android shell commands, diagnose process states, or write shell scripts. " +
                "Provide direct, concise terminal-friendly replies. Use markdown monospace formatting single-backticks for arguments " +
                "and multi-line codeblocks with 'bash' syntax for full commands so the user can easily copy them. " +
                "Always keep your responses highly informative yet short."

        // Construct request payload securely using org.json
        val requestJson = JSONObject().apply {
            val contentArray = JSONArray().apply {
                val userContent = JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                }
                put(userContent)
            }
            put("contents", contentArray)

            val systemInstructionJson = JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", systemInstruction)
                    })
                })
            }
            put("systemInstruction", systemInstructionJson)

            val generationConfig = JSONObject().apply {
                put("temperature", 0.3) // Lower temperature for accurate command suggestion
            }
            put("generationConfig", generationConfig)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = requestJson.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string()
                if (!response.isSuccessful) {
                    val errMsg = JSONObject(bodyString ?: "{}")
                        .optJSONObject("error")
                        ?.optString("message") ?: "HTTP error ${response.code}"
                    return@withContext "Error calling Gemini API: $errMsg"
                }

                if (bodyString.isNullOrEmpty()) {
                    return@withContext "Error: Empty response body returned."
                }

                val responseJson = JSONObject(bodyString)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val contentObj = candidate.optJSONObject("content")
                    if (contentObj != null) {
                        val parts = contentObj.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            return@withContext parts.getJSONObject(0).optString("text", "No speech generated.")
                        }
                    }
                }
                return@withContext "Error: Parsing failed, no content candidates available."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed", e)
            return@withContext "Network Error: ${e.localizedMessage ?: "Unknown connection failure"}"
        }
    }
}
