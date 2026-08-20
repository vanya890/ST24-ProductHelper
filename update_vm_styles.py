import re

with open('app/src/main/java/com/example/ui/editor/EditorViewModel.kt', 'r') as f:
    content = f.read()

# 1. Add Enum
if "enum class TemplateStyle" not in content:
    content = content.replace("enum class BackgroundType {", "enum class TemplateStyle {\n    CLASSIC, ST24_DARK, MODERN_LIGHT\n}\n\nenum class BackgroundType {")

# 2. Update EditorState
state_pattern = r"(data class EditorState\([^)]+val productOffsetY: Float = 0f)(\n\))"
state_replacement = r"""\1,
    
    val showStoreName: Boolean = true,
    val storeName: String = "STROY-MATERIALI-24",
    val showPhone: Boolean = true,
    val phone: String = "+7 (926) 163-75-07",
    val templateStyle: TemplateStyle = TemplateStyle.ST24_DARK
)"""
if "val templateStyle:" not in content:
    content = re.sub(state_pattern, state_replacement, content)
    # Also change default price
    content = content.replace('val price: String = "$99.99"', 'val price: String = "999 руб."')

# 3. Add overlay methods and replace updateFinalBitmap
overlay_methods = """
    private fun drawST24DarkOverlay(canvas: Canvas, width: Int, height: Int, state: EditorState) {
        // Top Header
        if (state.showStoreName && state.storeName.isNotEmpty()) {
            val gradPaint = Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, 0f, height * 0.15f, Color.parseColor("#99000000"), Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height * 0.15f, gradPaint)

            val headerPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = Math.min(width, height) * 0.04f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(state.storeName, width / 2f, height * 0.07f, headerPaint)
        }

        // Bottom Card
        val cardMargin = width * 0.05f
        val isLandscape = width > height
        val cardHeight = if (isLandscape) height * 0.35f else Math.min(width, height) * 0.35f
        
        val cardLeft = cardMargin
        val cardTop = height - cardMargin - cardHeight
        val cardRight = width - cardMargin
        val cardBottom = height - cardMargin

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#222222")
        }
        val cardRect = RectF(cardLeft, cardTop, cardRight, cardBottom)
        canvas.drawRoundRect(cardRect, 32f, 32f, cardPaint)

        // Accent line
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9800")
        }
        val accentRect = RectF(cardLeft, cardTop, cardLeft + 24f, cardBottom)
        canvas.drawRoundRect(accentRect, 32f, 32f, accentPaint)
        canvas.drawRect(cardLeft + 12f, cardTop, cardLeft + 24f, cardBottom, accentPaint)

        // QR Code
        val qrSize = (cardHeight * 0.7f).toInt()
        val qrMargin = cardHeight * 0.15f
        val qrRight = cardRight - qrMargin
        val qrTop = cardTop + qrMargin
        
        var qrUrl = state.link
        if (state.showLink && qrUrl.isNotEmpty()) {
            if (!qrUrl.startsWith("http://") && !qrUrl.startsWith("https://")) { qrUrl = "https://$qrUrl" }
            val qr = QrCodeHelper.generateQrCode(qrUrl, qrSize)
            if (qr != null) {
                val qrBgPaint = Paint().apply { color = Color.WHITE }
                val qrBgRect = RectF(qrRight - qrSize - 16f, qrTop - 16f, qrRight + 16f, qrTop + qrSize + 16f)
                canvas.drawRoundRect(qrBgRect, 16f, 16f, qrBgPaint)
                canvas.drawBitmap(qr, qrRight - qrSize, qrTop, null)
            }
        }

        // Phone number
        if (state.showPhone && state.phone.isNotEmpty()) {
            val phonePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF9800")
                textSize = cardHeight * 0.12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(state.phone, qrRight + 16f, cardTop + cardHeight - qrMargin, phonePaint)
        }

        // Product Name and Price
        val textLeft = cardLeft + 60f
        val textMaxWidth = (qrRight - qrSize - 32f - textLeft).toInt()
        
        val namePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = cardHeight * 0.18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        
        if (state.showName && state.name.isNotEmpty()) {
            var currentTextSize = namePaint.textSize
            var staticLayout: android.text.StaticLayout
            do {
                namePaint.textSize = currentTextSize
                staticLayout = android.text.StaticLayout(state.name, namePaint, textMaxWidth, android.text.Layout.Alignment.ALIGN_NORMAL, 1.1f, 0f, false)
                currentTextSize -= 2f
            } while (staticLayout.height > cardHeight * 0.45f && currentTextSize > 12f)

            canvas.save()
            canvas.translate(textLeft, cardTop + cardHeight * 0.15f)
            staticLayout.draw(canvas)
            canvas.restore()
            
            if (state.showPrice && state.price.isNotEmpty()) {
                val pricePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = cardHeight * 0.14f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val pTextLeft = textLeft
                val pTextTop = cardTop + cardHeight * 0.15f + staticLayout.height + pricePaint.textSize * 1.5f
                
                // Highlight bg for price
                val pBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E91E63") }
                val pWidth = pricePaint.measureText(state.price)
                canvas.drawRoundRect(RectF(pTextLeft - 16f, pTextTop - pricePaint.textSize, pTextLeft + pWidth + 16f, pTextTop + 16f), 12f, 12f, pBgPaint)
                canvas.drawText(state.price, pTextLeft, pTextTop, pricePaint)
            }
        }
    }

    private fun drawModernLightOverlay(canvas: Canvas, width: Int, height: Int, state: EditorState) {
        if (state.showStoreName && state.storeName.isNotEmpty()) {
            val headerPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#333333")
                textSize = Math.min(width, height) * 0.04f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(state.storeName, width / 2f, height * 0.08f, headerPaint)
        }

        val cardMargin = width * 0.05f
        val isLandscape = width > height
        val cardHeight = if (isLandscape) height * 0.35f else Math.min(width, height) * 0.35f
        val cardTop = height - cardMargin - cardHeight

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5F5F7") }
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { 
            color = Color.parseColor("#22000000")
            setShadowLayer(30f, 0f, 15f, Color.parseColor("#22000000")) 
        }
        
        val cardRect = RectF(cardMargin, cardTop, width - cardMargin, height - cardMargin)
        canvas.drawRoundRect(cardRect, 40f, 40f, shadowPaint)
        canvas.drawRoundRect(cardRect, 40f, 40f, cardPaint)

        val qrSize = (cardHeight * 0.7f).toInt()
        val qrMargin = cardHeight * 0.15f
        val qrRight = width - cardMargin - qrMargin
        val qrTop = cardTop + qrMargin
        
        var qrUrl = state.link
        if (state.showLink && qrUrl.isNotEmpty()) {
            if (!qrUrl.startsWith("http://") && !qrUrl.startsWith("https://")) { qrUrl = "https://$qrUrl" }
            val qr = QrCodeHelper.generateQrCode(qrUrl, qrSize)
            if (qr != null) {
                canvas.drawBitmap(qr, qrRight - qrSize, qrTop, null)
            }
        }

        if (state.showPhone && state.phone.isNotEmpty()) {
            val phonePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#007AFF")
                textSize = cardHeight * 0.12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(state.phone, qrRight, cardTop + cardHeight - qrMargin, phonePaint)
        }

        val textLeft = cardMargin + 60f
        val textMaxWidth = (qrRight - qrSize - 32f - textLeft).toInt()
        
        val namePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C1C1E")
            textSize = cardHeight * 0.18f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        
        if (state.showName && state.name.isNotEmpty()) {
            var currentTextSize = namePaint.textSize
            var staticLayout: android.text.StaticLayout
            do {
                namePaint.textSize = currentTextSize
                staticLayout = android.text.StaticLayout(state.name, namePaint, textMaxWidth, android.text.Layout.Alignment.ALIGN_NORMAL, 1.1f, 0f, false)
                currentTextSize -= 2f
            } while (staticLayout.height > cardHeight * 0.45f && currentTextSize > 12f)

            canvas.save()
            canvas.translate(textLeft, cardTop + cardHeight * 0.15f)
            staticLayout.draw(canvas)
            canvas.restore()
            
            if (state.showPrice && state.price.isNotEmpty()) {
                val pricePaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#007AFF")
                    textSize = cardHeight * 0.15f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                canvas.drawText(state.price, textLeft, cardTop + cardHeight * 0.15f + staticLayout.height + pricePaint.textSize * 1.5f, pricePaint)
            }
        }
    }

    private fun drawClassicOverlay(canvas: Canvas, width: Int, height: Int, state: EditorState) {
        val isLandscape = width > height
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = Math.min(width, height) * (if(isLandscape) 0.06f else 0.05f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        
        val productRatio = if(state.foregroundBitmap != null) state.foregroundBitmap.width.toFloat() / state.foregroundBitmap.height.toFloat() else 1f
        var top = 0f
        var productHeight = 0f
        var startX = 0f
        
        if (isLandscape) {
            productHeight = height * 0.8f
            var productWidth = productHeight * productRatio
            if (productWidth > width * 0.55f) {
                productWidth = width * 0.55f
                productHeight = productWidth / productRatio
            }
            top = (height - productHeight) / 2f
            startX = width * 0.6f
        } else {
            var productWidth = width * 0.8f
            productHeight = productWidth / productRatio
            if (productHeight > height * 0.65f) {
                productHeight = height * 0.65f
            }
            top = (height * 0.45f - productHeight / 2f)
            startX = width * 0.1f
        }
        
        var currentY = top + (if (isLandscape) productHeight * 0.3f else productHeight + Math.min(width, height) * 0.08f)
        
        if (state.showStoreName && state.storeName.isNotEmpty()) {
            val hPaint = Paint(textPaint).apply { textSize *= 0.8f; color = Color.DKGRAY }
            canvas.drawText(state.storeName, startX, currentY, hPaint)
            currentY += hPaint.textSize * 1.5f
        }
        
        if (state.showName && state.name.isNotEmpty()) {
            val nameTextPaint = android.text.TextPaint(textPaint)
            val maxWidth = if(isLandscape) (width * 0.35f).toInt() else (width * 0.8f).toInt()
            val staticLayout = android.text.StaticLayout(state.name, nameTextPaint, maxWidth, android.text.Layout.Alignment.ALIGN_NORMAL, 1.1f, 0f, false)
            canvas.save()
            canvas.translate(startX, currentY - nameTextPaint.textSize)
            staticLayout.draw(canvas)
            canvas.restore()
            currentY += staticLayout.height + nameTextPaint.textSize * 0.5f
        }
        
        if (state.showPrice && state.price.isNotEmpty()) {
            textPaint.color = Color.parseColor("#E91E63")
            canvas.drawText(state.price, startX, currentY, textPaint)
            currentY += textPaint.textSize * 1.5f
        }
        
        if (state.showPhone && state.phone.isNotEmpty()) {
            textPaint.color = Color.DKGRAY
            textPaint.textSize *= 0.8f
            canvas.drawText(state.phone, startX, currentY, textPaint)
        }
        
        if (state.showLink && state.link.isNotEmpty()) {
            var urlToEncode = state.link
            if (!urlToEncode.startsWith("http://") && !urlToEncode.startsWith("https://")) { urlToEncode = "https://$urlToEncode" }
            val qrSize = (Math.min(width, height) * (if(isLandscape) 0.25f else 0.18f)).toInt()
            val qr = QrCodeHelper.generateQrCode(urlToEncode, qrSize)
            if (qr != null) {
                val qrX = if (isLandscape) startX else width - qr.width - startX
                val qrY = if (isLandscape) height - top - qr.height else height - (width * 0.1f) - qr.height
                canvas.drawBitmap(qr, qrX, qrY, null)
            }
        }
    }
"""

replacement_update = """
    private fun updateFinalBitmap() {
        val currentState = _state.value
        val foreground = currentState.foregroundBitmap ?: return
        
        viewModelScope.launch(Dispatchers.Default) {
            val ogWidth = foreground.width
            val ogHeight = foreground.height

            val width: Int
            val height: Int

            when (currentState.format) {
                AspectRatioFormat.SQUARE_1_1 -> {
                    width = 1080
                    height = 1080
                }
                AspectRatioFormat.PORTRAIT_9_16 -> {
                    width = 1080
                    height = 1920
                }
                AspectRatioFormat.LANDSCAPE_16_9 -> {
                    width = 1920
                    height = 1080
                }
                AspectRatioFormat.ORIGINAL -> {
                    val maxDim = 2000f
                    if (ogWidth > ogHeight && ogWidth > maxDim) {
                        width = maxDim.toInt()
                        height = (maxDim * ogHeight / ogWidth).toInt()
                    } else if (ogHeight > ogWidth && ogHeight > maxDim) {
                        height = maxDim.toInt()
                        width = (maxDim * ogWidth / ogHeight).toInt()
                    } else {
                        width = ogWidth
                        height = ogHeight
                    }
                }
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // Draw background
            canvas.drawColor(Color.WHITE)
            if (currentState.backgroundType == BackgroundType.BRAND_WALL && currentState.brandLogoBitmap != null) {
                drawBrandWall(canvas, width, height, currentState)
            }
            
            val isLandscape = width > height
            val productRatio = foreground.width.toFloat() / foreground.height.toFloat()
            
            var productWidth = 0f
            var productHeight = 0f
            var left = 0f
            var top = 0f
            
            if (isLandscape) {
                productHeight = height * 0.8f
                productWidth = productHeight * productRatio
                if (productWidth > width * 0.55f) {
                    productWidth = width * 0.55f
                    productHeight = productWidth / productRatio
                }
                left = (width * 0.5f - productWidth) / 2f + (width * 0.05f)
                top = (height - productHeight) / 2f
            } else {
                productWidth = width * 0.8f
                productHeight = productWidth / productRatio
                if (productHeight > height * 0.65f) {
                    productHeight = height * 0.65f
                    productWidth = productHeight * productRatio
                }
                left = (width - productWidth) / 2f
                top = (height * 0.45f - productHeight / 2f) 
            }
            
            val destLeft = left + currentState.productOffsetX
            val destTop = top + currentState.productOffsetY
            
            val scaledWidth = productWidth * currentState.productScale
            val scaledHeight = productHeight * currentState.productScale

            val destRect = RectF(
                destLeft + (productWidth - scaledWidth)/2f, 
                destTop + (productHeight - scaledHeight)/2f, 
                destLeft + (productWidth + scaledWidth)/2f, 
                destTop + (productHeight + scaledHeight)/2f
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(foreground, null, destRect, paint)
            
            // Draw Overlay based on Style
            when (currentState.templateStyle) {
                TemplateStyle.ST24_DARK -> drawST24DarkOverlay(canvas, width, height, currentState)
                TemplateStyle.MODERN_LIGHT -> drawModernLightOverlay(canvas, width, height, currentState)
                TemplateStyle.CLASSIC -> drawClassicOverlay(canvas, width, height, currentState)
            }
            
            _state.update { it.copy(finalBitmap = bitmap) }
        }
    }
"""

start_idx = content.find("private fun updateFinalBitmap() {")
end_idx = content.find("private fun drawBrandWall(")

if start_idx != -1 and end_idx != -1:
    content = content[:start_idx] + replacement_update + overlay_methods + "\n    " + content[end_idx:]
    with open('app/src/main/java/com/example/ui/editor/EditorViewModel.kt', 'w') as f:
        f.write(content)
    print("Successfully updated EditorViewModel")
else:
    print("Could not find updateFinalBitmap")
