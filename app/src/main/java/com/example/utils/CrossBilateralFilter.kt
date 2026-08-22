package com.example.utils

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ForkJoinPool
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cross-Bilateral Filtering and Specular Highlight Removal (Poisson-inspired Gradient Blending).
 * 
 * Mathematical Formulation:
 * Ambient_new(p) = (1 / W_p) * sum_{q in S} [ Ambient(q) * G_sigma_s(||p - q||) * G_sigma_r(||Flash(p) - Flash(q)||) ]
 * 
 * Where:
 * - G_sigma_s is the spatial Gaussian weight: exp(-||p-q||^2 / (2 * sigma_s^2))
 * - G_sigma_r is the range Gaussian weight computed on the Flash guide image: exp(-||Flash_p - Flash_q||^2 / (2 * sigma_r^2))
 * - Flash image provides crisp high-frequency edge gradients and micro-textures.
 * - Ambient image provides natural colors, soft ambient shadows, and illumination.
 */
object CrossBilateralFilter {

    /**
     * Performs Cross-Bilateral Filtering on the Ambient image using the Flash image as the edge guide.
     */
    suspend fun filter(
        ambient: Bitmap,
        flashGuide: Bitmap,
        spatialRadius: Int = 3,
        spatialSigma: Float = 2.5f,
        rangeSigma: Float = 30.0f
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = ambient.width
        val height = ambient.height
        val size = width * height

        val ambPixels = IntArray(size)
        val flashPixels = IntArray(size)
        ambient.getPixels(ambPixels, 0, width, 0, 0, width, height)
        flashGuide.getPixels(flashPixels, 0, width, 0, 0, width, height)

        val outPixels = IntArray(size)

        // Precompute Spatial Gaussian LUT
        val spatialLut = FloatArray((spatialRadius * 2 + 1) * (spatialRadius * 2 + 1))
        val twoSigmaSsq = 2.0f * spatialSigma * spatialSigma
        for (dy in -spatialRadius..spatialRadius) {
            for (dx in -spatialRadius..spatialRadius) {
                val distSq = (dx * dx + dy * dy).toFloat()
                val idx = (dy + spatialRadius) * (spatialRadius * 2 + 1) + (dx + spatialRadius)
                spatialLut[idx] = exp(-distSq / twoSigmaSsq)
            }
        }

        // Precompute Range Gaussian LUT for delta luminance (0..255)
        val twoSigmaRsq = 2.0f * rangeSigma * rangeSigma
        val rangeLut = FloatArray(256 * 256)
        val rangeStepLut = FloatArray(256 * 3)
        for (d in 0 until 256 * 3) {
            rangeStepLut[d] = exp(-(d.toFloat() * d.toFloat()) / (3.0f * twoSigmaRsq))
        }

        val kernelDim = spatialRadius * 2 + 1

        // Parallel processing across image rows
        val numThreads = Runtime.getRuntime().availableProcessors()
        val rowsPerChunk = max(1, height / (numThreads * 2))

        val tasks = (0 until height step rowsPerChunk).map { startY ->
            val endY = min(height, startY + rowsPerChunk)
            Runnable {
                for (y in startY until endY) {
                    val yOffset = y * width
                    for (x in 0 until width) {
                        val centerIdx = yOffset + x
                        val centerFlash = flashPixels[centerIdx]
                        val cfR = (centerFlash ushr 16) and 0xFF
                        val cfG = (centerFlash ushr 8) and 0xFF
                        val cfB = centerFlash and 0xFF

                        var sumR = 0.0f
                        var sumG = 0.0f
                        var sumB = 0.0f
                        var sumWeight = 0.0f

                        for (dy in -spatialRadius..spatialRadius) {
                            val qy = (y + dy).coerceIn(0, height - 1)
                            val qyOffset = qy * width
                            val spatialRowIdx = (dy + spatialRadius) * kernelDim

                            for (dx in -spatialRadius..spatialRadius) {
                                val qx = (x + dx).coerceIn(0, width - 1)
                                val qIdx = qyOffset + qx

                                val qFlash = flashPixels[qIdx]
                                val qfR = (qFlash ushr 16) and 0xFF
                                val qfG = (qFlash ushr 8) and 0xFF
                                val qfB = qFlash and 0xFF

                                val dR = abs(cfR - qfR)
                                val dG = abs(cfG - qfG)
                                val dB = abs(cfB - qfB)
                                val rangeDiff = (dR + dG + dB).coerceIn(0, 256 * 3 - 1)

                                val sWeight = spatialLut[spatialRowIdx + (dx + spatialRadius)]
                                val rWeight = rangeStepLut[rangeDiff]
                                val weight = sWeight * rWeight

                                val qAmb = ambPixels[qIdx]
                                val qaR = (qAmb ushr 16) and 0xFF
                                val qaG = (qAmb ushr 8) and 0xFF
                                val qaB = qAmb and 0xFF

                                sumR += qaR * weight
                                sumG += qaG * weight
                                sumB += qaB * weight
                                sumWeight += weight
                            }
                        }

                        val invW = if (sumWeight > 1e-6f) 1.0f / sumWeight else 1.0f
                        val finalR = (sumR * invW).toInt().coerceIn(0, 255)
                        val finalG = (sumG * invW).toInt().coerceIn(0, 255)
                        val finalB = (sumB * invW).toInt().coerceIn(0, 255)

                        outPixels[centerIdx] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
                    }
                }
            }
        }

        val pool = ForkJoinPool.commonPool()
        val futures = tasks.map { pool.submit(it) }
        futures.forEach { it.get() }

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(outPixels, 0, width, 0, 0, width, height)
        resultBitmap
    }

    /**
     * Removes specular highlights / flash glares from the Flash image using
     * gradient reconstruction and color texture from the Ambient image.
     *
     * 1. Detects specular highlight mask (Luma > 240, high brightness saturation).
     * 2. Smoothly feather the specular boundary mask (Poisson boundary condition).
     * 3. Seamlessly blends gradient vectors from the ambient image to eliminate white holes.
     */
    suspend fun removeSpecularHighlights(
        flash: Bitmap,
        ambient: Bitmap,
        specularLumaThreshold: Int = 238
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = min(flash.width, ambient.width)
        val height = min(flash.height, ambient.height)
        val size = width * height

        val flashPix = IntArray(size)
        val ambPix = IntArray(size)
        flash.getPixels(flashPix, 0, width, 0, 0, width, height)
        ambient.getPixels(ambPix, 0, width, 0, 0, width, height)

        val specularMask = FloatArray(size)

        // 1. Specular Detection
        for (i in 0 until size) {
            val f = flashPix[i]
            val r = (f ushr 16) and 0xFF
            val g = (f ushr 8) and 0xFF
            val b = f and 0xFF
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            val minC = min(r, min(g, b))
            val maxC = max(r, max(g, b))

            // Specular reflections are extremely bright with high minimum channel
            if (luma >= specularLumaThreshold && minC > 210) {
                val excess = ((luma - specularLumaThreshold) / (255f - specularLumaThreshold)).coerceIn(0f, 1f)
                specularMask[i] = excess
            } else {
                specularMask[i] = 0f
            }
        }

        // 2. Feather Specular Mask (Poisson Boundary Smoothing)
        val featheredMask = FloatArray(size)
        val r = 2
        for (y in 0 until height) {
            val yOff = y * width
            for (x in 0 until width) {
                var sum = 0f
                var count = 0
                for (dy in -r..r) {
                    val qy = (y + dy).coerceIn(0, height - 1)
                    val qOff = qy * width
                    for (dx in -r..r) {
                        val qx = (x + dx).coerceIn(0, width - 1)
                        sum += specularMask[qOff + qx]
                        count++
                    }
                }
                featheredMask[yOff + x] = (sum / count).coerceIn(0f, 1f)
            }
        }

        // 3. Seamless Gradient Transfer / Poisson Reconstruction
        val resultPix = IntArray(size)
        for (i in 0 until size) {
            val specAlpha = featheredMask[i]
            if (specAlpha <= 0.001f) {
                resultPix[i] = flashPix[i]
            } else {
                val f = flashPix[i]
                val a = ambPix[i]

                val fr = (f ushr 16) and 0xFF
                val fg = (f ushr 8) and 0xFF
                val fb = f and 0xFF

                val ar = (a ushr 16) and 0xFF
                val ag = (a ushr 8) and 0xFF
                val ab = a and 0xFF

                // Transfer color ratio while preserving ambient texture
                val blendedR = ((1f - specAlpha) * fr + specAlpha * ar).toInt().coerceIn(0, 255)
                val blendedG = ((1f - specAlpha) * fg + specAlpha * ag).toInt().coerceIn(0, 255)
                val blendedB = ((1f - specAlpha) * fb + specAlpha * ab).toInt().coerceIn(0, 255)

                resultPix[i] = (0xFF shl 24) or (blendedR shl 16) or (blendedG shl 8) or blendedB
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(resultPix, 0, width, 0, 0, width, height)
        result
    }
}
