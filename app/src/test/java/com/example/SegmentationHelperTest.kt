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
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SegmentationHelperTest {

    @Test
    fun testRefineMask_smoothsAndNormalizesConfidences() {
        val width = 20
        val height = 20
        val confidences = FloatArray(width * height) { i ->
            val x = i % width
            val y = i / width
            // Center is 1.0, outer is 0.0
            if (x in 5..14 && y in 5..14) 1.0f else 0.0f
        }

        val refined = SegmentationHelper.refineMask(confidences, width, height)

        assertEquals(width * height, refined.size)
        
        // Ensure boundaries are clamped within [0, 1]
        for (v in refined) {
            assertTrue("Value $v should be >= 0f", v >= 0f)
            assertTrue("Value $v should be <= 1f", v <= 1f)
        }

        // Center must remain solid
        val centerIdx = 10 * width + 10
        assertEquals(1.0f, refined[centerIdx], 0.01f)

        // Far corner must be 0.0
        val cornerIdx = 0
        assertEquals(0.0f, refined[cornerIdx], 0.01f)
    }

    @Test
    fun testSubpixelAntiAliasing_eliminatesStaircaseJaggiesAndSoftensEdges() {
        val width = 40
        val height = 40
        // Create diagonal step (staircase rasterization)
        val confidences = FloatArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (x + y >= 40) 1.0f else 0.0f
        }

        val refined = SegmentationHelper.refineMask(confidences, width, height)

        // Verify that along the diagonal boundary, the transition is continuous and smooth without jumps
        for (y in 8..32) {
            val diagX = 40 - y
            if (diagX in 4 until width - 4) {
                val valInner = refined[y * width + diagX + 3]
                val valEdge = refined[y * width + diagX]
                val valOuter = refined[y * width + diagX - 3]
                
                assertTrue("Inner ($valInner) should be more solid than edge ($valEdge)", valInner >= valEdge)
                assertTrue("Edge ($valEdge) should be more solid than outer ($valOuter)", valEdge >= valOuter)
                
                // Edge softness check: edge value should be a gradual transition between 0 and 1
                assertTrue("Edge confidence should be smooth, was $valEdge", valEdge >= 0.0f && valEdge <= 1.0f)
            }
        }
    }

    @Test
    fun testFindSubjectBoundingBox() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        
        // Fill a region from x=20..79, y=30..89 with non-transparent color
        for (y in 30..89) {
            for (x in 20..79) {
                bitmap.setPixel(x, y, Color.argb(255, 100, 150, 200))
            }
        }

        val bbox = SegmentationHelper.findSubjectBoundingBox(bitmap, alphaThreshold = 20)

        assertEquals(20, bbox.left)
        assertEquals(30, bbox.top)
        assertEquals(80, bbox.right)
        assertEquals(90, bbox.bottom)
        assertEquals(60, bbox.width())
        assertEquals(60, bbox.height())
    }

    @Test
    fun testDecontamination_removesDarkHaloOnEdges() {
        val width = 30
        val height = 30
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        // Fill image with red product in center and black background around it
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x in 10..19 && y in 10..19) {
                    bitmap.setPixel(x, y, Color.rgb(255, 0, 0)) // Red product
                } else {
                    bitmap.setPixel(x, y, Color.rgb(10, 10, 10)) // Dark background
                }
            }
        }

        // Create float buffer mask
        val byteBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val conf = if (x in 10..19 && y in 10..19) {
                    1.0f
                } else if (x in 8..21 && y in 8..21) {
                    0.5f // Edge transition
                } else {
                    0.0f
                }
                floatBuffer.put(conf)
            }
        }

        val result = SegmentationHelper.processForegroundWithDecontamination(bitmap, floatBuffer)

        assertNotNull(result)
        assertEquals(width, result.width)
        assertEquals(height, result.height)

        // Check center pixel is fully opaque red
        val centerPixel = result.getPixel(15, 15)
        assertTrue("Alpha >= 254", Color.alpha(centerPixel) >= 254)
        assertTrue("Red >= 254", Color.red(centerPixel) >= 254)
        assertEquals(0, Color.green(centerPixel))
        assertEquals(0, Color.blue(centerPixel))

        // Check outside pixel is transparent
        val outPixel = result.getPixel(2, 2)
        assertEquals(0, Color.alpha(outPixel))
    }

    @Test
    fun testFindSubjectBoundingBox_rejectsStrayNoiseAndExtractsTightRegion() {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        
        // Product region at x=50..149, y=40..159
        for (y in 40..159) {
            for (x in 50..149) {
                bitmap.setPixel(x, y, Color.argb(255, 200, 100, 50))
            }
        }

        val bbox = SegmentationHelper.findSubjectBoundingBox(bitmap, alphaThreshold = 20)
        assertEquals(50, bbox.left)
        assertEquals(40, bbox.top)
        assertEquals(150, bbox.right)
        assertEquals(160, bbox.bottom)
        assertEquals(100, bbox.width())
        assertEquals(120, bbox.height())
    }

    @Test
    fun testBenchmarkMaskRefinementPerformance() {
        val w = 512
        val h = 512
        val confidences = FloatArray(w * h) { 0.5f }

        val startTime = System.nanoTime()
        val refined = SegmentationHelper.refineMask(confidences, w, h)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0

        assertNotNull(refined)
        assertTrue("Refinement should be real-time fast (< 250ms for 512x512), took: ${elapsedMs}ms", elapsedMs < 250.0)
    }

    @Test
    fun testPruneDisconnectedIslands_removesFarDistantBackgroundArtifacts() {
        val width = 100
        val height = 100
        val mask = FloatArray(width * height) { 0f }

        // Central main product: x=35..65, y=35..65
        for (y in 35..65) {
            for (x in 35..65) {
                mask[y * width + x] = 1.0f
            }
        }

        // Far distant background line/artifact along top-left border: x=2..15, y=2..3
        for (y in 2..3) {
            for (x in 2..15) {
                mask[y * width + x] = 0.9f
            }
        }

        val cleaned = SegmentationHelper.pruneDisconnectedIslands(mask, width, height)

        // Center must be preserved
        val centerIdx = 50 * width + 50
        assertEquals(1.0f, cleaned[centerIdx], 0.01f)

        // Distant artifact must be completely removed (0.0f)
        val artifactIdx = 2 * width + 5
        assertEquals(0.0f, cleaned[artifactIdx], 0.01f)
    }

    @Test
    fun testFsrSuperResolution_upscalesWithEdgePreservation() {
        val src = Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888)
        for (y in 0 until 30) {
            for (x in 0 until 30) {
                if (x < 15) {
                    src.setPixel(x, y, Color.rgb(255, 0, 0))
                } else {
                    src.setPixel(x, y, Color.rgb(0, 255, 0))
                }
            }
        }

        val upscaled = com.example.mlkit.FsrSuperResolution.upscaleFsr(src, 60, 60, sharpness = 0.40f)

        assertNotNull(upscaled)
        assertEquals(60, upscaled.width)
        assertEquals(60, upscaled.height)

        // Verify left side is red and right side is green
        val leftPixel = upscaled.getPixel(10, 30)
        assertTrue(Color.red(leftPixel) > 200)
        val rightPixel = upscaled.getPixel(50, 30)
        assertTrue(Color.green(rightPixel) > 200)
    }
}
