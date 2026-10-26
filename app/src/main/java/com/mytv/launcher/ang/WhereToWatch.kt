package com.mytv.launcher.ang

import com.mytv.launcher.sports.Service

/** Network / service names (as TVmaze writes them) → apps that stream them in the US. */
object WhereToWatch {
    private val map: List<Pair<String, List<Service>>> = listOf(
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
}
