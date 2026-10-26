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
        "paramount" to listOf(Service.PARAMOUNT),
        "cbs" to listOf(Service.PARAMOUNT, Service.YOUTUBE_TV),
        "mtv" to listOf(Service.PARAMOUNT),
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

    /** Streaming services we search for new shows on, with their TMDB (US) provider ids. */
    data class Provider(val service: Service, val name: String, val tmdbIds: List<Int>)

    val providers = listOf(
        Provider(Service.NETFLIX, "Netflix", listOf(8)),
        Provider(Service.HULU, "Hulu", listOf(15)),
        Provider(Service.PRIME_VIDEO, "Prime Video", listOf(9, 119)),
        Provider(Service.DISNEY, "Disney+", listOf(337)),
        Provider(Service.APPLE_TV, "Apple TV", listOf(350)),
        Provider(Service.PEACOCK, "Peacock", listOf(386, 387)),
        Provider(Service.MAX, "HBO Max", listOf(1899)),
        Provider(Service.PARAMOUNT, "Paramount+", listOf(2303, 531)),
    )
}
