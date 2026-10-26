package com.mytv.launcher.apps

import android.app.SearchManager
import android.content.Context
import android.content.Intent

/**
 * Hands a query to Google TV's own search (the same thing the remote's
 * microphone button does). Google TV knows which installed app has a title
 * and shows a "Watch on ..." button, so it's our fallback for anything we
 * can't open directly.
 */
object Search {
    fun open(context: Context, query: String): Boolean {
        val attempts = listOf(
            Intent("android.search.action.GLOBAL_SEARCH"),
            Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.katniss"),
            Intent(Intent.ACTION_WEB_SEARCH),
        )
        for (intent in attempts) {
            intent.putExtra(SearchManager.QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
