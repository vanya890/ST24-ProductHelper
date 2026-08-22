package com.example.mlkit

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max

object PipelineHelper {
    fun runNewPipeline(
        original: Bitmap,
        confidences: FloatArray,
        startX: Int,
        startY: Int,
        subjectWidth: Int,
        subjectHeight: Int,
        segmentationHelper: SegmentationHelper
    ): Bitmap {
        // 1. Extract the crop of the original image
        val cropPixels = IntArray(subjectWidth * subjectHeight)
        original.getPixels(cropPixels, 0, subjectWidth, startX, startY, subjectWidth, subjectHeight)
        val cropBitmap = Bitmap.createBitmap(cropPixels, subjectWidth, subjectHeight, Bitmap.Config.ARGB_8888)

        // Calculate upscale factor
        val maxDim = max(subjectWidth, subjectHeight)
        val targetDim = 1024f
        val scaleFactor = if (maxDim < targetDim) {
            (targetDim / maxDim).coerceIn(1.2f, 2.0f)
        } else {
            1.0f
        }
        val targetW = (subjectWidth * scaleFactor).toInt().coerceAtLeast(1)
        val targetH = (subjectHeight * scaleFactor).toInt().coerceAtLeast(1)

        // 2. Super-resolve the RGB crop
        val upscaledCropBitmap = segmentationHelper.runSuperResolution(cropBitmap, targetW, targetH)
        val upscaledCropPixels = IntArray(targetW * targetH)
        upscaledCropBitmap.getPixels(upscaledCropPixels, 0, targetW, 0, 0, targetW, targetH)

        val upscaledMask = if (scaleFactor > 1.05f) {
            segmentationHelper.upscaleChannelBilinear(confidences, subjectWidth, subjectHeight, targetW, targetH)
        } else {
            confidences
        }

        // 3. Step 2: Guided Image Filter with upscaled original image as guide
        val radius = max(4, max(targetW, targetH) / 100)
        val guidedMask = GuidedFilter.filter(upscaledCropPixels, upscaledMask, targetW, targetH, radius, 1e-3f)

        // 4. Step 1 & 3: Edge Extension (Unpremultiply & Core Laplace Diffusion)
        val cleanPixels = ForegroundEstimator.estimate(upscaledCropPixels, guidedMask, targetW, targetH, erosionRadius = 3)

        // 5. Step 4: Non-linear Alpha Compression (Smoothstep)
        val finalAlpha = segmentationHelper.compressAlphaSmoothstep(guidedMask, 0.10f, 0.90f)

        // 6. Step 5: Final Composite
        return composite(cleanPixels, finalAlpha, targetW, targetH)
    }
    
    private fun composite(cleanPixels: IntArray, mask: FloatArray, w: Int, h: Int): Bitmap {
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val alpha = mask[i]
            if (alpha <= 0.001f) {
                out[i] = Color.TRANSPARENT
            } else {
                val aInt = (alpha * 255).toInt().coerceIn(0, 255)
                val c = cleanPixels[i]
                out[i] = Color.argb(aInt, Color.red(c), Color.green(c), Color.blue(c))
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}

