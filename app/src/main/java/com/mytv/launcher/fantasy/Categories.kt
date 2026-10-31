package com.mytv.launcher.fantasy

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** One player's numbers in one game (or added up over several). */
data class StatLine(
    val pts: Int = 0,
    val reb: Int = 0,
    val ast: Int = 0,
    val stl: Int = 0,
    val blk: Int = 0,
    val tov: Int = 0,
    val fgm: Int = 0,
    val fga: Int = 0,
    val ftm: Int = 0,
    val fta: Int = 0,
    val tpm: Int = 0,
    val tpa: Int = 0,
    /** Games with 10+ in two of points, rebounds, assists, steals, blocks. */
    val doubles: Int = 0,
) {
    operator fun plus(o: StatLine) = StatLine(
        pts + o.pts, reb + o.reb, ast + o.ast, stl + o.stl, blk + o.blk, tov + o.tov,
        fgm + o.fgm, fga + o.fga, ftm + o.ftm, fta + o.fta, tpm + o.tpm, tpa + o.tpa, doubles + o.doubles,
    )

    /** "24 PTS · 8 REB · 6 AST · 2 STL · 3 3PM · 9/17 FG". */
    fun summary(): String = listOfNotNull(
        "$pts PTS", "$reb REB", "$ast AST",
        stl.takeIf { it > 0 }?.let { "$it STL" }, blk.takeIf { it > 0 }?.let { "$it BLK" },
        tpm.takeIf { it > 0 }?.let { "$it 3PM" }, tov.takeIf { it > 0 }?.let { "$it TO" },
        "$fgm/$fga FG",
    ).joinToString(" · ")

    companion object {
        /** One game's line; the double-double counts once if it's there. */
        fun game(pts: Int, reb: Int, ast: Int, stl: Int, blk: Int, tov: Int, fgm: Int, fga: Int, ftm: Int, fta: Int, tpm: Int, tpa: Int) =
            StatLine(
                pts, reb, ast, stl, blk, tov, fgm, fga, ftm, fta, tpm, tpa,
                doubles = if (listOf(pts, reb, ast, stl, blk).count { it >= 10 } >= 2) 1 else 0,
            )
    }
}

/** A head-to-head category, as the league names it ("PTS", "FG%", "2D"...). */
enum class Category(val code: String, val label: String, val lowerWins: Boolean = false) {
    PTS("PTS", "Points"),
    REB("REB", "Rebounds"),
    AST("AST", "Assists"),
    ST("ST", "Steals"),
    BLK("BLK", "Blocks"),
    TPM("3PTM", "3-pointers"),
    TO("TO", "Turnovers", lowerWins = true),
    DD("2D", "Double-doubles"),
    FG("FG%", "FG%"),
    FT("FT%", "FT%"),
    TP("3PT%", "3PT%"),
    ;

    val isPercent get() = this == FG || this == FT || this == TP

    /** The team's value: a count, or made/attempted (null with no attempts). */
    fun value(s: StatLine): Double? = when (this) {
        PTS -> s.pts.toDouble()
        REB -> s.reb.toDouble()
        AST -> s.ast.toDouble()
        ST -> s.stl.toDouble()
        BLK -> s.blk.toDouble()
        TPM -> s.tpm.toDouble()
        TO -> s.tov.toDouble()
        DD -> s.doubles.toDouble()
        FG -> if (s.fga == 0) null else s.fgm.toDouble() / s.fga
        FT -> if (s.fta == 0) null else s.ftm.toDouble() / s.fta
        TP -> if (s.tpa == 0) null else s.tpm.toDouble() / s.tpa
    }

    fun display(s: StatLine): String = value(s)?.let { v ->
        if (isPercent) String.format(java.util.Locale.US, "%.3f", v).removePrefix("0") else v.toInt().toString()
    } ?: "–"

    companion object {
        fun byCode(code: String): Category? = entries.firstOrNull { it.code.equals(code.trim(), ignoreCase = true) }
    }
}

enum class Outcome { WIN, LOSS, TIE }

data class CategoryResult(val category: Category, val mine: String, val theirs: String, val outcome: Outcome)

/** "4-6-1": categories won, lost, tied. */
data class Record(val wins: Int, val losses: Int, val ties: Int) {
    override fun toString() = "$wins-$losses-$ties"
}

object Scoring {
    /** Category by category, the way Fantrax scores a head-to-head categories matchup. */
    fun compare(mine: StatLine, theirs: StatLine, categories: List<Category>): List<CategoryResult> = categories.map { c ->
        val a = c.value(mine)
        val b = c.value(theirs)
        val outcome = when {
            a == null && b == null -> Outcome.TIE
            a == null -> Outcome.LOSS
            b == null -> Outcome.WIN
            kotlin.math.abs(a - b) < 1e-9 -> Outcome.TIE
            (a > b) != c.lowerWins -> Outcome.WIN
            else -> Outcome.LOSS
        }
        CategoryResult(c, c.display(mine), c.display(theirs), outcome)
    }

    fun record(results: List<CategoryResult>) = Record(
        results.count { it.outcome == Outcome.WIN },
        results.count { it.outcome == Outcome.LOSS },
        results.count { it.outcome == Outcome.TIE },
    )
}

/** ESPN's box score: each player's line, keyed by [nameKey] and team. */
object BoxScore {

    data class Player(val name: String, val team: String, val line: StatLine)

    fun parse(json: String): List<Player> {
        val out = mutableListOf<Player>()
        val teams = JSONObject(json).optJSONObject("boxscore")?.optJSONArray("players") ?: return out
        for (t in 0 until teams.length()) {
            val team = teams.optJSONObject(t) ?: continue
            val abbr = team.optJSONObject("team")?.optString("abbreviation").orEmpty()
            val groups = team.optJSONArray("statistics") ?: continue
            for (g in 0 until groups.length()) {
                val group = groups.optJSONObject(g) ?: continue
                val labels = group.optJSONArray("labels").strings().map { it.uppercase() }
                val athletes = group.optJSONArray("athletes") ?: continue
                for (a in 0 until athletes.length()) {
                    val entry = athletes.optJSONObject(a) ?: continue
                    val name = entry.optJSONObject("athlete")?.optString("displayName").orEmpty()
                    val stats = entry.optJSONArray("stats").strings()
                    if (name.isBlank() || stats.isEmpty()) continue
                    fun stat(label: String) = labels.indexOf(label).takeIf { it >= 0 }?.let { stats.getOrNull(it) }
                    fun count(label: String) = stat(label)?.toIntOrNull() ?: 0
                    fun pair(label: String): Pair<Int, Int> = stat(label)?.split('-')?.takeIf { it.size == 2 }
                        ?.let { (it[0].toIntOrNull() ?: 0) to (it[1].toIntOrNull() ?: 0) } ?: (0 to 0)
                    val fg = pair("FG")
                    val ft = pair("FT")
                    val tp = pair("3PT")
                    out += Player(
                        name, abbr,
                        StatLine.game(
                            count("PTS"), count("REB"), count("AST"), count("STL"), count("BLK"), count("TO"),
                            fg.first, fg.second, ft.first, ft.second, tp.first, tp.second,
                        ),
                    )
                }
            }
        }
        return out
    }

    /** "Luka Dončić Jr." and "Doncic, Luka" both -> "lukadoncic". */
    fun nameKey(name: String): String {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        val ordered = plain.split(',').map { it.trim() }.let { if (it.size == 2) "${it[1]} ${it[0]}" else plain }
        return ordered.lowercase().split(Regex("[^a-z]+"))
            .filter { it.isNotEmpty() && it !in setOf("jr", "sr", "ii", "iii", "iv") }
            .joinToString("")
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
}
