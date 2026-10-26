package com.mytv.launcher.topten

import com.mytv.launcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * "Top 10 on <service>" from TMDB: what's most popular right now among
 * titles streaming on that service in the US. (Netflix publishes its own
 * Top 10 but the others don't, so we use one source for all of them.)
 */
class TopTenRepository(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    private val key = BuildConfig.TMDB_API_KEY
    val isConfigured: Boolean get() = key.isNotBlank()

    suspend fun load(): Map<TopTenService, List<TopTitle>> = coroutineScope {
        if (!isConfigured) return@coroutineScope emptyMap()
        TopTenService.entries.map { service -> async { service to forService(service) } }
            .awaitAll()
            .filter { it.second.isNotEmpty() }
            .toMap()
    }

    private suspend fun forService(service: TopTenService): List<TopTitle> = coroutineScope {
        val providers = service.tmdbProviders.joinToString("|")
        val common = "watch_region=US&with_watch_providers=$providers&with_watch_monetization_types=flatrate" +
            "&sort_by=popularity.desc&language=en-US&include_adult=false"
        // Only shows that aired something lately, so long-finished series don't crowd the list.
        val recent = LocalDate.now().minusDays(90)
        val tv = (1..2).map { page ->
            async { get("/discover/tv?$common&air_date.gte=$recent&page=$page")?.let { TmdbParser.parseDiscover(it, isMovie = false) } }
        }
        val movies = async {
            get("/discover/movie?$common&vote_count.gte=20&page=1")?.let { TmdbParser.parseDiscover(it, isMovie = true) }
        }
        TmdbParser.topTen((tv.awaitAll() + movies.await()).filterNotNull())
    }

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            // Accept either kind of TMDB credential: the short v3 "API key"
            // or the long v4 "read access token".
            val bearer = key.length > 40
            val url = "https://api.themoviedb.org/3$path" + if (bearer) "" else "&api_key=$key"
            val req = Request.Builder().url(url).apply {
                if (bearer) header("Authorization", "Bearer $key")
            }.build()
            http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull()
    }
}
