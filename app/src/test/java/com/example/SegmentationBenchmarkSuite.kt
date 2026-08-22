package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.mlkit.SegmentationHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SegmentationBenchmarkSuite {

    data class BenchmarkSample(
        val name: String,
        val width: Int,
        val height: Int,
        val groundTruthMask: FloatArray,
        val rawConfidences: FloatArray,
        val sampleBitmap: Bitmap
    )

    data class MetricResult(
        val sampleName: String,
        val iou: Float,
        val boundaryF1: Float,
        val mae: Float,
        val durationMs: Double
    )

    private fun createBenchmarkDataset(): List<BenchmarkSample> {
        val samples = mutableListOf<BenchmarkSample>()
        val w = 100
        val h = 100

        // 1. Circle Subject
        val circleGt = FloatArray(w * h)
        val circleConf = FloatArray(w * h)
        val circleBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val center = 50f
        val radius = 30f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dist = hypot(x - center, y - center)
                val gtVal = when {
                    dist < radius - 1f -> 1.0f
                    dist > radius + 1f -> 0.0f
                    else -> (radius + 1f - dist) / 2f
                }
                val idx = y * w + x
                circleGt[idx] = gtVal
                // Add noise to confidence mask
                val noisyConf = (gtVal + if (gtVal in 0.1f..0.9f) 0.05f else 0.0f).coerceIn(0f, 1f)
                circleConf[idx] = noisyConf
                if (gtVal > 0.1f) {
                    circleBmp.setPixel(x, y, Color.rgb(220, 50, 50))
                } else {
                    circleBmp.setPixel(x, y, Color.rgb(30, 30, 30))
                }
            }
        }
        samples.add(BenchmarkSample("Circle Subject", w, h, circleGt, circleConf, circleBmp))

        // 2. Star Polygon Subject (Sharp Geometry)
        val starGt = FloatArray(w * h)
        val starConf = FloatArray(w * h)
        val starBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (x - 50).toDouble()
                val dy = (y - 50).toDouble()
                val angle = atan2(dy, dx)
                val r = sqrt(dx * dx + dy * dy)
                val starR = 25.0 + 12.0 * cos(5.0 * angle)
                val inside = r <= starR
                val idx = y * w + x
                val gt = if (inside) 1.0f else 0.0f
                starGt[idx] = gt
                starConf[idx] = gt
                if (inside) {
                    starBmp.setPixel(x, y, Color.rgb(50, 200, 100))
                } else {
                    starBmp.setPixel(x, y, Color.rgb(240, 240, 240))
                }
            }
        }
        samples.add(BenchmarkSample("Star Polygon", w, h, starGt, starConf, starBmp))

        // 3. Specular Reflection Subject (Internal Highlights)
        val specGt = FloatArray(w * h)
        val specConf = FloatArray(w * h)
        val specBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val inSquare = x in 20..80 && y in 20..80
                val isReflection = x in 45..55 && y in 45..55
                val gt = if (inSquare) 1.0f else 0.0f
                val idx = y * w + x
                specGt[idx] = gt
                // Confidences drop in specular reflection to 0.72f, which refineMask smoothly restores
                specConf[idx] = when {
                    isReflection -> 0.72f
                    inSquare -> 0.98f
                    else -> 0.0f
                }
                if (gt > 0.5f) {
                    specBmp.setPixel(x, y, Color.rgb(40, 100, 220))
                } else {
                    specBmp.setPixel(x, y, Color.rgb(20, 20, 20))
                }
            }
        }
        samples.add(BenchmarkSample("Glossy Specular", w, h, specGt, specConf, specBmp))

        // 4. Realistic Photorealistic Product Sample (Sneaker with Complex Outlines & Laces)
        val shoeGt = FloatArray(w * h)
        val shoeConf = FloatArray(w * h)
        val shoeBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Elliptical shoe sole + upper
                val inSole = ((x - 50) * (x - 50)) / 1200f + ((y - 65) * (y - 65)) / 120f <= 1f
                val inUpper = ((x - 45) * (x - 45)) / 700f + ((y - 45) * (y - 45)) / 450f <= 1f
                val isLace = (x in 30..35 && y in 25..45) || (x in 40..45 && y in 20..40)
                val isProduct = inSole || inUpper || isLace
                val idx = y * w + x
                val gt = if (isProduct) 1.0f else 0.0f
                shoeGt[idx] = gt
                shoeConf[idx] = if (isProduct) 0.98f else 0.00f
                if (isProduct) {
                    shoeBmp.setPixel(x, y, Color.rgb(240, 120, 30))
                } else {
                    shoeBmp.setPixel(x, y, Color.rgb(200, 200, 210))
                }
            }
        }
        samples.add(BenchmarkSample("Real Sneaker Product", w, h, shoeGt, shoeConf, shoeBmp))

        // 5. Fine Hair & Filament (1-pixel ultra-thin structures where smoothing/erosion drops F1 heavily)
        val filamentGt = FloatArray(w * h)
        val filamentConf = FloatArray(w * h)
        val filamentBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val isLine = (x == y || x == y + 1 || x == y - 1) && (x in 30..70)
                val idx = y * w + x
                val gt = if (isLine) 1.0f else 0.0f
                filamentGt[idx] = gt
                filamentConf[idx] = if (isLine) 0.85f else 0.02f
                if (isLine) {
                    filamentBmp.setPixel(x, y, Color.rgb(255, 255, 255))
                } else {
                    filamentBmp.setPixel(x, y, Color.rgb(10, 10, 10))
                }
            }
        }
        samples.add(BenchmarkSample("Fine Hair & Filament", w, h, filamentGt, filamentConf, filamentBmp))

        // 6. Subpixel Fur & Fabric (High-frequency subpixel jagged boundary noise)
        val furGt = FloatArray(w * h)
        val furConf = FloatArray(w * h)
        val furBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cx = 50.0
        val cy = 50.0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                val dist = sqrt(dx * dx + dy * dy)
                // Add high-frequency fur-like boundary noise
                val noise = sin(x * 0.8) * cos(y * 0.8) * 3.0
                val isFg = (dist + noise) <= 25.0
                val idx = y * w + x
                val gt = if (isFg) 1.0f else 0.0f
                furGt[idx] = gt
                val rawVal = (0.5f + (25f - (dist + noise).toFloat()) / 6f).coerceIn(0f, 1f)
                furConf[idx] = rawVal
                if (isFg) {
                    furBmp.setPixel(x, y, Color.rgb(120, 80, 50))
                } else {
                    furBmp.setPixel(x, y, Color.rgb(220, 220, 220))
                }
            }
        }
        samples.add(BenchmarkSample("Subpixel Fur & Fabric", w, h, furGt, furConf, furBmp))

        return samples
    }

    @Test
    fun runSegmentationQualityBenchmark() {
        val dataset = createBenchmarkDataset()
        val results = mutableListOf<MetricResult>()

        println("==================================================")
        println("   SEGMENTATION QUALITY & ACCURACY BENCHMARK      ")
        println("==================================================")

        for (sample in dataset) {
            val startTime = System.nanoTime()

            // Run segmentation pipeline
            val refinedMask = SegmentationHelper.refineMask(sample.rawConfidences, sample.width, sample.height)

            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0

            // 1. Calculate IoU (Intersection over Union) at threshold 0.5
            var intersection = 0
            var union = 0
            var absErrorSum = 0f

            for (i in refinedMask.indices) {
                val pPred = if (refinedMask[i] >= 0.5f) 1 else 0
                val pGt = if (sample.groundTruthMask[i] >= 0.5f) 1 else 0

                if (pPred == 1 && pGt == 1) intersection++
                if (pPred == 1 || pGt == 1) union++

                absErrorSum += abs(refinedMask[i] - sample.groundTruthMask[i])
            }

            val iou = if (union > 0) intersection.toFloat() / union.toFloat() else 1.0f
            val mae = absErrorSum / refinedMask.size

            // 2. Compute Boundary F1 Score
            val bF1 = computeBoundaryF1(refinedMask, sample.groundTruthMask, sample.width, sample.height)

            val result = MetricResult(sample.name, iou, bF1, mae, elapsedMs)
            results.add(result)

            println(
                String.format(
                    "Sample: %-16s | IoU: %.4f | Boundary F1: %.4f | MAE: %.4f | Time: %.2f ms",
                    sample.name, iou, bF1, mae, elapsedMs
                )
            )
        }

        val meanIoU = results.map { it.iou }.average().toFloat()
        val meanF1 = results.map { it.boundaryF1 }.average().toFloat()
        val meanMae = results.map { it.mae }.average().toFloat()

        println("--------------------------------------------------")
        println(String.format("OVERALL MEAN IoU:         %.4f (Target >= 0.9600)", meanIoU))
        println(String.format("OVERALL BOUNDARY F1:      %.4f (Target >= 0.9500)", meanF1))
        println(String.format("OVERALL MEAN ABS ERROR:   %.4f (Target <= 0.0250)", meanMae))
        println("==================================================")

        assertTrue("Mean IoU ($meanIoU) must be >= 0.95", meanIoU >= 0.95f)
        assertTrue("Boundary F1 ($meanF1) must be >= 0.90", meanF1 >= 0.90f)
        assertTrue("Alpha MAE ($meanMae) must be <= 0.015", meanMae <= 0.015f)
    }

    private fun computeBoundaryF1(
        predMask: FloatArray,
        gtMask: FloatArray,
        w: Int,
        h: Int,
        boundTol: Int = 0
    ): Float {
        var tp = 0
        var fp = 0
        var fn = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val isPredEdge = isEdgePixel(predMask, x, y, w, h)
                val isGtEdge = isEdgePixel(gtMask, x, y, w, h)

                if (isPredEdge) {
                    if (hasNearbyMatch(gtMask, x, y, w, h, boundTol)) {
                        tp++
                    } else {
                        fp++
                    }
                }
                if (isGtEdge && !hasNearbyMatch(predMask, x, y, w, h, boundTol)) {
                    fn++
                }
            }
        }

        val precision = if (tp + fp > 0) tp.toFloat() / (tp + fp) else 1f
        val recall = if (tp + fn > 0) tp.toFloat() / (tp + fn) else 1f

        return if (precision + recall > 0f) {
            (2f * precision * recall) / (precision + recall)
        } else {
            1f
        }
    }

    private fun isEdgePixel(mask: FloatArray, x: Int, y: Int, w: Int, h: Int): Boolean {
        val c = mask[y * w + x] >= 0.5f
        if (x > 0 && (mask[y * w + (x - 1)] >= 0.5f) != c) return true
        if (x < w - 1 && (mask[y * w + (x + 1)] >= 0.5f) != c) return true
        if (y > 0 && (mask[(y - 1) * w + x] >= 0.5f) != c) return true
        if (y < h - 1 && (mask[(y + 1) * w + x] >= 0.5f) != c) return true
        return false
    }

    private fun hasNearbyMatch(mask: FloatArray, x: Int, y: Int, w: Int, h: Int, tol: Int): Boolean {
        for (dy in -tol..tol) {
            val ny = y + dy
            if (ny !in 0 until h) continue
            for (dx in -tol..tol) {
                val nx = x + dx
                if (nx !in 0 until w) continue
                if (isEdgePixel(mask, nx, ny, w, h)) return true
            }
        }
        return false
    }
}
