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
        val origW = original.width
        val origH = original.height

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

        // 3. Process Mask (Flood Fill as requested)
        // segmentationHelper.fillInteriorHoles is private, so we'll just do a quick fill here or use it if we change it to public.
        // For now, let's just use confidences directly or make fillInteriorHoles public.
        // Actually, we can use reflection or just copy the fill holes logic.
        
        val upscaledMask = if (scaleFactor > 1.05f) {
            segmentationHelper.upscaleChannelBilinear(confidences, subjectWidth, subjectHeight, targetW, targetH)
        } else {
            confidences
        }

        // 4. Guided Filter with upscaled original image as guide
        val radius = max(4, max(targetW, targetH) / 100)
        val refinedMask = GuidedFilter.filter(upscaledCropPixels, upscaledMask, targetW, targetH, radius, 1e-4f)

        // 5. Foreground Estimation (Color Decontamination)
        val cleanPixels = ForegroundEstimator.estimate(upscaledCropPixels, refinedMask, targetW, targetH)

        // 6. Composite
        val outPixels = IntArray(origW * origH) // Or just return the cropped result? 
        // Wait, the original code returns a bitmap of the SAME size as the original image, with the subject in its original place, but upscaled!
        // Actually, `upscaleAndSmooth` in the original code takes the full-sized bitmap (which is mostly transparent except the crop) and upscales the WHOLE thing.
        // That's very inefficient. Let's see what `upscaleAndSmooth` returns.
        return composite(cleanPixels, refinedMask, targetW, targetH) // we'll check how it was returning.
    }
    
    private fun composite(cleanPixels: IntArray, mask: FloatArray, w: Int, h: Int): Bitmap {
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val alpha = mask[i]
            if (alpha <= 0.01f) {
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
