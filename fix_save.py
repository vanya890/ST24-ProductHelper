import re

with open('app/src/main/java/com/example/ui/editor/EditorViewModel.kt', 'r') as f:
    content = f.read()

replacement = """
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
                com.example.utils.LanczosHelper.resize(bitmap, newW, newH)
            } else {
                bitmap
            }
            
            // Save bitmap to file
            val file = File(context.filesDir, "product_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                finalToSave.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            val product = com.example.data.ProductEntity(
                name = currentState.name,
                price = currentState.price,
                link = currentState.link,
                imagePath = file.absolutePath
            )
            repository.insert(product)
            
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
    content = content[:start_save] + replacement
    
    # Remove extra closing bracket if it's there
    content = content.replace("    }\n\n}", "    }\n}")
    with open('app/src/main/java/com/example/ui/editor/EditorViewModel.kt', 'w') as f:
        f.write(content)
    print("Fixed saveProduct")
