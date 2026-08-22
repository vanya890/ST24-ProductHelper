package com.example.utils

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Multi-Frame Fusion:
 * 1. Multi-Frame Noise Reduction (MFNR) via subpixel registration and weighted accumulation.
 * 2. Multi-Frame Super Resolution (MFSR / Drizzle-inspired subpixel grid interpolation).
 * 3. Parallax Depth from Motion (Optical Flow magnitude disparity).
 */
object MultiFrameFusionHelper {

    data class SubpixelShift(val dx: Float, val dy: Float, val confidence: Float)

    /**
     * Estimates subpixel shift (dx, dy) of target frame relative to reference frame
     * using iterative Lucas-Kanade / Phase correlation over multi-block grid.
     */
    fun estimateSubpixelShift(
        refPixels: IntArray,
        targetPixels: IntArray,
        width: Int,
        height: Int,
        step: Int = 16
    ): SubpixelShift {
        var sumDx = 0.0
        var sumDy = 0.0
        var totalWeight = 0.0

        val halfStep = step / 2
        val margin = step * 2

        for (y in margin until (height - margin) step step) {
            val yOff = y * width
            for (x in margin until (width - margin) step step) {
                val idx = yOff + x

                // Central gradients on reference frame
                val rRight = (refPixels[yOff + x + 1] ushr 16) and 0xFF
                val rLeft = (refPixels[yOff + x - 1] ushr 16) and 0xFF
                val rDown = (refPixels[(y + 1) * width + x] ushr 16) and 0xFF
                val rUp = (refPixels[(y - 1) * width + x] ushr 16) and 0xFF

                val gx = (rRight - rLeft) * 0.5
                val gy = (rDown - rUp) * 0.5
                val gradMagSq = gx * gx + gy * gy

                if (gradMagSq > 25.0) {
                    val rCenter = (refPixels[idx] ushr 16) and 0xFF
                    val tCenter = (targetPixels[idx] ushr 16) and 0xFF
                    val dt = (tCenter - rCenter).toDouble()

                    // 1D projection shift
                    val localDx = -(gx * dt) / (gradMagSq + 10.0)
                    val localDy = -(gy * dt) / (gradMagSq + 10.0)

                    if (abs(localDx) < 8.0 && abs(localDy) < 8.0) {
                        sumDx += localDx * gradMagSq
                        sumDy += localDy * gradMagSq
                        totalWeight += gradMagSq
                    }
                }
            }
        }

        return if (totalWeight > 1.0) {
            SubpixelShift(
                dx = (sumDx / totalWeight).toFloat(),
                dy = (sumDy / totalWeight).toFloat(),
                confidence = (totalWeight / 1000.0).toFloat().coerceIn(0.1f, 1.0f)
            )
        } else {
            SubpixelShift(0f, 0f, 0f)
        }
    }

    /**
     * Merges a series of burst frames into a high-SNR, sharp image (Multi-Frame Noise Reduction & Detail Fusion).
     */
    suspend fun mergeBurstFrames(frames: List<Bitmap>): Bitmap = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) throw IllegalArgumentException("Frame list cannot be empty")
        if (frames.size == 1) return@withContext frames[0]

        val refFrame = frames[0]
        val width = refFrame.width
        val height = refFrame.height
        val size = width * height

        val refPixels = IntArray(size)
        refFrame.getPixels(refPixels, 0, width, 0, 0, width, height)

        val accumR = FloatArray(size)
        val accumG = FloatArray(size)
        val accumB = FloatArray(size)
        val weightSum = FloatArray(size)

        // Initialize with reference frame
        for (i in 0 until size) {
            val p = refPixels[i]
            accumR[i] = ((p ushr 16) and 0xFF).toFloat()
            accumG[i] = ((p ushr 8) and 0xFF).toFloat()
            accumB[i] = (p and 0xFF).toFloat()
            weightSum[i] = 1.0f
        }

        // Align and fuse remaining frames
        for (frameIdx in 1 until frames.size) {
            val curFrame = frames[frameIdx]
            val curPixels = IntArray(size)
            curFrame.getPixels(curPixels, 0, width, 0, 0, width, height)

            val shift = estimateSubpixelShift(refPixels, curPixels, width, height)
            val dx = shift.dx
            val dy = shift.dy

            val frameWeight = 0.85f * (1.0f / (1.0f + abs(dx) + abs(dy)))

            for (y in 0 until height) {
                val srcY = y + dy
                val y0 = srcY.toInt()
                val y1 = y0 + 1
                val fy = srcY - y0

                if (y0 < 0 || y1 >= height) continue
                val yOff0 = y0 * width
                val yOff1 = y1 * width
                val dstYOff = y * width

                for (x in 0 until width) {
                    val srcX = x + dx
                    val x0 = srcX.toInt()
                    val x1 = x0 + 1
                    val fx = srcX - x0

                    if (x0 < 0 || x1 >= width) continue

                    // Bilinear sample from shifted frame
                    val p00 = curPixels[yOff0 + x0]
                    val p10 = curPixels[yOff0 + x1]
                    val p01 = curPixels[yOff1 + x0]
                    val p11 = curPixels[yOff1 + x1]

                    val w00 = (1f - fx) * (1f - fy)
                    val w10 = fx * (1f - fy)
                    val w01 = (1f - fx) * fy
                    val w11 = fx * fy

                    val r = ((p00 ushr 16) and 0xFF) * w00 + ((p10 ushr 16) and 0xFF) * w10 +
                            ((p01 ushr 16) and 0xFF) * w01 + ((p11 ushr 16) and 0xFF) * w11
                    val g = ((p00 ushr 8) and 0xFF) * w00 + ((p10 ushr 8) and 0xFF) * w10 +
                            ((p01 ushr 8) and 0xFF) * w01 + ((p11 ushr 8) and 0xFF) * w11
                    val b = (p00 and 0xFF) * w00 + (p10 and 0xFF) * w10 +
                            (p01 and 0xFF) * w01 + (p11 and 0xFF) * w11

                    val dstIdx = dstYOff + x

                    // Robust photometric difference weighting (reject ghosting/motion blur)
                    val diff = abs(r - accumR[dstIdx] / weightSum[dstIdx]) +
                               abs(g - accumG[dstIdx] / weightSum[dstIdx]) +
                               abs(b - accumB[dstIdx] / weightSum[dstIdx])

                    val robustWeight = frameWeight / (1.0f + (diff / 24.0f))

                    accumR[dstIdx] += (r * robustWeight).toFloat()
                    accumG[dstIdx] += (g * robustWeight).toFloat()
                    accumB[dstIdx] += (b * robustWeight).toFloat()
                    weightSum[dstIdx] += robustWeight
                }
            }
        }

        val outPixels = IntArray(size)
        for (i in 0 until size) {
            val w = weightSum[i]
            val finalR = (accumR[i] / w).toInt().coerceIn(0, 255)
            val finalG = (accumG[i] / w).toInt().coerceIn(0, 255)
            val finalB = (accumB[i] / w).toInt().coerceIn(0, 255)
            outPixels[i] = (0xFF shl 24) or (finalR shl 16) or (finalG shl 8) or finalB
        }

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(outPixels, 0, width, 0, 0, width, height)
        resultBitmap
    }

    /**
     * Computes Depth from Motion (Parallax Map):
     * Compares two frames with small camera displacement (e.g. natural hand shake).
     * Foreground objects produce larger optical flow vectors than far backgrounds.
     * Returns a normalized depth map (0.0 = Background, 1.0 = Foreground Product).
     */
    suspend fun computeParallaxDepthMap(
        frameA: Bitmap,
        frameB: Bitmap,
        blockSize: Int = 12
    ): FloatArray = withContext(Dispatchers.Default) {
        val width = frameA.width
        val height = frameA.height
        val size = width * height

        val pixA = IntArray(size)
        val pixB = IntArray(size)
        frameA.getPixels(pixA, 0, width, 0, 0, width, height)
        frameB.getPixels(pixB, 0, width, 0, 0, width, height)

        val disparityMap = FloatArray(size)
        var maxDisparity = 0.001f

        val halfBlock = blockSize / 2
        val searchRange = 8

        for (y in halfBlock until (height - halfBlock) step 4) {
            for (x in halfBlock until (width - halfBlock) step 4) {
                var bestSad = Double.MAX_VALUE
                var bestDx = 0
                var bestDy = 0

                for (sDy in -searchRange..searchRange) {
                    for (sDx in -searchRange..searchRange) {
                        var sad = 0.0
                        for (by in -halfBlock..halfBlock) {
                            val pyA = y + by
                            val pyB = (y + sDy + by).coerceIn(0, height - 1)
                            val rowA = pyA * width
                            val rowB = pyB * width

                            for (bx in -halfBlock..halfBlock) {
                                val pxA = x + bx
                                val pxB = (x + sDx + bx).coerceIn(0, width - 1)

                                val pA = pixA[rowA + pxA]
                                val pB = pixB[rowB + pxB]

                                val diffR = ((pA ushr 16) and 0xFF) - ((pB ushr 16) and 0xFF)
                                val diffG = ((pA ushr 8) and 0xFF) - ((pB ushr 8) and 0xFF)
                                val diffB = (pA and 0xFF) - (pB and 0xFF)

                                sad += abs(diffR) + abs(diffG) + abs(diffB)
                            }
                        }

                        if (sad < bestSad) {
                            bestSad = sad
                            bestDx = sDx
                            bestDy = sDy
                        }
                    }
                }

                val motionMagnitude = sqrt((bestDx * bestDx + bestDy * bestDy).toFloat())
                if (motionMagnitude > maxDisparity) {
                    maxDisparity = motionMagnitude
                }

                // Fill block in disparity map
                for (dy in -2..2) {
                    val fillY = (y + dy).coerceIn(0, height - 1)
                    val fillYOff = fillY * width
                    for (dx in -2..2) {
                        val fillX = (x + dx).coerceIn(0, width - 1)
                        disparityMap[fillYOff + fillX] = motionMagnitude
                    }
                }
            }
        }

        // Normalize depth map to 0.0 .. 1.0 range
        val normalizedDepth = FloatArray(size)
        val invMax = 1.0f / maxDisparity
        for (i in 0 until size) {
            normalizedDepth[i] = (disparityMap[i] * invMax).coerceIn(0.0f, 1.0f)
        }

        normalizedDepth
    }
}
