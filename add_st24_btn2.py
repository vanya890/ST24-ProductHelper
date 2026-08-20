import re

with open('app/src/main/java/com/example/ui/editor/EditorScreen.kt', 'r') as f:
    content = f.read()

st24_btn = """
                BackgroundOption(
                    selected = state.backgroundType == BackgroundType.ST24_LOGO,
                    onClick = { viewModel.setBackgroundType(BackgroundType.ST24_LOGO) },
                    label = "Лого ST24",
                    modifier = Modifier.weight(1f)
                )"""

brand_btn = """                BackgroundOption(
                    selected = state.backgroundType == BackgroundType.BRAND_WALL,
                    onClick = { 
                        if (state.brandLogoBitmap == null) {
                            launcher.launch("image/*")
                        } else {
                            viewModel.setBackgroundType(BackgroundType.BRAND_WALL)
                        }
                    },
                    label = "Свой логотип",
                    modifier = Modifier.weight(1f)
                )"""

# First rename "Brand" to "Свой логотип" if needed
content = content.replace('label = "Brand",', 'label = "Свой логотип",')

# Then inject
if "Лого ST24" not in content:
    content = content.replace('label = "Свой логотип",\n                    modifier = Modifier.weight(1f)\n                )', 'label = "Свой логотип",\n                    modifier = Modifier.weight(1f)\n                )\n' + st24_btn)

with open('app/src/main/java/com/example/ui/editor/EditorScreen.kt', 'w') as f:
    f.write(content)
