package com.example.mlkit

import kotlin.math.max

object GuidedFilter {

    /**
     * Physically Correct Guided Image Filter (Kaiming He et al.).
     * Guided by original photo intensity I_i, calculates edge-aware mask q_i:
     * q_i = a_k * I_i + b_k
     * where a_k = cov_k(I, p) / (var_k(I) + eps) and b_k = mean(p)_k - a_k * mean(I)_k.
     */
    fun filter(
        pixels: IntArray,
        mask: FloatArray,
        w: Int,
        h: Int,
        radius: Int = 4,
        eps: Float = 1e-3f
    ): FloatArray {
        val size = w * h
        if (size == 0) return FloatArray(0)

        // 1. Extract Guide Image Intensity I in [0, 1] from original photo with 3x3 noise reduction
        val rawI = FloatArray(size)
        for (i in 0 until size) {
            val px = pixels[i]
            val r = (px shr 16 and 0xFF) / 255f
            val g = (px shr 8 and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            rawI[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        // Denoise guide image slightly (radius 1) so camera sensor grain does not transfer to alpha
        val I = boxFilter(rawI, w, h, 1)

        val p = mask

        // 2. Compute means in local window omega_k via fast O(1) box filter
        val r = maxOf(1, radius)
        val meanI = boxFilter(I, w, h, r)
        val meanP = boxFilter(p, w, h, r)

        // 3. Compute covariance cov(I, p) and variance var(I)
        val Ip = FloatArray(size) { i -> I[i] * p[i] }
        val II = FloatArray(size) { i -> I[i] * I[i] }

        val meanIp = boxFilter(Ip, w, h, r)
        val meanII = boxFilter(II, w, h, r)

        val covIp = FloatArray(size) { i -> meanIp[i] - meanI[i] * meanP[i] }
        val varI = FloatArray(size) { i -> meanII[i] - meanI[i] * meanI[i] }

        // 4. Calculate linear transform coefficients a_k and b_k
        val safeEps = maxOf(eps, 0.005f)
        val a = FloatArray(size) { i ->
            covIp[i] / (maxOf(0f, varI[i]) + safeEps)
        }
        val b = FloatArray(size) { i ->
            meanP[i] - a[i] * meanI[i]
        }

        // 5. Average coefficients over windows: meanA and meanB
        val meanA = boxFilter(a, w, h, r)
        val meanB = boxFilter(b, w, h, r)

        // 6. Compute output mask q_i = meanA_i * I_i + meanB_i
        val q = FloatArray(size) { i ->
            (meanA[i] * I[i] + meanB[i]).coerceIn(0f, 1f)
        }

        return q
    }

    /**
     * Fast O(1) per pixel 2D Box Filter using 1D horizontal & vertical sliding accumulator passes.
     */
    fun boxFilter(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val size = w * h
        if (size == 0) return FloatArray(0)

        val temp = FloatArray(size)
        val dest = FloatArray(size)

        // Horizontal pass
        java.util.stream.IntStream.range(0, h).parallel().forEach { y ->
            val yOff = y * w
            var sum = 0f
            val rClamped = r.coerceAtMost(w - 1)
            
            for (dx in -rClamped..rClamped) {
                val x = dx.coerceIn(0, w - 1)
                sum += src[yOff + x]
            }

            for (x in 0 until w) {
                val leftBoundary = (x - r).coerceAtLeast(0)
                val rightBoundary = (x + r).coerceAtMost(w - 1)
                val windowLen = rightBoundary - leftBoundary + 1
                temp[yOff + x] = sum / windowLen

                if (x < w - 1) {
                    val nextAdd = (x + r + 1).coerceAtMost(w - 1)
                    val prevRem = (x - r).coerceAtLeast(0)
                    sum += src[yOff + nextAdd] - src[yOff + prevRem]
                }
            }
        }

        // Vertical pass
        java.util.stream.IntStream.range(0, w).parallel().forEach { x ->
            var sum = 0f
            val rClamped = r.coerceAtMost(h - 1)

            for (dy in -rClamped..rClamped) {
                val y = dy.coerceIn(0, h - 1)
                sum += temp[y * w + x]
            }

            for (y in 0 until h) {
                val topBoundary = (y - r).coerceAtLeast(0)
                val bottomBoundary = (y + r).coerceAtMost(h - 1)
                val windowLen = bottomBoundary - topBoundary + 1
                dest[y * w + x] = sum / windowLen

                if (y < h - 1) {
                    val nextAdd = (y + r + 1).coerceAtMost(h - 1)
                    val prevRem = (y - r).coerceAtLeast(0)
                    sum += temp[nextAdd * w + x] - temp[prevRem * w + x]
                }
            }
        }

        return dest
    }
}



