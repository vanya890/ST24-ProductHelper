package com.example.utils

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Motion & Sharpness Detector for "Motion-Gated Sniper Capture".
 * - Monitors Gyroscope / Linear Acceleration for camera steadiness.
 * - Computes Laplacian Variance for focus / sharpness peak detection.
 */
class MotionAndSharpnessDetector(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile
    var currentMotionMagnitude: Float = 0.0f
        private set

    @Volatile
    var isDeviceSteady: Boolean = true
        private set

    private val sharpnessRingBuffer = FloatArray(8)
    private var bufferIdx = 0

    fun startListening() {
        gyroSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: accelSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stopListening() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val gx = event.values[0]
            val gy = event.values[1]
            val gz = event.values[2]
            val mag = sqrt(gx * gx + gy * gy + gz * gz)
            currentMotionMagnitude = mag
            // Threshold for hand steadiness: gyro angular speed < 0.25 rad/s
            isDeviceSteady = mag < 0.25f
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            val mag = abs(sqrt(ax * ax + ay * ay + az * az) - 9.81f)
            currentMotionMagnitude = mag
            isDeviceSteady = mag < 0.45f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Fast Laplacian Variance calculation for real-time sharpness evaluation.
     * Higher variance = crisper, sharper focus.
     */
    fun computeLaplacianSharpness(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val step = max(1, w / 160) // Downsample for 60fps real-time evaluation

        var sumLap = 0.0
        var sumLapSq = 0.0
        var sampleCount = 0

        val sampledW = w / step
        val sampledH = h / step
        val luma = IntArray(sampledW * sampledH)

        for (sy in 0 until sampledH) {
            val y = sy * step
            for (sx in 0 until sampledW) {
                val x = sx * step
                val p = bitmap.getPixel(x, y)
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                luma[sy * sampledW + sx] = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
            }
        }

        // Discrete Laplacian kernel: [[0, 1, 0], [1, -4, 1], [0, 1, 0]]
        for (sy in 1 until sampledH - 1) {
            val row = sy * sampledW
            for (sx in 1 until sampledW - 1) {
                val c = luma[row + sx]
                val up = luma[row - sampledW + sx]
                val down = luma[row + sampledW + sx]
                val left = luma[row + sx - 1]
                val right = luma[row + sx + 1]

                val lap = (up + down + left + right - 4 * c).toDouble()
                sumLap += lap
                sumLapSq += lap * lap
                sampleCount++
            }
        }

        if (sampleCount <= 0) return 0f

        val mean = sumLap / sampleCount
        val variance = (sumLapSq / sampleCount) - (mean * mean)

        val currentScore = variance.toFloat().coerceAtLeast(0f)
        sharpnessRingBuffer[bufferIdx % sharpnessRingBuffer.size] = currentScore
        bufferIdx++

        return currentScore
    }

    /**
     * Checks if current frame is at a sharpness peak relative to recent frames
     * and the device is currently steady.
     */
    fun isAtSharpnessPeakAndSteady(currentSharpness: Float): Boolean {
        if (!isDeviceSteady) return false
        var maxPast = 0f
        for (score in sharpnessRingBuffer) {
            if (score > maxPast) maxPast = score
        }
        // At peak if current score is at least 95% of peak history and absolute sharpness > 35
        return currentSharpness >= maxPast * 0.95f && currentSharpness > 35.0f
    }
}
