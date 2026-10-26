package com.mytv.launcher.topten

import com.mytv.launcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Minimal TMDB API client. Network and parsing both run off the main thread. */
object Tmdb {
    private val key = BuildConfig.TMDB_API_KEY
    val isConfigured: Boolean get() = key.isNotBlank()

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** GETs [path] (e.g. "/discover/tv?...") and runs [parse] on the result, all on a background thread. */
    suspend fun <T> get(path: String, parse: (String) -> T): T? = withContext(Dispatchers.IO) {
        runCatching {
            // Either kind of TMDB credential works: the short v3 "API key" or the long v4 token.
            val bearer = key.length > 40
            val sep = if ('?' in path) "&" else "?"
            val url = "https://api.themoviedb.org/3$path" + if (bearer) "" else "${sep}api_key=$key"
            val req = Request.Builder().url(url).apply { if (bearer) header("Authorization", "Bearer $key") }.build()
            http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
                ?.let(parse)
        }.getOrNull()
    }
}
