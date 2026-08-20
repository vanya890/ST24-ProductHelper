package com.example.mlkit

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.Subject
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object SegmentationHelper {

    private data class ScoredSubject(
        val subject: Subject,
        val score: Float,
        val containsCenter: Boolean,
        val centerDistNorm: Float
    )

    suspend fun segmentProduct(bitmap: Bitmap): Bitmap? {
        val subjectResultOptions = SubjectSegmenterOptions.SubjectResultOptions.Builder()
            .enableConfidenceMask()
            .build()
        val options = SubjectSegmenterOptions.Builder()
            .enableMultipleSubjects(subjectResultOptions)
            .enableForegroundConfidenceMask()
            .build()

        val segmenter = SubjectSegmentation.getClient(options)
        val image = InputImage.fromBitmap(bitmap, 0)

        return try {
            val result = segmenter.process(image).await()
            val subjects = result.subjects
            val width = bitmap.width
            val height = bitmap.height
            val centerX = width / 2f
            val centerY = height / 2f
            val halfDiag = sqrt((width * width + height * height).toDouble()).toFloat() / 2f

            if (subjects.isNotEmpty()) {
                val scoredSubjects = subjects.mapNotNull { subject ->
                    val mask = subject.confidenceMask ?: return@mapNotNull null
                    val sWidth = subject.width
                    val sHeight = subject.height
                    if (sWidth <= 0 || sHeight <= 0) return@mapNotNull null

                    val sX1 = subject.startX
                    val sY1 = subject.startY
                    val sX2 = sX1 + sWidth
                    val sY2 = sY1 + sHeight

                    val sCenterX = (sX1 + sX2) / 2f
                    val sCenterY = (sY1 + sY2) / 2f

                    val dist = sqrt((sCenterX - centerX) * (sCenterX - centerX) + (sCenterY - centerY) * (sCenterY - centerY))
                    val distNorm = dist / halfDiag

                    val containsCenter = centerX >= sX1 && centerX <= sX2 && centerY >= sY1 && centerY <= sY2
                    val areaRatio = (sWidth.toFloat() * sHeight.toFloat()) / (width.toFloat() * height.toFloat())

                    // Prioritize subjects aimed at by camera center
                    var centralityWeight = kotlin.math.exp(- (distNorm * distNorm) / (2f * 0.28f * 0.28f))
                    if (containsCenter) {
                        centralityWeight += 1.5f
                    }

                    // Heavily penalize huge background frames/walls covering >88% of image
                    if (areaRatio > 0.88f) {
                        centralityWeight *= 0.10f
                    }

                    val areaFactor = sqrt((areaRatio * 4.0f).coerceAtMost(1.0f))
                    val finalScore = centralityWeight * areaFactor

                    ScoredSubject(subject, finalScore, containsCenter, distNorm)
                }.sortedByDescending { it.score }

                if (scoredSubjects.isNotEmpty()) {
                    val primary = scoredSubjects.first()

                    // Find secondary centered subjects to merge (e.g. multi-part product)
                    val candidatesToMerge = scoredSubjects.filter { candidate ->
                        candidate == primary || (
                            candidate.score >= primary.score * 0.45f &&
                            (candidate.containsCenter || candidate.centerDistNorm < 0.35f)
                        )
                    }

                    if (candidatesToMerge.size == 1) {
                        val mainSubject = primary.subject
                        return processSubjectWithDecontamination(
                            bitmap,
                            mainSubject.confidenceMask!!,
                            mainSubject.startX,
                            mainSubject.startY,
                            mainSubject.width,
                            mainSubject.height
                        )
                    } else {
                        // Merge multi-part centered subjects
                        val minX = candidatesToMerge.minOf { it.subject.startX }.coerceIn(0, width - 1)
                        val minY = candidatesToMerge.minOf { it.subject.startY }.coerceIn(0, height - 1)
                        val maxX = candidatesToMerge.maxOf { it.subject.startX + it.subject.width }.coerceIn(minX + 1, width)
                        val maxY = candidatesToMerge.maxOf { it.subject.startY + it.subject.height }.coerceIn(minY + 1, height)

                        val mergedW = maxX - minX
                        val mergedH = maxY - minY
                        val mergedConfidences = FloatArray(mergedW * mergedH)

                        for (cand in candidatesToMerge) {
                            val sub = cand.subject
                            val maskBuf = sub.confidenceMask ?: continue
                            maskBuf.rewind()
                            val subConf = FloatArray(sub.width * sub.height)
                            maskBuf.get(subConf)

                            val offsetX = sub.startX - minX
                            val offsetY = sub.startY - minY

                            for (sy in 0 until sub.height) {
                                val my = offsetY + sy
                                if (my !in 0 until mergedH) continue
                                val mOff = my * mergedW
                                val sOff = sy * sub.width

                                for (sx in 0 until sub.width) {
                                    val mx = offsetX + sx
                                    if (mx !in 0 until mergedW) continue
                                    val valExisting = mergedConfidences[mOff + mx]
                                    val valNew = subConf[sOff + sx]
                                    mergedConfidences[mOff + mx] = max(valExisting, valNew)
                                }
                            }
                        }

                        return processSubjectMaskWithDecontamination(
                            bitmap,
                            mergedConfidences,
                            minX,
                            minY,
                            mergedW,
                            mergedH
                        )
                    }
                }
            }

            // Fallback to foreground confidence mask
            val mask = result.foregroundConfidenceMask
            if (mask != null) {
                processForegroundWithDecontamination(bitmap, mask)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("SegmentationHelper", "Error segmenting image", e)
            null
        }
    }

    /**
     * Finds the non-transparent bounding box of an object with alpha threshold.
     */
    fun findSubjectBoundingBox(bitmap: Bitmap, alphaThreshold: Int = 20): Rect {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        var found = false

        for (y in 0 until height) {
            val yOffset = y * width
            for (x in 0 until width) {
                val alpha = Color.alpha(pixels[yOffset + x])
                if (alpha > alphaThreshold) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    found = true
                }
            }
        }

        return if (found && maxX >= minX && maxY >= minY) {
            Rect(minX, minY, maxX + 1, maxY + 1)
        } else {
            Rect(0, 0, width, height)
        }
    }

    /**
     * Studio Matting with multi-radius color decontamination & guided feathering
     * to eliminate all hard edges, staircase jaggedness, and background color bleeding.
     */
    fun processSubjectWithDecontamination(
        original: Bitmap,
        mask: FloatBuffer,
        startX: Int,
        startY: Int,
        subjectWidth: Int,
        subjectHeight: Int
    ): Bitmap {
        val confidences = FloatArray(subjectWidth * subjectHeight)
        mask.rewind()
        mask.get(confidences)
        return processSubjectMaskWithDecontamination(original, confidences, startX, startY, subjectWidth, subjectHeight)
    }

    fun processSubjectMaskWithDecontamination(
        original: Bitmap,
        confidences: FloatArray,
        startX: Int,
        startY: Int,
        subjectWidth: Int,
        subjectHeight: Int
    ): Bitmap {
        val width = original.width
        val height = original.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val origPixels = IntArray(width * height)
        original.getPixels(origPixels, 0, width, 0, 0, width, height)

        // Extract sub-region pixels for fast spatial lookup and guided filtering
        val subPixels = IntArray(subjectWidth * subjectHeight)
        for (y in 0 until subjectHeight) {
            val srcY = (startY + y).coerceIn(0, height - 1)
            val srcYOffset = srcY * width
            val subYOffset = y * subjectWidth
            for (x in 0 until subjectWidth) {
                val srcX = (startX + x).coerceIn(0, width - 1)
                subPixels[subYOffset + x] = origPixels[srcYOffset + srcX]
            }
        }

        // Refine mask using joint image-guided bilateral anti-aliasing & subpixel edge snapping
        val processedMask = refineMaskWithImageGuidance(
            confidences,
            subPixels,
            subjectWidth,
            subjectHeight
        )

        // Perform Alpha Matting Unmixing & Background Decontamination
        val cleanPixels = unmixAndDecontaminate(
            subPixels,
            processedMask,
            subjectWidth,
            subjectHeight
        )

        val outPixels = IntArray(width * height)
        val edgeMinThreshold = 0.005f

        for (y in 0 until subjectHeight) {
            val imgY = startY + y
            if (imgY !in 0 until height) continue
            val imgYOffset = imgY * width
            val subYOffset = y * subjectWidth

            for (x in 0 until subjectWidth) {
                val imgX = startX + x
                if (imgX !in 0 until width) continue

                val sIdx = subYOffset + x
                val alphaConf = processedMask[sIdx]

                if (alphaConf <= edgeMinThreshold) continue

                val pIndex = imgYOffset + imgX
                val pixel = origPixels[pIndex]
                val origAlpha = Color.alpha(pixel) / 255f
                val finalAlpha = (origAlpha * alphaConf).coerceIn(0f, 1f)
                val finalAlphaInt = (finalAlpha * 255f).toInt()

                if (finalAlphaInt <= 0) continue

                val cleanPx = cleanPixels[sIdx]
                val cR = Color.red(cleanPx)
                val cG = Color.green(cleanPx)
                val cB = Color.blue(cleanPx)

                outPixels[pIndex] = Color.argb(finalAlphaInt, cR, cG, cB)
            }
        }

        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun unmixAndDecontaminate(
        pixels: IntArray,
        mask: FloatArray,
        width: Int,
        height: Int
    ): IntArray {
        val size = width * height
        val cleanPixels = IntArray(size)
        val radius = 12

        for (y in 0 until height) {
            val yOff = y * width
            for (x in 0 until width) {
                val idx = yOff + x
                val alpha = mask[idx]

                if (alpha >= 0.99f) {
                    cleanPixels[idx] = pixels[idx]
                } else if (alpha <= 0.002f) {
                    cleanPixels[idx] = Color.TRANSPARENT
                } else {
                    val obsPx = pixels[idx]
                    val oR = Color.red(obsPx).toFloat()
                    val oG = Color.green(obsPx).toFloat()
                    val oB = Color.blue(obsPx).toFloat()

                    var fgR = 0f; var fgG = 0f; var fgB = 0f; var fgW = 0f
                    var bgR = 0f; var bgG = 0f; var bgB = 0f; var bgW = 0f

                    for (dy in -radius..radius) {
                        val ny = y + dy
                        if (ny !in 0 until height) continue
                        val nYOff = ny * width

                        for (dx in -radius..radius) {
                            val nx = x + dx
                            if (nx !in 0 until width) continue

                            val nIdx = nYOff + nx
                            val nAlpha = mask[nIdx]
                            val nPx = pixels[nIdx]
                            val distSq = (dx * dx + dy * dy).toFloat() + 0.1f
                            val w = 1f / distSq

                            if (nAlpha >= 0.85f) {
                                fgR += Color.red(nPx) * w
                                fgG += Color.green(nPx) * w
                                fgB += Color.blue(nPx) * w
                                fgW += w
                            } else if (nAlpha <= 0.05f) {
                                bgR += Color.red(nPx) * w
                                bgG += Color.green(nPx) * w
                                bgB += Color.blue(nPx) * w
                                bgW += w
                            }
                        }
                    }

                    val meanFgR = if (fgW > 0f) fgR / fgW else oR
                    val meanFgG = if (fgW > 0f) fgG / fgW else oG
                    val meanFgB = if (fgW > 0f) fgB / fgW else oB

                    val meanBgR = if (bgW > 0f) bgR / bgW else oR
                    val meanBgG = if (bgW > 0f) bgG / bgW else oB
                    val meanBgB = if (bgW > 0f) bgB / bgW else oB

                    // PhotoRoom Defringe & Color Inpainting:
                    // Unmix observed color to subtract background contamination, then blend towards pure interior foreground RGB
                    val safeAlpha = alpha.coerceAtLeast(0.15f)
                    val unmixedR = (oR - (1f - alpha) * meanBgR) / safeAlpha
                    val unmixedG = (oG - (1f - alpha) * meanBgG) / safeAlpha
                    val unmixedB = (oB - (1f - alpha) * meanBgB) / safeAlpha

                    // Foreground bleeding factor: replaces dirty edge RGB with pure interior product RGB
                    val fgBleedWeight = (1f - alpha * alpha).coerceIn(0f, 1f)
                    val finalR = (unmixedR * (1f - fgBleedWeight) + meanFgR * fgBleedWeight).toInt().coerceIn(0, 255)
                    val finalG = (unmixedG * (1f - fgBleedWeight) + meanFgG * fgBleedWeight).toInt().coerceIn(0, 255)
                    val finalB = (unmixedB * (1f - fgBleedWeight) + meanFgB * fgBleedWeight).toInt().coerceIn(0, 255)

                    cleanPixels[idx] = Color.rgb(finalR, finalG, finalB)
                }
            }
        }

        return cleanPixels
    }

    fun processForegroundWithDecontamination(original: Bitmap, mask: FloatBuffer): Bitmap {
        val width = original.width
        val height = original.height
        return processSubjectWithDecontamination(original, mask, 0, 0, width, height)
    }

    /**
     * Advanced Subpixel Matting Pipeline:
     * 1. Adaptive Soft Morphological Filter: Cleans single-pixel background fringes while preserving sharp fine details
     * 2. Joint Image-Guided Bilateral Filter & Edge-Tangent FXAA Subpixel Filtering: Eliminates diagonal and curved staircase rasterization (anti-aliasing)
     * 3. 5-Tap Separable Gaussian Smoothing Kernel [1, 4, 6, 4, 1] / 16 for true organic, ultra-smooth photographic feathering
     * 4. Continuous Quintic Hermite Smootherstep Curve (C2 continuity) for Studio Polish
     */
    fun refineMask(confidences: FloatArray, width: Int, height: Int): FloatArray {
        return refineMaskWithImageGuidance(confidences, null, width, height)
    }

    fun refineMaskWithImageGuidance(
        confidences: FloatArray,
        pixels: IntArray?,
        width: Int,
        height: Int
    ): FloatArray {
        val size = width * height
        val cleaned = FloatArray(size)

        // 1. PhotoRoom-Grade Subpixel Boundary Choke (Erosion of dirty background border)
        for (y in 0 until height) {
            val yOffset = y * width
            val prevYOffset = (y - 1).coerceAtLeast(0) * width
            val nextYOffset = (y + 1).coerceAtMost(height - 1) * width

            for (x in 0 until width) {
                val idx = yOffset + x
                val c = confidences[idx]
                if (c in 0.005f..0.995f) {
                    val prevX = (x - 1).coerceAtLeast(0)
                    val nextX = (x + 1).coerceAtMost(width - 1)

                    val l = confidences[yOffset + prevX]
                    val r = confidences[yOffset + nextX]
                    val t = confidences[prevYOffset + x]
                    val b = confidences[nextYOffset + x]

                    val tl = confidences[prevYOffset + prevX]
                    val tr = confidences[prevYOffset + nextX]
                    val bl = confidences[nextYOffset + prevX]
                    val br = confidences[nextYOffset + nextX]

                    val minN = min(min(min(l, r), min(t, b)), min(min(tl, tr), min(bl, br)))
                    // Choke boundary inward by 1.2px to strip halo pixels
                    cleaned[idx] = (c * 0.35f + minN * 0.65f).coerceIn(0f, 1f)
                } else {
                    cleaned[idx] = c
                }
            }
        }

        // 2. Image-Guided Joint Bilateral Filtering
        val guided = FloatArray(size)
        if (pixels != null) {
            for (y in 0 until height) {
                val yOffset = y * width
                for (x in 0 until width) {
                    val idx = yOffset + x
                    val centerConf = cleaned[idx]

                    if (centerConf in 0.01f..0.98f) {
                        val centerPx = pixels[idx]
                        val cR = Color.red(centerPx)
                        val cG = Color.green(centerPx)
                        val cB = Color.blue(centerPx)

                        var confSum = 0f
                        var weightSum = 0f

                        for (dy in -2..2) {
                            val ny = (y + dy).coerceIn(0, height - 1)
                            val nYOffset = ny * width
                            for (dx in -2..2) {
                                val nx = (x + dx).coerceIn(0, width - 1)
                                val nIdx = nYOffset + nx
                                val nPx = pixels[nIdx]

                                val spatialDistSq = (dx * dx + dy * dy).toFloat()
                                val spatialW = kotlin.math.exp(-spatialDistSq / 4.5f)

                                val dR = Color.red(nPx) - cR
                                val dG = Color.green(nPx) - cG
                                val dB = Color.blue(nPx) - cB
                                val colorDistSq = (dR * dR + dG * dG + dB * dB).toFloat()
                                val colorW = kotlin.math.exp(-colorDistSq / 1800f)

                                val w = spatialW * colorW
                                confSum += cleaned[nIdx] * w
                                weightSum += w
                            }
                        }

                        guided[idx] = if (weightSum > 0f) (confSum / weightSum).coerceIn(0f, 1f) else centerConf
                    } else {
                        guided[idx] = centerConf
                    }
                }
            }
        } else {
            System.arraycopy(cleaned, 0, guided, 0, size)
        }

        // 3. Subpixel Edge Anti-Aliasing (Directional gradient & 9-point subpixel filter)
        val antialiased = FloatArray(size)
        for (y in 0 until height) {
            val yOffset = y * width
            val prevYOffset = (y - 1).coerceAtLeast(0) * width
            val nextYOffset = (y + 1).coerceAtMost(height - 1) * width

            for (x in 0 until width) {
                val idx = yOffset + x
                val center = guided[idx]

                if (center in 0.01f..0.99f) {
                    val prevX = (x - 1).coerceAtLeast(0)
                    val nextX = (x + 1).coerceAtMost(width - 1)

                    val left = guided[yOffset + prevX]
                    val right = guided[yOffset + nextX]
                    val top = guided[prevYOffset + x]
                    val bottom = guided[nextYOffset + x]

                    val topLeft = guided[prevYOffset + prevX]
                    val topRight = guided[prevYOffset + nextX]
                    val bottomLeft = guided[nextYOffset + prevX]
                    val bottomRight = guided[nextYOffset + nextX]

                    val gx = (topRight + 2f * right + bottomRight) - (topLeft + 2f * left + bottomLeft)
                    val gy = (bottomLeft + 2f * bottom + bottomRight) - (topLeft + 2f * top + topRight)
                    val gradMag = sqrt((gx * gx + gy * gy).toDouble()).toFloat()

                    if (gradMag > 0.08f) {
                        val directSum = left + right + top + bottom
                        val cornerSum = topLeft + topRight + bottomLeft + bottomRight
                        val subpixel = (center * 4f + directSum * 2f + cornerSum * 1f) / 16f
                        antialiased[idx] = subpixel
                    } else {
                        antialiased[idx] = center
                    }
                } else {
                    antialiased[idx] = center
                }
            }
        }

        // 4. High-Quality 5-Tap Separable Gaussian Smoothing (Kernel: [1, 4, 6, 4, 1] / 16)
        val temp = FloatArray(size)
        for (y in 0 until height) {
            val yOff = y * width
            for (x in 0 until width) {
                val xm2 = (x - 2).coerceAtLeast(0)
                val xm1 = (x - 1).coerceAtLeast(0)
                val xp1 = (x + 1).coerceAtMost(width - 1)
                val xp2 = (x + 2).coerceAtMost(width - 1)

                val valM2 = antialiased[yOff + xm2]
                val valM1 = antialiased[yOff + xm1]
                val valC  = antialiased[yOff + x]
                val valP1 = antialiased[yOff + xp1]
                val valP2 = antialiased[yOff + xp2]

                temp[yOff + x] = (valM2 * 1f + valM1 * 4f + valC * 6f + valP1 * 4f + valP2 * 1f) / 16f
            }
        }

        val blurred = FloatArray(size)
        for (x in 0 until width) {
            for (y in 0 until height) {
                val ym2 = (y - 2).coerceAtLeast(0)
                val ym1 = (y - 1).coerceAtLeast(0)
                val yp1 = (y + 1).coerceAtMost(height - 1)
                val yp2 = (y + 2).coerceAtMost(height - 1)

                val valM2 = temp[ym2 * width + x]
                val valM1 = temp[ym1 * width + x]
                val valC  = temp[y * width + x]
                val valP1 = temp[yp1 * width + x]
                val valP2 = temp[yp2 * width + x]

                blurred[y * width + x] = (valM2 * 1f + valM1 * 4f + valC * 6f + valP1 * 4f + valP2 * 1f) / 16f
            }
        }

        // 5. Continuous Quintic Hermite Smootherstep Curve Mapping (C2 continuity)
        val finalMask = FloatArray(size)
        val lowCut = 0.05f
        val highCut = 0.95f
        val range = highCut - lowCut

        for (i in 0 until size) {
            val raw = blurred[i]
            if (raw <= lowCut) {
                finalMask[i] = 0f
            } else if (raw >= highCut) {
                finalMask[i] = 1f
            } else {
                val t = (raw - lowCut) / range
                // Quintic smootherstep curve: 6t^5 - 15t^4 + 10t^3
                finalMask[i] = t * t * t * (t * (t * 6f - 15f) + 10f)
            }
        }

        return finalMask
    }

    fun enhanceStudioColorSpace(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var totalL = 0.0
        var count = 0
        for (i in pixels.indices) {
            val a = Color.alpha(pixels[i])
            if (a > 30) {
                val r = Color.red(pixels[i]) / 255.0
                val g = Color.green(pixels[i]) / 255.0
                val b = Color.blue(pixels[i]) / 255.0
                val l = 0.2126 * r + 0.7152 * g + 0.0722 * b
                totalL += l
                count++
            }
        }
        if (count == 0) return bitmap

        val avgL = totalL / count
        val targetL = 0.52
        val factor = if (avgL > 0.05) (targetL / avgL).coerceIn(0.85, 1.25) else 1.0

        for (i in pixels.indices) {
            val a = Color.alpha(pixels[i])
            if (a > 0) {
                var r = (Color.red(pixels[i]) * factor).toInt().coerceIn(0, 255)
                var g = (Color.green(pixels[i]) * factor).toInt().coerceIn(0, 255)
                var b = (Color.blue(pixels[i]) * factor).toInt().coerceIn(0, 255)

                val gray = (0.299 * r + 0.587 * g + 0.114 * b)
                r = (gray + 1.04 * (r - gray)).toInt().coerceIn(0, 255)
                g = (gray + 1.04 * (g - gray)).toInt().coerceIn(0, 255)
                b = (gray + 1.04 * (b - gray)).toInt().coerceIn(0, 255)

                pixels[i] = Color.argb(a, r, g, b)
            }
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }
}

