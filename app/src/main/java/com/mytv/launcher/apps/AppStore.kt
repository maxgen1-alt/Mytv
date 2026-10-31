package com.mytv.launcher.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mytv.launcher.system.AutoPlay

/** Getting apps: Google Play on the TV, straight to an app's page when we know which. */
object AppStore {
    private const val PLAY = "com.android.vending"

    /** Google Play: [pkg]'s page (Install button), or the store's front page. */
    fun open(context: Context, pkg: String? = null): Boolean {
        val attempts = listOfNotNull(
            pkg?.let { Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$it")).setPackage(PLAY) },
            pkg?.let { Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$it")) },
            context.packageManager.getLeanbackLaunchIntentForPackage(PLAY),
            context.packageManager.getLaunchIntentForPackage(PLAY),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MARKET),
        )
        for (intent in attempts) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        // Last resort: Google TV's search, which offers an Install button too.
        return Search.open(context, if (pkg == RedZone.NFL_APP) "NFL app" else "Google Play Store")
    }
}

/** NFL RedZone, in the NFL app (it comes with NFL+ Premium or a RedZone subscription). */
object RedZone {
    const val NFL_APP = "com.gotv.nflgamecenter.us.lite"

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(NFL_APP, 0) }.isSuccess

    /**
     * Opens the NFL app and has the home helper press RedZone, then Watch. If
     * the app isn't installed, opens its Google Play page instead.
     */
    fun open(context: Context): Boolean {
        if (!installed(context)) return AppStore.open(context, NFL_APP)
        val launch = context.packageManager.getLeanbackLaunchIntentForPackage(NFL_APP)
            ?: context.packageManager.getLaunchIntentForPackage(NFL_APP)
            ?: return false
        AutoPlay.startSteps(NFL_APP, "the NFL app", listOf(listOf("redzone", "red zone"), listOf("watch live", "watch now", "watch", "play")))
        return runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }.isSuccess
    }
}
