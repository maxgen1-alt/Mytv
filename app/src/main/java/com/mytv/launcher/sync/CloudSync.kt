package com.mytv.launcher.sync

import com.mytv.launcher.BuildConfig
import com.mytv.launcher.ang.TasteStore
import com.mytv.launcher.apps.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Keeps ratings the same on every TV, through your own Supabase project
 * (table mytv_ratings). Each rating is one row; the newest change wins.
 * Rows are tagged with SYNC_HOUSEHOLD and the table's security rule only
 * lets a device see rows with the household it sends.
 */
object CloudSync {
    // The project URL, with or without the API path pasted after it.
    private val url = BuildConfig.SUPABASE_URL.trim().trimEnd('/').removeSuffix("/rest/v1").trimEnd('/')
    private val key = BuildConfig.SUPABASE_KEY
    private val household = BuildConfig.SYNC_HOUSEHOLD
    val isConfigured: Boolean get() = url.isNotBlank() && key.isNotBlank() && household.isNotBlank()

    /** For the apps screen: how the last sync went. */
    @Volatile var status: String = if (url.isBlank() || key.isBlank() || household.isBlank()) "Sync: not set up in this build" else "Sync: not run yet"
        private set
    @Volatile private var failure: String? = null

    /** Starts a round of syncing: clears the last round's error. */
    fun begin() { failure = null }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Downloads the other TV's changes into [store], then uploads ours.
     * [profile] is "me" or "ang". Returns true if anything arrived.
     */
    suspend fun sync(store: TasteStore, profile: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext false
        store.seedForSync()
        var changed = false
        get("/rest/v1/mytv_ratings?select=key,data,updated_at&profile=eq.$profile")?.let { body ->
            val rows = runCatching { JSONArray(body) }.getOrNull() ?: return@let
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val at = runCatching { Instant.parse(row.getString("updated_at")).toEpochMilli() }.getOrNull() ?: continue
                val data = row.opt("data").takeIf { it is JSONObject }?.toString()
                if (store.applyRemote(TasteStore.Change(row.getString("key"), data, at))) changed = true
            }
        }
        push(store, profile)
        report()
        changed
    }

    /**
     * The apps row (hidden apps + order), stored as one row (profile "apps")
     * in the same table. Newest change wins. Returns true if this TV's row changed.
     */
    suspend fun syncLayout(apps: InstalledApps): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext false
        var local = apps.layout()
        val body = get("/rest/v1/mytv_ratings?select=data,updated_at&profile=eq.apps&key=eq.layout") ?: run { report(); return@withContext false }
        val row = runCatching { JSONArray(body).optJSONObject(0) }.getOrNull()
        val remoteAt = row?.let { runCatching { Instant.parse(it.getString("updated_at")).toEpochMilli() }.getOrNull() } ?: 0L
        val data = row?.optJSONObject("data")
        var changed = false
        // First sync: a row set up on this TV before syncing existed, and nothing online yet.
        if (row == null && local.changedAt == 0L && (local.hidden.isNotEmpty() || local.order.isNotEmpty())) {
            apps.markLayoutChanged()
            local = apps.layout()
        }
        if (data != null && remoteAt > local.changedAt) {
            fun list(name: String) = data.optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            apps.applyLayout(InstalledApps.Layout(list("hidden").toSet(), list("order"), remoteAt))
            changed = true
        } else if (local.changedAt > remoteAt) {
            pushLayout(apps)
        }
        report()
        changed
    }

    /** Uploads the apps row right after it changes here. */
    suspend fun pushLayout(apps: InstalledApps) = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext
        val l = apps.layout()
        if (l.changedAt == 0L) return@withContext
        val row = JSONObject()
            .put("household", household)
            .put("profile", "apps")
            .put("key", "layout")
            .put("data", JSONObject().put("hidden", JSONArray(l.hidden.toList())).put("order", JSONArray(l.order)))
            .put("updated_at", Instant.ofEpochMilli(l.changedAt).toString())
        upsert(JSONArray().put(row))
    }

    private fun report() {
        val time = java.time.LocalTime.now().withNano(0).withSecond(0)
        status = failure?.let { "Sync: failed at $time ($it)" } ?: "Sync: OK at $time"
    }

    /** Uploads local changes (called right after rating something). */
    suspend fun push(store: TasteStore, profile: String) = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext
        val pending = store.pending()
        if (pending.isEmpty()) return@withContext
        val rows = JSONArray()
        pending.forEach { c ->
            rows.put(
                JSONObject()
                    .put("household", household)
                    .put("profile", profile)
                    .put("key", c.key)
                    .put("data", c.json?.let { JSONObject(it) } ?: JSONObject.NULL)
                    .put("updated_at", Instant.ofEpochMilli(c.at).toString()),
            )
        }
        if (upsert(rows)) store.markUploaded(pending.map { it.key })
    }

    private fun upsert(rows: JSONArray): Boolean = runCatching {
        val req = request("/rest/v1/mytv_ratings?on_conflict=household,profile,key")
            .header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(rows.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) failure = explain(r.code, r.body?.string())
            r.isSuccessful
        }
    }.getOrElse { failure = "no connection"; false }

    private fun get(path: String): String? = runCatching {
        http.newCall(request(path).build()).execute().use { r ->
            if (!r.isSuccessful) failure = explain(r.code, r.body?.string())
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrElse { failure = "no connection"; null }

    /** Supabase's error → a few plain words. */
    private fun explain(code: Int, body: String?): String {
        val pgrst = runCatching { JSONObject(body.orEmpty()).optString("code") }.getOrDefault("")
        return when {
            pgrst == "PGRST205" -> "table mytv_ratings missing: run the SQL from the README in Supabase"
            code == 401 || code == 403 -> "Supabase refused the key (HTTP $code)"
            code == 404 -> "wrong Supabase URL (HTTP 404)"
            else -> "HTTP $code"
        }
    }

    private fun request(path: String) = Request.Builder()
        .url(url + path)
        .header("apikey", key)
        .header("x-household", household)
        .apply {
            // Older "anon" keys are JWTs and also go in Authorization; new
            // sb_publishable_ keys must only be sent as apikey.
            if (key.startsWith("eyJ")) header("Authorization", "Bearer $key")
        }
}
