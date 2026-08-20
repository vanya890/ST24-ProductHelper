import re

with open('app/src/main/java/com/example/ui/editor/EditorScreen.kt', 'r') as f:
    content = f.read()

# 1. Update TopAppBar
content = content.replace('title = { Text("Product Lab", fontWeight = FontWeight.Medium) }', 'title = { Text("Stroy-Materiali-24.ru Product Lab", fontWeight = FontWeight.Medium) }')

# 2. Add TemplateStyle import if needed
if "TemplateStyle" not in content:
    content = content.replace("import com.example.ui.editor.AspectRatioFormat", "import com.example.ui.editor.AspectRatioFormat\nimport com.example.ui.editor.TemplateStyle")

# 3. Add TemplateStyle UI
style_ui = """
    Spacer(modifier = Modifier.height(16.dp))
    
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("TEMPLATE STYLE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TemplateStyle.values().forEach { style ->
                    val isSelected = state.templateStyle == style
                    Surface(
                        modifier = Modifier.weight(1f).height(40.dp).clickable { viewModel.updateTemplateStyle(style) },
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp),
                        border = if (!isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = style.name.replace("_", " "),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
"""

content = content.replace('Text("FORMAT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)', style_ui + '\n\n            Spacer(modifier = Modifier.height(16.dp))\n            Text("FORMAT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)')

# 4. Add Store Name and Phone Number text fields
details_start = content.find('Text("PRODUCT DETAILS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)')
details_replace = """Text("PRODUCT DETAILS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedTextField(
                value = state.storeName,
                onValueChange = { viewModel.updateStoreName(it) },
                label = { Text("Store/Header Name") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Checkbox(checked = state.showStoreName, onCheckedChange = { viewModel.toggleStoreName(it) })
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
"""
content = content.replace('Text("PRODUCT DETAILS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)', details_replace)

phone_field = """
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.phone,
                onValueChange = { viewModel.updatePhone(it) },
                label = { Text("Phone Number") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Checkbox(checked = state.showPhone, onCheckedChange = { viewModel.togglePhone(it) })
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                singleLine = true
            )
"""
content = content.replace('unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)\n                )\n            )\n        }\n    }\n    \n    Spacer(modifier = Modifier.height(16.dp))\n    \n    Surface(', 'unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)\n                )\n            )' + phone_field + '\n        }\n    }\n    \n    Spacer(modifier = Modifier.height(16.dp))\n    \n    Surface(')

with open('app/src/main/java/com/example/ui/editor/EditorScreen.kt', 'w') as f:
    f.write(content)
print("Screen updated")
