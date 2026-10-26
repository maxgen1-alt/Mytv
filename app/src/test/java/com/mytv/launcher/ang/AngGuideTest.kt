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

    @Test
    fun caughtUpShowOnlyAppearsWithNewSeason() {
        val old = show(1, "Off Campus", listOf(season(1, "2026-05-13")))
        val fresh = show(2, "Sterling Point", listOf(season(1, "2025-08-05"), season(2, "2026-10-10")))
        val g = AngGuideBuilder.build(
            today,
            listOf(CuratedShow("Off Campus", Follow.NEW_SEASONS) to old, CuratedShow("Sterling Point", Follow.NEW_SEASONS) to fresh),
            emptyList(),
        )
        assertEquals(listOf("Sterling Point"), g.watchNow.map { it.title })
        assertEquals("New season", g.watchNow.single().badge)
    }

    @Test
    fun caughtUpHidesSeasonsSheAlreadySaw() {
        val crew = show(6, "Crew Girl", listOf(season(1, "2026-09-10")), service = "Netflix")
        val spec = CuratedShow("Crew Girl", Follow.NEW_SEASONS, caughtUpAsOf = LocalDate.of(2026, 10, 26))
        assertTrue(AngGuideBuilder.build(today, listOf(spec to crew), emptyList()).watchNow.isEmpty())

        // Same rule from a long-press "Mark as watched".
        val sp = show(7, "Sterling Point", listOf(season(2, "2026-10-20")))
        val g = AngGuideBuilder.build(
            today,
            listOf(CuratedShow("Sterling Point", Follow.NEW_SEASONS) to sp),
            emptyList(),
            watched = mapOf("tvmaze:7" to today),
        )
        assertTrue(g.watchNow.isEmpty())

        // A season after she caught up still shows.
        val s3 = show(7, "Sterling Point", listOf(season(2, "2026-10-20"), season(3, "2026-10-25")))
        val g2 = AngGuideBuilder.build(
            today,
            listOf(CuratedShow("Sterling Point", Follow.NEW_SEASONS, caughtUpAsOf = LocalDate.of(2026, 10, 21)) to s3),
            emptyList(),
        )
        assertEquals(listOf("Sterling Point"), g2.watchNow.map { it.title })
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
        val g = AngGuideBuilder.build(today, listOf(CuratedShow("The Kardashians", Follow.ALWAYS) to k), emptyList())
        assertEquals(listOf("The Kardashians"), g.watchNow.map { it.title })
        assertEquals(null, g.watchNow.single().badge)
    }

    @Test
    fun upcomingFromSeasonsOrNextEpisode() {
        val sh = show(4, "Summer House", listOf(season(9, "2025-02-01"), season(10, "2027-01-15")), type = "Reality", service = "Bravo")
        val li = show(5, "Love Island USA", listOf(season(8, null)), type = "Reality", service = "Peacock",
            next = TvEpisode(8, 1, LocalDate.of(2026, 11, 3)))
        val g = AngGuideBuilder.build(
            today,
            listOf(CuratedShow("Summer House", Follow.NEW_SEASONS) to sh, CuratedShow("Love Island USA", Follow.NEW_SEASONS) to li),
            emptyList(),
        )
        assertEquals(listOf("Love Island USA", "Summer House"), g.upcoming.map { it.title })
        assertTrue(g.watchNow.isEmpty())
    }

    @Test
    fun comingSoonOnlyHerKindOnAppsWeHave() {
        val premieres = listOf(
            Premiere(show(10, "New Teen Romance", service = "Netflix"), 1, today.plusDays(3)),
            Premiere(show(11, "Crime Thing", genres = listOf("Crime", "Drama"), service = "Netflix"), 1, today.plusDays(2)),
            Premiere(show(12, "ITVX Romance", service = "ITVX"), 1, today.plusDays(4)),
            Premiere(show(13, "Lyubov Show", language = "Russian", service = "Netflix"), 1, today.plusDays(5)),
            Premiere(show(14, "Steamy", genres = listOf("Romance", "Adult"), service = "Netflix"), 1, today.plusDays(6)),
            Premiere(show(15, "Already Aired", service = "Netflix"), 1, today.minusDays(1)),
        )
        val available = { s: String? -> com.mytv.launcher.ang.WhereToWatch.servicesFor(s).isNotEmpty() }
        val g = AngGuideBuilder.build(today, emptyList(), premieres, isAvailable = available)
        assertEquals(listOf("New Teen Romance"), g.upcoming.map { it.title })
    }

    @Test
    fun newShowsFromDiscoverySkipWatchedCuratedAndUnavailable() {
        fun d(key: String, title: String, service: String, pop: Double) =
            AngItem(key, title, "", null, service, null, null, pop)
        val discovered = listOf(
            d("tmdb:1", "Fresh Romance", "Netflix", 50.0),
            d("tmdb:2", "Big Rom Com", "Hulu", 90.0),
            d("tmdb:3", "Seen It", "Netflix", 70.0),
            d("tmdb:4", "Off Campus", "Prime Video", 80.0),
            d("tmdb:5", "Flix Only", "Passionflix", 99.0),
        )
        val curated = listOf(CuratedShow("Off Campus", Follow.NEW_SEASONS) to show(4, "Off Campus"))
        val available = { s: String? -> com.mytv.launcher.ang.WhereToWatch.servicesFor(s).isNotEmpty() }
        val g = AngGuideBuilder.build(
            today, curated, emptyList(),
            watched = mapOf("tmdb:3" to today),
            isAvailable = available,
            discovered = discovered,
        )
        assertEquals(listOf("Big Rom Com", "Fresh Romance"), g.newShows.map { it.title })
    }

    @Test
    fun parsesTmdbDiscovery() {
        val json = """{"results":[
            {"id":1,"name":"Rom","original_language":"en","origin_country":["US"],"genre_ids":[18],"first_air_date":"2026-10-01","poster_path":"/p.jpg","popularity":12.5},
            {"id":2,"name":"Toon Love","original_language":"en","origin_country":["US"],"genre_ids":[16]},
            {"id":3,"name":"Aussie Love","original_language":"en","origin_country":["AU"],"genre_ids":[10764]},
            {"id":4,"name":"Amor","original_language":"es","origin_country":["US"]},
            {"id":5,"name":"Racy","original_language":"en","adult":true}]}"""
        val items = AngDiscovery.parseDiscover(json, "Netflix", today)
        assertEquals(listOf("Rom"), items.map { it.title })
        assertEquals("tmdb:1", items[0].key)
        assertEquals("New show", items[0].badge)
        assertEquals("2026 · Netflix", items[0].detail)
        assertEquals(42, AngDiscovery.parseKeywordId("""{"results":[{"id":7,"name":"romance novel"},{"id":42,"name":"Romance"}]}""", "romance"))
    }

    @Test
    fun parsesTvMazeShapes() {
        val showJson = """
            {"id":7,"name":"Love Island","type":"Reality","language":"English","genres":["Romance"],
             "network":{"name":"ITV2","country":{"code":"GB"}},"webChannel":null,"image":{"medium":"http://x/p.jpg"},
             "_embedded":{"seasons":[{"number":12,"premiereDate":"2026-06-01","endDate":"2026-07-30"},
                                     {"number":13,"premiereDate":null,"endDate":null}],
                          "nextepisode":{"season":13,"number":1,"airdate":"2027-06-05"}}}
        """.trimIndent()
        val s = TvMazeParser.parseShow(org.json.JSONObject(showJson))
        assertEquals("ITV2", s.service)
        assertEquals("GB", s.country)
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
