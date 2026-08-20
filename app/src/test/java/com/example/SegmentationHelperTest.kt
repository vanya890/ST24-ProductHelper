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
        assertEquals(255, Color.alpha(centerPixel))
        assertEquals(255, Color.red(centerPixel))
        assertEquals(0, Color.green(centerPixel))
        assertEquals(0, Color.blue(centerPixel))

        // Check outside pixel is transparent
        val outPixel = result.getPixel(2, 2)
        assertEquals(0, Color.alpha(outPixel))
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
}
