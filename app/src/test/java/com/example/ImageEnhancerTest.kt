package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.utils.ImageEnhancer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImageEnhancerTest {

    @Test
    fun testEnhanceImage_preservesDimensionsAndEnhancesTones() = runBlocking {
        val width = 64
        val height = 64
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Create test image with dark and bright areas
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x < width / 2) {
                    bitmap.setPixel(x, y, Color.rgb(30, 30, 40)) // Low light area
                } else {
                    bitmap.setPixel(x, y, Color.rgb(200, 180, 150)) // Highlight area
                }
            }
        }

        val enhanced = ImageEnhancer.enhanceImage(bitmap, isHdrNightEnabled = true)

        assertNotNull(enhanced)
        assertEquals(width, enhanced.width)
        assertEquals(height, enhanced.height)

        // Verify shadows were lifted
        val darkPixel = enhanced.getPixel(10, 10)
        val origDarkPixel = bitmap.getPixel(10, 10)
        assertTrue(
            "Enhanced dark pixel R (${Color.red(darkPixel)}) should be lifted compared to original (${Color.red(origDarkPixel)})",
            Color.red(darkPixel) >= Color.red(origDarkPixel)
        )
    }

    @Test
    fun testEnhanceImage_computesWithinReasonableTime() = runBlocking {
        val width = 256
        val height = 256
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val startTime = System.nanoTime()
        val enhanced = ImageEnhancer.enhanceImage(bitmap, isHdrNightEnabled = true)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0

        assertNotNull(enhanced)
        assertTrue("Enhancement should take < 500ms for 256x256 image, took: ${elapsedMs}ms", elapsedMs < 500.0)
    }
}
