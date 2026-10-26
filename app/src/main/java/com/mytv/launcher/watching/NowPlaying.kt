package com.mytv.launcher.watching

/**
 * Turns what a streaming app reports as "now playing" (title / subtitle on its
 * media session) into a show + episode. Apps differ: some put the show in the
 * title and "S2:E5 The Name" in the subtitle, others the other way round.
 */
object NowPlaying {

    data class Parsed(val show: String, val season: Int?, val episode: Int?, val episodeTitle: String?)

    private val sxe = Regex("""\bS(?:eason)?\s*(\d{1,2})\s*[:·,.\- ]*\s*E(?:p(?:isode)?)?\.?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val seasonEpisode = Regex("""\bSeason\s+(\d{1,2})\D{1,6}Episode\s+(\d{1,3})\b""", RegexOption.IGNORE_CASE)

    /** Apps we never note: music, short videos, us. */
    private val skip = listOf(
        "com.mytv.launcher", "youtube", "spotify", "music", "pandora", "audible", "soundcloud", "tidal", "deezer",
        "podcast", "com.google.android.katniss", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
    )

    fun shouldTrack(packageName: String): Boolean = skip.none { packageName.lowercase().contains(it) }

    /** Too short to be a show or movie (trailers, previews, ads). Live streams report no length. */
    fun tooShort(durationMillis: Long): Boolean = durationMillis in 1 until 8 * 60_000L

    fun parse(title: String?, subtitle: String?, appName: String): Parsed? {
        val t = title?.trim()?.takeIf { it.isNotBlank() }
        val s = subtitle?.trim()?.takeIf { it.isNotBlank() && !it.equals(appName, ignoreCase = true) }
        if (t == null) return s?.let { Parsed(it, null, null, null) }

        fun match(x: String) = sxe.find(x) ?: seasonEpisode.find(x)
        fun rest(x: String, m: MatchResult) =
            x.removeRange(m.range).trim(' ', ':', '-', '·', '|', ',', '"').takeIf { it.isNotBlank() }

        // "S2:E5 The Name" in the subtitle → title is the show.
        if (s != null) match(s)?.let { m ->
            return Parsed(t, m.groupValues[1].toInt(), m.groupValues[2].toInt(), rest(s, m))
        }
        // Everything in the title: "The Show: S2 E5: The Name" (subtitle, if any, is the show).
        match(t)?.let { m ->
            val before = t.substring(0, m.range.first).trim(' ', ':', '-', '·', '|', ',')
            val after = t.substring(m.range.last + 1).trim(' ', ':', '-', '·', '|', ',', '"')
            val show = s ?: before.takeIf { it.isNotBlank() } ?: return null
            return Parsed(show, m.groupValues[1].toInt(), m.groupValues[2].toInt(), after.takeIf { it.isNotBlank() && it != show })
        }
        // Title = episode name, subtitle = show (the common Android TV layout); else it's a movie.
        return if (s != null) Parsed(s, null, null, t) else Parsed(t, null, null, null)
    }
}
