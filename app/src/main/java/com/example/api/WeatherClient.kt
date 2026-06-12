package com.example.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object WeatherClient {
    private const val TAG = "WeatherClient"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun getWeatherAscii(city: String): String = withContext(Dispatchers.IO) {
        val encodedCity = java.net.URLEncoder.encode(city.trim(), "UTF-8")
        // "0" option gives current weather without 3-day forecast, perfectly fitting on standard mobile terminal screens
        val url = if (encodedCity.isEmpty()) "https://wttr.in/?0" else "https://wttr.in/$encodedCity?0"

        val request = Request.Builder()
            .url(url)
            // CRITICAL: wttr.in checks user-agent to determine whether to output terminal ASCII/ANSI or standard HTML. 
            // Setting it to "curl" guarantees an gorgeous, raw terminal format directly in stdout.
            .header("User-Agent", "curl")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "Error: Failed to fetch weather (HTTP code ${response.code})"
                }
                val body = response.body?.string() ?: ""
                if (body.isEmpty()) {
                    return@withContext "Error: No weather content received."
                }
                
                // Strip ANSI escapes like \u001B[38;5;226m if the terminal does not support colored ANSI,
                // or keep them if we support basic parsing. Let's do a simple strip of major rich controls
                // so it prints cleanly in native compose text boxes, or keep the raw chars.
                val cleanAscii = body.replace(Regex("\u001B\\[[;\\d]*m"), "")
                cleanAscii
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get weather data", e)
            return@withContext "Failed to connect to weather service: ${e.localizedMessage ?: "timeout"}"
        }
    }
}
