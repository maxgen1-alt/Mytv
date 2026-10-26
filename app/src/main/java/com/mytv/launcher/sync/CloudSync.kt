package com.mytv.launcher.sync

import com.mytv.launcher.BuildConfig
import com.mytv.launcher.ang.TasteStore
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
    private val url = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key = BuildConfig.SUPABASE_KEY
    private val household = BuildConfig.SYNC_HOUSEHOLD
    val isConfigured: Boolean get() = url.isNotBlank() && key.isNotBlank() && household.isNotBlank()

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
        changed
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
        val ok = runCatching {
            val req = request("/rest/v1/mytv_ratings?on_conflict=household,profile,key")
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(rows.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        if (ok) store.markUploaded(pending.map { it.key })
    }

    private fun get(path: String): String? = runCatching {
        http.newCall(request(path).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

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
