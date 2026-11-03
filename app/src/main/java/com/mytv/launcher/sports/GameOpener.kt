package com.mytv.launcher.sports

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.mytv.launcher.apps.InstalledApps
import com.mytv.launcher.system.AutoPlay
import com.mytv.launcher.watching.WatchNext

/**
 * Straight to a game, from anywhere (MyTV's screens or the fantasy panel over
 * another app), never a search:
 * 1. NBA on League Pass: that game's page in Prime Video, and MyTV's helper
 *    presses its buttons (profile, Watch live) for you;
 * 2. the game itself, if the app showing it lists it on the TV;
 * 3. YouTube TV: the channel showing it;
 * 4. otherwise that app, opened on its own.
 * False (after saying where it's on) if none of your apps has it.
 */
class GameOpener(context: Context) {
    private val app = context.applicationContext
    private val installed = InstalledApps(app)
    private val watchNext = WatchNext(app)

    /** Your apps, from MyTV's saved app list (fast; no need for MyTV to be open). */
    suspend fun apps(): List<InstalledApp> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        installed.cached().map { InstalledApp(it.packageName, it.label) }
    }

    suspend fun open(game: Game, title: String, words: List<String>, option: WatchOption? = null, apps: List<InstalledApp>? = null): Boolean {
        val options = (listOfNotNull(option) + Broadcasters.optionsFor(game, apps ?: apps())).distinctBy { it.app.packageName }
        // NBA on League Pass: straight to that game in Prime Video.
        options.firstOrNull()?.takeIf { Service.PRIME_VIDEO.matches(it.app.packageName, it.app.label) }?.let { prime ->
            if (openOnLeaguePass(game, title, prime.app.packageName)) return true
        }
        val link = watchNext.findGame(words, options.map { it.app.packageName })
        // Prime, Peacock and YouTube TV: the helper presses the profile and Watch live buttons for you.
        link?.let { (pkg, _) -> helped(pkg, options)?.let { name -> AutoPlay.start(pkg, words, name, onGame = true) } }
        if (link != null && watchNext.openLink(link.first, link.second)) {
            val label = options.firstOrNull { it.app.packageName == link.first }?.app?.label.orEmpty()
            toast("$title · starting on $label")
            return true
        }
        val first = options.firstOrNull()
        // YouTube TV: straight onto the channel showing it (it starts playing), else its Live guide.
        if (first != null && Service.YOUTUBE_TV.matches(first.app.packageName, first.app.label)) {
            val channel = YouTubeTvChannels.linkFor(first.network)
            if (channel != null) AutoPlay.start(first.app.packageName, emptyList(), "YouTube TV", onGame = true)
            if (channel != null && watchNext.openLink(first.app.packageName, channel)) {
                toast("$title · ${first.network} on YouTube TV")
                return true
            }
            AutoPlay.cancel() // the Live guide: you pick the channel there, nothing to press
            if (watchNext.openLink(first.app.packageName, "https://tv.youtube.com/live")) {
                toast("$title is on ${first.network} · YouTube TV Live")
                return true
            }
        }
        // Just the app: the helper finds the game's tile (both teams) on its home screen, then Watch live.
        if (first != null) helped(first.app.packageName, options)?.let { name -> AutoPlay.start(first.app.packageName, words, name, onGame = false) }
        if (first != null && installed.launch(first.app.packageName)) {
            toast("$title is on ${first.network} · opening ${first.app.label}")
            return true
        }
        val where = (game.broadcasts.ifEmpty { game.league.defaultNetworks }).joinToString(", ")
        toast("$title is on $where, which isn't one of your apps")
        return false
    }

    /**
     * The game's own page in Prime Video, if League Pass lists it. Prime is
     * started fresh on it (handed a game while it's already open, it quits),
     * and the helper presses its profile and Watch live buttons for you.
     */
    suspend fun openOnLeaguePass(game: Game, title: String, primePackage: String): Boolean {
        if (game.league.sport != "basketball") return false
        val card = LeaguePass.find(leaguePass.cards(), game) ?: return false
        // Already watching Prime (the fantasy panel over a game): handed a new game while it's on
        // screen, Prime quits to the home screen. Put it in the background first, like pressing
        // Home, then start it on the game, the way that works from MyTV's own buttons.
        com.mytv.launcher.system.HomeOverride.service?.let { svc ->
            val front = runCatching { svc.rootInActiveWindow?.packageName?.toString() }.getOrNull()
            if (front == primePackage) {
                com.mytv.launcher.system.HomeOverride.holdOff(15_000L)
                svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                kotlinx.coroutines.delay(1_000L)
            }
        }
        AutoPlay.start(primePackage, card.title.split(" vs. "))
        if (!watchNext.openFresh(primePackage, LeaguePass.link(card))) return false
        toast("$title · opening it on League Pass")
        return true
    }

    /** The helper's name for [pkg] if it's one of the apps it presses buttons in (Prime, Peacock, YouTube TV). */
    private fun helped(pkg: String, options: List<WatchOption>): String? {
        val label = options.firstOrNull { it.app.packageName == pkg }?.app?.label.orEmpty()
        return when {
            Service.PRIME_VIDEO.matches(pkg, label) -> "Prime"
            Service.PEACOCK.matches(pkg, label) -> "Peacock"
            Service.YOUTUBE_TV.matches(pkg, label) -> "YouTube TV"
            else -> null
        }
    }

    private fun toast(msg: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }

    companion object {
        /** One copy of the League Pass page for everyone (it's fetched at most every 5 minutes). */
        private val leaguePass = LeaguePassClient()
    }
}
