import re

with open('app/src/main/java/com/example/ui/editor/EditorScreen.kt', 'r') as f:
    content = f.read()

# Strings to replace
replacements = {
    'Stroy-Materiali-24.ru Product Lab': 'Stroy-Materiali-24.ru Фоторедактор',
    'TEMPLATE STYLE': 'СТИЛЬ ШАБЛОНА',
    'FORMAT': 'ФОРМАТ',
    'TEMPLATE BACKGROUND': 'ФОН ШАБЛОНА',
    'White': 'Белый',
    'Brand Wall': 'Свой логотип',
    'Change Logo': 'Изменить логотип',
    'Size': 'Размер',
    'Spacing': 'Отступы',
    'Offset X': 'Смещение X',
    'Offset Y': 'Смещение Y',
    'Checkerboard Pattern': 'Шахматный порядок',
    'PRODUCT DETAILS': 'ИНФОРМАЦИЯ О ТОВАРЕ',
    'Store/Header Name': 'Название магазина / Заголовок',
    'Phone Number': 'Номер телефона',
    'Product Name': 'Название товара',
    'Price': 'Цена',
    'Link (QR Code)': 'Ссылка (QR-код)',
    'Save to Gallery': 'Сохранить в галерею',
}

for old, new in replacements.items():
    content = content.replace(old, new)
    
# Add ST24 Logo button in background type
st24_btn = """
                        FilterChip(
                            selected = state.backgroundType == BackgroundType.ST24_LOGO,
                            onClick = { viewModel.updateBackgroundType(BackgroundType.ST24_LOGO) },
                            label = { Text("Лого ST24") },
                            leadingIcon = if (state.backgroundType == BackgroundType.ST24_LOGO) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null
                        )"""

content = content.replace(
    'label = { Text("Свой логотип") },\n                            leadingIcon = if (state.backgroundType == BackgroundType.BRAND_WALL) {\n                                { Icon(Icons.Default.Check, contentDescription = null) }\n                            } else null\n                        )',
    'label = { Text("Свой логотип") },\n                            leadingIcon = if (state.backgroundType == BackgroundType.BRAND_WALL) {\n                                { Icon(Icons.Default.Check, contentDescription = null) }\n                            } else null\n                        )' + st24_btn
)

with open('app/src/main/java/com/example/ui/editor/EditorScreen.kt', 'w') as f:
    f.write(content)

# CameraScreen
with open('app/src/main/java/com/example/ui/camera/CameraScreen.kt', 'r') as f:
    camera_content = f.read()

camera_content = camera_content.replace('Take Photo', 'Сделать фото')
camera_content = camera_content.replace('Gallery', 'Галерея')
camera_content = camera_content.replace('Product Photo', 'Фото товара')
camera_content = camera_content.replace('Settings', 'Настройки')

with open('app/src/main/java/com/example/ui/camera/CameraScreen.kt', 'w') as f:
    f.write(camera_content)

# GalleryScreen
with open('app/src/main/java/com/example/ui/gallery/GalleryScreen.kt', 'r') as f:
    gallery_content = f.read()

gallery_content = gallery_content.replace('Gallery', 'Галерея')
gallery_content = gallery_content.replace('No products saved yet', 'Сохраненных товаров пока нет')

with open('app/src/main/java/com/example/ui/gallery/GalleryScreen.kt', 'w') as f:
    f.write(gallery_content)
    
