package com.mytv.launcher.sports

/** A streaming app, recognised by keywords in its package name or label. */
enum class Service(private val keywords: List<String>, private val exactLabels: List<String> = emptyList()) {
    PEACOCK(listOf("peacock")),
    PARAMOUNT(listOf("paramount", "com.cbs.ott", "com.cbs.app")),
    ESPN(listOf("espn")),
    PRIME_VIDEO(listOf("prime video", "com.amazon.avod", "amazon.amazonvideo")),
    MAX(listOf("hbo max", "com.wbd.stream", "com.hbo."), exactLabels = listOf("max")),
    NBA(listOf("com.nba.", "nba app"), exactLabels = listOf("nba")),
    SPECTRUM_SPORTSNET(listOf("sportsnet")),
    SPECTRUM_TV(listOf("spectrum tv", "com.twc.")),
    NBC_SPORTS(listOf("nbc sports", "com.nbcsports", "com.nbcuni.nbcsports")),
    TELEMUNDO(listOf("telemundo")),
    FOX(listOf("fox one", "fox sports", "com.foxsports", "com.fox.")),
    APPLE_TV(listOf("apple tv", "com.apple.atve")),
    NETFLIX(listOf("netflix")),
    DISNEY(listOf("disney")),
    YOUTUBE_TV(listOf("youtube tv", "tvunplugged")),
    HULU(listOf("hulu")),
    SLING(listOf("sling")),
    FUBO(listOf("fubo")),
    DIRECTV(listOf("directv")),
    ;

    fun matches(packageName: String, label: String): Boolean {
        val l = label.lowercase().trim()
        if (l in exactLabels) return true
        val haystack = "${packageName.lowercase()} $l"
        return keywords.any { haystack.contains(it) }
    }
}

data class InstalledApp(val packageName: String, val label: String)

data class WatchOption(val app: InstalledApp, val network: String)

object Broadcasters {

    /** Live-TV services that carry the big cable/broadcast channels. */
    private val liveTv = listOf(Service.YOUTUBE_TV, Service.HULU, Service.SLING, Service.FUBO, Service.DIRECTV)

    /**
     * Network names as ESPN writes them -> apps that can show that network,
     * best first. Checked top to bottom with `contains`, so more specific
     * names ("espn+", "cbssn", "nbcsn") must come before general ones.
     */
    private val networks: List<Pair<String, List<Service>>> = listOf(
        "peacock" to listOf(Service.PEACOCK),
        // Paramount+ also comes as a Prime Video channel.
        "paramount" to listOf(Service.PARAMOUNT, Service.PRIME_VIDEO),
        "espn+" to listOf(Service.ESPN),
        "espn" to listOf(Service.ESPN) + liveTv,
        "abc" to listOf(Service.ESPN) + liveTv,
        "prime" to listOf(Service.PRIME_VIDEO),
        "amazon" to listOf(Service.PRIME_VIDEO),
        "nba tv" to listOf(Service.NBA, Service.YOUTUBE_TV, Service.SLING, Service.DIRECTV),
        "league pass" to listOf(Service.NBA),
        "sportsnet" to listOf(Service.SPECTRUM_SPORTSNET, Service.SPECTRUM_TV, Service.DIRECTV),
        "spectrum" to listOf(Service.SPECTRUM_SPORTSNET, Service.SPECTRUM_TV, Service.DIRECTV),
        "cbssn" to listOf(Service.PARAMOUNT, Service.PRIME_VIDEO) + liveTv,
        "cbs" to listOf(Service.PARAMOUNT, Service.PRIME_VIDEO) + liveTv,
        "nbcsn" to listOf(Service.NBC_SPORTS) + liveTv,
        "nbc" to listOf(Service.PEACOCK, Service.NBC_SPORTS) + liveTv,
        "usa" to liveTv + listOf(Service.NBC_SPORTS),
        "telemundo" to listOf(Service.PEACOCK, Service.TELEMUNDO) + liveTv,
        "universo" to listOf(Service.PEACOCK, Service.TELEMUNDO) + liveTv,
        "tnt" to listOf(Service.MAX) + liveTv,
        "tbs" to listOf(Service.MAX) + liveTv,
        "trutv" to listOf(Service.MAX) + liveTv,
        "fox" to listOf(Service.FOX) + liveTv,
        "fs1" to listOf(Service.FOX) + liveTv,
        "fs2" to listOf(Service.FOX) + liveTv,
        "apple" to listOf(Service.APPLE_TV),
        "netflix" to listOf(Service.NETFLIX),
    )

    fun servicesFor(network: String): List<Service> {
        val n = network.lowercase().trim()
        return networks.firstOrNull { n.contains(it.first) }?.second ?: emptyList()
    }

    /**
     * Every installed app that can show this game, best first: networks ESPN
     * lists for the game (in ESPN's order), then the competition's usual
     * US broadcasters as a fallback.
     */
    fun optionsFor(game: Game, installed: List<InstalledApp>): List<WatchOption> {
        val networks = (game.broadcasts + game.league.defaultNetworks).distinct()
        val out = LinkedHashMap<String, WatchOption>()
        for (network in networks) {
            for (service in servicesFor(network)) {
                installed.filter { service.matches(it.packageName, it.label) }.forEach { app ->
                    out.putIfAbsent(app.packageName, WatchOption(app, network))
                }
            }
        }
        return out.values.toList()
    }
}
