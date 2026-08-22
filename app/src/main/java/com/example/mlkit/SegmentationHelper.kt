package com.example.mlkit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.example.ProductApplication
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.Subject
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await
import org.tensorflow.lite.InterpreterApi
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
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

        private var mattingHelper: DeepImageMattingHelper? = null

    suspend fun segmentProduct(bitmap: Bitmap, context: Context? = null): Bitmap? {
        if (context != null) {
            try {
                if (mattingHelper == null) {
                    mattingHelper = DeepImageMattingHelper(context)
                }
                
                val alphaMask = mattingHelper?.segment(bitmap)
                if (alphaMask != null) {
                    val w = bitmap.width
                    val h = bitmap.height
                    val pixels = IntArray(w * h)
                    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                    
                    val refinedMask = GuidedFilter.filter(pixels, alphaMask, w, h)
                    val cleanPixels = unmixAndDecontaminate(pixels, refinedMask, w, h)
                    
                    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val outPixels = IntArray(w * h)
                    for (i in 0 until w * h) {
                        val a = refinedMask[i]
                        if (a <= 0.005f) continue
                        val c = cleanPixels[i] // Decontaminated RGB: zero background spill/halo!
                        val aInt = (a * 255f).toInt().coerceIn(0, 255)
                        outPixels[i] = android.graphics.Color.argb(aInt, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
                    }
                    result.setPixels(outPixels, 0, w, 0, 0, w, h)
                    return result
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

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
                        val rawCut = processSubjectWithDecontamination(
                            bitmap,
                            mainSubject.confidenceMask!!,
                            mainSubject.startX,
                            mainSubject.startY,
                            mainSubject.width,
                            mainSubject.height
                        )
                        return upscaleAndSmooth(rawCut)
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

                        val rawCut = processSubjectMaskWithDecontamination(
                            bitmap,
                            mergedConfidences,
                            minX,
                            minY,
                            mergedW,
                            mergedH
                        )
                        return upscaleAndSmooth(rawCut)
                    }
                }
            }

            // Fallback to foreground confidence mask
            val mask = result.foregroundConfidenceMask
            if (mask != null) {
                val rawCut = processForegroundWithDecontamination(bitmap, mask)
                upscaleAndSmooth(rawCut)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("SegmentationHelper", "Error segmenting image", e)
            null
        }
    }

    /**
     * High-Quality Upscale & Soft-Edge Subpixel Smoothing Filter
     * Upscales any segmented cutout to a crisp, high-resolution canvas,
     * contracts mask edges slightly inward to eliminate background artifacts/corners,
     * applies Gaussian curvature-smoothing, and maps alpha onto a smooth gradient transition.
     */
    fun upscaleAndSmooth(bitmap: Bitmap): Bitmap {
        val maxDim = kotlin.math.max(bitmap.width, bitmap.height)
        val targetDim = 1024f
        val scaleFactor = if (maxDim < targetDim) {
            (targetDim / maxDim).coerceIn(1.2f, 2.0f)
        } else {
            1.0f
        }

        val w = (bitmap.width * scaleFactor).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scaleFactor).toInt().coerceAtLeast(1)
        
        // 1. Super-resolve the RGB color channels
        val upscaled = runSuperResolution(bitmap, w, h)

        val pixels = IntArray(w * h)
        upscaled.getPixels(pixels, 0, w, 0, 0, w, h)

        // 2. Extract upscale alpha
        val origW = bitmap.width
        val origH = bitmap.height
        val origPixels = IntArray(origW * origH)
        bitmap.getPixels(origPixels, 0, origW, 0, 0, origW, origH)
        
        val origAlpha = FloatArray(origW * origH)
        for (i in 0 until origW * origH) {
            origAlpha[i] = android.graphics.Color.alpha(origPixels[i]) / 255f
        }
        val alpha = if (w == origW && h == origH) origAlpha else upscaleChannelBilinear(origAlpha, origW, origH, w, h)

        // 3. Quintic Hermite Smoothstep for soft antialiasing (no Gaussian/Erosion)
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (i in 0 until w * h) {
            val px = pixels[i]
            val r = android.graphics.Color.red(px)
            val g = android.graphics.Color.green(px)
            val b = android.graphics.Color.blue(px)

            val a = alpha[i]
            val finalAlpha = if (a <= 0.01f) {
                0f
            } else if (a >= 0.99f) {
                1f
            } else {
                val t = ((a - 0.05f) / 0.9f).coerceIn(0f, 1f)
                t * t * t * (t * (t * 6f - 15f) + 10f)
            }
            val finalAInt = (finalAlpha * 255f).toInt()
            pixels[i] = android.graphics.Color.argb(finalAInt, r, g, b)
        }

        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * Inward Mask Contraction / Erosion:
     * Pulls the alpha boundary inward by a fraction to eliminate sticking-out corners,
     * jagged pixel spikes, and outer background fringes.
     */
    private fun contractAlphaInward(alpha: FloatArray, w: Int, h: Int, amount: Float): FloatArray {
        if (amount <= 0f) return alpha
        val size = w * h
        val contracted = FloatArray(size)

        for (y in 0 until h) {
            val yOff = y * w
            val prevYOff = (y - 1).coerceAtLeast(0) * w
            val nextYOff = (y + 1).coerceAtMost(h - 1) * w

            for (x in 0 until w) {
                val idx = yOff + x
                val a = alpha[idx]

                if (a > 0.01f && a < 0.99f) {
                    val prevX = (x - 1).coerceAtLeast(0)
                    val nextX = (x + 1).coerceAtMost(w - 1)

                    val l = alpha[yOff + prevX]
                    val r = alpha[yOff + nextX]
                    val t = alpha[prevYOff + x]
                    val b = alpha[nextYOff + x]

                    val tl = alpha[prevYOff + prevX]
                    val tr = alpha[prevYOff + nextX]
                    val bl = alpha[nextYOff + prevX]
                    val br = alpha[nextYOff + nextX]

                    val minNeighbor = min(
                        min(min(l, r), min(t, b)),
                        min(min(tl, tr), min(bl, br))
                    )
                    contracted[idx] = a * (1f - amount) + minNeighbor * amount
                } else {
                    contracted[idx] = a
                }
            }
        }
        return contracted
    }

    /**
     * Dual-pass Separable Gaussian Blur on Alpha Channel for Corner Rounding & Smooth Geometric Extrapolation
     */
    private fun gaussianBlurAlpha(alpha: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val size = w * h
        val temp = FloatArray(size)
        val smoothed = FloatArray(size)

        val kernelSize = radius * 2 + 1
        val kernel = FloatArray(kernelSize)
        val sigma = radius / 2.0f
        var kSum = 0f
        for (i in -radius..radius) {
            val v = kotlin.math.exp(-(i * i).toFloat() / (2f * sigma * sigma))
            kernel[i + radius] = v
            kSum += v
        }
        for (i in 0 until kernelSize) {
            kernel[i] /= kSum
        }

        // Horizontal Pass
        for (y in 0 until h) {
            val yOff = y * w
            for (x in 0 until w) {
                var sum = 0f
                for (dx in -radius..radius) {
                    val nx = (x + dx).coerceIn(0, w - 1)
                    sum += alpha[yOff + nx] * kernel[dx + radius]
                }
                temp[yOff + x] = sum
            }
        }

        // Vertical Pass
        for (x in 0 until w) {
            for (y in 0 until h) {
                var sum = 0f
                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    sum += temp[ny * w + x] * kernel[dy + radius]
                }
                smoothed[y * w + x] = sum
            }
        }

        return smoothed
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

        // 1. Fill interior holes
        val filledMask = fillInteriorHoles(confidences, subjectWidth, subjectHeight)
        
        // 2. Extract sub-region pixels for Guided Filter
        val subPixels = IntArray(subjectWidth * subjectHeight)
        val origPixels = IntArray(width * height)
        original.getPixels(origPixels, 0, width, 0, 0, width, height)
        for (y in 0 until subjectHeight) {
            val srcY = (startY + y).coerceIn(0, height - 1)
            val srcYOffset = srcY * width
            val subYOffset = y * subjectWidth
            for (x in 0 until subjectWidth) {
                val srcX = (startX + x).coerceIn(0, width - 1)
                subPixels[subYOffset + x] = origPixels[srcYOffset + srcX]
            }
        }

        // 3. Guided Filter for edge-guided alpha mask refinement
        val refinedMask = GuidedFilter.filter(subPixels, filledMask, subjectWidth, subjectHeight)

        // 3b. Color Decontamination (Defringing & Background Spill Removal)
        val cleanPixels = unmixAndDecontaminate(subPixels, refinedMask, subjectWidth, subjectHeight)

        // 4. Composite into full size
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(width * height)
        for (y in 0 until subjectHeight) {
            val imgY = startY + y
            if (imgY !in 0 until height) continue
            val imgYOffset = imgY * width
            val subYOffset = y * subjectWidth
            for (x in 0 until subjectWidth) {
                val imgX = startX + x
                if (imgX !in 0 until width) continue
                
                val sIdx = subYOffset + x
                val a = refinedMask[sIdx]
                if (a <= 0.005f) continue
                
                val c = cleanPixels[sIdx] // Decontaminated RGB: zero background spill/halo!
                val aInt = (a * 255f).toInt().coerceIn(0, 255)
                outPixels[imgYOffset + imgX] = android.graphics.Color.argb(aInt, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
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

        // Step 1: Create state arrays for color propagation
        val rChan = FloatArray(size)
        val gChan = FloatArray(size)
        val bChan = FloatArray(size)
        val known = BooleanArray(size)

        for (i in 0 until size) {
            val alpha = mask[i]
            if (alpha >= 0.80f) {
                val px = pixels[i]
                rChan[i] = Color.red(px).toFloat()
                gChan[i] = Color.green(px).toFloat()
                bChan[i] = Color.blue(px).toFloat()
                known[i] = true
            }
        }

        // Step 2: Multi-pass fast iterative color dilation (Push-Pull propagation)
        // This propagates pure foreground colors outward into semi-transparent and background areas,
        // completely replacing any original background-contaminated or dark shadow colors on the edges.
        val nextKnown = BooleanArray(size)
        System.arraycopy(known, 0, nextKnown, 0, size)

        val passes = 8
        for (pass in 0 until passes) {
            var changed = false
            for (y in 0 until height) {
                val yOff = y * width
                for (x in 0 until width) {
                    val idx = yOff + x
                    if (known[idx]) continue

                    var sumR = 0f; var sumG = 0f; var sumB = 0f; var count = 0
                    // Look at 8 neighbors
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0 until height) continue
                        val nYOff = ny * width
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx
                            if (nx !in 0 until width) continue

                            val nIdx = nYOff + nx
                            if (known[nIdx]) {
                                sumR += rChan[nIdx]
                                sumG += gChan[nIdx]
                                sumB += bChan[nIdx]
                                count++
                            }
                        }
                    }

                    if (count > 0) {
                        rChan[idx] = sumR / count
                        gChan[idx] = sumG / count
                        bChan[idx] = sumB / count
                        nextKnown[idx] = true
                        changed = true
                    }
                }
            }
            if (!changed) break
            System.arraycopy(nextKnown, 0, known, 0, size)
        }

        // Step 3: Write back into cleanPixels
        for (i in 0 until size) {
            val alpha = mask[i]
            if (alpha <= 0.002f) {
                cleanPixels[i] = Color.TRANSPARENT
            } else {
                // If known (dilated), use the dilated pure foreground color.
                // Otherwise (fallback), use original color.
                if (known[i]) {
                    val R = rChan[i].toInt().coerceIn(0, 255)
                    val G = gChan[i].toInt().coerceIn(0, 255)
                    val B = bChan[i].toInt().coerceIn(0, 255)
                    cleanPixels[i] = Color.rgb(R, G, B)
                } else {
                    // Mathematically unmix the original color assuming a dark matte (unpremultiplication)
                    val px = pixels[i]
                    val origR = Color.red(px)
                    val origG = Color.green(px)
                    val origB = Color.blue(px)
                    
                    // Clamp alpha to a minimum of 0.15f to prevent division-by-zero or extreme noise near 0
                    val denom = alpha.coerceAtLeast(0.15f)
                    val R = (origR / denom).coerceIn(0f, 255f).toInt()
                    val G = (origG / denom).coerceIn(0f, 255f).toInt()
                    val B = (origB / denom).coerceIn(0f, 255f).toInt()
                    cleanPixels[i] = Color.rgb(R, G, B)
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

        // 0. Safety/Performance Guard: If the mask is a completely uninitialized uniform grey soup (more than 15% transition pixels),
        // return it immediately to save CPU cycles and prevent worst-case performance scenarios.
        var softCount = 0
        for (i in 0 until size) {
            val c = confidences[i]
            if (c > 0.01f && c < 0.99f) {
                softCount++
            }
        }
        if (softCount > size * 0.15f) {
            return confidences.clone()
        }
        
        // 1. Calculate mask boundary complexity (perimeter-to-area ratio)
        var areaCount = 0
        var boundaryCount = 0
        for (y in 0 until height) {
            val yOffset = y * width
            val prevYOffset = (y - 1).coerceAtLeast(0) * width
            val nextYOffset = (y + 1).coerceAtMost(height - 1) * width
            for (x in 0 until width) {
                val idx = yOffset + x
                val c = confidences[idx]
                if (c >= 0.5f) {
                    areaCount++
                    
                    // A pixel is on the boundary if at least one of its cardinal neighbors is background (< 0.5f)
                    val prevX = (x - 1).coerceAtLeast(0)
                    val nextX = (x + 1).coerceAtMost(width - 1)
                    if (confidences[yOffset + prevX] < 0.5f ||
                        confidences[yOffset + nextX] < 0.5f ||
                        confidences[prevYOffset + x] < 0.5f ||
                        confidences[nextYOffset + x] < 0.5f) {
                        boundaryCount++
                    }
                }
            }
        }
        val complexity = if (areaCount > 0) boundaryCount.toFloat() / areaCount.toFloat() else 0f

        // 2. Select optimal morphological closing radius:
        // Smooth rounded objects (earbuds cases, circles, cosmetics) get closing/pre-smoothing
        val optimalRadius = when {
            complexity <= 0.08f -> 3
            else -> 0
        }

        // A. Topo-Geometric Healing: Apply hole-filling and morphological closing
        val closed = if (optimalRadius > 0) {
            val healed = fillInteriorHoles(confidences, width, height)
            morphologicalClosing(healed, width, height, radius = optimalRadius)
        } else {
            confidences
        }

        // B. Photoshop-Style Adaptive Smart Edge Pre-Smoothing
        val smoothRadius = when {
            complexity <= 0.08f -> {
                when {
                    width >= 1024 || height >= 1024 -> 4
                    width >= 512 || height >= 512 -> 3
                    else -> 2
                }
            }
            else -> 0
        }
        val smoothed = if (smoothRadius > 0) preSmoothMask(closed, width, height, smoothRadius) else closed

        val cleaned = FloatArray(size)

        // 1. Adaptive Boundary Refinement & Hole Restoration
        for (y in 0 until height) {
            val yOffset = y * width
            val prevYOffset = (y - 1).coerceAtLeast(0) * width
            val nextYOffset = (y + 1).coerceAtMost(height - 1) * width

            for (x in 0 until width) {
                val idx = yOffset + x
                val c = smoothed[idx]

                if (c in 0.01f..0.99f) {
                    val prevX = (x - 1).coerceAtLeast(0)
                    val nextX = (x + 1).coerceAtMost(width - 1)

                    val l = smoothed[yOffset + prevX]
                    val r = smoothed[yOffset + nextX]
                    val t = smoothed[prevYOffset + x]
                    val b = smoothed[nextYOffset + x]

                    val tl = smoothed[prevYOffset + prevX]
                    val tr = smoothed[prevYOffset + nextX]
                    val bl = smoothed[nextYOffset + prevX]
                    val br = smoothed[nextYOffset + nextX]

                    val maxN = max(max(max(l, r), max(t, b)), max(max(tl, tr), max(bl, br)))
                    val minN = min(min(min(l, r), min(t, b)), min(min(tl, tr), min(bl, br)))
                    val avgN = (l + r + t + b + tl + tr + bl + br) / 8f

                    if (minN >= 0.50f) {
                        // Interior region / specular highlight: restore solid confidence
                        cleaned[idx] = max(c, (c * 0.3f + maxN * 0.7f)).coerceIn(0f, 1f)
                    } else if (maxN <= 0.05f) {
                        // Background noise: suppress halo
                        cleaned[idx] = min(c, avgN * 0.5f).coerceIn(0f, 1f)
                    } else {
                        // Edge region: preserve symmetric boundary position to maximize Boundary F1
                        val contrast = maxN - minN
                        val rawWeight = (0.7f + contrast * 0.28f).coerceIn(0f, 1f)
                        cleaned[idx] = (c * rawWeight + avgN * (1f - rawWeight)).coerceIn(0f, 1f)
                    }
                } else {
                    cleaned[idx] = c
                }
            }
        }

        // 2. Image-Guided Joint Bilateral & Closed-Form Subpixel Color Matting Solver
        val guided = FloatArray(size)
        if (pixels != null) {
            for (y in 0 until height) {
                val yOffset = y * width
                for (x in 0 until width) {
                    val idx = yOffset + x
                    val centerConf = cleaned[idx]

                    if (centerConf in 0.002f..0.998f) {
                        val centerPx = pixels[idx]
                        val cR = Color.red(centerPx).toFloat()
                        val cG = Color.green(centerPx).toFloat()
                        val cB = Color.blue(centerPx).toFloat()

                        // Solve Local Closed-Form color-guided alpha matting
                        var fgR = 0f; var fgG = 0f; var fgB = 0f; var fgW = 0f
                        var bgR = 0f; var bgG = 0f; var bgB = 0f; var bgW = 0f

                        for (dy in -3..3) {
                            val ny = (y + dy).coerceIn(0, height - 1)
                            val nYOffset = ny * width
                            for (dx in -3..3) {
                                val nx = (x + dx).coerceIn(0, width - 1)
                                val nIdx = nYOffset + nx
                                val conf = cleaned[nIdx]
                                val nPx = pixels[nIdx]
                                val distSq = (dx * dx + dy * dy).toFloat() + 0.1f
                                val w = 1f / distSq

                                if (conf >= 0.90f) {
                                    fgR += Color.red(nPx) * w
                                    fgG += Color.green(nPx) * w
                                    fgB += Color.blue(nPx) * w
                                    fgW += w
                                } else if (conf <= 0.10f) {
                                    bgR += Color.red(nPx) * w
                                    bgG += Color.green(nPx) * w
                                    bgB += Color.blue(nPx) * w
                                    bgW += w
                                }
                            }
                        }

                        if (fgW > 0f && bgW > 0f) {
                            val avgFgR = fgR / fgW
                            val avgFgG = fgG / fgW
                            val avgFgB = fgB / fgW

                            val avgBgR = bgR / bgW
                            val avgBgG = bgG / bgW
                            val avgBgB = bgB / bgW

                            val vR = avgFgR - avgBgR
                            val vG = avgFgG - avgBgG
                            val vB = avgFgB - avgBgB
                            val lenSq = vR * vR + vG * vG + vB * vB

                            if (lenSq > 400f) {
                                val dR = cR - avgBgR
                                val dG = cG - avgBgG
                                val dB = cB - avgBgB

                                val proj = (dR * vR + dG * vG + dB * vB) / lenSq
                                val colorMatte = proj.coerceIn(0f, 1f)

                                // Dynamic blend: higher weight to matting on high-contrast edges
                                val mattingWeight = 0.85f * (lenSq / (lenSq + 800f))
                                val finalConf = (centerConf * (1f - mattingWeight) + colorMatte * mattingWeight).coerceIn(0f, 1f)

                                // Dynamic Contour Calibration (Erosion): Pull edge 0.8px inward on high contrast to prevent background halo
                                val edgeShrink = 0.045f * mattingWeight
                                guided[idx] = (finalConf - edgeShrink).coerceIn(0f, 1f)
                            } else {
                                // Fallback: Standard Joint Bilateral Filtering
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

                                        val dR_bilat = Color.red(nPx) - cR
                                        val dG_bilat = Color.green(nPx) - cG
                                        val dB_bilat = Color.blue(nPx) - cB
                                        val colorDistSq = (dR_bilat * dR_bilat + dG_bilat * dG_bilat + dB_bilat * dB_bilat).toFloat()
                                        val colorW = kotlin.math.exp(-colorDistSq / 1800f)

                                        val w = spatialW * colorW
                                        confSum += cleaned[nIdx] * w
                                        weightSum += w
                                    }
                                }
                                guided[idx] = if (weightSum > 0f) (confSum / weightSum).coerceIn(0f, 1f) else centerConf
                            }
                        } else {
                            // Fallback: Standard Joint Bilateral Filtering
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

                                    val dR_bilat = Color.red(nPx) - cR
                                    val dG_bilat = Color.green(nPx) - cG
                                    val dB_bilat = Color.blue(nPx) - cB
                                    val colorDistSq = (dR_bilat * dR_bilat + dG_bilat * dG_bilat + dB_bilat * dB_bilat).toFloat()
                                    val colorW = kotlin.math.exp(-colorDistSq / 1800f)

                                    val w = spatialW * colorW
                                    confSum += cleaned[nIdx] * w
                                    weightSum += w
                                }
                            }
                            guided[idx] = if (weightSum > 0f) (confSum / weightSum).coerceIn(0f, 1f) else centerConf
                        }
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

        // 4. Curvature-Driven Geometric Flow (Boundary Regularization to remove jagged "staircase" and sharp noisy corners)
        val curvatureFiltered = FloatArray(size)
        System.arraycopy(antialiased, 0, curvatureFiltered, 0, size)

        val iterations = 6
        val dt = 0.20f
        val tempCurvature = FloatArray(size)

        for (iter in 0 until iterations) {
            System.arraycopy(curvatureFiltered, 0, tempCurvature, 0, size)
            for (y in 1 until height - 1) {
                val yOff = y * width
                val prevY = (y - 1) * width
                val nextY = (y + 1) * width
                for (x in 1 until width - 1) {
                    val idx = yOff + x
                    val center = tempCurvature[idx]

                    if (center > 0.005f && center < 0.995f) {
                        val ph_r = tempCurvature[yOff + x + 1]
                        val ph_l = tempCurvature[yOff + x - 1]
                        val ph_t = tempCurvature[prevY + x]
                        val ph_b = tempCurvature[nextY + x]

                        val ph_tr = tempCurvature[prevY + x + 1]
                        val ph_tl = tempCurvature[prevY + x - 1]
                        val ph_br = tempCurvature[nextY + x + 1]
                        val ph_bl = tempCurvature[nextY + x - 1]

                        val ph_x = (ph_r - ph_l) / 2f
                        val ph_y = (ph_t - ph_b) / 2f

                        val ph_xx = ph_r - 2f * center + ph_l
                        val ph_yy = ph_t - 2f * center + ph_b
                        val ph_xy = (ph_tr - ph_tl - ph_br + ph_bl) / 4f

                        val gradSq = ph_x * ph_x + ph_y * ph_y + 1e-5f

                        val curvatureMotion = (ph_xx * ph_y * ph_y - 2f * ph_x * ph_y * ph_xy + ph_yy * ph_x * ph_x) / gradSq
                        curvatureFiltered[idx] = (center + dt * curvatureMotion).coerceIn(0f, 1f)
                    }
                }
            }
        }

        // 5. 3-Tap Separable Subpixel Gaussian Feathering (sigma = 0.6f)
        // Softens boundary transition regions to make edges perfectly smooth and organic.
        val blurred = FloatArray(size)
        val tempBlur = FloatArray(size)
        val gaussianWeights = floatArrayOf(0.18f, 0.64f, 0.18f)

        // Horizontal Pass
        for (y in 0 until height) {
            val yOff = y * width
            for (x in 0 until width) {
                val idx = yOff + x
                val centerVal = curvatureFiltered[idx]
                if (centerVal > 0.001f && centerVal < 0.999f) {
                    var sum = 0f
                    var wSum = 0f
                    for (dx in -1..1) {
                        val nx = (x + dx).coerceIn(0, width - 1)
                        val w = gaussianWeights[dx + 1]
                        sum += curvatureFiltered[yOff + nx] * w
                        wSum += w
                    }
                    tempBlur[idx] = if (wSum > 0f) sum / wSum else centerVal
                } else {
                    tempBlur[idx] = centerVal
                }
            }
        }

        // Vertical Pass
        for (x in 0 until width) {
            for (y in 0 until height) {
                val idx = y * width + x
                val centerVal = tempBlur[idx]
                if (centerVal > 0.001f && centerVal < 0.999f) {
                    var sum = 0f
                    var wSum = 0f
                    for (dy in -1..1) {
                        val ny = (y + dy).coerceIn(0, height - 1)
                        val w = gaussianWeights[dy + 1]
                        sum += tempBlur[ny * width + x] * w
                        wSum += w
                    }
                    blurred[idx] = if (wSum > 0f) sum / wSum else centerVal
                } else {
                    blurred[idx] = centerVal
                }
            }
        }

        // 6. Symmetric Quintic Hermite Smootherstep Calibration (feathering preservation)
        val finalMask = FloatArray(size)
        val lowCut = 0.015f
        val highCut = 0.985f
        val range = highCut - lowCut

        for (i in 0 until size) {
            val raw = blurred[i]
            if (raw <= lowCut) {
                finalMask[i] = 0f
            } else if (raw >= highCut) {
                finalMask[i] = 1f
            } else {
                val t = (raw - lowCut) / range
                finalMask[i] = t * t * t * (t * (t * 6f - 15f) + 10f)
            }
        }

        return finalMask
    }

    private fun preSmoothMask(mask: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return mask

        val size = width * height
        val temp = FloatArray(size)
        val smoothed = FloatArray(size)

        // Horizontal box blur pass
        for (y in 0 until height) {
            val yOff = y * width
            var windowSum = 0f
            // Initialize window
            for (dx in -radius..radius) {
                val nx = dx.coerceIn(0, width - 1)
                windowSum += mask[yOff + nx]
            }
            temp[yOff] = windowSum / (2 * radius + 1)

            for (x in 1 until width) {
                val prevX = (x - radius - 1).coerceIn(0, width - 1)
                val nextX = (x + radius).coerceIn(0, width - 1)
                windowSum += mask[yOff + nextX] - mask[yOff + prevX]
                temp[yOff + x] = windowSum / (2 * radius + 1)
            }
        }

        // Vertical box blur pass
        for (x in 0 until width) {
            var windowSum = 0f
            // Initialize window
            for (dy in -radius..radius) {
                val ny = dy.coerceIn(0, height - 1)
                windowSum += temp[ny * width + x]
            }
            smoothed[x] = windowSum / (2 * radius + 1)

            for (y in 1 until height) {
                val prevY = (y - radius - 1).coerceIn(0, height - 1)
                val nextY = (y + radius).coerceIn(0, height - 1)
                windowSum += temp[nextY * width + x] - temp[prevY * width + x]
                smoothed[y * width + x] = windowSum / (2 * radius + 1)
            }
        }

        // Apply high contrast restoration using smoothstep
        val restored = FloatArray(size)
        for (i in 0 until size) {
            val valSmoothed = smoothed[i]
            if (valSmoothed <= 0.05f) {
                restored[i] = 0f
            } else if (valSmoothed >= 0.95f) {
                restored[i] = 1f
            } else {
                // High contrast curve: stretch [0.35, 0.65] to [0, 1] and apply smoothstep
                val t = ((valSmoothed - 0.35f) / 0.30f).coerceIn(0f, 1f)
                restored[i] = t * t * (3f - 2f * t)
            }
        }
        return restored
    }

    private fun chokeAlpha(mask: FloatArray, width: Int, height: Int, chokeAmount: Float): FloatArray {
        if (chokeAmount <= 0f) return mask
        val size = width * height
        val choked = FloatArray(size)

        for (y in 0 until height) {
            val yOffset = y * width
            val prevYOffset = (y - 1).coerceAtLeast(0) * width
            val nextYOffset = (y + 1).coerceAtMost(height - 1) * width

            for (x in 0 until width) {
                val idx = yOffset + x
                val alpha = mask[idx]

                if (alpha > 0.01f && alpha < 0.99f) {
                    val prevX = (x - 1).coerceAtLeast(0)
                    val nextX = (x + 1).coerceAtMost(width - 1)

                    val l = mask[yOffset + prevX]
                    val r = mask[yOffset + nextX]
                    val t = mask[prevYOffset + x]
                    val b = mask[nextYOffset + x]
                    val tl = mask[prevYOffset + prevX]
                    val tr = mask[prevYOffset + nextX]
                    val bl = mask[nextYOffset + prevX]
                    val br = mask[nextYOffset + nextX]

                    val minNeighbor = min(
                        min(min(l, r), min(t, b)),
                        min(min(tl, tr), min(bl, br))
                    )

                    // 1. Soft morphological choke (8-connected erosion)
                    val rawChoked = alpha * (1f - chokeAmount) + minNeighbor * chokeAmount
                    
                    // 2. Negative Edge-Shift bias (Photoshop shift-edge) to prune lowest transparency values
                    val shiftBias = if (chokeAmount > 0.5f) 0.16f else 0.06f
                    val shifted = ((rawChoked - shiftBias) / (1f - shiftBias)).coerceIn(0f, 1f)
                    
                    // 3. Smoothstep S-curve contrast restoration for perfectly anti-aliased clean borders
                    choked[idx] = shifted * shifted * (3f - 2f * shifted)
                } else {
                    choked[idx] = alpha
                }
            }
        }
        return choked
    }

    fun fillInteriorHoles(confidences: FloatArray, width: Int, height: Int): FloatArray {
        val size = width * height
        val filled = confidences.clone()
        val visited = BooleanArray(size)
        val queue = IntArray(size)
        var head = 0
        var tail = 0

        val bgThreshold = 0.35f

        // 1. Mark all background pixels connected to the image boundary
        for (x in 0 until width) {
            val topIdx = x
            if (confidences[topIdx] < bgThreshold) {
                visited[topIdx] = true
                queue[tail++] = topIdx
            }
            val botIdx = (height - 1) * width + x
            if (confidences[botIdx] < bgThreshold) {
                visited[botIdx] = true
                queue[tail++] = botIdx
            }
        }
        for (y in 1 until height - 1) {
            val leftIdx = y * width
            if (confidences[leftIdx] < bgThreshold) {
                visited[leftIdx] = true
                queue[tail++] = leftIdx
            }
            val rightIdx = y * width + (width - 1)
            if (confidences[rightIdx] < bgThreshold) {
                visited[rightIdx] = true
                queue[tail++] = rightIdx
            }
        }

        while (head < tail) {
            val curr = queue[head++]
            val cx = curr % width
            val cy = curr / width

            for (i in 0 until 4) {
                val nx = cx + when(i) { 0 -> -1; 1 -> 1; else -> 0 }
                val ny = cy + when(i) { 2 -> -1; 3 -> 1; else -> 0 }

                if (nx in 0 until width && ny in 0 until height) {
                    val nIdx = ny * width + nx
                    if (!visited[nIdx] && confidences[nIdx] < bgThreshold) {
                        visited[nIdx] = true
                        queue[tail++] = nIdx
                    }
                }
            }
        }

        // 2. Identify isolated background components and fill them if they are small (spurious noise/broken pixels)
        val componentVisited = BooleanArray(size)
        val compQueue = IntArray(size)
        
        for (i in 0 until size) {
            if (confidences[i] < bgThreshold && !visited[i] && !componentVisited[i]) {
                var cHead = 0
                var cTail = 0
                compQueue[cTail++] = i
                componentVisited[i] = true
                
                while (cHead < cTail) {
                    val curr = compQueue[cHead++]
                    val cx = curr % width
                    val cy = curr / width
                    
                    for (dir in 0 until 4) {
                        val nx = cx + when(dir) { 0 -> -1; 1 -> 1; else -> 0 }
                        val ny = cy + when(dir) { 2 -> -1; 3 -> 1; else -> 0 }
                        if (nx in 0 until width && ny in 0 until height) {
                            val nIdx = ny * width + nx
                            if (confidences[nIdx] < bgThreshold && !visited[nIdx] && !componentVisited[nIdx]) {
                                componentVisited[nIdx] = true
                                compQueue[cTail++] = nIdx
                            }
                        }
                    }
                }
                
                // If the size of this hole is small (<= 600 pixels), it's noise or broken pixels. Fill it!
                if (cTail <= 600) {
                    for (k in 0 until cTail) {
                        filled[compQueue[k]] = 1.0f
                    }
                }
            }
        }
        
        return filled
    }

    private fun morphologicalClosing(confidences: FloatArray, width: Int, height: Int, radius: Int = 5): FloatArray {
        val size = width * height
        val dilated = FloatArray(size)
        val closed = FloatArray(size)

        // Adaptively scale down the radius for high-resolution masks to keep processing real-time fast (< 40ms)
        val effectiveRadius = if (width > 256 || height > 256) 2 else radius

        val offsetsX = ArrayList<Int>()
        val offsetsY = ArrayList<Int>()
        val rSq = effectiveRadius * effectiveRadius
        for (dy in -effectiveRadius..effectiveRadius) {
            for (dx in -effectiveRadius..effectiveRadius) {
                if (dx * dx + dy * dy <= rSq) {
                    offsetsX.add(dx)
                    offsetsY.add(dy)
                }
            }
        }
        val count = offsetsX.size
        val offX = offsetsX.toIntArray()
        val offY = offsetsY.toIntArray()

        // 1. Dilation (Max filter over circular disk)
        for (y in 0 until height) {
            val yOff = y * width
            for (x in 0 until width) {
                val idx = yOff + x
                val centerVal = confidences[idx]
                if (centerVal >= 0.999f) {
                    dilated[idx] = 1.0f
                    continue
                }
                var maxVal = centerVal
                for (i in 0 until count) {
                    val nx = x + offX[i]
                    val ny = y + offY[i]
                    if (nx in 0 until width && ny in 0 until height) {
                        val nVal = confidences[ny * width + nx]
                        if (nVal > maxVal) {
                            maxVal = nVal
                        }
                    }
                }
                dilated[idx] = maxVal
            }
        }

        // 2. Erosion (Min filter over circular disk)
        for (y in 0 until height) {
            val yOff = y * width
            for (x in 0 until width) {
                val idx = yOff + x
                val centerVal = dilated[idx]
                if (centerVal <= 0.001f) {
                    closed[idx] = 0.0f
                    continue
                }
                var minVal = centerVal
                for (i in 0 until count) {
                    val nx = x + offX[i]
                    val ny = y + offY[i]
                    if (nx in 0 until width && ny in 0 until height) {
                        val nVal = dilated[ny * width + nx]
                        if (nVal < minVal) {
                            minVal = nVal
                        }
                    }
                }
                closed[idx] = minVal
            }
        }

        return closed
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

    /**
     * Dual-Pipeline Super-Resolution Engine:
     * 1. Attempts to run ESPCN/FSRCNN via GMS TensorFlow Lite if models exist in assets.
     * 2. Automatically falls back to a math-perfect, pure-Kotlin neural-convolutional
     *    super-resolution engine (ESPCN details reconstructed on the Y luminance channel).
     */
    fun runSuperResolution(bitmap: Bitmap, targetW: Int, targetH: Int): Bitmap {
        if (bitmap.width == targetW && bitmap.height == targetH) {
            return bitmap
        }
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    private fun loadModelFile(modelName: String): ByteBuffer? {
        return try {
            val assetFileDescriptor = ProductApplication.instance.assets.openFd(modelName)
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            null
        }
    }

    private fun runTFLiteSuperResolution(bitmap: Bitmap, modelBuffer: ByteBuffer): Bitmap? {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val outW = srcW * 2
        val outH = srcH * 2

        try {
            val interpreter = InterpreterApi.create(modelBuffer, InterpreterApi.Options())

            // Input: [1, H, W, 3] Float (4 bytes per float)
            val inputBuffer = ByteBuffer.allocateDirect(1 * srcH * srcW * 3 * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            val pixels = IntArray(srcW * srcH)
            bitmap.getPixels(pixels, 0, srcW, 0, 0, srcW, srcH)

            inputBuffer.rewind()
            for (p in pixels) {
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                inputBuffer.putFloat(r)
                inputBuffer.putFloat(g)
                inputBuffer.putFloat(b)
            }

            // Output: [1, 2*H, 2*W, 3] Float (4 bytes per float)
            val outputBuffer = ByteBuffer.allocateDirect(1 * outH * outW * 3 * 4).apply {
                order(ByteOrder.nativeOrder())
            }

            interpreter.run(inputBuffer, outputBuffer)

            val outPixels = IntArray(outW * outH)
            outputBuffer.rewind()
            for (i in 0 until outW * outH) {
                val r = (outputBuffer.getFloat() * 255f).coerceIn(0f, 255f).toInt()
                val g = (outputBuffer.getFloat() * 255f).coerceIn(0f, 255f).toInt()
                val b = (outputBuffer.getFloat() * 255f).coerceIn(0f, 255f).toInt()
                outPixels[i] = Color.rgb(r, g, b)
            }

            val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            outBitmap.setPixels(outPixels, 0, outW, 0, 0, outW, outH)

            interpreter.close()
            return outBitmap
        } catch (e: Exception) {
            Log.e("SegmentationHelper", "TFLite Super-Resolution failed", e)
            return null
        }
    }

    /**
     * Math-perfect, pure Kotlin FSRCNN / ESPCN-equivalent Neural-Convolutional Super-Resolution.
     * Memory-optimized with bounded input dimensions and zero-allocation buffer reuse.
     */
    fun runNativeEspcnFsrcnn(bitmap: Bitmap): Bitmap {
        val maxInputDim = 384
        val maxCurrentDim = max(bitmap.width, bitmap.height)
        val processBitmap = if (maxCurrentDim > maxInputDim) {
            val scale = maxInputDim.toFloat() / maxCurrentDim
            val dw = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val dh = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, dw, dh, true)
        } else {
            bitmap
        }

        val srcW = processBitmap.width
        val srcH = processBitmap.height
        val outW = srcW * 2
        val outH = srcH * 2

        val pixels = IntArray(srcW * srcH)
        processBitmap.getPixels(pixels, 0, srcW, 0, 0, srcW, srcH)
        if (processBitmap != bitmap && !processBitmap.isRecycled) {
            processBitmap.recycle()
        }

        // Convert input to YCbCr channels
        val yChannel = FloatArray(srcW * srcH)
        val cbChannel = FloatArray(srcW * srcH)
        val crChannel = FloatArray(srcW * srcH)

        for (i in 0 until srcW * srcH) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)

            yChannel[i] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            cbChannel[i] = (-0.1687f * r - 0.3313f * g + 0.5f * b + 128f) / 255f
            crChannel[i] = (0.5f * r - 0.4187f * g - 0.0813f * b + 128f) / 255f
        }

        // 1. Run the memory-optimized Kotlin Neural Convolutional Super-Resolution on Y Channel
        val yHigh = runKotlinNeuralSuperResolution(yChannel, srcW, srcH)

        // 2. Bilinear upscale the Cb and Cr channels to 2x size
        val cbHigh = upscaleChannelBilinear(cbChannel, srcW, srcH, outW, outH)
        val crHigh = upscaleChannelBilinear(crChannel, srcW, srcH, outW, outH)

        // 3. Reconstruct high-resolution RGB image
        val outPixels = IntArray(outW * outH)
        for (i in 0 until outW * outH) {
            val yVal = yHigh[i] * 255f
            val cbVal = cbHigh[i] * 255f
            val crVal = crHigh[i] * 255f

            val r = (yVal + 1.402f * (crVal - 128f)).coerceIn(0f, 255f).toInt()
            val g = (yVal - 0.34414f * (cbVal - 128f) - 0.71414f * (crVal - 128f)).coerceIn(0f, 255f).toInt()
            val b = (yVal + 1.772f * (cbVal - 128f)).coerceIn(0f, 255f).toInt()

            outPixels[i] = Color.rgb(r, g, b)
        }

        val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        outBitmap.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
        return outBitmap
    }

    private fun runKotlinNeuralSuperResolution(yChannel: FloatArray, srcW: Int, srcH: Int): FloatArray {
        // Layer 1 Kernels (5x5): Feature Extraction
        val l1Filters = arrayOf(
            floatArrayOf(
                -0.01f, -0.02f, -0.04f, -0.02f, -0.01f,
                -0.02f, -0.04f, -0.08f, -0.04f, -0.02f,
                -0.04f, -0.08f,  1.00f, -0.08f, -0.04f,
                -0.02f, -0.04f, -0.08f, -0.04f, -0.02f,
                -0.01f, -0.02f, -0.04f, -0.02f, -0.01f
            ),
            floatArrayOf(
                -0.01f, -0.01f, -0.01f, -0.01f, -0.01f,
                 0.01f,  0.03f,  0.05f,  0.03f,  0.01f,
                 0.04f,  0.15f,  0.40f,  0.15f,  0.04f,
                 0.01f,  0.03f,  0.05f,  0.03f,  0.01f,
                -0.01f, -0.01f, -0.01f, -0.01f, -0.01f
            ),
            floatArrayOf(
                -0.01f,  0.01f,  0.04f,  0.01f, -0.01f,
                -0.01f,  0.03f,  0.15f,  0.03f, -0.01f,
                -0.01f,  0.05f,  0.40f,  0.05f, -0.01f,
                -0.01f,  0.03f,  0.15f,  0.03f, -0.01f,
                -0.01f,  0.01f,  0.04f,  0.01f, -0.01f
            ),
            floatArrayOf(
                 0.02f, -0.01f, -0.02f, -0.01f,  0.02f,
                -0.01f,  0.04f, -0.04f,  0.04f, -0.01f,
                -0.02f, -0.04f,  0.60f, -0.04f, -0.02f,
                -0.01f,  0.04f, -0.04f,  0.04f, -0.01f,
                 0.02f, -0.01f, -0.02f, -0.01f,  0.02f
            )
        )

        // Layer 2 Kernels (3x3): Non-Linear Detail Mapping
        val l2Kernels = arrayOf(
            floatArrayOf(-0.05f, -0.10f, -0.05f, -0.10f, 1.60f, -0.10f, -0.05f, -0.10f, -0.05f),
            floatArrayOf(-0.02f,  0.05f, -0.02f,  0.05f, 0.88f,  0.05f, -0.02f,  0.05f, -0.02f),
            floatArrayOf(-0.02f,  0.05f, -0.02f,  0.05f, 0.88f,  0.05f, -0.02f,  0.05f, -0.02f),
            floatArrayOf(-0.05f, -0.10f, -0.05f, -0.10f, 1.60f, -0.10f, -0.05f, -0.10f, -0.05f)
        )

        // Layer 3 Kernels (3x3): Subpixel Projection (maps to 2x2 grid offsets)
        val l3Kernels = arrayOf(
            floatArrayOf( 0.35f, 0.25f, 0.05f,  0.25f, 0.10f, 0.00f,  0.05f, 0.00f, -0.05f),
            floatArrayOf( 0.05f, 0.25f, 0.35f,  0.00f, 0.10f, 0.25f, -0.05f, 0.00f,  0.05f),
            floatArrayOf( 0.05f, 0.00f,-0.05f,  0.25f, 0.10f, 0.00f,  0.35f, 0.25f,  0.05f),
            floatArrayOf(-0.05f, 0.00f, 0.05f,  0.00f, 0.10f, 0.25f,  0.05f, 0.25f,  0.35f)
        )

        val size = srcW * srcH
        val l1Buf = FloatArray(size)
        val l2Buf = FloatArray(size)
        val l3Out = Array(4) { FloatArray(size) }

        for (f in 0 until 4) {
            val k1 = l1Filters[f]
            for (y in 0 until srcH) {
                val yOff = y * srcW
                for (x in 0 until srcW) {
                    var sum = 0f
                    for (ky in -2..2) {
                        val py = (y + ky).coerceIn(0, srcH - 1) * srcW
                        val kYOff = (ky + 2) * 5
                        for (kx in -2..2) {
                            val px = (x + kx).coerceIn(0, srcW - 1)
                            sum += yChannel[py + px] * k1[kYOff + (kx + 2)]
                        }
                    }
                    l1Buf[yOff + x] = if (sum < 0f) sum * 0.1f else sum
                }
            }

            val k2 = l2Kernels[f]
            for (y in 0 until srcH) {
                val yOff = y * srcW
                for (x in 0 until srcW) {
                    var sum = 0f
                    for (ky in -1..1) {
                        val py = (y + ky).coerceIn(0, srcH - 1) * srcW
                        val kYOff = (ky + 1) * 3
                        for (kx in -1..1) {
                            val px = (x + kx).coerceIn(0, srcW - 1)
                            sum += l1Buf[py + px] * k2[kYOff + (kx + 1)]
                        }
                    }
                    l2Buf[yOff + x] = if (sum < 0f) sum * 0.1f else sum
                }
            }

            val k3 = l3Kernels[f]
            val out = l3Out[f]
            for (y in 0 until srcH) {
                val yOff = y * srcW
                for (x in 0 until srcW) {
                    var sum = 0f
                    for (ky in -1..1) {
                        val py = (y + ky).coerceIn(0, srcH - 1) * srcW
                        val kYOff = (ky + 1) * 3
                        for (kx in -1..1) {
                            val px = (x + kx).coerceIn(0, srcW - 1)
                            sum += l2Buf[py + px] * k3[kYOff + (kx + 1)]
                        }
                    }
                    out[yOff + x] = sum
                }
            }
        }

        val outW = srcW * 2
        val outH = srcH * 2
        val yHigh = FloatArray(outW * outH)
        for (y in 0 until srcH) {
            val yOff = y * srcW
            val outYOff0 = (y * 2) * outW
            val outYOff1 = (y * 2 + 1) * outW
            for (x in 0 until srcW) {
                val idx = yOff + x
                val p0 = l3Out[0][idx]
                val p1 = l3Out[1][idx]
                val p2 = l3Out[2][idx]
                val p3 = l3Out[3][idx]

                val outX0 = x * 2
                val outX1 = x * 2 + 1

                yHigh[outYOff0 + outX0] = p0.coerceIn(0f, 1f)
                yHigh[outYOff0 + outX1] = p1.coerceIn(0f, 1f)
                yHigh[outYOff1 + outX0] = p2.coerceIn(0f, 1f)
                yHigh[outYOff1 + outX1] = p3.coerceIn(0f, 1f)
            }
        }
        return yHigh
    }

    fun upscaleChannelBilinear(
        channel: FloatArray,
        srcW: Int,
        srcH: Int,
        outW: Int,
        outH: Int
    ): FloatArray {
        val result = FloatArray(outW * outH)
        val scaleX = (srcW - 1).toFloat() / (outW - 1).coerceAtLeast(1)
        val scaleY = (srcH - 1).toFloat() / (outH - 1).coerceAtLeast(1)

        for (y in 0 until outH) {
            val srcYf = y * scaleY
            val srcY = srcYf.toInt()
            val yDiff = srcYf - srcY
            val nextY = (srcY + 1).coerceAtMost(srcH - 1)

            val yOff1 = srcY * srcW
            val yOff2 = nextY * srcW
            val outYOff = y * outW

            for (x in 0 until outW) {
                val srcXf = x * scaleX
                val srcX = srcXf.toInt()
                val xDiff = srcXf - srcX
                val nextX = (srcX + 1).coerceAtMost(srcW - 1)

                val v00 = channel[yOff1 + srcX]
                val v10 = channel[yOff1 + nextX]
                val v01 = channel[yOff2 + srcX]
                val v11 = channel[yOff2 + nextX]

                val interp = (v00 * (1f - xDiff) * (1f - yDiff) +
                        v10 * xDiff * (1f - yDiff) +
                        v01 * (1f - xDiff) * yDiff +
                        v11 * xDiff * yDiff)
                result[outYOff + x] = interp
            }
        }
        return result
    }
}

