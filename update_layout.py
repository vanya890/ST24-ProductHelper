import re

with open('app/src/main/java/com/example/ui/editor/EditorViewModel.kt', 'r') as f:
    content = f.read()

replacement = """
            // Layout logic based on format
            val isLandscape = width > height
            val productRatio = foreground.width.toFloat() / foreground.height.toFloat()
            
            var productWidth = 0f
            var productHeight = 0f
            var left = 0f
            var top = 0f
            var startX = 0f
            var currentY = 0f
            
            if (isLandscape) {
                // Landscape: Product on left, Details on right
                productHeight = height * 0.8f
                productWidth = productHeight * productRatio
                if (productWidth > width * 0.55f) {
                    productWidth = width * 0.55f
                    productHeight = productWidth / productRatio
                }
                left = (width * 0.5f - productWidth) / 2f + (width * 0.05f)
                top = (height - productHeight) / 2f
                
                startX = width * 0.6f
                currentY = top + (productHeight * 0.3f)
            } else {
                // Portrait/Square: Product top, Details bottom
                productWidth = width * 0.8f
                productHeight = productWidth / productRatio
                
                if (productHeight > height * 0.65f) {
                    productHeight = height * 0.65f
                    productWidth = productHeight * productRatio
                }
                
                left = (width - productWidth) / 2f
                top = (height * 0.45f - productHeight / 2f) // slightly higher than center
                
                startX = width * 0.1f
                currentY = top + productHeight + (Math.min(width, height) * 0.08f)
            }
            
            val destRect = Rect(left.toInt(), top.toInt(), (left + productWidth).toInt(), (top + productHeight).toInt())
            canvas.drawBitmap(foreground, null, destRect, null)
            
            // Draw Text & QR
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = Math.min(width, height) * (if(isLandscape) 0.06f else 0.05f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.LEFT
            }
            
            if (currentState.showName) {
                canvas.drawText(currentState.name, startX, currentY, textPaint)
                currentY += textPaint.textSize * 1.5f
            }
            
            if (currentState.showPrice) {
                textPaint.color = Color.parseColor("#E91E63")
                canvas.drawText(currentState.price, startX, currentY, textPaint)
                currentY += textPaint.textSize * 1.5f
            }
            
            if (currentState.showLink && currentState.link.isNotEmpty()) {
                var urlToEncode = currentState.link
                if (!urlToEncode.startsWith("http://") && !urlToEncode.startsWith("https://")) {
                    urlToEncode = "https://$urlToEncode"
                }
                
                val qrSize = (Math.min(width, height) * (if(isLandscape) 0.25f else 0.18f)).toInt()
                val qr = QrCodeHelper.generateQrCode(urlToEncode, qrSize)
                if (qr != null) {
                    val qrX = if (isLandscape) startX else width - qr.width - startX
                    val qrY = if (isLandscape) height - top - qr.height else height - (width * 0.1f) - qr.height
                    canvas.drawBitmap(qr, qrX, qrY, null)
                }
            }
"""

# Find the block to replace
start_str = "            // Draw product centered"
end_str = "            _state.update { it.copy(finalBitmap = bitmap) }"
start_idx = content.find(start_str)
end_idx = content.find(end_str)

if start_idx != -1 and end_idx != -1:
    new_content = content[:start_idx] + replacement + "            \n" + content[end_idx:]
    with open('app/src/main/java/com/example/ui/editor/EditorViewModel.kt', 'w') as f:
        f.write(new_content)
    print("Replaced successfully")
else:
    print("Could not find blocks")
