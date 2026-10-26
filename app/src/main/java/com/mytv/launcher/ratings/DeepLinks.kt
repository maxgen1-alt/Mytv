package com.mytv.launcher.ratings

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.mytv.launcher.ang.AngItem
import com.mytv.launcher.sports.Service
import com.mytv.launcher.topten.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Opens a title straight on its page inside the streaming app.
 *
 * Streaming apps each use their own title ids (Netflix "80057281", Apple
 * "umc.cmc…"). Wikidata keeps those ids for most titles, plus each service's
 * official link format, so: TMDB id → Wikidata item → that app's id and link
 * format → a link the app opens on the title's page. Everything is cached.
 */
object DeepLinks {
    private const val WEEK = 7 * 24 * 3600_000L
    private lateinit var prefs: SharedPreferences
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Words in Wikidata's property names for each app (e.g. "Netflix ID", "Apple TV show ID"), best first. */
    private val serviceWords = mapOf(
        Service.NETFLIX to listOf("netflix"),
        Service.PRIME_VIDEO to listOf("prime video", "amazon"),
        Service.HULU to listOf("hulu"),
        Service.DISNEY to listOf("disney+"),
        Service.APPLE_TV to listOf("apple tv"),
        Service.MAX to listOf("hbo max", "max"),
        Service.PEACOCK to listOf("peacock"),
        Service.PARAMOUNT to listOf("paramount+"),
    )

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = context.getSharedPreferences("deeplinks", Context.MODE_PRIVATE)
    }

    /** An intent that opens [item]'s page in [app] (installed as [packageName]), or null if we can't build one. */
    suspend fun intentFor(context: Context, item: AngItem, app: Service, packageName: String): Intent? = withContext(Dispatchers.IO) {
        if (!::prefs.isInitialized) return@withContext null
        val words = serviceWords[app] ?: return@withContext null
        val qid = wikidataId(item) ?: return@withContext null
        val ids = claims(qid) // property id -> value
        if (ids.isEmpty()) return@withContext null
        val props = properties(ids.keys) // property id -> (label, link format)

        // That service's id property; prefer the show / movie variant that matches.
        val kind = if (item.isMovie) listOf("movie", "film") else listOf("show", "series", "season")
        fun wordRank(label: String) = words.indexOfFirst { label.lowercase().contains(it) }
        val pick = props.entries
            .filter { (_, p) -> wordRank(p.first) >= 0 && p.second.contains("$1") }
            .sortedWith(
                compareBy<Map.Entry<String, Pair<String, String>>> { wordRank(it.value.first) }
                    .thenByDescending { e -> kind.any { e.value.first.lowercase().contains(it) } },
            )
            .firstOrNull() ?: return@withContext null
        val url = pick.value.second.replace("$1", ids.getValue(pick.key))

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Netflix's TV app wants to know a launcher sent it.
        if (packageName.contains("netflix")) intent.putExtra("source", "30")
        intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    /** TMDB id (or IMDb id) → Wikidata item, e.g. "Q12345". Cached forever. */
    private suspend fun wikidataId(item: AngItem): String? {
        val cacheKey = "q:${item.key}"
        prefs.getString(cacheKey, null)?.let { return it.ifBlank { null } }
        val tmdbId = item.tmdbId ?: item.imdbId?.let { imdb ->
            Tmdb.get("/find/$imdb?external_source=imdb_id") { body ->
                val o = JSONObject(body)
                (if (item.isMovie) o.optJSONArray("movie_results") else o.optJSONArray("tv_results"))
                    ?.optJSONObject(0)?.optLong("id")
            }
        } ?: return null
        val qid = Tmdb.get("/${if (item.isMovie) "movie" else "tv"}/$tmdbId/external_ids") {
            JSONObject(it).optString("wikidata_id")
        } ?: return null
        prefs.edit().putString(cacheKey, qid).apply()
        return qid.ifBlank { null }
    }

    /** The item's string-valued external ids: property id → value (cached a week). */
    private fun claims(qid: String): Map<String, String> {
        val cacheKey = "c:$qid"
        prefs.getString(cacheKey, null)?.let { cached ->
            val o = JSONObject(cached)
            if (System.currentTimeMillis() - o.optLong("at") < WEEK) return o.getJSONObject("ids").toStringMap()
        }
        val body = wikidata("action=wbgetentities&ids=$qid&props=claims") ?: return emptyMap()
        val claims = JSONObject(body).optJSONObject("entities")?.optJSONObject(qid)?.optJSONObject("claims") ?: return emptyMap()
        val ids = JSONObject()
        claims.keys().forEach { prop ->
            val value = claims.optJSONArray(prop)?.optJSONObject(0)?.optJSONObject("mainsnak")
                ?.optJSONObject("datavalue")?.opt("value")
            if (value is String) ids.put(prop, value)
        }
        prefs.edit().putString(cacheKey, JSONObject().put("at", System.currentTimeMillis()).put("ids", ids).toString()).apply()
        return ids.toStringMap()
    }

    /** Property id → (English label, link format with "$1"). Cached forever: these don't change. */
    private fun properties(ids: Set<String>): Map<String, Pair<String, String>> {
        val out = HashMap<String, Pair<String, String>>()
        val missing = ids.filter { id ->
            val cached = prefs.getString("p:$id", null) ?: return@filter true
            cached.split("\t").takeIf { it.size == 2 }?.let { out[id] = it[0] to it[1] }
            false
        }
        missing.chunked(50).forEach { chunk ->
            val body = wikidata("action=wbgetentities&ids=${chunk.joinToString("|")}&props=labels|claims&languages=en") ?: return@forEach
            val entities = JSONObject(body).optJSONObject("entities") ?: return@forEach
            val edit = prefs.edit()
            chunk.forEach { id ->
                val e = entities.optJSONObject(id)
                val label = e?.optJSONObject("labels")?.optJSONObject("en")?.optString("value").orEmpty()
                // P1630 = formatter URL, e.g. "https://www.netflix.com/title/$1".
                val format = e?.optJSONObject("claims")?.optJSONArray("P1630")?.optJSONObject(0)
                    ?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value").orEmpty()
                edit.putString("p:$id", "$label\t$format")
                out[id] = label to format
            }
            edit.apply()
        }
        return out
    }

    private fun wikidata(query: String): String? = runCatching {
        val req = Request.Builder()
            .url("https://www.wikidata.org/w/api.php?format=json&$query")
            .header("User-Agent", "MyTV-launcher/1.0 (personal Google TV home screen)")
            .build()
        http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    private fun JSONObject.toStringMap(): Map<String, String> = keys().asSequence().associateWith { optString(it) }
}
