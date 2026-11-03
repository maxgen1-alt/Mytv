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
 * colour around its edge) is dropped; everything that stands out from it is the logo.
 * Made once per app picture, off the main thread, and kept in memory.
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
        var br = 0L; var bgG = 0L; var bb = 0L; var n = 0
        if (!transparentBg) {
            fun add(c: Int) {
                if ((c ushr 24) >= 64 && (c and 0xF0F0F0) == bgKey) {
                    br += (c shr 16) and 0xFF; bgG += (c shr 8) and 0xFF; bb += c and 0xFF; n++
                }
            }
            for (x in 0 until w) { add(px[x]); add(px[(h - 1) * w + x]) }
            for (y in 0 until h) { add(px[y * w]); add(px[y * w + w - 1]) }
        }
        val r0 = if (n > 0) (br / n).toInt() else 0
        val g0 = if (n > 0) (bgG / n).toInt() else 0
        val b0 = if (n > 0) (bb / n).toInt() else 0

        var lit = 0
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            val strength = if (a < 40) 0 else if (transparentBg) a else {
                val d = max(abs(((c shr 16) and 0xFF) - r0), max(abs(((c shr 8) and 0xFF) - g0), abs((c and 0xFF) - b0)))
                // Soft edge: under 28 is background, over 110 fully logo.
                ((d - 28) * 255 / 82).coerceIn(0, 255) * a / 255
            }
            if (strength > 128) lit++
            px[i] = (strength shl 24) or 0xFFFFFF
        }
        // Nothing (or almost everything) stood out: no clean logo, use the app's name instead.
        val share = lit.toFloat() / px.size
        if (share < 0.01f || share > 0.75f) return null
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap().also { it.prepareToDraw() }
    }
}
