import re

with open('app/src/main/java/com/example/ui/editor/EditorViewModel.kt', 'r') as f:
    content = f.read()

# Add ST24_LOGO to BackgroundType enum
if "ST24_LOGO" not in content:
    content = content.replace("enum class BackgroundType {\n    WHITE, BRAND_WALL\n}", "enum class BackgroundType {\n    WHITE, BRAND_WALL, ST24_LOGO\n}")

if "com.caverock.androidsvg.SVG" not in content:
    content = content.replace("import android.graphics.Typeface", "import android.graphics.Typeface\nimport com.caverock.androidsvg.SVG\nimport com.example.R")

# Update updateFinalBitmap background drawing
draw_bg_replace = """            // Draw background
            canvas.drawColor(Color.WHITE)
            if (currentState.backgroundType == BackgroundType.BRAND_WALL && currentState.brandLogoBitmap != null) {
                drawBrandWall(canvas, width, height, currentState)
            } else if (currentState.backgroundType == BackgroundType.ST24_LOGO) {
                try {
                    val svg = SVG.getFromResource(applicationContext, R.raw.logo)
                    val bgBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val bgCanvas = Canvas(bgBitmap)
                    
                    // The logo's default document width might be small, so we want it to cover a good portion or tile it.
                    // Let's draw it like a watermark or a brand wall? The user said "фиксированным логотипом".
                    // Let's draw it tiled like a brand wall, but fixed size.
                    val docWidth = svg.documentWidth
                    val docHeight = svg.documentHeight
                    if (docWidth > 0 && docHeight > 0) {
                        val scale = 200f / docWidth
                        val scaledW = (docWidth * scale).toInt()
                        val scaledH = (docHeight * scale).toInt()
                        
                        val logoBitmap = Bitmap.createBitmap(scaledW, scaledH, Bitmap.Config.ARGB_8888)
                        val logoCanvas = Canvas(logoBitmap)
                        logoCanvas.scale(scale, scale)
                        svg.renderToCanvas(logoCanvas)
                        
                        // Now tile it
                        val totalSize = 250
                        val offsetX = 0
                        val offsetY = 0
                        val cols = (width / totalSize) + 2
                        val rows = (height / totalSize) + 2
                        for (row in -1..rows) {
                            for (col in -1..cols) {
                                var x = col * totalSize + offsetX
                                var y = row * totalSize + offsetY
                                if (row % 2 != 0) x += totalSize / 2
                                canvas.drawBitmap(logoBitmap, x.toFloat(), y.toFloat(), null)
                            }
                        }
                    }
                } catch(e: Exception) {
                    e.printStackTrace()
                }
            }"""

content = re.sub(r"\s*// Draw background\s*canvas\.drawColor\(Color\.WHITE\)\s*if \(currentState\.backgroundType == BackgroundType\.BRAND_WALL && currentState\.brandLogoBitmap != null\) \{\s*drawBrandWall\(canvas, width, height, currentState\)\s*\}", "\n" + draw_bg_replace, content)

with open('app/src/main/java/com/example/ui/editor/EditorViewModel.kt', 'w') as f:
    f.write(content)
