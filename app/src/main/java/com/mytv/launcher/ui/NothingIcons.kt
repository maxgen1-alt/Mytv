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
 * the logo has a white wordmark or symbol over colour, only the white is kept. A logo with
 * big filled shapes isn't cut out at all (the tile shows its banner in black and white). When selected, the logo lights up in the app's own brand
 * colour (worked out from the banner). Made once per app picture, off the main thread,
 * and kept in memory.
 */
object NothingIcons {
    /** An app's logo shape (null if it can't be cut out cleanly) and its brand colour (null if it has none). */
    class Glyph(val shape: ImageBitmap?, val brand: Int?)

    private val cache = HashMap<ImageBitmap, Glyph>()

    /** The logo as a white shape on transparent, and the app's brand colour, from its banner. */
    fun glyph(art: ImageBitmap): Glyph {
        synchronized(cache) { cache[art]?.let { return it } }
        val out = runCatching { make(art) }.getOrNull() ?: Glyph(null, null)
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
        val bright = ((lo - 190) * 255 / 45).coerceIn(0, 255)
        val grey = ((90 - spread) * 255 / 50).coerceIn(0, 255)
        return bright * grey / 255
    }

    /**
     * The banner's brand colour: its most common strong colour (Prime's blue, Netflix's red),
     * brightened a little if it's too dark to see on black. Null for black-and-white logos.
     */
    private fun brand(px: IntArray): Int? {
        val counts = HashMap<Int, Int>()
        for (c in px) {
            if ((c ushr 24) < 128) continue
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val hi = max(r, max(g, b)); val lo = minOf(r, minOf(g, b))
            if (hi < 70 || (hi - lo) * 100 < hi * 45) continue // dark or greyish
            val key = c and 0xE0E0E0
            counts[key] = (counts[key] ?: 0) + 1
        }
        val best = counts.maxByOrNull { it.value } ?: return null
        if (best.value < px.size / 200) return null // a few stray pixels aren't a brand colour
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        for (c in px) if ((c ushr 24) >= 128 && (c and 0xE0E0E0) == best.key) {
            sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; n++
        }
        var r = (sr / n).toInt(); var g = (sg / n).toInt(); var b = (sb / n).toInt()
        val luma = (299 * r + 587 * g + 114 * b) / 1000
        if (luma < 110) {
            // Too dark on black: mix in some white.
            val t = (110 - luma) / 160f
            r += ((255 - r) * t).toInt(); g += ((255 - g) * t).toInt(); b += ((255 - b) * t).toInt()
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun make(art: ImageBitmap): Glyph? {
        var src = art.asAndroidBitmap()
        if (src.config == Bitmap.Config.HARDWARE) src = src.copy(Bitmap.Config.ARGB_8888, false)
        val w = src.width
        val h = src.height
        if (w < 8 || h < 8) return Glyph(null, null)
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val brandColour = brand(px)

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

        // 3. Big filled shapes (a shield's panel, a solid box) don't cut out cleanly: then the
        // tile shows the original banner in black and white instead. Letters stay as they are.
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
            if (solid[i] && dirs.all { d -> on(x + d[0], y + d[1]) }) return Glyph(null, brandColour)
            if (strength[i] > 128) lit++
        }
        // Crisp edges, no haze: faint shading and reflections under a logo are dropped.
        for (i in px.indices) px[i] = (((strength[i] - 90) * 255 / 80).coerceIn(0, 255) shl 24) or 0xFFFFFF

        // Nothing (or almost everything) stood out: no clean logo, use the app's name instead.
        val share = lit.toFloat() / px.size
        if (share < 0.005f || share > 0.75f) return Glyph(null, brandColour)
        return Glyph(Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap().also { it.prepareToDraw() }, brandColour)
    }
}
