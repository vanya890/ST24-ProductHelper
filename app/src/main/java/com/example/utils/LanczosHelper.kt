package com.example.utils

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin

object LanczosHelper {
    private fun lanczos3(x: Float): Float {
        val absX = abs(x)
        if (absX >= 3f) return 0f
        if (absX < 1e-5f) return 1f
        val piX = Math.PI * absX
        return (sin(piX) / piX * sin(piX / 3.0) / (piX / 3.0)).toFloat()
    }

    fun resize(src: Bitmap, dstWidth: Int, dstHeight: Int): Bitmap {
        if (src.width == dstWidth && src.height == dstHeight) return src
        
        val srcWidth = src.width
        val srcHeight = src.height

        val scaleX = dstWidth.toFloat() / srcWidth
        val scaleY = dstHeight.toFloat() / srcHeight

        val tempPixels = IntArray(dstWidth * srcHeight)
        val srcPixels = IntArray(srcWidth * srcHeight)
        src.getPixels(srcPixels, 0, srcWidth, 0, 0, srcWidth, srcHeight)

        // Horizontal pass
        java.util.stream.IntStream.range(0, srcHeight).parallel().forEach { y ->
            for (x in 0 until dstWidth) {
                val srcX = (x + 0.5f) / scaleX - 0.5f
                val startX = floor((srcX - 3).toDouble()).toInt()
                val endX = ceil((srcX + 3).toDouble()).toInt()

                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                var weightSum = 0f

                for (ix in startX..endX) {
                    val clampedX = ix.coerceIn(0, srcWidth - 1)
                    val weight = lanczos3(srcX - ix)
                    weightSum += weight

                    val pixel = srcPixels[y * srcWidth + clampedX]
                    val alpha = Color.alpha(pixel).toFloat()
                    val alphaFactor = alpha / 255f
                    val red = Color.red(pixel) * alphaFactor
                    val green = Color.green(pixel) * alphaFactor
                    val blue = Color.blue(pixel) * alphaFactor

                    a += alpha * weight
                    r += red * weight
                    g += green * weight
                    b += blue * weight
                }

                val invSum = if (abs(weightSum) > 1e-5f) 1f / weightSum else 1f
                val fa = (a * invSum).coerceIn(0f, 255f)
                val frRaw = r * invSum
                val fgRaw = g * invSum
                val fbRaw = b * invSum

                val faFactor = fa / 255f
                val fr = if (faFactor > 1e-4f) (frRaw / faFactor).toInt().coerceIn(0, 255) else 0
                val fg = if (faFactor > 1e-4f) (fgRaw / faFactor).toInt().coerceIn(0, 255) else 0
                val fb = if (faFactor > 1e-4f) (fbRaw / faFactor).toInt().coerceIn(0, 255) else 0

                tempPixels[y * dstWidth + x] = Color.argb(fa.toInt().coerceIn(0, 255), fr, fg, fb)
            }
        }

        val dstPixels = IntArray(dstWidth * dstHeight)

        // Vertical pass
        java.util.stream.IntStream.range(0, dstWidth).parallel().forEach { x ->
            for (y in 0 until dstHeight) {
                val srcY = (y + 0.5f) / scaleY - 0.5f
                val startY = floor((srcY - 3).toDouble()).toInt()
                val endY = ceil((srcY + 3).toDouble()).toInt()

                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                var weightSum = 0f

                for (iy in startY..endY) {
                    val clampedY = iy.coerceIn(0, srcHeight - 1)
                    val weight = lanczos3(srcY - iy)
                    weightSum += weight

                    val pixel = tempPixels[clampedY * dstWidth + x]
                    val alpha = Color.alpha(pixel).toFloat()
                    val alphaFactor = alpha / 255f
                    val red = Color.red(pixel) * alphaFactor
                    val green = Color.green(pixel) * alphaFactor
                    val blue = Color.blue(pixel) * alphaFactor

                    a += alpha * weight
                    r += red * weight
                    g += green * weight
                    b += blue * weight
                }

                val invSum = if (abs(weightSum) > 1e-5f) 1f / weightSum else 1f
                val fa = (a * invSum).coerceIn(0f, 255f)
                val frRaw = r * invSum
                val fgRaw = g * invSum
                val fbRaw = b * invSum

                val faFactor = fa / 255f
                val fr = if (faFactor > 1e-4f) (frRaw / faFactor).toInt().coerceIn(0, 255) else 0
                val fg = if (faFactor > 1e-4f) (fgRaw / faFactor).toInt().coerceIn(0, 255) else 0
                val fb = if (faFactor > 1e-4f) (fbRaw / faFactor).toInt().coerceIn(0, 255) else 0

                dstPixels[y * dstWidth + x] = Color.argb(fa.toInt().coerceIn(0, 255), fr, fg, fb)
            }
        }

        val dst = Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888)
        dst.setPixels(dstPixels, 0, dstWidth, 0, 0, dstWidth, dstHeight)
        return dst
    }
}
