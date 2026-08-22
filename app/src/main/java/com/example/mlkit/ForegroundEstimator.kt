package com.example.mlkit

import android.graphics.Color

object ForegroundEstimator {
    fun estimate(pixels: IntArray, alpha: FloatArray, w: Int, h: Int): IntArray {
        val size = w * h
        val F = IntArray(size)
        
        // Very fast approximation: Push-Pull or nearest neighbor propagation
        // For each unknown pixel, we find the closest known foreground pixel.
        // We can do this with a Jump Flooding Algorithm (JFA) for distance transform!
        
        val inf = 9999999
        val closestX = IntArray(size) { -1 }
        val closestY = IntArray(size) { -1 }
        
        // Initialize
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (alpha[idx] >= 0.95f) {
                    closestX[idx] = x
                    closestY[idx] = y
                }
            }
        }
        
        // JFA Passes
        var step = kotlin.math.max(w, h) / 2
        while (step > 0) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    
                    var bestDist = inf
                    var bx = closestX[idx]
                    var by = closestY[idx]
                    if (bx != -1 && by != -1) {
                        bestDist = (bx - x) * (bx - x) + (by - y) * (by - y)
                    }
                    
                    // Check neighbors
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx * step
                            val ny = y + dy * step
                            if (nx in 0 until w && ny in 0 until h) {
                                val nIdx = ny * w + nx
                                val cx = closestX[nIdx]
                                val cy = closestY[nIdx]
                                if (cx != -1 && cy != -1) {
                                    val dist = (cx - x) * (cx - x) + (cy - y) * (cy - y)
                                    if (dist < bestDist) {
                                        bestDist = dist
                                        bx = cx
                                        by = cy
                                    }
                                }
                            }
                        }
                    }
                    closestX[idx] = bx
                    closestY[idx] = by
                }
            }
            step /= 2
        }
        
        // Now fill F using the closest foreground pixel's color
        for (i in 0 until size) {
            val a = alpha[i]
            if (a <= 0.01f) {
                F[i] = Color.TRANSPARENT
            } else if (a >= 0.95f) {
                val px = pixels[i]
                F[i] = Color.rgb(Color.red(px), Color.green(px), Color.blue(px))
            } else {
                val cx = closestX[i]
                val cy = closestY[i]
                if (cx != -1 && cy != -1) {
                    val px = pixels[cy * w + cx]
                    F[i] = Color.rgb(Color.red(px), Color.green(px), Color.blue(px))
                } else {
                    val px = pixels[i]
                    F[i] = Color.rgb(Color.red(px), Color.green(px), Color.blue(px))
                }
            }
        }
        
        return F
    }
}
