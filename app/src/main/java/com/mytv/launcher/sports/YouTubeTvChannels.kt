package com.mytv.launcher.sports

/**
 * YouTube TV's own links to its live channels (Share → Copy link on a
 * channel in YouTube TV). Opening one starts that channel playing, so a game
 * on NBC opens playing instead of on YouTube TV's home screen.
 */
object YouTubeTvChannels {
    /** Network name as ESPN writes it (checked with `contains`) → YouTube TV link. Most specific first. */
    private const val NBC = "https://tv.youtube.com/watch/fIbKmfCLA9o?vp=0gEEEgIwAQ%3D%3D"
    private const val NBC_SPORTS_EXTRA = "https://tv.youtube.com/watch/SonoVJHSR0A?vp=0gEEEgIwAQ%3D%3D"

    /**
     * NBC games on the NBC channel; NBC Sports / Peacock listings (games YouTube TV
     * shows on NBC Sports Extra) on that channel.
     */
    fun linkFor(network: String): String? {
        val n = network.lowercase()
        return when {
            "nbc sports" in n || "nbcsn" in n || "peacock" in n -> NBC_SPORTS_EXTRA
            "nbc" in n -> NBC
            else -> null
        }
    }
}
