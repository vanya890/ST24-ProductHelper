package com.example.mlkit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class DeepImageMattingHelper(context: Context) {
    private var interpreter: Interpreter? = null
    private var inputWidth = 320
    private var inputHeight = 320
    private var inputChannels = 3
    private var isNCHW = false
    
    init {
        try {
            val availableCores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            val options = Interpreter.Options()
            options.setNumThreads(availableCores)
            
            val assetFileDescriptor = context.assets.openFd("u2net_fp16.tflite")
            val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = fileInputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            
            interpreter = Interpreter(modelBuffer, options)
            
            val inputTensor = interpreter!!.getInputTensor(0)
            val inputShape = inputTensor.shape()
            if (inputShape.size >= 4) {
                if (inputShape[1] == 1 || inputShape[1] == 3) {
                    isNCHW = true
                    inputChannels = inputShape[1]
                    inputHeight = inputShape[2]
                    inputWidth = inputShape[3]
                } else {
                    isNCHW = false
                    inputHeight = inputShape[1]
                    inputWidth = inputShape[2]
                    inputChannels = inputShape[3]
                }
            } else if (inputShape.size == 3) {
                inputHeight = inputShape[0]
                inputWidth = inputShape[1]
                inputChannels = inputShape[2]
                isNCHW = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun segment(original: Bitmap): FloatArray? {
        val interp = interpreter ?: return null

        return try {
            val resized = Bitmap.createScaledBitmap(original, inputWidth, inputHeight, true)
            val numPixels = inputWidth * inputHeight
            
            // Prepare input buffer
            val inputBuffer = ByteBuffer.allocateDirect(4 * numPixels * inputChannels)
            inputBuffer.order(ByteOrder.nativeOrder())
            
            val pixels = IntArray(numPixels)
            resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            
            if (isNCHW) {
                // R channel
                for (i in 0 until numPixels) {
                    val p = pixels[i]
                    val r = ((Color.red(p) / 255f) - 0.485f) / 0.229f
                    inputBuffer.putFloat(r)
                }
                // G channel
                for (i in 0 until numPixels) {
                    val p = pixels[i]
                    val g = ((Color.green(p) / 255f) - 0.456f) / 0.224f
                    inputBuffer.putFloat(g)
                }
                // B channel
                for (i in 0 until numPixels) {
                    val p = pixels[i]
                    val b = ((Color.blue(p) / 255f) - 0.406f) / 0.225f
                    inputBuffer.putFloat(b)
                }
            } else {
                for (i in 0 until numPixels) {
                    val p = pixels[i]
                    val r = ((Color.red(p) / 255f) - 0.485f) / 0.229f
                    val g = ((Color.green(p) / 255f) - 0.456f) / 0.224f
                    val b = ((Color.blue(p) / 255f) - 0.406f) / 0.225f
                    inputBuffer.putFloat(r)
                    inputBuffer.putFloat(g)
                    inputBuffer.putFloat(b)
                }
            }
            inputBuffer.rewind()
            
            val numOutputs = interp.outputTensorCount
            val mainOutputBuffer: ByteBuffer
            
            if (numOutputs <= 1) {
                val outputTensor = interp.getOutputTensor(0)
                val totalElems = outputTensor.shape().fold(1) { acc, d -> acc * d }
                mainOutputBuffer = ByteBuffer.allocateDirect(4 * totalElems)
                mainOutputBuffer.order(ByteOrder.nativeOrder())
                interp.run(inputBuffer, mainOutputBuffer)
            } else {
                val outputs = HashMap<Int, Any>()
                var primaryBuffer: ByteBuffer? = null
                for (i in 0 until numOutputs) {
                    val shape = interp.getOutputTensor(i).shape()
                    val totalElems = shape.fold(1) { acc, d -> acc * d }
                    val buf = ByteBuffer.allocateDirect(4 * totalElems)
                    buf.order(ByteOrder.nativeOrder())
                    outputs[i] = buf
                    if (i == 0) primaryBuffer = buf
                }
                val inputs = arrayOf<Any>(inputBuffer)
                interp.runForMultipleInputsOutputs(inputs, outputs)
                mainOutputBuffer = primaryBuffer!!
            }
            
            mainOutputBuffer.rewind()
            val floatBuf = mainOutputBuffer.asFloatBuffer()
            val totalFloats = floatBuf.remaining().coerceAtMost(numPixels)
            val confidences = FloatArray(numPixels)
            floatBuf.get(confidences, 0, totalFloats)
            
            var minVal = Float.MAX_VALUE
            var maxVal = -Float.MAX_VALUE
            for (i in 0 until totalFloats) {
                val v = confidences[i]
                if (v < minVal) minVal = v
                if (v > maxVal) maxVal = v
            }
            
            val outConf = FloatArray(numPixels)
            val needsSigmoid = minVal < -0.01f || maxVal > 1.01f
            for (i in 0 until numPixels) {
                val raw = if (i < totalFloats) confidences[i] else 0f
                outConf[i] = if (needsSigmoid) {
                    (1f / (1f + kotlin.math.exp(-raw))).coerceIn(0f, 1f)
                } else {
                    raw.coerceIn(0f, 1f)
                }
            }
            
            upscaleBilinear(outConf, inputWidth, inputHeight, original.width, original.height)
        } catch (e: Exception) {
            android.util.Log.e("DeepImageMattingHelper", "Error running segment", e)
            null
        }
    }
    
    private fun upscaleBilinear(
        channel: FloatArray,
        srcW: Int,
        srcH: Int,
        outW: Int,
        outH: Int
    ): FloatArray {
        val result = FloatArray(outW * outH)
        val scaleX = (srcW - 1).toFloat() / (outW - 1).coerceAtLeast(1)
        val scaleY = (srcH - 1).toFloat() / (outH - 1).coerceAtLeast(1)

        java.util.stream.IntStream.range(0, outH).parallel().forEach { y ->
            val srcYf = y * scaleY
            val srcY = srcYf.toInt()
            val yDiff = srcYf - srcY
            val nextY = (srcY + 1).coerceAtMost(srcH - 1)

            val yOff1 = srcY * srcW
            val yOff2 = nextY * srcW
            val outYOff = y * outW

            for (x in 0 until outW) {
                val srcXf = x * scaleX
                val srcX = srcXf.toInt()
                val xDiff = srcXf - srcX
                val nextX = (srcX + 1).coerceAtMost(srcW - 1)

                val v00 = channel[yOff1 + srcX]
                val v10 = channel[yOff1 + nextX]
                val v01 = channel[yOff2 + srcX]
                val v11 = channel[yOff2 + nextX]

                val interp = (v00 * (1f - xDiff) * (1f - yDiff) +
                        v10 * xDiff * (1f - yDiff) +
                        v01 * (1f - xDiff) * yDiff +
                        v11 * xDiff * yDiff)
                result[outYOff + x] = interp
            }
        }
        return result
    }

    fun close() {
        interpreter?.close()
        
    }
}
