import re

with open('app/src/main/java/com/example/ui/editor/EditorViewModel.kt', 'r') as f:
    content = f.read()

# Just replace the whole inside of init
start = content.find("fun init(imageUri: String) {")
end = content.find("fun processImage(bitmap: Bitmap) {")

if start != -1 and end != -1:
    init_content = """fun init(imageUri: String) {
        val prefs = applicationContext.getSharedPreferences("product_prefs", Context.MODE_PRIVATE)
        _state.update { 
            it.copy(
                originalImageUri = imageUri, 
                isLoading = true,
                name = prefs.getString("last_name", "Product Name") ?: "Product Name",
                price = prefs.getString("last_price", "999 руб.") ?: "999 руб.",
                link = prefs.getString("last_link", "https://stroy-materiali-24.ru") ?: "https://stroy-materiali-24.ru",
                storeName = prefs.getString("last_store_name", "STROY-MATERIALI-24") ?: "STROY-MATERIALI-24",
                phone = prefs.getString("last_phone", "+7 (926) 163-75-07") ?: "+7 (926) 163-75-07"
            ) 
        }
    }
    
    """
    content = content[:start] + init_content + content[end:]
    
    with open('app/src/main/java/com/example/ui/editor/EditorViewModel.kt', 'w') as f:
        f.write(content)
    print("Fixed init")
