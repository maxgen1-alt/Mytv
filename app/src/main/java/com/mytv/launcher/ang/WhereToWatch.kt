package com.mytv.launcher.ang

import com.mytv.launcher.sports.Service

/** Network / service names (as TVmaze writes them) → apps that stream them in the US. */
object WhereToWatch {
    private val map: List<Pair<String, List<Service>>> = listOf(
        // Services you don't have: matched first so they map to nothing.
        "itvx" to emptyList(),
        "passionflix" to emptyList(),
        "netflix" to listOf(Service.NETFLIX),
        "prime" to listOf(Service.PRIME_VIDEO),
        "amazon" to listOf(Service.PRIME_VIDEO),
        "hulu" to listOf(Service.HULU),
        "peacock" to listOf(Service.PEACOCK),
        "bravo" to listOf(Service.PEACOCK, Service.YOUTUBE_TV, Service.HULU),
        "e!" to listOf(Service.PEACOCK, Service.YOUTUBE_TV),
        "nbc" to listOf(Service.PEACOCK, Service.YOUTUBE_TV),
        "hbo" to listOf(Service.MAX),
        "max" to listOf(Service.MAX),
        // Paramount+ comes with your Prime Video account (Prime Video Channels).
        "paramount" to listOf(Service.PARAMOUNT, Service.PRIME_VIDEO),
        "cbs" to listOf(Service.PARAMOUNT, Service.PRIME_VIDEO, Service.YOUTUBE_TV),
        "mtv" to listOf(Service.PARAMOUNT, Service.PRIME_VIDEO),
        "showtime" to listOf(Service.PARAMOUNT, Service.PRIME_VIDEO),
        "disney" to listOf(Service.DISNEY),
        "freeform" to listOf(Service.HULU, Service.DISNEY),
        "abc" to listOf(Service.HULU, Service.DISNEY),
        "fx" to listOf(Service.HULU),
        "apple" to listOf(Service.APPLE_TV),
        // Love Island UK streams on Hulu in the US.
        "itv" to listOf(Service.HULU),
    )

    fun servicesFor(serviceName: String?): List<Service> {
        val n = serviceName?.lowercase() ?: return emptyList()
        return map.firstOrNull { n.contains(it.first) }?.second ?: emptyList()
    }

    /**
     * Streaming services we search for shows on: their TMDB (US) watch-provider
     * ids, and their TMDB network ids (for originals that haven't premiered yet).
     */
    data class Provider(
        val service: Service,
        val name: String,
        val tmdbIds: List<Int>,
        val tmdbNetworks: List<Int>,
        /** Apps that give you this service (Paramount+ also comes through Prime Video). */
        val apps: List<Service> = listOf(service),
    )

    val providers = listOf(
        Provider(Service.NETFLIX, "Netflix", listOf(8), listOf(213)),
        Provider(Service.HULU, "Hulu", listOf(15), listOf(453)),
        Provider(Service.PRIME_VIDEO, "Prime Video", listOf(9, 119), listOf(1024)),
        Provider(Service.DISNEY, "Disney+", listOf(337), listOf(2739)),
        Provider(Service.APPLE_TV, "Apple TV", listOf(350), listOf(2552)),
        Provider(Service.PEACOCK, "Peacock", listOf(386, 387), listOf(3353)),
        Provider(Service.MAX, "HBO Max", listOf(1899), listOf(3186, 49)),
        // 582 = Paramount+ Amazon Channel.
        Provider(Service.PARAMOUNT, "Paramount+", listOf(2303, 531, 582), listOf(4330), apps = listOf(Service.PARAMOUNT, Service.PRIME_VIDEO)),
    )
}
