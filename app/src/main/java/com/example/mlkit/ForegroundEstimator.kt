package com.example.mlkit

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

object ForegroundEstimator {

    /**
     * Unpremultiply original pixels:
     * Divides original RGB color by alpha (clamped at >= 0.001f) to eliminate
     * dark background spill or pre-baked matte artifacts before any color calculations.
     */
    fun unpremultiply(pixels: IntArray, alpha: FloatArray, w: Int, h: Int): Array<FloatArray> {
        val size = w * h
        val rChan = FloatArray(size)
        val gChan = FloatArray(size)
        val bChan = FloatArray(size)

        for (i in 0 until size) {
            val px = pixels[i]
            val a = alpha[i].coerceAtLeast(0.001f)
            val r = Color.red(px).toFloat()
            val g = Color.green(px).toFloat()
            val b = Color.blue(px).toFloat()

            rChan[i] = (r / a).coerceIn(0f, 255f)
            gChan[i] = (g / a).coerceIn(0f, 255f)
            bChan[i] = (b / a).coerceIn(0f, 255f)
        }

        return arrayOf(rChan, gChan, bChan)
    }

    /**
     * Fast & High-Precision Local Boundary Color Decontamination.
     * Estimates clean foreground color for boundary pixels (where alpha < 0.95)
     * by taking a spatial inverse-distance-weighted average of nearby pure foreground colors (alpha >= 0.80).
     * Eliminates background halos in a single fast pass (< 2ms) without multi-hundred-pass grid diffusion.
     */
    fun estimate(pixels: IntArray, alpha: FloatArray, w: Int, h: Int, erosionRadius: Int = 3): IntArray {
        val size = w * h
        if (size == 0) return IntArray(0)

        val cleanPixels = IntArray(size)
        val searchRadius = maxOf(3, erosionRadius)

        java.util.stream.IntStream.range(0, h).parallel().forEach { y ->
            val yOff = y * w
            for (x in 0 until w) {
                val idx = yOff + x
                val a = alpha[idx]

                if (a <= 0.001f) {
                    cleanPixels[idx] = 0
                    continue
                }

                val px = pixels[idx]
                val origR = Color.red(px)
                val origG = Color.green(px)
                val origB = Color.blue(px)

                // If deep inside pure solid object, keep original color directly
                if (a >= 0.95f) {
                    cleanPixels[idx] = px
                    continue
                }

                // Semi-transparent boundary pixel: search local neighborhood for solid foreground colors
                var sumR = 0f
                var sumG = 0f
                var sumB = 0f
                var sumWeight = 0f

                val minX = maxOf(0, x - searchRadius)
                val maxX = minOf(w - 1, x + searchRadius)
                val minY = maxOf(0, y - searchRadius)
                val maxY = minOf(h - 1, y + searchRadius)

                for (ny in minY..maxY) {
                    val nYOff = ny * w
                    val dy = ny - y
                    for (nx in minX..maxX) {
                        val nIdx = nYOff + nx
                        val nAlpha = alpha[nIdx]

                        if (nAlpha >= 0.80f) {
                            val dx = nx - x
                            val distSq = dx * dx + dy * dy
                            val weight = 1.0f / (distSq + 0.1f)

                            val nPx = pixels[nIdx]
                            sumR += Color.red(nPx) * weight
                            sumG += Color.green(nPx) * weight
                            sumB += Color.blue(nPx) * weight
                            sumWeight += weight
                        }
                    }
                }

                if (sumWeight > 0f) {
                    val r = (sumR / sumWeight).toInt().coerceIn(0, 255)
                    val g = (sumG / sumWeight).toInt().coerceIn(0, 255)
                    val b = (sumB / sumWeight).toInt().coerceIn(0, 255)
                    cleanPixels[idx] = Color.rgb(r, g, b)
                } else {
                    // Fallback to unpremultiplied color if no solid neighbor within radius
                    val safeA = a.coerceAtLeast(0.01f)
                    val r = (origR / safeA).toInt().coerceIn(0, 255)
                    val g = (origG / safeA).toInt().coerceIn(0, 255)
                    val b = (origB / safeA).toInt().coerceIn(0, 255)
                    cleanPixels[idx] = Color.rgb(r, g, b)
                }
            }
        }

        return cleanPixels
    }
}


