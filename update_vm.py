import re

with open('app/src/main/java/com/example/ui/editor/EditorViewModel.kt', 'r') as f:
    content = f.read()

# 1. Update State
state_replacement = """
    val isCheckerboard: Boolean = true,
    
    val productScale: Float = 1f,
    val productOffsetX: Float = 0f,
    val productOffsetY: Float = 0f
)
"""
content = re.sub(r"val isCheckerboard: Boolean = true\n\)", state_replacement, content)

# 2. Add product transform method
transform_method = """
    fun updateProductTransform(scaleMultiplier: Float, panX: Float, panY: Float) {
        _state.update { 
            it.copy(
                productScale = (it.productScale * scaleMultiplier).coerceIn(0.1f, 5f),
                productOffsetX = it.productOffsetX + panX,
                productOffsetY = it.productOffsetY + panY
            )
        }
        updateFinalBitmap()
    }
"""

content = content.replace("fun saveProduct(onComplete: () -> Unit) {", transform_method + "\n\n    fun saveProduct(onComplete: () -> Unit) {")

# 3. Update updateFinalBitmap product drawing
product_draw_replace = """
            val destLeft = left + currentState.productOffsetX
            val destTop = top + currentState.productOffsetY
            
            val scaledWidth = productWidth * currentState.productScale
            val scaledHeight = productHeight * currentState.productScale

            val destRect = RectF(
                destLeft + (productWidth - scaledWidth)/2f, 
                destTop + (productHeight - scaledHeight)/2f, 
                destLeft + (productWidth + scaledWidth)/2f, 
                destTop + (productHeight + scaledHeight)/2f
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(foreground, null, destRect, paint)
"""
content = re.sub(r"val destRect = Rect\(left\.toInt\(\), top\.toInt\(\), \(left \+ productWidth\)\.toInt\(\), \(top \+ productHeight\)\.toInt\(\)\)\n\s*canvas\.drawBitmap\(foreground, null, destRect, null\)", product_draw_replace, content)

# 4. Save Product Upscale
save_replace = """
    fun saveProduct(onComplete: () -> Unit) {
        val currentState = _state.value
        val bitmap = currentState.finalBitmap ?: return
        
        _state.update { it.copy(isLoading = true) }
        
        viewModelScope.launch(Dispatchers.IO) {
            val maxDim = Math.max(bitmap.width, bitmap.height)
            val targetDim = 3000
            
            val finalToSave = if (maxDim != targetDim) {
                val scale = targetDim.toFloat() / maxDim
                val newW = (bitmap.width * scale).toInt()
                val newH = (bitmap.height * scale).toInt()
                // Use Lanczos3 for upscale/downscale quality
                com.example.utils.LanczosHelper.resize(bitmap, newW, newH)
            } else {
                bitmap
            }
            
            val product = com.example.data.ProductEntity(
                name = currentState.name,
                price = currentState.price,
                link = currentState.link,
                imageUri = currentState.originalImageUri 
            )
            repository.insertProduct(product, finalToSave)
            
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                _state.update { it.copy(isLoading = false) }
                onComplete()
            }
        }
    }
"""

start_save = content.find("fun saveProduct(onComplete: () -> Unit) {")
end_save = content.find("}", content.find("}", content.find("}", start_save) + 1) + 1) + 1

if start_save != -1 and end_save != -1:
    content = content[:start_save] + save_replace + content[end_save:]
    
with open('app/src/main/java/com/example/ui/editor/EditorViewModel.kt', 'w') as f:
    f.write(content)
print("Updated ViewModel")
