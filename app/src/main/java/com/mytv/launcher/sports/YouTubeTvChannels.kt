package com.mytv.launcher.sports

/**
 * YouTube TV's own links to its live channels (Share → Copy link on a
 * channel in YouTube TV). Opening one starts that channel playing, so a game
 * on NBC opens playing instead of on YouTube TV's home screen.
 */
object YouTubeTvChannels {
    /** Network name as ESPN writes it (checked with `contains`) → YouTube TV link. Most specific first. */
    private val links = listOf(
        "nbc" to "https://tv.youtube.com/watch/fIbKmfCLA9o?vp=0gEEEgIwAQ%3D%3D",
    )

    fun linkFor(network: String): String? {
        val n = network.lowercase()
        // NBC Sports Network / Peacock-only listings aren't the NBC channel.
        if ("nbcsn" in n || "nbc sports" in n) return null
        return links.firstOrNull { n.contains(it.first) }?.second
    }
}
