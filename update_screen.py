import re

with open('app/src/main/java/com/example/ui/editor/EditorScreen.kt', 'r') as f:
    content = f.read()

# Add pointerInput import if missing
if "androidx.compose.ui.input.pointer.pointerInput" not in content:
    content = content.replace("import androidx.compose.ui.Modifier", "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.input.pointer.pointerInput\nimport androidx.compose.foundation.gestures.detectTransformGestures")

replacement = """                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(24.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
                                        .background(Color.White)
                                        .pointerInput(Unit) {
                                            detectTransformGestures { _, pan, zoom, _ ->
                                                viewModel.updateProductTransform(zoom, pan.x, pan.y)
                                            }
                                        }"""
                                        
content = re.sub(r"modifier = Modifier\s*\.fillMaxSize\(\)\s*\.clip\(RoundedCornerShape\(24\.dp\)\)\s*\.border\(1\.dp, MaterialTheme\.colorScheme\.outline, RoundedCornerShape\(24\.dp\)\)\s*\.background\(Color\.White\)", replacement, content)


replacement_mobile = """                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 400.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
                                        .background(Color.White)
                                        .pointerInput(Unit) {
                                            detectTransformGestures { _, pan, zoom, _ ->
                                                viewModel.updateProductTransform(zoom, pan.x, pan.y)
                                            }
                                        }"""
                                        
content = re.sub(r"modifier = Modifier\s*\.fillMaxWidth\(\)\s*\.heightIn\(max = 400\.dp\)\s*\.clip\(RoundedCornerShape\(24\.dp\)\)\s*\.border\(1\.dp, MaterialTheme\.colorScheme\.outline, RoundedCornerShape\(24\.dp\)\)\s*\.background\(Color\.White\)", replacement_mobile, content)


with open('app/src/main/java/com/example/ui/editor/EditorScreen.kt', 'w') as f:
    f.write(content)
print("Updated EditorScreen")
