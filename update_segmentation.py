import re

with open('app/src/main/java/com/example/mlkit/SegmentationHelper.kt', 'r') as f:
    content = f.read()

replacement_subject = """
    private fun applySoftEdgeMaskToSubject(original: Bitmap, mask: FloatBuffer, startX: Int, startY: Int, subjectWidth: Int, subjectHeight: Int): Bitmap {
        val width = original.width
        val height = original.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        val confidences = FloatArray(subjectWidth * subjectHeight)
        mask.rewind()
        mask.get(confidences)

        val radius = Math.max(2, Math.min(subjectWidth, subjectHeight) / 250)
        
        // 1. Erode to remove background halos
        val eroded = FloatArray(subjectWidth * subjectHeight)
        for (y in 0 until subjectHeight) {
            for (x in 0 until subjectWidth) {
                var minC = confidences[y * subjectWidth + x]
                if (minC < 1f) {
                    for (dy in -2..2) {
                        for (dx in -2..2) {
                            val ny = (y + dy).coerceIn(0, subjectHeight - 1)
                            val nx = (x + dx).coerceIn(0, subjectWidth - 1)
                            val c = confidences[ny * subjectWidth + nx]
                            if (c < minC) minC = c
                        }
                    }
                }
                eroded[y * subjectWidth + x] = minC
            }
        }
        
        // 2. Fast Gaussian-like Blur (2 passes)
        val temp = FloatArray(subjectWidth * subjectHeight)
        
        // Horizontal pass
        for (y in 0 until subjectHeight) {
            var sum = 0f
            for (i in -radius..radius) {
                val nx = (i).coerceIn(0, subjectWidth - 1)
                sum += eroded[y * subjectWidth + nx]
            }
            for (x in 0 until subjectWidth) {
                temp[y * subjectWidth + x] = sum / (2 * radius + 1)
                val nextX = (x + radius + 1).coerceAtMost(subjectWidth - 1)
                val prevX = (x - radius).coerceAtLeast(0)
                sum += eroded[y * subjectWidth + nextX] - eroded[y * subjectWidth + prevX]
            }
        }
        
        // Vertical pass
        val blurred = FloatArray(subjectWidth * subjectHeight)
        for (x in 0 until subjectWidth) {
            var sum = 0f
            for (i in -radius..radius) {
                val ny = (i).coerceIn(0, subjectHeight - 1)
                sum += temp[ny * subjectWidth + x]
            }
            for (y in 0 until subjectHeight) {
                blurred[y * subjectWidth + x] = sum / (2 * radius + 1)
                val nextY = (y + radius + 1).coerceAtMost(subjectHeight - 1)
                val prevY = (y - radius).coerceAtLeast(0)
                sum += temp[nextY * subjectWidth + x] - temp[prevY * subjectWidth + x]
            }
        }

        // 3. Smootherstep for final feathering
        val smoothedConfidences = FloatArray(subjectWidth * subjectHeight)
        for (i in 0 until subjectWidth * subjectHeight) {
            val c = blurred[i]
            smoothedConfidences[i] = if (c <= 0f) 0f else if (c >= 1f) 1f else {
                c * c * c * (c * (c * 6f - 15f) + 10f)
            }
        }
        
        val pixels = IntArray(width * height)
        original.getPixels(pixels, 0, width, 0, 0, width, height)
        
        for (i in 0 until width * height) {
            pixels[i] = Color.TRANSPARENT
        }

        original.getPixels(pixels, 0, width, 0, 0, width, height)
        
        for (y in 0 until subjectHeight) {
            for (x in 0 until subjectWidth) {
                val imgX = startX + x
                val imgY = startY + y
                if (imgX in 0 until width && imgY in 0 until height) {
                    val pIndex = imgY * width + imgX
                    val sIndex = y * subjectWidth + x
                    
                    val pixel = pixels[pIndex]
                    val originalAlpha = Color.alpha(pixel)
                    val newAlpha = (originalAlpha * smoothedConfidences[sIndex]).toInt()
                    
                    pixels[pIndex] = Color.argb(
                        newAlpha,
                        Color.red(pixel),
                        Color.green(pixel),
                        Color.blue(pixel)
                    )
                }
            }
        }
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x < startX || x >= startX + subjectWidth || y < startY || y >= startY + subjectHeight) {
                    pixels[y * width + x] = Color.TRANSPARENT
                }
            }
        }
        
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
"""

replacement_foreground = """
    private fun applySoftEdgeMask(original: Bitmap, mask: FloatBuffer): Bitmap {
        val width = original.width
        val height = original.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        val confidences = FloatArray(width * height)
        mask.rewind()
        mask.get(confidences)

        val radius = Math.max(2, Math.min(width, height) / 250)
        
        // 1. Erode
        val eroded = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var minC = confidences[y * width + x]
                if (minC < 1f) {
                    for (dy in -2..2) {
                        for (dx in -2..2) {
                            val ny = (y + dy).coerceIn(0, height - 1)
                            val nx = (x + dx).coerceIn(0, width - 1)
                            val c = confidences[ny * width + nx]
                            if (c < minC) minC = c
                        }
                    }
                }
                eroded[y * width + x] = minC
            }
        }
        
        // 2. Blur
        val temp = FloatArray(width * height)
        for (y in 0 until height) {
            var sum = 0f
            for (i in -radius..radius) {
                val nx = (i).coerceIn(0, width - 1)
                sum += eroded[y * width + nx]
            }
            for (x in 0 until width) {
                temp[y * width + x] = sum / (2 * radius + 1)
                val nextX = (x + radius + 1).coerceAtMost(width - 1)
                val prevX = (x - radius).coerceAtLeast(0)
                sum += eroded[y * width + nextX] - eroded[y * width + prevX]
            }
        }
        
        val blurred = FloatArray(width * height)
        for (x in 0 until width) {
            var sum = 0f
            for (i in -radius..radius) {
                val ny = (i).coerceIn(0, height - 1)
                sum += temp[ny * width + x]
            }
            for (y in 0 until height) {
                blurred[y * width + x] = sum / (2 * radius + 1)
                val nextY = (y + radius + 1).coerceAtMost(height - 1)
                val prevY = (y - radius).coerceAtLeast(0)
                sum += temp[nextY * width + x] - temp[prevY * width + x]
            }
        }

        val smoothedConfidences = FloatArray(width * height)
        for (i in 0 until width * height) {
            val c = blurred[i]
            smoothedConfidences[i] = if (c <= 0f) 0f else if (c >= 1f) 1f else {
                c * c * c * (c * (c * 6f - 15f) + 10f)
            }
        }
        
        val pixels = IntArray(width * height)
        original.getPixels(pixels, 0, width, 0, 0, width, height)
        
        for (i in 0 until width * height) {
            val pixel = pixels[i]
            val originalAlpha = Color.alpha(pixel)
            val newAlpha = (originalAlpha * smoothedConfidences[i]).toInt()
            
            pixels[i] = Color.argb(
                newAlpha,
                Color.red(pixel),
                Color.green(pixel),
                Color.blue(pixel)
            )
        }
        
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
"""

start1 = content.find("private fun applySoftEdgeMaskToSubject")
end1 = content.find("private fun applySoftEdgeMask")

start2 = content.find("private fun applySoftEdgeMask")
end2 = content.rfind("}")

if start1 != -1 and end1 != -1 and start2 != -1:
    new_content = content[:start1] + replacement_subject + replacement_foreground + "}\n"
    with open('app/src/main/java/com/example/mlkit/SegmentationHelper.kt', 'w') as f:
        f.write(new_content)
    print("Segmentation updated successfully")
else:
    print("Could not find blocks in SegmentationHelper")
