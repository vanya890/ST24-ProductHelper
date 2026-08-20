import os

def fix_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    # Revert incorrect translations
    replacements = {
        'fillMaxРазмер': 'fillMaxSize',
        'Color.Белый': 'Color.White',
        'brandLogoРазмер': 'brandLogoSize',
        'brandLogoОтступы': 'brandLogoSpacing',
        'updateЦена': 'updatePrice',
        'showЦена': 'showPrice',
        'toggleЦена': 'togglePrice',
        'ГалереяViewModel': 'GalleryViewModel',
        'onNavigateToГалерея': 'onNavigateToGallery'
    }
    
    for old, new in replacements.items():
        content = content.replace(old, new)
        
    with open(filepath, 'w') as f:
        f.write(content)

fix_file('app/src/main/java/com/example/ui/editor/EditorScreen.kt')
fix_file('app/src/main/java/com/example/ui/gallery/GalleryScreen.kt')
fix_file('app/src/main/java/com/example/MainActivity.kt')
