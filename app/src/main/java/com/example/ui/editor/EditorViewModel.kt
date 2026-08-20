package com.example.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.caverock.androidsvg.SVG
import com.example.R
import com.example.data.ProductEntity
import com.example.data.ProductRepository
import com.example.mlkit.SegmentationHelper
import com.example.utils.LanczosHelper
import com.example.utils.QrCodeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class EditorState(
    val isLoading: Boolean = true,
    val originalImageUri: String = "",
    val foregroundBitmap: Bitmap? = null,
    val finalBitmap: Bitmap? = null,
    
    val showName: Boolean = true,
    val name: String = "Название товара",
    val showPrice: Boolean = true,
    val price: String = "999 руб.",
    val showLink: Boolean = true,
    val link: String = "https://stroy-materiali-24.ru",
    
    val backgroundType: BackgroundType = BackgroundType.WHITE,
    val format: AspectRatioFormat = AspectRatioFormat.PORTRAIT_9_16,
    val brandLogoBitmap: Bitmap? = null,
    val brandLogoSize: Float = 120f,
    val brandLogoSpacing: Float = 60f,
    val brandLogoOffsetX: Float = 0f,
    val brandLogoOffsetY: Float = 0f,
    val isCheckerboard: Boolean = true,
    val brandLogoSkip: Int = 1, // 1 = Все, 2 = Через один, 3 = Через два, 4 = Редкая сетка
    val brandLogoHaloRadius: Float = 180f,
    val brandLogoHaloIntensity: Float = 1.0f,
    
    val productScale: Float = 1f,
    val productOffsetX: Float = 0f,
    val productOffsetY: Float = 0f,
    
    val showStoreName: Boolean = true,
    val storeName: String = "STROY-MATERIALI-24",
    val showPhone: Boolean = true,
    val phone: String = "+7 (926) 163-75-07",
    val templateStyle: TemplateStyle = TemplateStyle.ST24_DARK
)

enum class TemplateStyle {
    CLASSIC, ST24_DARK, MODERN_LIGHT
}

enum class BackgroundType {
    WHITE, BRAND_WALL, ST24_LOGO
}

enum class AspectRatioFormat {
    ORIGINAL, SQUARE_1_1, PORTRAIT_9_16, LANDSCAPE_16_9
}

class EditorViewModel(
    private val repository: ProductRepository,
    private val applicationContext: Context
) : ViewModel() {
    
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    fun init(imageUri: String) {
        val prefs = applicationContext.getSharedPreferences("product_prefs", Context.MODE_PRIVATE)
        _state.update { 
            it.copy(
                originalImageUri = imageUri, 
                isLoading = true,
                name = prefs.getString("last_name", "Название товара") ?: "Название товара",
                price = prefs.getString("last_price", "999 руб.") ?: "999 руб.",
                link = prefs.getString("last_link", "https://stroy-materiali-24.ru") ?: "https://stroy-materiali-24.ru",
                storeName = prefs.getString("last_store_name", "STROY-MATERIALI-24") ?: "STROY-MATERIALI-24",
                phone = prefs.getString("last_phone", "+7 (926) 163-75-07") ?: "+7 (926) 163-75-07"
            ) 
        }
        viewModelScope.launch {
            val bitmap = loadBitmap(imageUri)
            if (bitmap != null) {
                val foreground = SegmentationHelper.segmentProduct(bitmap) ?: bitmap
                _state.update { it.copy(foregroundBitmap = foreground, isLoading = false) }
                resetProductPosition()
            } else {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private suspend fun loadBitmap(uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            val inputStream = applicationContext.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            null
        }
    }

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

    fun updateName(name: String) {
        _state.update { it.copy(name = name) }
        applicationContext.getSharedPreferences("product_prefs", Context.MODE_PRIVATE).edit().putString("last_name", name).apply()
        updateFinalBitmap()
    }
    
    fun toggleName(show: Boolean) {
        _state.update { it.copy(showName = show) }
        updateFinalBitmap()
    }

    fun updatePrice(price: String) {
        _state.update { it.copy(price = price) }
        applicationContext.getSharedPreferences("product_prefs", Context.MODE_PRIVATE).edit().putString("last_price", price).apply()
        updateFinalBitmap()
    }
    
    fun togglePrice(show: Boolean) {
        _state.update { it.copy(showPrice = show) }
        updateFinalBitmap()
    }

    fun updateLink(link: String) {
        _state.update { it.copy(link = link) }
        applicationContext.getSharedPreferences("product_prefs", Context.MODE_PRIVATE).edit().putString("last_link", link).apply()
        updateFinalBitmap()
    }
    
    fun toggleLink(show: Boolean) {
        _state.update { it.copy(showLink = show) }
        updateFinalBitmap()
    }
    
    fun setFormat(format: AspectRatioFormat) {
        _state.update { it.copy(format = format) }
        resetProductPosition()
    }

    fun setBackgroundType(type: BackgroundType) {
        _state.update { it.copy(backgroundType = type) }
        updateFinalBitmap()
    }
    
    fun setBrandLogo(bitmap: Bitmap?) {
        _state.update { it.copy(brandLogoBitmap = bitmap, backgroundType = BackgroundType.BRAND_WALL) }
        updateFinalBitmap()
    }
    
    fun updateBrandWallSettings(
        size: Float, 
        spacing: Float, 
        offsetX: Float, 
        offsetY: Float, 
        isCheckerboard: Boolean,
        skip: Int = _state.value.brandLogoSkip,
        haloRadius: Float = _state.value.brandLogoHaloRadius,
        haloIntensity: Float = _state.value.brandLogoHaloIntensity
    ) {
        _state.update { 
            it.copy(
                brandLogoSize = size, 
                brandLogoSpacing = spacing, 
                brandLogoOffsetX = offsetX, 
                brandLogoOffsetY = offsetY, 
                isCheckerboard = isCheckerboard,
                brandLogoSkip = skip,
                brandLogoHaloRadius = haloRadius,
                brandLogoHaloIntensity = haloIntensity
            ) 
        }
        updateFinalBitmap()
    }

    fun updateProductTransform(scaleMultiplier: Float, panX: Float, panY: Float) {
        _state.update { 
            it.copy(
                productScale = (it.productScale * scaleMultiplier).coerceIn(0.2f, 5f),
                productOffsetX = it.productOffsetX + panX,
                productOffsetY = it.productOffsetY + panY
            )
        }
        updateFinalBitmap()
    }

    /**
     * Intelligently centers and auto-scales the product to fill the studio template area.
     */
    fun resetProductPosition() {
        val foreground = _state.value.foregroundBitmap
        if (foreground == null) {
            _state.update { it.copy(productOffsetX = 0f, productOffsetY = 0f, productScale = 1f) }
            updateFinalBitmap()
            return
        }

        val (canvasW, canvasH) = getCanvasDimensions(_state.value.format, foreground.width, foreground.height)
        val isLandscape = canvasW > canvasH
        val bbox = SegmentationHelper.findSubjectBoundingBox(foreground)
        val bboxW = bbox.width().toFloat().coerceAtLeast(10f)
        val bboxH = bbox.height().toFloat().coerceAtLeast(10f)

        // Available free studio area
        val availW: Float
        val availH: Float
        val targetCenterX: Float
        val targetCenterY: Float

        if (isLandscape) {
            availW = canvasW * 0.54f
            availH = canvasH * 0.82f
            targetCenterX = canvasW * 0.30f
            targetCenterY = canvasH * 0.50f
        } else {
            val headerH = if (_state.value.showStoreName && _state.value.storeName.isNotEmpty()) canvasH * 0.11f else canvasH * 0.03f
            val cardH = if (canvasW == canvasH) canvasH * 0.22f else canvasH * 0.175f
            availW = canvasW * 0.90f
            availH = (canvasH - headerH - cardH) * 0.92f
            targetCenterX = canvasW * 0.50f
            targetCenterY = headerH + (canvasH - headerH - cardH) * 0.50f
        }

        // Calculate optimal scale so bbox occupies ~85% of available space
        val scaleX = availW / bboxW
        val scaleY = availH / bboxH
        val optimalScale = min(scaleX, scaleY).coerceIn(0.3f, 3.5f)

        // Initial reference placement for entire foreground bitmap
        val defaultW = if (isLandscape) canvasW * 0.58f else canvasW * 0.88f
        val ratio = foreground.width.toFloat() / foreground.height.toFloat()
        var baseW = defaultW
        var baseH = baseW / ratio
        if (isLandscape && baseH > canvasH * 0.85f) {
            baseH = canvasH * 0.85f
            baseW = baseH * ratio
        }

        val baseScale = optimalScale * (bboxW / baseW)
        val normalizedScale = baseScale.coerceIn(0.5f, 3.0f)

        _state.update { 
            it.copy(
                productOffsetX = 0f,
                productOffsetY = 0f,
                productScale = normalizedScale
            ) 
        }
        updateFinalBitmap()
    }

    private fun getCanvasDimensions(format: AspectRatioFormat, ogWidth: Int, ogHeight: Int): Pair<Int, Int> {
        return when (format) {
            AspectRatioFormat.SQUARE_1_1 -> Pair(1080, 1080)
            AspectRatioFormat.PORTRAIT_9_16 -> Pair(1080, 1920)
            AspectRatioFormat.LANDSCAPE_16_9 -> Pair(1920, 1080)
            AspectRatioFormat.ORIGINAL -> {
                val maxDim = 1920f
                if (ogWidth >= ogHeight && ogWidth > maxDim) {
                    Pair(maxDim.toInt(), (maxDim * ogHeight / ogWidth).toInt())
                } else if (ogHeight > ogWidth && ogHeight > maxDim) {
                    Pair((maxDim * ogWidth / ogHeight).toInt(), maxDim.toInt())
                } else {
                    Pair(ogWidth, ogHeight)
                }
            }
        }
    }

    fun updateFinalBitmap() {
        val currentState = _state.value
        val foreground = currentState.foregroundBitmap ?: return
        
        viewModelScope.launch(Dispatchers.Default) {
            val (width, height) = getCanvasDimensions(currentState.format, foreground.width, foreground.height)
            val isLandscape = width > height
            val productRatio = foreground.width.toFloat() / foreground.height.toFloat()

            // 1. Calculate Product Destination Bounds
            var productWidth: Float
            var productHeight: Float
            val left: Float
            val top: Float

            if (isLandscape) {
                val availableWidth = width * 0.58f
                val availableHeight = height * 0.85f

                productHeight = availableHeight
                productWidth = productHeight * productRatio
                if (productWidth > availableWidth) {
                    productWidth = availableWidth
                    productHeight = productWidth / productRatio
                }

                left = (availableWidth - productWidth) / 2f + (width * 0.03f)
                top = (height - productHeight) / 2f
            } else {
                val headerOffset = if (currentState.showStoreName && currentState.storeName.isNotEmpty()) height * 0.12f else height * 0.03f
                val cardMargin = width * 0.04f
                val cardHeight = height * 0.26f
                val footerOffset = cardHeight + cardMargin + (height * 0.02f)

                val availableWidth = width * 0.88f
                val availableHeight = height - headerOffset - footerOffset

                productWidth = availableWidth
                productHeight = productWidth / productRatio
                if (productHeight > availableHeight) {
                    productHeight = availableHeight
                    productWidth = productHeight * productRatio
                }

                left = (width - productWidth) / 2f
                top = headerOffset + (availableHeight - productHeight) / 2f
            }

            val scaledWidth = productWidth * currentState.productScale
            val scaledHeight = productHeight * currentState.productScale

            val destLeft = left + currentState.productOffsetX + (productWidth - scaledWidth) / 2f
            val destTop = top + currentState.productOffsetY + (productHeight - scaledHeight) / 2f

            val productBounds = RectF(
                destLeft,
                destTop,
                destLeft + scaledWidth,
                destTop + scaledHeight
            )

            val subjectBBox = SegmentationHelper.findSubjectBoundingBox(foreground)
            val origW = foreground.width.toFloat().coerceAtLeast(1f)
            val origH = foreground.height.toFloat().coerceAtLeast(1f)
            val tightLeft = destLeft + (subjectBBox.left / origW) * scaledWidth
            val tightTop = destTop + (subjectBBox.top / origH) * scaledHeight
            val tightRight = destLeft + (subjectBBox.right / origW) * scaledWidth
            val tightBottom = destTop + (subjectBBox.bottom / origH) * scaledHeight
            val tightProductBounds = RectF(tightLeft, tightTop, tightRight, tightBottom)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // 2. Draw base background
            canvas.drawColor(Color.WHITE)
            
            if (currentState.backgroundType == BackgroundType.BRAND_WALL && currentState.brandLogoBitmap != null) {
                drawBrandWall(canvas, width, height, currentState, tightProductBounds)
            } else if (currentState.backgroundType == BackgroundType.ST24_LOGO) {
                drawST24LogoWall(canvas, width, height, currentState, tightProductBounds)
            }

            // 3. Draw high-quality foreground product
            val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(foreground, null, productBounds, filterPaint)
            
            // 4. Draw Template Overlays (with strict non-overflow block CSS-like layout)
            when (currentState.templateStyle) {
                TemplateStyle.ST24_DARK -> drawST24DarkOverlay(canvas, width, height, currentState)
                TemplateStyle.MODERN_LIGHT -> drawModernLightOverlay(canvas, width, height, currentState)
                TemplateStyle.CLASSIC -> drawClassicOverlay(canvas, width, height, currentState)
            }
            
            _state.update { it.copy(finalBitmap = bitmap) }
        }
    }

    private fun drawST24DarkOverlay(canvas: Canvas, width: Int, height: Int, state: EditorState) {
        val isLandscape = width > height
        val baseDim = min(width, height)

        // 1. Top Header with Premium Gradient & Pill Badge
        if (state.showStoreName && state.storeName.isNotEmpty()) {
            val gradHeight = height * 0.14f
            val topGradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, gradHeight, Color.parseColor("#B3000000"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), gradHeight, topGradPaint)

            val headerTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = baseDim * 0.034f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.08f
            }
            val textWidth = headerTextPaint.measureText(state.storeName)
            val pillPaddingH = baseDim * 0.036f
            val pillPaddingV = baseDim * 0.016f
            val pillW = textWidth + (pillPaddingH * 2)
            val pillH = headerTextPaint.textSize + (pillPaddingV * 2)
            val pillX = (width - pillW) / 2f
            val pillY = height * 0.035f

            val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#441A1A1A")
            }
            val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.parseColor("#33FFFFFF")
            }
            val pillRect = RectF(pillX, pillY, pillX + pillW, pillY + pillH)
            canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, pillBgPaint)
            canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, pillBorderPaint)

            val fontMetrics = headerTextPaint.fontMetrics
            val baseline = pillY + (pillH - (fontMetrics.ascent + fontMetrics.descent)) / 2f
            headerTextPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(state.storeName, width / 2f, baseline, headerTextPaint)
        }

        // 2. Bottom Smooth Vignette / Gradient
        val bottomGradH = height * 0.30f
        val bottomGradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, height - bottomGradH, 0f, height.toFloat(),
                Color.TRANSPARENT, Color.parseColor("#70000000"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, height - bottomGradH, width.toFloat(), height.toFloat(), bottomGradPaint)

        // 3. Card Container Dimensions - Perfectly Compact & Proportioned
        val cardMargin = width * 0.035f
        val cardHeight: Float
        val cardLeft: Float
        val cardTop: Float
        val cardRight: Float
        val cardBottom: Float

        if (isLandscape) {
            cardHeight = height * 0.82f
            cardLeft = width * 0.60f
            cardRight = width - cardMargin
            cardTop = (height - cardHeight) / 2f
            cardBottom = cardTop + cardHeight
        } else {
            cardHeight = if (width == height) (height * 0.21f).coerceIn(190f, 250f) else (height * 0.175f).coerceIn(190f, 310f)
            cardLeft = cardMargin
            cardRight = width - cardMargin
            cardBottom = height - cardMargin
            cardTop = cardBottom - cardHeight
        }

        val cardRadius = 24f
        val cardRect = RectF(cardLeft, cardTop, cardRight, cardBottom)

        // Drop Shadow & Card Surface
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#55000000")
            setShadowLayer(20f, 0f, 6f, Color.parseColor("#55000000"))
        }
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, shadowPaint)

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1F1F21")
        }
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, cardPaint)

        // Orange Accent Left Bar
        val accentWidth = 12f
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9800")
        }
        val accentRect = RectF(cardLeft, cardTop, cardLeft + accentWidth, cardBottom)
        canvas.drawRoundRect(accentRect, cardRadius, cardRadius, accentPaint)
        canvas.drawRect(cardLeft + (accentWidth / 2f), cardTop, cardLeft + accentWidth, cardBottom, accentPaint)

        // 4. Adaptive Right Column: QR Code + Phone Block (Vertically Centered)
        val hasQr = state.showLink && state.link.isNotEmpty()
        val hasPhone = state.showPhone && state.phone.isNotEmpty()
        
        var qrLeft = 0f
        var qrRight = 0f

        if (hasQr && hasPhone) {
            val qrBoxSize = (cardHeight * 0.58f).coerceAtMost(cardHeight - 48f)
            val phoneTextSize = (cardHeight * 0.095f).coerceIn(16f, 30f)
            val gap = 6f
            val totalRightH = qrBoxSize + gap + phoneTextSize
            val rightStartY = cardTop + (cardHeight - totalRightH) / 2f

            val qrTop = rightStartY
            val qrBottom = qrTop + qrBoxSize
            qrRight = cardRight - (cardHeight * 0.10f)
            qrLeft = qrRight - qrBoxSize

            var qrUrl = state.link
            if (!qrUrl.startsWith("http://") && !qrUrl.startsWith("https://")) {
                qrUrl = "https://$qrUrl"
            }
            val qr = QrCodeHelper.generateQrCode(qrUrl, (qrBoxSize * 0.94f).toInt())
            if (qr != null) {
                val qrBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                val qrBgRect = RectF(qrLeft, qrTop, qrLeft + qrBoxSize, qrBottom)
                canvas.drawRoundRect(qrBgRect, 14f, 14f, qrBgPaint)
                val qrX = qrLeft + (qrBoxSize - qr.width) / 2f
                val qrY = qrTop + (qrBoxSize - qr.height) / 2f
                canvas.drawBitmap(qr, qrX, qrY, null)
            }

            val phonePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF9800")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = phoneTextSize
            }
            val phoneCenterX = qrLeft + (qrBoxSize / 2f)
            val phoneBaseline = qrBottom + gap + phoneTextSize * 0.82f
            canvas.drawText(state.phone, phoneCenterX, phoneBaseline, phonePaint)

        } else if (hasQr) {
            val qrBoxSize = cardHeight * 0.72f
            val qrTop = cardTop + (cardHeight - qrBoxSize) / 2f
            val qrBottom = qrTop + qrBoxSize
            qrRight = cardRight - (cardHeight * 0.10f)
            qrLeft = qrRight - qrBoxSize

            var qrUrl = state.link
            if (!qrUrl.startsWith("http://") && !qrUrl.startsWith("https://")) {
                qrUrl = "https://$qrUrl"
            }
            val qr = QrCodeHelper.generateQrCode(qrUrl, (qrBoxSize * 0.94f).toInt())
            if (qr != null) {
                val qrBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                val qrBgRect = RectF(qrLeft, qrTop, qrLeft + qrBoxSize, qrBottom)
                canvas.drawRoundRect(qrBgRect, 14f, 14f, qrBgPaint)
                val qrX = qrLeft + (qrBoxSize - qr.width) / 2f
                val qrY = qrTop + (qrBoxSize - qr.height) / 2f
                canvas.drawBitmap(qr, qrX, qrY, null)
            }
        } else if (hasPhone) {
            val phoneTextSize = (cardHeight * 0.12f).coerceIn(20f, 36f)
            val phonePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF9800")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
                textSize = phoneTextSize
            }
            val phoneRight = cardRight - (cardHeight * 0.15f)
            val phoneY = cardTop + (cardHeight / 2f) + phoneTextSize * 0.35f
            canvas.drawText(state.phone, phoneRight, phoneY, phonePaint)
        }

        // 5. Left Column: Product Name & Price Badge (Vertically Centered to Eliminate Dead Space)
        val textLeft = cardLeft + accentWidth + (cardHeight * 0.12f)
        val textRightLimit = if (hasQr) qrLeft - (cardHeight * 0.08f) else if (hasPhone) cardRight - (cardHeight * 0.8f) else cardRight - (cardHeight * 0.12f)
        val textMaxWidth = (textRightLimit - textLeft).toInt().coerceAtLeast(80)

        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (cardHeight * 0.15f).coerceIn(24f, 44f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var staticLayout: StaticLayout? = null
        if (state.showName && state.name.isNotEmpty()) {
            var currentSize = namePaint.textSize
            do {
                namePaint.textSize = currentSize
                staticLayout = StaticLayout(
                    state.name,
                    namePaint,
                    textMaxWidth,
                    Layout.Alignment.ALIGN_NORMAL,
                    1.08f,
                    0f,
                    false
                )
                currentSize -= 2f
            } while (staticLayout.height > (cardHeight * 0.46f) && currentSize > 16f)
        }

        val pricePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (cardHeight * 0.12f).coerceIn(20f, 38f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val hasName = staticLayout != null
        val hasPrice = state.showPrice && state.price.isNotEmpty()

        val pPadH = 18f
        val pPadV = 8f
        val pPillH = pricePaint.textSize + (pPadV * 2)
        val pTextWidth = pricePaint.measureText(state.price)
        val pPillW = pTextWidth + (pPadH * 2)
        val gapLeft = 8f

        val totalLeftH = (if (hasName) staticLayout!!.height else 0) + 
                         (if (hasName && hasPrice) gapLeft else 0f) + 
                         (if (hasPrice) pPillH else 0f)

        val leftStartY = cardTop + (cardHeight - totalLeftH) / 2f

        if (hasName) {
            canvas.save()
            canvas.translate(textLeft, leftStartY)
            staticLayout!!.draw(canvas)
            canvas.restore()
        }

        if (hasPrice) {
            val pPillY = leftStartY + (if (hasName) staticLayout!!.height + gapLeft else 0f)
            val pricePillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E91E63")
            }
            val priceRect = RectF(textLeft, pPillY, textLeft + pPillW, pPillY + pPillH)
            canvas.drawRoundRect(priceRect, 12f, 12f, pricePillPaint)

            val priceBaseline = pPillY + pPadV + pricePaint.textSize * 0.82f
            canvas.drawText(state.price, textLeft + pPadH, priceBaseline, pricePaint)
        }
    }

    private fun drawModernLightOverlay(canvas: Canvas, width: Int, height: Int, state: EditorState) {
        val isLandscape = width > height
        val baseDim = min(width, height)

        if (state.showStoreName && state.storeName.isNotEmpty()) {
            val headerTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1C1C1E")
                textSize = baseDim * 0.034f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.06f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(state.storeName, width / 2f, height * 0.055f, headerTextPaint)
        }

        val cardMargin = width * 0.035f
        val cardHeight = if (isLandscape) height * 0.82f else if (width == height) (height * 0.21f).coerceIn(190f, 250f) else (height * 0.175f).coerceIn(190f, 310f)
        val cardLeft = if (isLandscape) width * 0.60f else cardMargin
        val cardRight = width - cardMargin
        val cardBottom = height - cardMargin
        val cardTop = if (isLandscape) (height - cardHeight) / 2f else cardBottom - cardHeight

        val cardRect = RectF(cardLeft, cardTop, cardRight, cardBottom)

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#14000000")
            setShadowLayer(20f, 0f, 6f, Color.parseColor("#14000000"))
        }
        canvas.drawRoundRect(cardRect, 24f, 24f, shadowPaint)

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8F9FA")
        }
        canvas.drawRoundRect(cardRect, 24f, 24f, cardPaint)

        val accentWidth = 10f
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#007AFF")
        }
        val accentRect = RectF(cardLeft, cardTop, cardLeft + accentWidth, cardBottom)
        canvas.drawRoundRect(accentRect, 24f, 24f, accentPaint)
        canvas.drawRect(cardLeft + (accentWidth / 2f), cardTop, cardLeft + accentWidth, cardBottom, accentPaint)

        val hasQr = state.showLink && state.link.isNotEmpty()
        val hasPhone = state.showPhone && state.phone.isNotEmpty()

        var qrLeft = 0f
        var qrRight = 0f

        if (hasQr && hasPhone) {
            val qrBoxSize = (cardHeight * 0.58f).coerceAtMost(cardHeight - 48f)
            val phoneTextSize = (cardHeight * 0.095f).coerceIn(16f, 30f)
            val gap = 6f
            val totalRightH = qrBoxSize + gap + phoneTextSize
            val rightStartY = cardTop + (cardHeight - totalRightH) / 2f

            val qrTop = rightStartY
            val qrBottom = qrTop + qrBoxSize
            qrRight = cardRight - (cardHeight * 0.10f)
            qrLeft = qrRight - qrBoxSize

            var qrUrl = state.link
            if (!qrUrl.startsWith("http://") && !qrUrl.startsWith("https://")) {
                qrUrl = "https://$qrUrl"
            }
            val qr = QrCodeHelper.generateQrCode(qrUrl, (qrBoxSize * 0.94f).toInt())
            if (qr != null) {
                val qrBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                val qrBgRect = RectF(qrLeft, qrTop, qrLeft + qrBoxSize, qrBottom)
                canvas.drawRoundRect(qrBgRect, 14f, 14f, qrBgPaint)
                canvas.drawBitmap(qr, qrLeft + (qrBoxSize - qr.width) / 2f, qrTop + (qrBoxSize - qr.height) / 2f, null)
            }

            val phonePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#007AFF")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = phoneTextSize
            }
            val phoneCenterX = qrLeft + (qrBoxSize / 2f)
            val phoneBaseline = qrBottom + gap + phoneTextSize * 0.82f
            canvas.drawText(state.phone, phoneCenterX, phoneBaseline, phonePaint)

        } else if (hasQr) {
            val qrBoxSize = cardHeight * 0.72f
            val qrTop = cardTop + (cardHeight - qrBoxSize) / 2f
            val qrBottom = qrTop + qrBoxSize
            qrRight = cardRight - (cardHeight * 0.10f)
            qrLeft = qrRight - qrBoxSize

            var qrUrl = state.link
            if (!qrUrl.startsWith("http://") && !qrUrl.startsWith("https://")) {
                qrUrl = "https://$qrUrl"
            }
            val qr = QrCodeHelper.generateQrCode(qrUrl, (qrBoxSize * 0.94f).toInt())
            if (qr != null) {
                val qrBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                val qrBgRect = RectF(qrLeft, qrTop, qrLeft + qrBoxSize, qrBottom)
                canvas.drawRoundRect(qrBgRect, 14f, 14f, qrBgPaint)
                canvas.drawBitmap(qr, qrLeft + (qrBoxSize - qr.width) / 2f, qrTop + (qrBoxSize - qr.height) / 2f, null)
            }
        } else if (hasPhone) {
            val phoneTextSize = (cardHeight * 0.12f).coerceIn(20f, 36f)
            val phonePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#007AFF")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
                textSize = phoneTextSize
            }
            val phoneRight = cardRight - (cardHeight * 0.15f)
            val phoneY = cardTop + (cardHeight / 2f) + phoneTextSize * 0.35f
            canvas.drawText(state.phone, phoneRight, phoneY, phonePaint)
        }

        // Left Column: Product Name & Price Badge
        val textLeft = cardLeft + accentWidth + (cardHeight * 0.12f)
        val textRightLimit = if (hasQr) qrLeft - (cardHeight * 0.08f) else if (hasPhone) cardRight - (cardHeight * 0.8f) else cardRight - (cardHeight * 0.12f)
        val textMaxWidth = (textRightLimit - textLeft).toInt().coerceAtLeast(80)

        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1C1C1E")
            textSize = (cardHeight * 0.15f).coerceIn(24f, 44f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var staticLayout: StaticLayout? = null
        if (state.showName && state.name.isNotEmpty()) {
            var currentSize = namePaint.textSize
            do {
                namePaint.textSize = currentSize
                staticLayout = StaticLayout(
                    state.name,
                    namePaint,
                    textMaxWidth,
                    Layout.Alignment.ALIGN_NORMAL,
                    1.08f,
                    0f,
                    false
                )
                currentSize -= 2f
            } while (staticLayout.height > (cardHeight * 0.46f) && currentSize > 16f)
        }

        val pricePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (cardHeight * 0.12f).coerceIn(20f, 38f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val hasName = staticLayout != null
        val hasPrice = state.showPrice && state.price.isNotEmpty()

        val pPadH = 18f
        val pPadV = 8f
        val pPillH = pricePaint.textSize + (pPadV * 2)
        val pTextWidth = pricePaint.measureText(state.price)
        val pPillW = pTextWidth + (pPadH * 2)
        val gapLeft = 8f

        val totalLeftH = (if (hasName) staticLayout!!.height else 0) + 
                         (if (hasName && hasPrice) gapLeft else 0f) + 
                         (if (hasPrice) pPillH else 0f)

        val leftStartY = cardTop + (cardHeight - totalLeftH) / 2f

        if (hasName) {
            canvas.save()
            canvas.translate(textLeft, leftStartY)
            staticLayout!!.draw(canvas)
            canvas.restore()
        }

        if (hasPrice) {
            val pPillY = leftStartY + (if (hasName) staticLayout!!.height + gapLeft else 0f)
            val pricePillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#007AFF")
            }
            val priceRect = RectF(textLeft, pPillY, textLeft + pPillW, pPillY + pPillH)
            canvas.drawRoundRect(priceRect, 12f, 12f, pricePillPaint)

            val priceBaseline = pPillY + pPadV + pricePaint.textSize * 0.82f
            canvas.drawText(state.price, textLeft + pPadH, priceBaseline, pricePaint)
        }
    }

    private fun drawClassicOverlay(canvas: Canvas, width: Int, height: Int, state: EditorState) {
        val isLandscape = width > height
        val baseDim = min(width, height)

        if (state.showStoreName && state.storeName.isNotEmpty()) {
            val hPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = baseDim * 0.036f
                color = Color.parseColor("#333333")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(state.storeName, width / 2f, height * 0.055f, hPaint)
        }

        val cardMargin = width * 0.035f
        val cardHeight = if (isLandscape) height * 0.82f else if (width == height) (height * 0.21f).coerceIn(190f, 250f) else (height * 0.175f).coerceIn(190f, 310f)
        val cardLeft = if (isLandscape) width * 0.60f else cardMargin
        val cardRight = width - cardMargin
        val cardBottom = height - cardMargin
        val cardTop = if (isLandscape) (height - cardHeight) / 2f else cardBottom - cardHeight

        val cardRect = RectF(cardLeft, cardTop, cardRight, cardBottom)
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFFFF")
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#E0E0E0")
        }
        canvas.drawRoundRect(cardRect, 20f, 20f, cardPaint)
        canvas.drawRoundRect(cardRect, 20f, 20f, borderPaint)

        val hasQr = state.showLink && state.link.isNotEmpty()
        val hasPhone = state.showPhone && state.phone.isNotEmpty()

        var qrLeft = 0f
        var qrRight = 0f

        if (hasQr && hasPhone) {
            val qrBoxSize = (cardHeight * 0.58f).coerceAtMost(cardHeight - 48f)
            val phoneTextSize = (cardHeight * 0.095f).coerceIn(16f, 30f)
            val gap = 6f
            val totalRightH = qrBoxSize + gap + phoneTextSize
            val rightStartY = cardTop + (cardHeight - totalRightH) / 2f

            val qrTop = rightStartY
            val qrBottom = qrTop + qrBoxSize
            qrRight = cardRight - (cardHeight * 0.10f)
            qrLeft = qrRight - qrBoxSize

            var qrUrl = state.link
            if (!qrUrl.startsWith("http://") && !qrUrl.startsWith("https://")) {
                qrUrl = "https://$qrUrl"
            }
            val qr = QrCodeHelper.generateQrCode(qrUrl, (qrBoxSize * 0.94f).toInt())
            if (qr != null) {
                val qrBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5F5F5") }
                val qrBgRect = RectF(qrLeft, qrTop, qrLeft + qrBoxSize, qrBottom)
                canvas.drawRoundRect(qrBgRect, 14f, 14f, qrBgPaint)
                canvas.drawBitmap(qr, qrLeft + (qrBoxSize - qr.width) / 2f, qrTop + (qrBoxSize - qr.height) / 2f, null)
            }

            val phonePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E91E63")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = phoneTextSize
            }
            val phoneCenterX = qrLeft + (qrBoxSize / 2f)
            val phoneBaseline = qrBottom + gap + phoneTextSize * 0.82f
            canvas.drawText(state.phone, phoneCenterX, phoneBaseline, phonePaint)

        } else if (hasQr) {
            val qrBoxSize = cardHeight * 0.72f
            val qrTop = cardTop + (cardHeight - qrBoxSize) / 2f
            val qrBottom = qrTop + qrBoxSize
            qrRight = cardRight - (cardHeight * 0.10f)
            qrLeft = qrRight - qrBoxSize

            var qrUrl = state.link
            if (!qrUrl.startsWith("http://") && !qrUrl.startsWith("https://")) {
                qrUrl = "https://$qrUrl"
            }
            val qr = QrCodeHelper.generateQrCode(qrUrl, (qrBoxSize * 0.94f).toInt())
            if (qr != null) {
                val qrBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5F5F5") }
                val qrBgRect = RectF(qrLeft, qrTop, qrLeft + qrBoxSize, qrBottom)
                canvas.drawRoundRect(qrBgRect, 14f, 14f, qrBgPaint)
                canvas.drawBitmap(qr, qrLeft + (qrBoxSize - qr.width) / 2f, qrTop + (qrBoxSize - qr.height) / 2f, null)
            }
        } else if (hasPhone) {
            val phoneTextSize = (cardHeight * 0.12f).coerceIn(20f, 36f)
            val phonePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E91E63")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
                textSize = phoneTextSize
            }
            val phoneRight = cardRight - (cardHeight * 0.15f)
            val phoneY = cardTop + (cardHeight / 2f) + phoneTextSize * 0.35f
            canvas.drawText(state.phone, phoneRight, phoneY, phonePaint)
        }

        val textLeft = cardLeft + 20f
        val textRightLimit = if (hasQr) qrLeft - 18f else if (hasPhone) cardRight - 180f else cardRight - 20f
        val textMaxWidth = (textRightLimit - textLeft).toInt().coerceAtLeast(80)

        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = (cardHeight * 0.15f).coerceIn(24f, 44f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var staticLayout: StaticLayout? = null
        if (state.showName && state.name.isNotEmpty()) {
            var currentSize = namePaint.textSize
            do {
                namePaint.textSize = currentSize
                staticLayout = StaticLayout(
                    state.name,
                    namePaint,
                    textMaxWidth,
                    Layout.Alignment.ALIGN_NORMAL,
                    1.08f,
                    0f,
                    false
                )
                currentSize -= 2f
            } while (staticLayout.height > (cardHeight * 0.46f) && currentSize > 16f)
        }

        val pricePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E91E63")
            textSize = (cardHeight * 0.13f).coerceIn(22f, 40f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val hasName = staticLayout != null
        val hasPrice = state.showPrice && state.price.isNotEmpty()
        val gapLeft = 8f
        val priceTextH = pricePaint.textSize

        val totalLeftH = (if (hasName) staticLayout!!.height else 0) + 
                         (if (hasName && hasPrice) gapLeft else 0f) + 
                         (if (hasPrice) priceTextH else 0f)

        val leftStartY = cardTop + (cardHeight - totalLeftH) / 2f

        if (hasName) {
            canvas.save()
            canvas.translate(textLeft, leftStartY)
            staticLayout!!.draw(canvas)
            canvas.restore()
        }

        if (hasPrice) {
            val pY = leftStartY + (if (hasName) staticLayout!!.height + gapLeft else 0f) + pricePaint.textSize * 0.85f
            canvas.drawText(state.price, textLeft, pY, pricePaint)
        }
    }

    private fun drawBrandWall(
        canvas: Canvas, 
        width: Int, 
        height: Int, 
        state: EditorState,
        productBounds: RectF
    ) {
        val logo = state.brandLogoBitmap ?: return
        val size = state.brandLogoSize.toInt().coerceAtLeast(20)
        val spacing = state.brandLogoSpacing.toInt().coerceAtLeast(5)
        val totalSize = size + spacing
        
        val scaledLogo = Bitmap.createScaledBitmap(logo, size, size, true)
        
        val offsetX = state.brandLogoOffsetX.toInt()
        val offsetY = state.brandLogoOffsetY.toInt()
        
        val cols = (width / totalSize) + 6
        val rows = (height / totalSize) + 6
        
        val baseAlpha = 255
        val haloRadius = state.brandLogoHaloRadius
        val haloIntensity = state.brandLogoHaloIntensity
        val skip = state.brandLogoSkip

        val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        for (row in -4..rows) {
            for (col in -4..cols) {
                // Skip density filter
                if (shouldSkipTile(row, col, skip)) continue

                var x = col * totalSize + offsetX
                val y = row * totalSize + offsetY
                
                if (state.isCheckerboard && row % 2 != 0) {
                    x += totalSize / 2
                }

                val tileCenterX = x + size / 2f
                val tileCenterY = y + size / 2f

                // Distance from tile center to tight product silhouette rectangle
                val dist = distanceToRect(tileCenterX, tileCenterY, productBounds)
                
                val alphaMultiplier = if (haloRadius > 0f && dist < haloRadius) {
                    val t = (dist / haloRadius).coerceIn(0f, 1f)
                    val smooth = t * t * (3f - 2f * t) // Smooth Hermite S-curve gradient
                    (1f - haloIntensity) + haloIntensity * smooth
                } else {
                    1f
                }

                val finalAlpha = (baseAlpha * alphaMultiplier).toInt().coerceIn(0, 255)
                if (finalAlpha > 5) {
                    tilePaint.alpha = finalAlpha
                    canvas.drawBitmap(scaledLogo, x.toFloat(), y.toFloat(), tilePaint)
                }
            }
        }
    }

    private fun drawST24LogoWall(
        canvas: Canvas, 
        width: Int, 
        height: Int, 
        state: EditorState,
        productBounds: RectF
    ) {
        try {
            val svg = SVG.getFromResource(applicationContext, R.raw.logo)
            val docWidth = svg.documentWidth
            val docHeight = svg.documentHeight
            if (docWidth > 0 && docHeight > 0) {
                val targetW = state.brandLogoSize.coerceAtLeast(30f)
                val scale = targetW / docWidth
                val targetH = docHeight * scale

                val logoBitmap = Bitmap.createBitmap(targetW.toInt(), targetH.toInt(), Bitmap.Config.ARGB_8888)
                val logoCanvas = Canvas(logoBitmap)
                logoCanvas.scale(scale, scale)
                svg.renderToCanvas(logoCanvas)

                val spacingX = state.brandLogoSpacing.toInt().coerceAtLeast(5)
                val spacingY = state.brandLogoSpacing.toInt().coerceAtLeast(5)
                val stepX = targetW.toInt() + spacingX
                val stepY = targetH.toInt() + spacingY

                val cols = (width / stepX) + 6
                val rows = (height / stepY) + 6

                val baseAlpha = 255
                val haloRadius = state.brandLogoHaloRadius
                val haloIntensity = state.brandLogoHaloIntensity
                val skip = state.brandLogoSkip
                val offsetX = state.brandLogoOffsetX.toInt()
                val offsetY = state.brandLogoOffsetY.toInt()

                val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

                for (row in -4..rows) {
                    for (col in -4..cols) {
                        if (shouldSkipTile(row, col, skip)) continue

                        var x = col * stepX + offsetX
                        val y = row * stepY + offsetY
                        if (state.isCheckerboard && row % 2 != 0) {
                            x += stepX / 2
                        }

                        val tileCenterX = x + targetW / 2f
                        val tileCenterY = y + targetH / 2f
                        val dist = distanceToRect(tileCenterX, tileCenterY, productBounds)

                        val alphaMultiplier = if (haloRadius > 0f && dist < haloRadius) {
                            val t = (dist / haloRadius).coerceIn(0f, 1f)
                            val smooth = t * t * (3f - 2f * t) // Smooth Hermite S-curve gradient
                            (1f - haloIntensity) + haloIntensity * smooth
                        } else {
                            1f
                        }

                        val finalAlpha = (baseAlpha * alphaMultiplier).toInt().coerceIn(0, 255)
                        if (finalAlpha > 5) {
                            tilePaint.alpha = finalAlpha
                            canvas.drawBitmap(logoBitmap, x.toFloat(), y.toFloat(), tilePaint)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun shouldSkipTile(row: Int, col: Int, skip: Int): Boolean {
        return when (skip) {
            2 -> (row + col) % 2 != 0 // Half density (через 1)
            3 -> (row * 2 + col) % 3 != 0 // 1/3 density (через 2)
            4 -> (row % 2 != 0 || col % 2 != 0) // 1/4 density (редкая сетка)
            else -> false
        }
    }

    private fun distanceToRect(px: Float, py: Float, rect: RectF): Float {
        val dx = max(0f, max(rect.left - px, px - rect.right))
        val dy = max(0f, max(rect.top - py, py - rect.bottom))
        return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    fun saveProduct(onComplete: () -> Unit) {
        val currentState = _state.value
        val bitmap = currentState.finalBitmap ?: return
        
        _state.update { it.copy(isLoading = true) }
        
        viewModelScope.launch(Dispatchers.IO) {
            val maxDim = max(bitmap.width, bitmap.height)
            val targetDim = 2400
            
            val finalToSave = if (maxDim != targetDim) {
                val scale = targetDim.toFloat() / maxDim
                val newW = (bitmap.width * scale).toInt()
                val newH = (bitmap.height * scale).toInt()
                LanczosHelper.resize(bitmap, newW, newH)
            } else {
                bitmap
            }
            
            val file = File(applicationContext.filesDir, "product_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                finalToSave.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            val product = ProductEntity(
                name = currentState.name,
                price = currentState.price,
                link = currentState.link,
                imagePath = file.absolutePath
            )
            repository.insert(product)
            
            withContext(Dispatchers.Main) {
                _state.update { it.copy(isLoading = false) }
                onComplete()
            }
        }
    }
}
