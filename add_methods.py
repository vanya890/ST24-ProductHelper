import re

with open('app/src/main/java/com/example/ui/editor/EditorViewModel.kt', 'r') as f:
    content = f.read()

methods = """
    fun updateStoreName(storeName: String) {
        _state.update { it.copy(storeName = storeName) }
        applicationContext.getSharedPreferences("product_prefs", Context.MODE_PRIVATE).edit().putString("last_store_name", storeName).apply()
        updateFinalBitmap()
    }
    
    fun toggleStoreName(show: Boolean) {
        _state.update { it.copy(showStoreName = show) }
        updateFinalBitmap()
    }

    fun updatePhone(phone: String) {
        _state.update { it.copy(phone = phone) }
        applicationContext.getSharedPreferences("product_prefs", Context.MODE_PRIVATE).edit().putString("last_phone", phone).apply()
        updateFinalBitmap()
    }
    
    fun togglePhone(show: Boolean) {
        _state.update { it.copy(showPhone = show) }
        updateFinalBitmap()
    }

    fun updateTemplateStyle(style: TemplateStyle) {
        _state.update { it.copy(templateStyle = style) }
        updateFinalBitmap()
    }
"""

content = content.replace("    fun updateName(name: String) {", methods + "\n    fun updateName(name: String) {")

init_replace = """name = prefs.getString("last_name", "Product Name") ?: "Product Name",
                price = prefs.getString("last_price", "999 руб.") ?: "999 руб.",
                link = prefs.getString("last_link", "https://stroy-materiali-24.ru") ?: "https://stroy-materiali-24.ru",
                storeName = prefs.getString("last_store_name", "STROY-MATERIALI-24") ?: "STROY-MATERIALI-24",
                phone = prefs.getString("last_phone", "+7 (926) 163-75-07") ?: "+7 (926) 163-75-07","""
                
content = re.sub(r'name = prefs\.getString\("last_name", "Product Name"\) \?: "Product Name",\s*price = prefs\.getString\("last_price", "\$99\.99"\) \?: "\$99\.99",\s*link = prefs\.getString\("last_link", "https://example\.com"\) \?: "https://example\.com"', init_replace, content)

with open('app/src/main/java/com/example/ui/editor/EditorViewModel.kt', 'w') as f:
    f.write(content)
