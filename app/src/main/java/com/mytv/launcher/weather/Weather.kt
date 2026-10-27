package com.mytv.launcher.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Current weather where the TV is (located from its internet address), from Open-Meteo. No keys needed. */
object Weather {
    data class Now(val tempText: String, val icon: String, val place: String)

    private val http = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    @Volatile private var cached: Pair<Long, Now>? = null
    @Volatile private var place: Triple<Double, Double, Pair<String, Boolean>>? = null // lat, lon, (city, fahrenheit)

    /** Refreshed at most every 30 minutes. */
    suspend fun now(): Now? = withContext(Dispatchers.IO) {
        cached?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.let { return@withContext it.second }
        val (lat, lon, info) = place ?: locate()?.also { place = it } ?: return@withContext cached?.second
        val (city, fahrenheit) = info
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day" +
            if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        val body = get(url) ?: return@withContext cached?.second
        val cur = runCatching { JSONObject(body).getJSONObject("current") }.getOrNull() ?: return@withContext cached?.second
        val now = Now(
            tempText = "${Math.round(cur.optDouble("temperature_2m"))}°",
            icon = icon(cur.optInt("weather_code"), cur.optInt("is_day", 1) == 1),
            place = city,
        )
        cached = System.currentTimeMillis() to now
        now
    }

    private fun locate(): Triple<Double, Double, Pair<String, Boolean>>? {
        val o = get("https://ipapi.co/json/")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        if (!o.has("latitude")) return null
        val fahrenheit = o.optString("country_code") in setOf("US", "LR", "MM")
        return Triple(o.optDouble("latitude"), o.optDouble("longitude"), o.optString("city") to fahrenheit)
    }

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", "MyTV").build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    /** WMO weather code → emoji. */
    fun icon(code: Int, day: Boolean): String = when (code) {
        0 -> if (day) "☀️" else "🌙"
        1, 2 -> if (day) "🌤️" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "❄️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }
}
