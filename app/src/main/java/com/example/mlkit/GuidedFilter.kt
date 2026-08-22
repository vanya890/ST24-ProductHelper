package com.example.mlkit

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

object GuidedFilter {

    private val expLUT = FloatArray(255 * 3 + 1) { i ->
        exp(-i.toFloat() / 35.0f)
    }

    /**
     * Color-Aware Joint Bilateral Matting Refinement.
     * Snaps mask edges smoothly to physical 3D RGB color gradients of the source image.
     * Guarantees natural organic object curves with subpixel anti-aliased borders.
     */
    fun filter(pixels: IntArray, mask: FloatArray, w: Int, h: Int, radius: Int = 2, eps: Float = 1e-4f): FloatArray {
        val size = w * h
        val result = FloatArray(size)

        // 1. Smooth Alpha Contrast Curve (Smoothstep)
        // Maps mask smoothly: <0.08 -> 0.0, >0.82 -> 1.0, preserving subpixel smooth transition zone
        val smoothedMask = FloatArray(size)
        val isBoundary = BooleanArray(size)

        for (i in 0 until size) {
            val v = mask[i]
            val sVal = when {
                v >= 0.82f -> 1.0f
                v <= 0.08f -> 0.0f
                else -> {
                    val norm = (v - 0.08f) / 0.74f
                    norm * norm * (3.0f - 2.0f * norm)
                }
            }
            smoothedMask[i] = sVal
        }

        // 2. Identify active edge boundary pixels (where alpha transitions smoothly)
        for (y in 0 until h) {
            val yOff = y * w
            for (x in 0 until w) {
                val idx = yOff + x
                val v = smoothedMask[idx]
                if (v > 0.01f && v < 0.99f) {
                    isBoundary[idx] = true
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0 until h) continue
                        val nYOff = ny * w
                        for (dx in -1..1) {
                            val nx = x + dx
                            if (nx in 0 until w) {
                                isBoundary[nYOff + nx] = true
                            }
                        }
                    }
                }
            }
        }

        // 3. Perform Joint Color-Aware Bilateral Filter on boundary pixels
        val spatialLUT = floatArrayOf(1.0f, 0.70f, 0.35f)

        for (y in 0 until h) {
            val yOff = y * w
            for (x in 0 until w) {
                val idx = yOff + x

                if (!isBoundary[idx]) {
                    result[idx] = smoothedMask[idx]
                    continue
                }

                val cI = pixels[idx]
                val rI = Color.red(cI)
                val gI = Color.green(cI)
                val bI = Color.blue(cI)

                var weightedAlphaSum = 0.0f
                var totalWeight = 0.0f

                for (dy in -2..2) {
                    val ny = y + dy
                    if (ny !in 0 until h) continue
                    val nYOff = ny * w
                    val sWeightY = spatialLUT[abs(dy)]

                    for (dx in -2..2) {
                        val nx = x + dx
                        if (nx !in 0 until w) continue

                        val sWeightX = spatialLUT[abs(dx)]
                        val spatialW = sWeightY * sWeightX

                        val nIdx = nYOff + nx
                        val cJ = pixels[nIdx]
                        val rJ = Color.red(cJ)
                        val gJ = Color.green(cJ)
                        val bJ = Color.blue(cJ)

                        val colorDiff = abs(rI - rJ) + abs(gI - gJ) + abs(bI - bJ)
                        val colorW = expLUT[min(255 * 3, colorDiff)]

                        val combinedW = spatialW * colorW

                        weightedAlphaSum += smoothedMask[nIdx] * combinedW
                        totalWeight += combinedW
                    }
                }

                val refinedAlpha = if (totalWeight > 0.0001f) (weightedAlphaSum / totalWeight) else smoothedMask[idx]
                result[idx] = refinedAlpha.coerceIn(0.0f, 1.0f)
            }
        }

        return result
    }
}


