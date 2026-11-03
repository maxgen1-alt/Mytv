package com.mytv.launcher.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs
import kotlin.math.max

/**
 * Nothing-style app icons: the app's own logo, cut out of its banner as a single-colour
 * shape (white, tinted red when selected). The banner's background colour (the most common
 * colour around its edge) is dropped; everything that stands out from it is the logo. When
 * the logo has a white wordmark or symbol over colour, only the white is kept, and thick
 * filled shapes are drawn as outlines. Made once per app picture, off the main thread,
 * and kept in memory.
 */
object NothingIcons {
    private val cache = HashMap<ImageBitmap, ImageBitmap?>()

    /** The logo as a white shape on transparent, or null if it can't be separated cleanly. */
    fun glyph(art: ImageBitmap): ImageBitmap? {
        synchronized(cache) { if (cache.containsKey(art)) return cache[art] }
        val out = runCatching { make(art) }.getOrNull()
        synchronized(cache) {
            if (cache.size > 64) cache.clear()
            cache[art] = out
        }
        return out
    }

    /** How white a pixel is (0-255): bright and without colour. */
    private fun whiteness(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        val lo = minOf(r, minOf(g, b))
        val spread = max(r, max(g, b)) - lo
        val bright = ((lo - 150) * 255 / 60).coerceIn(0, 255)
        val grey = ((90 - spread) * 255 / 50).coerceIn(0, 255)
        return bright * grey / 255
    }

    private fun make(art: ImageBitmap): ImageBitmap? {
        var src = art.asAndroidBitmap()
        if (src.config == Bitmap.Config.HARDWARE) src = src.copy(Bitmap.Config.ARGB_8888, false)
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return null
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // Background: the most common (coarse) colour along the edge; transparent counts too.
        val counts = HashMap<Int, Int>()
        fun edge(c: Int) {
            val key = if ((c ushr 24) < 64) -1 else (c and 0xF0F0F0)
            counts[key] = (counts[key] ?: 0) + 1
        }
        for (x in 0 until w) { edge(px[x]); edge(px[(h - 1) * w + x]) }
        for (y in 0 until h) { edge(px[y * w]); edge(px[y * w + w - 1]) }
        val bgKey = counts.maxByOrNull { it.value }?.key ?: -1
        val transparentBg = bgKey == -1
        // The background's real average colour (not the coarse key).
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        if (!transparentBg) {
            fun add(c: Int) {
                if ((c ushr 24) >= 64 && (c and 0xF0F0F0) == bgKey) {
                    sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; n++
                }
            }
            for (x in 0 until w) { add(px[x]); add(px[(h - 1) * w + x]) }
            for (y in 0 until h) { add(px[y * w]); add(px[y * w + w - 1]) }
        }
        val r0 = if (n > 0) (sr / n).toInt() else 0
        val g0 = if (n > 0) (sg / n).toInt() else 0
        val b0 = if (n > 0) (sb / n).toInt() else 0

        // 1. Everything that stands out from the background.
        val strength = IntArray(px.size)
        var litAll = 0
        var litWhite = 0
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            strength[i] = if (a < 40) 0 else if (transparentBg) a else {
                val d = max(abs(((c shr 16) and 0xFF) - r0), max(abs(((c shr 8) and 0xFF) - g0), abs((c and 0xFF) - b0)))
                // Soft edge: under 28 is background, over 110 fully logo.
                ((d - 28) * 255 / 82).coerceIn(0, 255) * a / 255
            }
            if (strength[i] > 128) {
                litAll++
                if (whiteness(c) > 128) litWhite++
            }
        }

        // 2. Most streaming logos have a white wordmark or symbol over colour (a gradient,
        // a red play box, a blue panel): when there's a real white wordmark on a dark
        // background, keep just the white.
        val bgLuma = (299 * r0 + 587 * g0 + 114 * b0) / 1000
        if (!transparentBg && bgLuma < 150 && litWhite >= px.size * 0.004f) {
            for (i in px.indices) strength[i] = strength[i] * whiteness(px[i]) / 255
        }

        // 3. Only really big filled areas (a shield's white panel) become outlines; letters and
        // bold symbols (Apple's apple, "tv") stay solid.
        val r = max(8, minOf(w, h) / 9)
        val solid = BooleanArray(px.size) { strength[it] > 128 }
        fun on(x: Int, y: Int) = x in 0 until w && y in 0 until h && solid[y * w + x]
        val dirs = arrayOf(
            intArrayOf(r, 0), intArrayOf(-r, 0), intArrayOf(0, r), intArrayOf(0, -r),
            intArrayOf(r, r), intArrayOf(-r, r), intArrayOf(r, -r), intArrayOf(-r, -r),
        )
        var lit = 0
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (solid[i] && dirs.all { d -> on(x + d[0], y + d[1]) }) strength[i] = 0
            if (strength[i] > 128) lit++
        }
        // Crisp edges, no haze: faint shading and reflections under a logo are dropped.
        for (i in px.indices) px[i] = (((strength[i] - 90) * 255 / 80).coerceIn(0, 255) shl 24) or 0xFFFFFF

        // Nothing (or almost everything) stood out: no clean logo, use the app's name instead.
        val share = lit.toFloat() / px.size
        if (share < 0.005f || share > 0.75f) return null
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap().also { it.prepareToDraw() }
    }
}
