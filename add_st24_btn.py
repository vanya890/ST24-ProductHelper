import re

with open('app/src/main/java/com/example/ui/editor/EditorScreen.kt', 'r') as f:
    content = f.read()

btn_code = """
                        FilterChip(
                            selected = state.backgroundType == BackgroundType.ST24_LOGO,
                            onClick = { viewModel.updateBackgroundType(BackgroundType.ST24_LOGO) },
                            label = { Text("Лого ST24") },
                            leadingIcon = if (state.backgroundType == BackgroundType.ST24_LOGO) {
                                { Icon(Icons.Default.Check, contentDescription = null) }
                            } else null
                        )"""

if "Лого ST24" not in content:
    content = content.replace(
        'label = { Text("Свой логотип") },\n                            leadingIcon = if (state.backgroundType == BackgroundType.BRAND_WALL) {\n                                { Icon(Icons.Default.Check, contentDescription = null) }\n                            } else null\n                        )',
        'label = { Text("Свой логотип") },\n                            leadingIcon = if (state.backgroundType == BackgroundType.BRAND_WALL) {\n                                { Icon(Icons.Default.Check, contentDescription = null) }\n                            } else null\n                        )\n' + btn_code
    )

with open('app/src/main/java/com/example/ui/editor/EditorScreen.kt', 'w') as f:
    f.write(content)
