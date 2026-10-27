package com.mytv.launcher.mine

import com.mytv.launcher.ang.AngItem
import com.mytv.launcher.ang.FollowedShow
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** New episodes of the shows you follow: out today ("Tonight" row), and a "New episode Thursday" label for the week. */
object Tonight {

    /** Shows with an episode out today (or yesterday: streaming drops land overnight). */
    fun row(followed: List<FollowedShow>, today: LocalDate): List<AngItem> =
        followed.mapNotNull { f ->
            val ep = listOfNotNull(f.lastEpisode, f.nextEpisode)
                .filter { it.date == today || it.date == today.minusDays(1) }
                .maxByOrNull { it.date } ?: return@mapNotNull null
            f.item.copy(
                detail = listOfNotNull(ep.code, ep.name.takeIf { it.isNotBlank() && !it.startsWith("Episode ") }, f.item.service)
                    .joinToString(" · "),
                badge = if (ep.date == today) "New tonight" else "New episode",
                date = ep.date,
            )
        }.distinctBy { it.key }

    /** "New episode tonight / tomorrow / Thursday" for a show with one coming this week, else null. */
    fun label(f: FollowedShow, today: LocalDate): String? {
        val d = f.nextEpisode?.date ?: return null
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, d)
        return when (days) {
            0L -> "New episode tonight"
            1L -> "New episode tomorrow"
            in 2..6 -> "New episode " + d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
            else -> null
        }
    }
}
