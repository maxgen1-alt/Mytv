package com.mytv.launcher.ang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AngGuideTest {

    private val today = LocalDate.of(2026, 10, 26)

    private fun show(
        id: Long,
        name: String,
        seasons: List<TvSeason> = emptyList(),
        type: String = "Scripted",
        genres: List<String> = listOf("Drama", "Romance"),
        service: String = "Prime Video",
        next: TvEpisode? = null,
        language: String? = "English",
        country: String? = null,
    ) = TvShow(id, name, type, language, genres, service, country, null, seasons, next)

    private fun season(n: Int, premiere: String?) = TvSeason(n, premiere?.let(LocalDate::parse), null)

    private fun build(
        curated: List<Pair<CuratedShow, TvShow>> = emptyList(),
        ratings: List<Rated> = emptyList(),
        followed: List<FollowedShow> = emptyList(),
        shows: List<AngItem> = emptyList(),
        movies: List<AngItem> = emptyList(),
        likedRecs: Map<String, Int> = emptyMap(),
        dislikedRecs: Map<String, Int> = emptyMap(),
        upcomingShows: List<AngItem> = emptyList(),
        available: (String?) -> Boolean = { WhereToWatch.servicesFor(it).isNotEmpty() },
    ) = AngGuideBuilder.build(
        today,
        AngInputs(
            curated = curated, ratings = ratings, followed = followed, shows = shows, movies = movies,
            upcomingShows = upcomingShows, likedRecs = likedRecs, dislikedRecs = dislikedRecs,
        ),
        available,
    )

    private fun cand(id: Long, title: String, service: String = "Netflix", pop: Double = 10.0, date: LocalDate? = today.minusMonths(2), movie: Boolean = false) =
        AngItem(AngDiscovery.key(id, movie), title, "", null, service, date, null, pop, id, movie, 7.0)

    @Test
    fun caughtUpShowOnlyAppearsWithNewSeason() {
        val old = show(1, "Off Campus", listOf(season(1, "2026-05-13")))
        val fresh = show(2, "Sterling Point", listOf(season(1, "2025-08-05"), season(2, "2026-10-10")))
        val g = build(curated = listOf(CuratedShow("Off Campus", Follow.NEW_SEASONS) to old, CuratedShow("Sterling Point", Follow.NEW_SEASONS) to fresh))
        assertEquals(listOf("Sterling Point"), g.watchNow.map { it.title })
        assertEquals("New season", g.watchNow.single().badge)
    }

    @Test
    fun caughtUpDateAndAlreadyWatchedHideSeasonsSheSaw() {
        val crew = show(6, "Crew Girl", listOf(season(1, "2026-09-10")), service = "Netflix")
        val spec = CuratedShow("Crew Girl", Follow.NEW_SEASONS, caughtUpAsOf = LocalDate.of(2026, 10, 26))
        assertTrue(build(curated = listOf(spec to crew)).watchNow.isEmpty())

        val sp = show(7, "Sterling Point", listOf(season(2, "2026-10-20")))
        val watched = Rated("tvmaze:7", "Sterling Point", Rating.WATCHED, today)
        assertTrue(build(curated = listOf(CuratedShow("Sterling Point", Follow.NEW_SEASONS) to sp), ratings = listOf(watched)).watchNow.isEmpty())
    }

    @Test
    fun wontWatchHidesCuratedEverywhere() {
        val sh = show(4, "Summer House", listOf(season(10, "2027-01-15")), type = "Reality", service = "Bravo")
        val g = build(
            curated = listOf(CuratedShow("Summer House", Follow.NEW_SEASONS) to sh),
            ratings = listOf(Rated("x", "Summer House", Rating.WONT_WATCH, today)),
        )
        assertTrue(g.upcoming.isEmpty())
    }

    @Test
    fun englishOnlyAndRomComsAndAnyRomanticReality() {
        assertFalse(AngShows.isHerKindOfShow(show(1, "Lyubov", language = "Russian")))
        assertFalse(AngShows.isHerKindOfShow(show(1, "Lyubov", language = "English", country = "RU")))
        assertFalse(AngShows.isHerKindOfShow(show(1, "Unknown Lang", language = null)))
        for (c in listOf("AU", "NZ", "IE")) assertFalse(AngShows.isHerKindOfShow(show(1, "Elsewhere", country = c)))
        for (c in listOf("US", "CA", "GB")) assertTrue(AngShows.isHerKindOfShow(show(1, "Here", country = c)))
        assertTrue(AngShows.isHerKindOfShow(show(1, "Rom Com", genres = listOf("Comedy", "Romance"), country = "US")))
        assertTrue(AngShows.isHerKindOfShow(show(1, "Pure Romance", genres = listOf("Romance"), country = "GB")))
        assertTrue(
            AngShows.isHerKindOfShow(show(1, "Love Shack", type = "Reality", genres = emptyList(), service = "Hulu", country = "US")),
        )
    }

    @Test
    fun kardashiansAlwaysThere() {
        val k = show(3, "The Kardashians", listOf(season(1, "2022-04-14")), type = "Reality", service = "Hulu")
        val g = build(curated = listOf(CuratedShow("The Kardashians", Follow.ALWAYS) to k))
        assertEquals(listOf("The Kardashians"), g.watchNow.map { it.title })
        assertEquals(null, g.watchNow.single().badge)
    }

    @Test
    fun upcomingFromSeasonsOrNextEpisode() {
        val sh = show(4, "Summer House", listOf(season(9, "2025-02-01"), season(10, "2027-01-15")), type = "Reality", service = "Bravo")
        val li = show(5, "Love Island USA", listOf(season(8, null)), type = "Reality", service = "Peacock",
            next = TvEpisode(8, 1, LocalDate.of(2026, 11, 3)))
        val g = build(curated = listOf(CuratedShow("Summer House", Follow.NEW_SEASONS) to sh, CuratedShow("Love Island USA", Follow.NEW_SEASONS) to li))
        assertEquals(listOf("Love Island USA", "Summer House"), g.upcoming.map { it.title })
        assertTrue(g.watchNow.isEmpty())
    }

    @Test
    fun likedShowsAreFollowedForNewSeasons() {
        val liked = Rated("tmdb:tv:50", "Liked Show", Rating.LIKE, LocalDate.of(2026, 9, 1), 50)
        val item = AngItem("tmdb:tv:50", "Liked Show", "", null, "Netflix", null, null)
        val airing = FollowedShow(item, latestSeason = 2 to LocalDate.of(2026, 10, 15), nextSeason = null)
        val announced = FollowedShow(item.copy(key = "tmdb:tv:51", title = "Other Liked"), latestSeason = 1 to LocalDate.of(2025, 1, 1), nextSeason = 2 to LocalDate.of(2027, 3, 1))
        val g = build(ratings = listOf(liked, liked.copy(key = "tmdb:tv:51", title = "Other Liked")), followed = listOf(airing, announced))
        assertEquals(listOf("Liked Show"), g.watchNow.map { it.title })
        assertEquals("Season 2 · Netflix", g.watchNow.single().detail)
        assertEquals(listOf("Other Liked"), g.upcoming.map { it.title })
    }

    @Test
    fun suggestionRowsSplitByAgeAndRespectRatingsAndApps() {
        val shows = listOf(
            cand(1, "Fresh Romance", date = today.minusMonths(3)),
            cand(2, "Old Gem", date = today.minusYears(4)),
            cand(3, "Seen It", date = today.minusYears(2)),
            cand(4, "ITVX Thing", service = "ITVX"),
            cand(5, "Flix Only", service = "Passionflix"),
        )
        val movies = listOf(cand(10, "Rom Com", movie = true), cand(11, "Hated", movie = true))
        val g = build(
            shows = shows,
            movies = movies,
            ratings = listOf(
                Rated("tmdb:tv:3", "Seen It", Rating.WATCHED, today),
                Rated("tmdb:movie:11", "Hated", Rating.DISLIKE, today),
            ),
        )
        assertEquals(listOf("Fresh Romance"), g.newShows.map { it.title })
        assertEquals(listOf("Old Gem"), g.missed.map { it.title })
        assertEquals(listOf("Rom Com"), g.movies.map { it.title })
    }

    @Test
    fun likesBoostAndDislikesSinkSimilarTitles() {
        val a = cand(1, "Popular", pop = 100.0, date = today.minusYears(3))
        val b = cand(2, "Like Her Fave", pop = 20.0, date = today.minusYears(3))
        val c = cand(3, "Like Her Hated", pop = 300.0, date = today.minusYears(3))
        val g = build(
            shows = listOf(a, b, c),
            likedRecs = mapOf(b.key to 3),
            dislikedRecs = mapOf(c.key to 2),
        )
        assertEquals(listOf("Like Her Fave", "Popular", "Like Her Hated"), g.missed.map { it.title })
    }

    @Test
    fun comingSoonAddsNewShowsOfHerKind() {
        val sh = show(4, "Summer House", listOf(season(10, "2027-01-15")), type = "Reality", service = "Bravo")
        val g = build(
            curated = listOf(CuratedShow("Summer House", Follow.NEW_SEASONS) to sh),
            upcomingShows = listOf(
                cand(20, "New Dating Show", date = today.plusDays(10)),
                cand(21, "Teen Romance Drama", service = "Prime Video", date = today.plusMonths(2)),
                cand(22, "Not On Our Apps", service = "ITVX", date = today.plusDays(5)),
                cand(23, "Nope", date = today.plusDays(20)),
                cand(24, "Already Out", date = today.minusDays(1)),
            ),
            ratings = listOf(Rated("tmdb:tv:23", "Nope", Rating.WONT_WATCH, today)),
        )
        assertEquals(listOf("New Dating Show", "Teen Romance Drama", "Summer House"), g.upcoming.map { it.title })
        assertEquals("New show · Netflix", g.upcoming.first().detail)
    }

    @Test
    fun likedSuggestionGetsHeartBadge() {
        val g = build(shows = listOf(cand(1, "Fave")), ratings = listOf(Rated("tmdb:tv:1", "Fave", Rating.LIKE, today, 1)))
        assertEquals("♥ Liked", g.newShows.single().badge)
    }

    @Test
    fun parsesTmdbResults() {
        val json = """{"results":[
            {"id":1,"name":"Rom","original_language":"en","origin_country":["US"],"genre_ids":[18],"first_air_date":"2026-10-01","poster_path":"/p.jpg","popularity":12.5,"vote_average":7.9},
            {"id":2,"name":"Toon Love","original_language":"en","origin_country":["US"],"genre_ids":[16]},
            {"id":3,"name":"Aussie Love","original_language":"en","origin_country":["AU"],"genre_ids":[10764]},
            {"id":4,"name":"Amor","original_language":"es","origin_country":["US"]},
            {"id":5,"name":"Racy","original_language":"en","adult":true},
            {"id":6,"name":"Ancient","original_language":"en","first_air_date":"2009-01-01"},
            {"id":7,"title":"A Movie","media_type":"movie","original_language":"en"}]}"""
        val items = AngDiscovery.parseResults(json, isMovie = false, serviceName = "Netflix", today = today)
        assertEquals(listOf("Rom"), items.map { it.title })
        assertEquals("tmdb:tv:1", items[0].key)
        assertEquals("New show", items[0].badge)
        assertEquals("2026 · Netflix", items[0].detail)
        assertEquals(7.9, items[0].voteAverage, 0.001)

        val movies = """{"results":[
            {"id":9,"title":"Love Again","original_language":"en","genre_ids":[10749,35],"release_date":"2023-05-05"},
            {"id":8,"title":"Scary Love","original_language":"en","genre_ids":[10749,27]},
            {"id":7,"title":"Just Comedy","original_language":"en","genre_ids":[35]}]}"""
        assertEquals(listOf("Love Again"), AngDiscovery.parseResults(movies, true, "Hulu", today).map { it.title })

        assertEquals(42, AngDiscovery.parseKeywordId("""{"results":[{"id":7,"name":"romance novel"},{"id":42,"name":"Romance"}]}""", "romance"))
        assertEquals(setOf(8, 15), AngDiscovery.parseProviders("""{"results":{"US":{"flatrate":[{"provider_id":8},{"provider_id":15}]},"GB":{"flatrate":[{"provider_id":39}]}}}"""))
        assertEquals(77L, AngDiscovery.parseSearchId("""{"results":[{"id":1,"name":"Other"},{"id":77,"name":"Off Campus"}]}""", "Off Campus", false))
    }

    @Test
    fun parsesFollowedShowSeasons() {
        val json = """{"id":50,"name":"Liked Show","poster_path":"/x.jpg","networks":[{"name":"Netflix"}],
            "seasons":[{"season_number":0,"air_date":"2020-01-01"},{"season_number":1,"air_date":"2025-03-01"},{"season_number":2,"air_date":"2026-10-15"}],
            "next_episode_to_air":{"season_number":3,"episode_number":1,"air_date":"2027-02-01"}}"""
        val f = AngDiscovery.parseFollowed(json, Rated("tmdb:tv:50", "Liked Show", Rating.LIKE, today, 50), today)
        assertEquals(2 to LocalDate.of(2026, 10, 15), f.latestSeason)
        assertEquals(3 to LocalDate.of(2027, 2, 1), f.nextSeason)
        assertEquals("Netflix", f.item.service)
    }

    @Test
    fun parsesTvMazeShapes() {
        val showJson = """
            {"id":7,"name":"Love Island","type":"Reality","language":"English","genres":["Romance"],
             "network":{"name":"ITV2","country":{"code":"GB"}},"webChannel":null,"externals":{"imdb":"tt2879786"},"image":{"medium":"http://x/p.jpg"},
             "_embedded":{"seasons":[{"number":12,"premiereDate":"2026-06-01","endDate":"2026-07-30"},
                                     {"number":13,"premiereDate":null,"endDate":null}],
                          "nextepisode":{"season":13,"number":1,"airdate":"2027-06-05"}}}
        """.trimIndent()
        val s = TvMazeParser.parseShow(org.json.JSONObject(showJson))
        assertEquals("ITV2", s.service)
        assertEquals("GB", s.country)
        assertEquals("tt2879786", s.imdbId)
        assertEquals("https://x/p.jpg", s.imageUrl)
        assertEquals(2, s.seasons.size)
        assertEquals(LocalDate.of(2027, 6, 5), s.nextEpisode?.airdate)

        val search = """[{"score":0.9,"show":{"id":1,"name":"Love Island","network":{"name":"CBS"}}},
                         {"score":0.8,"show":{"id":2,"name":"Love Island","network":{"name":"ITV2"}}}]"""
        assertEquals(2L, TvMazeParser.pickSearchResult(search, "ITV")?.id)

        val schedule = """[{"season":2,"number":1,"airdate":"2026-11-01","_embedded":{"show":{"id":9,"name":"X","webChannel":{"name":"Netflix"}}}},
                           {"season":2,"number":2,"airdate":"2026-11-02","_embedded":{"show":{"id":9,"name":"X"}}}]"""
        val p = TvMazeParser.parsePremieres(schedule)
        assertEquals(1, p.size)
        assertEquals("Netflix", p.single().show.service)
        assertFalse(p.single().show.genres.isNotEmpty())
    }
}
