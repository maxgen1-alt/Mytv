package com.mytv.launcher.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mytv.launcher.system.AutoPlay
import com.mytv.launcher.system.Device

/** Getting apps: Google Play on the TV (Amazon Appstore on a Fire TV), straight to an app's page when we know which. */
object AppStore {
    private const val PLAY = "com.android.vending"

    /** Google Play: [pkg]'s page (Install button), or the store's front page. */
    fun open(context: Context, pkg: String? = null): Boolean {
        com.mytv.launcher.system.HomeOverride.holdOff(60_000L)
        if (Device.isFireTv(context)) return openAmazon(context, pkg)
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

    /**
     * Amazon Appstore. Fire TV apps often have other package names than on Google TV, so an
     * app is looked up by name ("NFL") rather than by its Google Play package.
     */
    private fun openAmazon(context: Context, pkg: String?): Boolean {
        val store = Device.AMAZON_APPSTORE
        val name = if (pkg == RedZone.NFL_APP) "NFL" else null
        val attempts = listOfNotNull(
            name?.let { Intent(Intent.ACTION_VIEW, Uri.parse("amzn://apps/android?s=" + Uri.encode(it))).setPackage(store) },
            pkg?.takeIf { name == null }?.let { Intent(Intent.ACTION_VIEW, Uri.parse("amzn://apps/android?p=$it")).setPackage(store) },
            context.packageManager.getLeanbackLaunchIntentForPackage(store),
            context.packageManager.getLaunchIntentForPackage(store),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MARKET),
        )
        for (intent in attempts) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }
}

/** NFL RedZone, in the NFL app (it comes with NFL+ Premium or a RedZone subscription). */
object RedZone {
    const val NFL_APP = "com.gotv.nflgamecenter.us.lite"

    /** The NFL app as installed here: Google TV's package, or on a Fire TV whichever app is called "NFL". */
    fun installedPackage(context: Context): String? {
        val pm = context.packageManager
        if (runCatching { pm.getPackageInfo(NFL_APP, 0) }.isSuccess) return NFL_APP
        if (!Device.isFireTv(context)) return null
        val launchers = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap { cat ->
            runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0) }.getOrDefault(emptyList())
        }
        return launchers.firstOrNull { info ->
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault("").trim()
            label.equals("NFL", ignoreCase = true) || label.startsWith("NFL ", ignoreCase = true) && !label.contains("sunday", true)
        }?.activityInfo?.packageName
    }

    fun installed(context: Context): Boolean = installedPackage(context) != null

    /**
     * Opens the NFL app and has the home helper press RedZone, then Watch. If
     * the app isn't installed, opens its Google Play page instead.
     */
    fun open(context: Context): Boolean {
        val app = installedPackage(context) ?: return AppStore.open(context, NFL_APP)
        val launch = context.packageManager.getLeanbackLaunchIntentForPackage(app)
            ?: context.packageManager.getLaunchIntentForPackage(app)
            ?: return false
        com.mytv.launcher.system.HomeOverride.holdOff(20_000L)
        AutoPlay.startSteps(app, "the NFL app", listOf(listOf("redzone", "red zone"), listOf("watch live", "watch now", "watch", "play")))
        return runCatching {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }.isSuccess
    }
}
