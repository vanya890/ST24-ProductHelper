package com.example.utils

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class StudioColorProfile(val displayName: String, val description: String) {
    STUDIO_CLEAN("Чистая Студия (Clean)", "Идеально чистый белый свет, четкий контраст без примесей"),
    COMMERCIAL_VIBRANT("Коммерческий Сочный", "Насыщенные цвета упаковки, глубокий контраст"),
    WARM_BOUTIQUE("Теплый Бутик (Warm)", "Мягкий золотистый свет для премиальных товаров"),
    TRUE_NEUTRAL("Истинный Нейтрал", "Калиброванная цветопередача с точным балансом белого")
}

/**
 * 3D LUT (Look-Up Table) Engine with Trilinear Interpolation for Studio Color Grading.
 */
object ColorLutHelper {

    private const val LUT_SIZE = 17 // Standard mobile 17x17x17 3D LUT lattice

    /**
     * Applies a 3D LUT profile to a bitmap.
     */
    suspend fun applyColorProfile(
        bitmap: Bitmap,
        profile: StudioColorProfile
    ): Bitmap = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height
        val size = width * height

        val pixels = IntArray(size)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val lut = generate3dLut(profile)
        val outPixels = IntArray(size)

        val step = 255.0f / (LUT_SIZE - 1)
        val invStep = 1.0f / step

        for (i in 0 until size) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF

            // Map RGB (0..255) to 3D LUT coordinates with trilinear interpolation
            val rF = r * invStep
            val gF = g * invStep
            val bF = b * invStep

            val r0 = rF.toInt().coerceIn(0, LUT_SIZE - 2)
            val g0 = gF.toInt().coerceIn(0, LUT_SIZE - 2)
            val b0 = bF.toInt().coerceIn(0, LUT_SIZE - 2)

            val r1 = r0 + 1
            val g1 = g0 + 1
            val b1 = b0 + 1

            val dr = rF - r0
            val dg = gF - g0
            val db = bF - b0

            // Trilinear interpolation of 8 lattice corners
            val c000 = lut[r0][g0][b0]
            val c100 = lut[r1][g0][b0]
            val c010 = lut[r0][g1][b0]
            val c110 = lut[r1][g1][b0]
            val c001 = lut[r0][g0][b1]
            val c101 = lut[r1][g0][b1]
            val c011 = lut[r0][g1][b1]
            val c111 = lut[r1][g1][b1]

            val c00 = c000.lerp(c100, dr)
            val c01 = c001.lerp(c101, dr)
            val c10 = c010.lerp(c110, dr)
            val c11 = c011.lerp(c111, dr)

            val c0 = c00.lerp(c10, dg)
            val c1 = c01.lerp(c11, dg)

            val finalC = c0.lerp(c1, db)

            outPixels[i] = (a shl 24) or (finalC.r shl 16) or (finalC.g shl 8) or finalC.b
        }

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(outPixels, 0, width, 0, 0, width, height)
        resultBitmap
    }

    private data class RgbF(val r: Int, val g: Int, val b: Int) {
        fun lerp(other: RgbF, t: Float): RgbF {
            val nr = (r + (other.r - r) * t).toInt().coerceIn(0, 255)
            val ng = (g + (other.g - g) * t).toInt().coerceIn(0, 255)
            val nb = (b + (other.b - b) * t).toInt().coerceIn(0, 255)
            return RgbF(nr, ng, nb)
        }
    }

    private fun generate3dLut(profile: StudioColorProfile): Array<Array<Array<RgbF>>> {
        val lut = Array(LUT_SIZE) { Array(LUT_SIZE) { Array(LUT_SIZE) { RgbF(0, 0, 0) } } }
        val step = 255.0f / (LUT_SIZE - 1)

        for (rIdx in 0 until LUT_SIZE) {
            val rVal = (rIdx * step) / 255.0f
            for (gIdx in 0 until LUT_SIZE) {
                val gVal = (gIdx * step) / 255.0f
                for (bIdx in 0 until LUT_SIZE) {
                    val bVal = (bIdx * step) / 255.0f

                    val out = when (profile) {
                        StudioColorProfile.STUDIO_CLEAN -> {
                            // Studio Clean: Bright neutral whites, deep blacks, subtle highlight recovery
                            val luma = 0.299f * rVal + 0.587f * gVal + 0.114f * bVal
                            val contrast = (rVal.pow(1.05f) * 0.98f + luma * 0.02f)
                            val rOut = (contrast * 255f).toInt().coerceIn(0, 255)
                            val gOut = ((gVal.pow(1.04f) * 0.98f + luma * 0.02f) * 255f).toInt().coerceIn(0, 255)
                            val bOut = ((bVal.pow(1.02f) * 0.98f + luma * 0.02f) * 255f).toInt().coerceIn(0, 255)
                            RgbF(rOut, gOut, bOut)
                        }
                        StudioColorProfile.COMMERCIAL_VIBRANT -> {
                            // Commercial Vibrant: Rich saturated chromatic tones, boosted micro-contrast
                            val maxC = max(rVal, max(gVal, bVal))
                            val minC = min(rVal, min(gVal, bVal))
                            val sat = if (maxC > 0) (maxC - minC) / maxC else 0f
                            val boost = 1.0f + (1.0f - sat) * 0.22f

                            val rOut = ((rVal * boost).pow(1.08f) * 255f).toInt().coerceIn(0, 255)
                            val gOut = ((gVal * boost).pow(1.08f) * 255f).toInt().coerceIn(0, 255)
                            val bOut = ((bVal * boost).pow(1.08f) * 255f).toInt().coerceIn(0, 255)
                            RgbF(rOut, gOut, bOut)
                        }
                        StudioColorProfile.WARM_BOUTIQUE -> {
                            // Warm Boutique: Gentle warm golden tone curve
                            val rOut = ((rVal.pow(0.96f) * 1.05f) * 255f).toInt().coerceIn(0, 255)
                            val gOut = ((gVal.pow(0.98f) * 1.02f) * 255f).toInt().coerceIn(0, 255)
                            val bOut = ((bVal.pow(1.04f) * 0.95f) * 255f).toInt().coerceIn(0, 255)
                            RgbF(rOut, gOut, bOut)
                        }
                        StudioColorProfile.TRUE_NEUTRAL -> {
                            // True Neutral: Linear sRGB reference
                            val rOut = (rVal * 255f).toInt().coerceIn(0, 255)
                            val gOut = (gVal * 255f).toInt().coerceIn(0, 255)
                            val bOut = (bVal * 255f).toInt().coerceIn(0, 255)
                            RgbF(rOut, gOut, bOut)
                        }
                    }
                    lut[rIdx][gIdx][bIdx] = out
                }
            }
        }
        return lut
    }
}
