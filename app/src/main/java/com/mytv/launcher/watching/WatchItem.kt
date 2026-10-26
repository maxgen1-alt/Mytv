package com.mytv.launcher.watching

data class WatchItem(
    val key: String,
    val title: String,
    /** e.g. "Netflix · S2 · E5 · The Name" or "Movie · 2024" */
    val subtitle: String,
    val imageUrl: String?,
    /** 0..1 for something paused mid-way, null for "next episode" items. */
    val progress: Float?,
    val lastWatchedMillis: Long,
    /** What we hand to Google TV search if we can't open it directly. */
    val searchQuery: String,
    /** Where this came from: the app's name for Watch Next items, or "Trakt". */
    val source: String,
    /** App that published the item (Watch Next only). */
    val packageName: String? = null,
    /** Deep link that resumes playback inside the app (Watch Next only). */
    val intentUri: String? = null,
)
