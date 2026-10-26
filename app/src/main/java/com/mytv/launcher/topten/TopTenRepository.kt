package com.mytv.launcher.topten

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate

/**
 * "Top 10 on <service>" from TMDB: brand-new and trending titles streaming on
 * that service in the US. Feeds the "Popular on Your Apps" row.
 */
class TopTenRepository {
    val isConfigured: Boolean get() = Tmdb.isConfigured

    /** Only for [services] (the ones you have installed). */
    suspend fun load(services: Set<TopTenService>): Map<TopTenService, List<TopTitle>> = coroutineScope {
        if (!isConfigured || services.isEmpty()) return@coroutineScope emptyMap()
        // What everyone's watching this week (all services), used to boost the ranking.
        val trending = (1..3).map { page ->
            async { Tmdb.get("/trending/all/week?language=en-US&page=$page", TmdbParser::parseTrending) }
        }.awaitAll().filterNotNull().flatten()
        services.map { service -> async { service to forService(service, trending) } }
            .awaitAll()
            .filter { it.second.isNotEmpty() }
            .toMap()
    }

    private suspend fun forService(service: TopTenService, trending: List<String>): List<TopTitle> = coroutineScope {
        val providers = service.tmdbProviders.joinToString("|")
        val common = "watch_region=US&with_watch_providers=$providers&with_watch_monetization_types=flatrate" +
            "&sort_by=popularity.desc&language=en-US&include_adult=false"
        val today = LocalDate.now()
        // Shows with new episodes in the last month (new seasons, weekly shows)...
        val tv = (1..2).map { page ->
            async {
                Tmdb.get("/discover/tv?$common&air_date.gte=${today.minusDays(30)}&air_date.lte=${today.plusDays(1)}&page=$page") {
                    TmdbParser.parseDiscover(it, isMovie = false)
                }
            }
        }
        // ...and movies from the last six months (originals, plus films just arrived from cinemas).
        val movies = (1..2).map { page ->
            async {
                Tmdb.get("/discover/movie?$common&primary_release_date.gte=${today.minusDays(180)}&page=$page") {
                    TmdbParser.parseDiscover(it, isMovie = true)
                }
            }
        }
        TmdbParser.topTen((tv + movies).awaitAll().filterNotNull(), trending, today)
    }
}
