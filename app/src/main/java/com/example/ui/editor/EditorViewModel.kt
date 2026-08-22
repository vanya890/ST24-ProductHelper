package com.example.ui.editor

import android.content.ContentValues
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
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.widget.Toast
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
    val isLocked: Boolean = false,
    
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

    private fun savePresetToPrefs() {
        val state = _state.value
        val prefs = applicationContext.getSharedPreferences("product_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("is_locked", state.isLocked)
            putString("preset_format", state.format.name)
            putString("preset_template_style", state.templateStyle.name)
            putString("preset_background_type", state.backgroundType.name)
            putFloat("preset_logo_size", state.brandLogoSize)
            putFloat("preset_logo_spacing", state.brandLogoSpacing)
            putFloat("preset_logo_offset_x", state.brandLogoOffsetX)
            putFloat("preset_logo_offset_y", state.brandLogoOffsetY)
            putInt("preset_logo_skip", state.brandLogoSkip)
            putFloat("preset_logo_halo_radius", state.brandLogoHaloRadius)
            putFloat("preset_logo_halo_intensity", state.brandLogoHaloIntensity)
            putBoolean("preset_is_checkerboard", state.isCheckerboard)
            putString("last_store_name", state.storeName)
            putBoolean("preset_show_store_name", state.showStoreName)
            putString("last_phone", state.phone)
            putBoolean("preset_show_phone", state.showPhone)
            putString("last_link", state.link)
            putBoolean("preset_show_link", state.showLink)
            putBoolean("preset_show_name", state.showName)
            putBoolean("preset_show_price", state.showPrice)
            apply()
        }
    }

    fun toggleLock(locked: Boolean? = null) {
        val newLock = locked ?: !_state.value.isLocked
        _state.update { it.copy(isLocked = newLock) }
        savePresetToPrefs()
        val msg = if (newLock) {
            "Стиль заблокирован! Пользователям доступны только Название и Цена."
        } else {
            "Настройки стиля разблокированы."
        }
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    fun init(imageUri: String) {
        val prefs = applicationContext.getSharedPreferences("product_prefs", Context.MODE_PRIVATE)
        val savedIsLocked = prefs.getBoolean("is_locked", false)
        val savedFormatStr = prefs.getString("preset_format", AspectRatioFormat.PORTRAIT_9_16.name)
        val savedFormat = try { AspectRatioFormat.valueOf(savedFormatStr!!) } catch (e: Exception) { AspectRatioFormat.PORTRAIT_9_16 }
        
        val savedStyleStr = prefs.getString("preset_template_style", TemplateStyle.ST24_DARK.name)
        val savedStyle = try { TemplateStyle.valueOf(savedStyleStr!!) } catch (e: Exception) { TemplateStyle.ST24_DARK }

        val savedBgStr = prefs.getString("preset_background_type", BackgroundType.WHITE.name)
        val savedBgType = try { BackgroundType.valueOf(savedBgStr!!) } catch (e: Exception) { BackgroundType.WHITE }

        var savedLogoBitmap: Bitmap? = null
        val logoFile = File(applicationContext.filesDir, "saved_brand_logo.png")
        if (logoFile.exists()) {
            try {
                savedLogoBitmap = BitmapFactory.decodeFile(logoFile.absolutePath)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        _state.update { 
            it.copy(
                originalImageUri = imageUri, 
                isLoading = true,
                isLocked = savedIsLocked,
                format = savedFormat,
                templateStyle = savedStyle,
                backgroundType = savedBgType,
                brandLogoBitmap = savedLogoBitmap,
                brandLogoSize = prefs.getFloat("preset_logo_size", 120f),
                brandLogoSpacing = prefs.getFloat("preset_logo_spacing", 60f),
                brandLogoOffsetX = prefs.getFloat("preset_logo_offset_x", 0f),
                brandLogoOffsetY = prefs.getFloat("preset_logo_offset_y", 0f),
                brandLogoSkip = prefs.getInt("preset_logo_skip", 1),
                brandLogoHaloRadius = prefs.getFloat("preset_logo_halo_radius", 180f),
                brandLogoHaloIntensity = prefs.getFloat("preset_logo_halo_intensity", 1.0f),
                isCheckerboard = prefs.getBoolean("preset_is_checkerboard", true),
                name = prefs.getString("last_name", "Название товара") ?: "Название товара",
                price = prefs.getString("last_price", "999 руб.") ?: "999 руб.",
                link = prefs.getString("last_link", "https://stroy-materiali-24.ru") ?: "https://stroy-materiali-24.ru",
                showLink = prefs.getBoolean("preset_show_link", true),
                storeName = prefs.getString("last_store_name", "STROY-MATERIALI-24") ?: "STROY-MATERIALI-24",
                showStoreName = prefs.getBoolean("preset_show_store_name", true),
                phone = prefs.getString("last_phone", "+7 (926) 163-75-07") ?: "+7 (926) 163-75-07",
                showPhone = prefs.getBoolean("preset_show_phone", true),
                showName = prefs.getBoolean("preset_show_name", true),
                showPrice = prefs.getBoolean("preset_show_price", true)
            ) 
        }
        viewModelScope.launch {
            val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadBitmap(imageUri) }
            if (bitmap != null) {
                val rawForeground = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { 
                    SegmentationHelper.segmentProduct(bitmap, applicationContext) 
                } ?: bitmap
                val foreground = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    cropToTightBoundingBox(rawForeground)
                }
                _state.update { 
                    it.copy(
                        foregroundBitmap = foreground,
                        isLoading = false
                    ) 
                }
                resetProductPosition()
            } else {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun cropToTightBoundingBox(bitmap: Bitmap): Bitmap {
        val bbox = SegmentationHelper.findSubjectBoundingBox(bitmap, alphaThreshold = 10)
        if (bbox.width() > 10 && bbox.height() > 10 && (bbox.width() < bitmap.width || bbox.height() < bitmap.height)) {
            val pad = 4
            val left = max(0, bbox.left - pad)
            val top = max(0, bbox.top - pad)
            val right = min(bitmap.width, bbox.right + pad)
            val bottom = min(bitmap.height, bbox.bottom + pad)
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0) {
                return Bitmap.createBitmap(bitmap, left, top, w, h)
            }
        }
        return bitmap
    }

    private suspend fun loadBitmap(uriString: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val uri = Uri.parse(uriString)
            applicationContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPremultiplied = true
                    inDither = true
                    inSampleSize = 1
                }
                BitmapFactory.decodeStream(inputStream, null, options)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun updateStoreName(storeName: String) {
        _state.update { it.copy(storeName = storeName) }
        savePresetToPrefs()
        updateFinalBitmap()
    }
    
    fun toggleStoreName(show: Boolean) {
        _state.update { it.copy(showStoreName = show) }
        savePresetToPrefs()
        updateFinalBitmap()
    }

    fun updatePhone(phone: String) {
        _state.update { it.copy(phone = phone) }
        savePresetToPrefs()
        updateFinalBitmap()
    }
    
    fun togglePhone(show: Boolean) {
        _state.update { it.copy(showPhone = show) }
        savePresetToPrefs()
        updateFinalBitmap()
    }

    fun updateTemplateStyle(style: TemplateStyle) {
        _state.update { it.copy(templateStyle = style) }
        savePresetToPrefs()
        updateFinalBitmap()
    }

    fun updateName(name: String) {
        _state.update { it.copy(name = name) }
        savePresetToPrefs()
        updateFinalBitmap()
    }
    
    fun toggleName(show: Boolean) {
        _state.update { it.copy(showName = show) }
        savePresetToPrefs()
        updateFinalBitmap()
    }

    fun updatePrice(price: String) {
        _state.update { it.copy(price = price) }
        savePresetToPrefs()
        updateFinalBitmap()
    }
    
    fun togglePrice(show: Boolean) {
        _state.update { it.copy(showPrice = show) }
        savePresetToPrefs()
        updateFinalBitmap()
    }

    fun updateLink(link: String) {
        _state.update { it.copy(link = link) }
        savePresetToPrefs()
        updateFinalBitmap()
    }
    
    fun toggleLink(show: Boolean) {
        _state.update { it.copy(showLink = show) }
        savePresetToPrefs()
        updateFinalBitmap()
    }
    
    fun setFormat(format: AspectRatioFormat) {
        _state.update { it.copy(format = format) }
        savePresetToPrefs()
        resetProductPosition()
    }

    fun setBackgroundType(type: BackgroundType) {
        _state.update { it.copy(backgroundType = type) }
        savePresetToPrefs()
        updateFinalBitmap()
    }
    
    fun setBrandLogo(bitmap: Bitmap?) {
        if (bitmap != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val logoFile = File(applicationContext.filesDir, "saved_brand_logo.png")
                    FileOutputStream(logoFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        _state.update { it.copy(brandLogoBitmap = bitmap, backgroundType = BackgroundType.BRAND_WALL) }
        savePresetToPrefs()
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
        savePresetToPrefs()
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
     * Centers and scales the cut-out product to 95% of the template width.
     */
    fun resetProductPosition() {
        _state.update { 
            it.copy(
                productOffsetX = 0f,
                productOffsetY = 0f,
                productScale = 1f
            ) 
        }
        updateFinalBitmap()
    }

    private fun getCanvasDimensions(format: AspectRatioFormat, ogWidth: Int, ogHeight: Int, targetMaxDim: Int = 1920): Pair<Int, Int> {
        return when (format) {
            AspectRatioFormat.SQUARE_1_1 -> Pair(targetMaxDim, targetMaxDim)
            AspectRatioFormat.PORTRAIT_9_16 -> Pair((targetMaxDim * 9f / 16f).toInt(), targetMaxDim)
            AspectRatioFormat.LANDSCAPE_16_9 -> Pair(targetMaxDim, (targetMaxDim * 9f / 16f).toInt())
            AspectRatioFormat.ORIGINAL -> {
                val maxDim = targetMaxDim.toFloat()
                if (ogWidth >= ogHeight && ogWidth > 0) {
                    Pair(maxDim.toInt(), (maxDim * ogHeight / ogWidth).toInt())
                } else if (ogHeight > ogWidth && ogHeight > 0) {
                    Pair((maxDim * ogWidth / ogHeight).toInt(), maxDim.toInt())
                } else {
                    Pair(targetMaxDim, targetMaxDim)
                }
            }
        }
    }

    private fun renderCardCanvas(currentState: EditorState, targetMaxDim: Int = 1920): Bitmap? {
        val foreground = currentState.foregroundBitmap ?: return null

        val (width, height) = getCanvasDimensions(currentState.format, foreground.width, foreground.height, targetMaxDim)
        val isLandscape = width > height
        val productRatio = foreground.width.toFloat() / foreground.height.toFloat()

        val availableWidth: Float
        val availableHeight: Float
        val centerX: Float
        val centerY: Float

        if (isLandscape) {
            val studioW = width * 0.58f
            availableWidth = studioW * 0.95f
            availableHeight = height * 0.88f
            centerX = width * 0.30f
            centerY = height * 0.50f
        } else {
            val headerOffset = if (currentState.showStoreName && currentState.storeName.isNotEmpty()) height * 0.12f else height * 0.03f
            val cardMargin = width * 0.035f
            val cardHeight = if (width == height) height * 0.21f else height * 0.175f
            val footerOffset = cardHeight + cardMargin + (height * 0.02f)

            // 95% of template width
            availableWidth = width * 0.95f
            availableHeight = (height - headerOffset - footerOffset).coerceAtLeast(height * 0.4f) * 0.96f
            centerX = width / 2f
            centerY = headerOffset + (height - headerOffset - footerOffset) / 2f
        }

        var productWidth = availableWidth
        var productHeight = productWidth / productRatio

        if (productHeight > availableHeight) {
            productHeight = availableHeight
            productWidth = productHeight * productRatio
        }

        val scaledWidth = productWidth * currentState.productScale
        val scaledHeight = productHeight * currentState.productScale

        val scaleFactor = max(width, height) / 1920f
        val panXScaled = currentState.productOffsetX * scaleFactor
        val panYScaled = currentState.productOffsetY * scaleFactor

        val destLeft = centerX - (scaledWidth / 2f) + panXScaled
        val destTop = centerY - (scaledHeight / 2f) + panYScaled

        val productBounds = RectF(
            destLeft,
            destTop,
            destLeft + scaledWidth,
            destTop + scaledHeight
        )

        val tightProductBounds = productBounds

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Pure clean background
        canvas.drawColor(Color.WHITE)

        // Draw Brand Wall or ST24 Logo Wall
        if (currentState.backgroundType == BackgroundType.BRAND_WALL && currentState.brandLogoBitmap != null) {
            drawBrandWall(canvas, width, height, currentState, tightProductBounds)
        } else if (currentState.backgroundType == BackgroundType.ST24_LOGO) {
            drawST24LogoWall(canvas, width, height, currentState, tightProductBounds)
        }

        val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            isFilterBitmap = true
            isDither = true
        }
        canvas.drawBitmap(foreground, null, productBounds, filterPaint)

        when (currentState.templateStyle) {
            TemplateStyle.ST24_DARK -> drawST24DarkOverlay(canvas, width, height, currentState)
            TemplateStyle.MODERN_LIGHT -> drawModernLightOverlay(canvas, width, height, currentState)
            TemplateStyle.CLASSIC -> drawClassicOverlay(canvas, width, height, currentState)
        }

        return bitmap
    }

    fun updateFinalBitmap() {
        val currentState = _state.value
        if (currentState.foregroundBitmap == null) return

        viewModelScope.launch(Dispatchers.Default) {
            val bitmap = renderCardCanvas(currentState, targetMaxDim = 1920)
            if (bitmap != null) {
                _state.update { it.copy(finalBitmap = bitmap) }
            }
        }
    }

    private fun drawST24DarkOverlay(canvas: Canvas, width: Int, height: Int, state: EditorState) {
        val isLandscape = width > height
        val baseDim = min(width, height)
        val scale = max(width, height) / 1920f

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
                strokeWidth = 2f * scale
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
            cardHeight = if (width == height) (height * 0.21f).coerceIn(190f * scale, 250f * scale) else (height * 0.175f).coerceIn(190f * scale, 310f * scale)
            cardLeft = cardMargin
            cardRight = width - cardMargin
            cardBottom = height - cardMargin
            cardTop = cardBottom - cardHeight
        }

        val cardRadius = 24f * scale
        val cardRect = RectF(cardLeft, cardTop, cardRight, cardBottom)

        // Drop Shadow & Card Surface
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#55000000")
            setShadowLayer(20f * scale, 0f, 6f * scale, Color.parseColor("#55000000"))
        }
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, shadowPaint)

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1F1F21")
        }
        canvas.drawRoundRect(cardRect, cardRadius, cardRadius, cardPaint)

        // Orange Accent Left Bar
        val accentWidth = 12f * scale
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
            val qrBoxSize = (cardHeight * 0.58f).coerceAtMost(cardHeight - 40f * scale)
            val gap = 6f * scale
            
            // Calculate phone size dynamically so it NEVER overflows card boundaries
            val phonePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF9800")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val maxPhoneW = qrBoxSize * 1.25f
            var phoneTextSize = (cardHeight * 0.095f).coerceIn(14f * scale, 26f * scale)
            phonePaint.textSize = phoneTextSize
            while (phonePaint.measureText(state.phone) > maxPhoneW && phoneTextSize > 10f * scale) {
                phoneTextSize -= 1f * scale
                phonePaint.textSize = phoneTextSize
            }

            val totalRightH = qrBoxSize + gap + phoneTextSize
            val rightStartY = cardTop + (cardHeight - totalRightH) / 2f

            val qrTop = rightStartY
            val qrBottom = qrTop + qrBoxSize
            qrRight = cardRight - (cardHeight * 0.08f)
            qrLeft = qrRight - qrBoxSize

            var qrUrl = state.link
            if (!qrUrl.startsWith("http://") && !qrUrl.startsWith("https://")) {
                qrUrl = "https://$qrUrl"
            }
            // Generate QR code filling 95% of white box
            val qrInnerSize = (qrBoxSize - 8f).toInt().coerceAtLeast(24)
            val qr = QrCodeHelper.generateQrCode(qrUrl, qrInnerSize)
            if (qr != null) {
                val qrBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                val qrBgRect = RectF(qrLeft, qrTop, qrLeft + qrBoxSize, qrBottom)
                canvas.drawRoundRect(qrBgRect, 14f, 14f, qrBgPaint)
                val qrX = qrLeft + (qrBoxSize - qr.width) / 2f
                val qrY = qrTop + (qrBoxSize - qr.height) / 2f
                canvas.drawBitmap(qr, qrX, qrY, null)
            }

            val phoneCenterX = qrLeft + (qrBoxSize / 2f)
            val phoneBaseline = qrBottom + gap + phoneTextSize * 0.82f
            canvas.drawText(state.phone, phoneCenterX, phoneBaseline, phonePaint)

        } else if (hasQr) {
            val qrBoxSize = cardHeight * 0.70f
            val qrTop = cardTop + (cardHeight - qrBoxSize) / 2f
            val qrBottom = qrTop + qrBoxSize
            qrRight = cardRight - (cardHeight * 0.08f)
            qrLeft = qrRight - qrBoxSize

            var qrUrl = state.link
            if (!qrUrl.startsWith("http://") && !qrUrl.startsWith("https://")) {
                qrUrl = "https://$qrUrl"
            }
            val qrInnerSize = (qrBoxSize - 8f).toInt().coerceAtLeast(24)
            val qr = QrCodeHelper.generateQrCode(qrUrl, qrInnerSize)
            if (qr != null) {
                val qrBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                val qrBgRect = RectF(qrLeft, qrTop, qrLeft + qrBoxSize, qrBottom)
                canvas.drawRoundRect(qrBgRect, 14f, 14f, qrBgPaint)
                val qrX = qrLeft + (qrBoxSize - qr.width) / 2f
                val qrY = qrTop + (qrBoxSize - qr.height) / 2f
                canvas.drawBitmap(qr, qrX, qrY, null)
            }
        } else if (hasPhone) {
            val maxPhoneW = (cardRight - cardLeft) * 0.45f
            val phonePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF9800")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            var phoneTextSize = (cardHeight * 0.12f).coerceIn(16f, 32f)
            phonePaint.textSize = phoneTextSize
            while (phonePaint.measureText(state.phone) > maxPhoneW && phoneTextSize > 10f) {
                phoneTextSize -= 1f
                phonePaint.textSize = phoneTextSize
            }
            val phoneRight = cardRight - (cardHeight * 0.08f)
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
                staticLayout = android.text.StaticLayout.Builder.obtain(state.name, 0, state.name.length, namePaint, textMaxWidth).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.08f).setIncludePad(false).build()
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
        val scale = max(width, height) / 1920f

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
        val cardHeight = if (isLandscape) height * 0.82f else if (width == height) (height * 0.21f).coerceIn(190f * scale, 250f * scale) else (height * 0.175f).coerceIn(190f * scale, 310f * scale)
        val cardLeft = if (isLandscape) width * 0.60f else cardMargin
        val cardRight = width - cardMargin
        val cardBottom = height - cardMargin
        val cardTop = if (isLandscape) (height - cardHeight) / 2f else cardBottom - cardHeight

        val cardRect = RectF(cardLeft, cardTop, cardRight, cardBottom)

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#14000000")
            setShadowLayer(20f * scale, 0f, 6f * scale, Color.parseColor("#14000000"))
        }
        canvas.drawRoundRect(cardRect, 24f * scale, 24f * scale, shadowPaint)

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8F9FA")
        }
        canvas.drawRoundRect(cardRect, 24f * scale, 24f * scale, cardPaint)

        val accentWidth = 10f * scale
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#007AFF")
        }
        val accentRect = RectF(cardLeft, cardTop, cardLeft + accentWidth, cardBottom)
        canvas.drawRoundRect(accentRect, 24f * scale, 24f * scale, accentPaint)
        canvas.drawRect(cardLeft + (accentWidth / 2f), cardTop, cardLeft + accentWidth, cardBottom, accentPaint)

        val hasQr = state.showLink && state.link.isNotEmpty()
        val hasPhone = state.showPhone && state.phone.isNotEmpty()

        var qrLeft = 0f
        var qrRight = 0f

        if (hasQr && hasPhone) {
            val qrBoxSize = (cardHeight * 0.58f).coerceAtMost(cardHeight - 40f * scale)
            val gap = 6f * scale

            val phonePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#007AFF")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val maxPhoneW = qrBoxSize * 1.25f
            var phoneTextSize = (cardHeight * 0.095f).coerceIn(14f * scale, 26f * scale)
            phonePaint.textSize = phoneTextSize
            while (phonePaint.measureText(state.phone) > maxPhoneW && phoneTextSize > 10f * scale) {
                phoneTextSize -= 1f * scale
                phonePaint.textSize = phoneTextSize
            }

            val totalRightH = qrBoxSize + gap + phoneTextSize
            val rightStartY = cardTop + (cardHeight - totalRightH) / 2f

            val qrTop = rightStartY
            val qrBottom = qrTop + qrBoxSize
            qrRight = cardRight - (cardHeight * 0.08f)
            qrLeft = qrRight - qrBoxSize

            var qrUrl = state.link
            if (!qrUrl.startsWith("http://") && !qrUrl.startsWith("https://")) {
                qrUrl = "https://$qrUrl"
            }
            val qrInnerSize = (qrBoxSize - 8f).toInt().coerceAtLeast(24)
            val qr = QrCodeHelper.generateQrCode(qrUrl, qrInnerSize)
            if (qr != null) {
                val qrBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                val qrBgRect = RectF(qrLeft, qrTop, qrLeft + qrBoxSize, qrBottom)
                canvas.drawRoundRect(qrBgRect, 14f, 14f, qrBgPaint)
                canvas.drawBitmap(qr, qrLeft + (qrBoxSize - qr.width) / 2f, qrTop + (qrBoxSize - qr.height) / 2f, null)
            }

            val phoneCenterX = qrLeft + (qrBoxSize / 2f)
            val phoneBaseline = qrBottom + gap + phoneTextSize * 0.82f
            canvas.drawText(state.phone, phoneCenterX, phoneBaseline, phonePaint)

        } else if (hasQr) {
            val qrBoxSize = cardHeight * 0.70f
            val qrTop = cardTop + (cardHeight - qrBoxSize) / 2f
            val qrBottom = qrTop + qrBoxSize
            qrRight = cardRight - (cardHeight * 0.08f)
            qrLeft = qrRight - qrBoxSize

            var qrUrl = state.link
            if (!qrUrl.startsWith("http://") && !qrUrl.startsWith("https://")) {
                qrUrl = "https://$qrUrl"
            }
            val qrInnerSize = (qrBoxSize - 8f).toInt().coerceAtLeast(24)
            val qr = QrCodeHelper.generateQrCode(qrUrl, qrInnerSize)
            if (qr != null) {
                val qrBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                val qrBgRect = RectF(qrLeft, qrTop, qrLeft + qrBoxSize, qrBottom)
                canvas.drawRoundRect(qrBgRect, 14f, 14f, qrBgPaint)
                canvas.drawBitmap(qr, qrLeft + (qrBoxSize - qr.width) / 2f, qrTop + (qrBoxSize - qr.height) / 2f, null)
            }
        } else if (hasPhone) {
            val maxPhoneW = (cardRight - cardLeft) * 0.45f
            val phonePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#007AFF")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            var phoneTextSize = (cardHeight * 0.12f).coerceIn(16f, 32f)
            phonePaint.textSize = phoneTextSize
            while (phonePaint.measureText(state.phone) > maxPhoneW && phoneTextSize > 10f) {
                phoneTextSize -= 1f
                phonePaint.textSize = phoneTextSize
            }
            val phoneRight = cardRight - (cardHeight * 0.08f)
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
                staticLayout = android.text.StaticLayout.Builder.obtain(state.name, 0, state.name.length, namePaint, textMaxWidth).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.08f).setIncludePad(false).build()
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
        val scale = max(width, height) / 1920f

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
        val cardHeight = if (isLandscape) height * 0.82f else if (width == height) (height * 0.21f).coerceIn(190f * scale, 250f * scale) else (height * 0.175f).coerceIn(190f * scale, 310f * scale)
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
            strokeWidth = 2f * scale
            color = Color.parseColor("#E0E0E0")
        }
        canvas.drawRoundRect(cardRect, 20f * scale, 20f * scale, cardPaint)
        canvas.drawRoundRect(cardRect, 20f * scale, 20f * scale, borderPaint)

        val hasQr = state.showLink && state.link.isNotEmpty()
        val hasPhone = state.showPhone && state.phone.isNotEmpty()

        var qrLeft = 0f
        var qrRight = 0f

        if (hasQr && hasPhone) {
            val qrBoxSize = (cardHeight * 0.58f).coerceAtMost(cardHeight - 40f)
            val gap = 6f

            val phonePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E91E63")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val maxPhoneW = qrBoxSize * 1.25f
            var phoneTextSize = (cardHeight * 0.095f).coerceIn(14f, 26f)
            phonePaint.textSize = phoneTextSize
            while (phonePaint.measureText(state.phone) > maxPhoneW && phoneTextSize > 10f) {
                phoneTextSize -= 1f
                phonePaint.textSize = phoneTextSize
            }

            val totalRightH = qrBoxSize + gap + phoneTextSize
            val rightStartY = cardTop + (cardHeight - totalRightH) / 2f

            val qrTop = rightStartY
            val qrBottom = qrTop + qrBoxSize
            qrRight = cardRight - (cardHeight * 0.08f)
            qrLeft = qrRight - qrBoxSize

            var qrUrl = state.link
            if (!qrUrl.startsWith("http://") && !qrUrl.startsWith("https://")) {
                qrUrl = "https://$qrUrl"
            }
            val qrInnerSize = (qrBoxSize - 8f).toInt().coerceAtLeast(24)
            val qr = QrCodeHelper.generateQrCode(qrUrl, qrInnerSize)
            if (qr != null) {
                val qrBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5F5F5") }
                val qrBgRect = RectF(qrLeft, qrTop, qrLeft + qrBoxSize, qrBottom)
                canvas.drawRoundRect(qrBgRect, 14f, 14f, qrBgPaint)
                canvas.drawBitmap(qr, qrLeft + (qrBoxSize - qr.width) / 2f, qrTop + (qrBoxSize - qr.height) / 2f, null)
            }

            val phoneCenterX = qrLeft + (qrBoxSize / 2f)
            val phoneBaseline = qrBottom + gap + phoneTextSize * 0.82f
            canvas.drawText(state.phone, phoneCenterX, phoneBaseline, phonePaint)

        } else if (hasQr) {
            val qrBoxSize = cardHeight * 0.70f
            val qrTop = cardTop + (cardHeight - qrBoxSize) / 2f
            val qrBottom = qrTop + qrBoxSize
            qrRight = cardRight - (cardHeight * 0.08f)
            qrLeft = qrRight - qrBoxSize

            var qrUrl = state.link
            if (!qrUrl.startsWith("http://") && !qrUrl.startsWith("https://")) {
                qrUrl = "https://$qrUrl"
            }
            val qrInnerSize = (qrBoxSize - 8f).toInt().coerceAtLeast(24)
            val qr = QrCodeHelper.generateQrCode(qrUrl, qrInnerSize)
            if (qr != null) {
                val qrBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5F5F5") }
                val qrBgRect = RectF(qrLeft, qrTop, qrLeft + qrBoxSize, qrBottom)
                canvas.drawRoundRect(qrBgRect, 14f, 14f, qrBgPaint)
                canvas.drawBitmap(qr, qrLeft + (qrBoxSize - qr.width) / 2f, qrTop + (qrBoxSize - qr.height) / 2f, null)
            }
        } else if (hasPhone) {
            val maxPhoneW = (cardRight - cardLeft) * 0.45f
            val phonePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E91E63")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            var phoneTextSize = (cardHeight * 0.12f).coerceIn(16f, 32f)
            phonePaint.textSize = phoneTextSize
            while (phonePaint.measureText(state.phone) > maxPhoneW && phoneTextSize > 10f) {
                phoneTextSize -= 1f
                phonePaint.textSize = phoneTextSize
            }
            val phoneRight = cardRight - (cardHeight * 0.08f)
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
                staticLayout = android.text.StaticLayout.Builder.obtain(state.name, 0, state.name.length, namePaint, textMaxWidth).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.08f).setIncludePad(false).build()
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
        _state.update { it.copy(isLoading = true) }
        
        viewModelScope.launch(Dispatchers.IO) {
            // Render high-resolution product card canvas upscaled to exactly 3000px max dimension
            val targetMaxDim = 3000

            val highResBitmap = renderCardCanvas(currentState, targetMaxDim = targetMaxDim)
                ?: currentState.finalBitmap
                
            if (highResBitmap == null) {
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(isLoading = false) }
                }
                return@launch
            }
            
            // 1. Save to app internal storage (100% Lossless PNG)
            val file = File(applicationContext.filesDir, "product_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                highResBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            // 2. Save to Android system gallery (Pictures/ProductCards)
            saveToDeviceGallery(applicationContext, highResBitmap, "ProductCard_${System.currentTimeMillis()}")
            
            val product = ProductEntity(
                name = currentState.name,
                price = currentState.price,
                link = currentState.link,
                imagePath = file.absolutePath
            )
            repository.insert(product)
            
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    applicationContext,
                    "Карточка сохранена в галерею (3000px)",
                    Toast.LENGTH_LONG
                ).show()
                _state.update { it.copy(isLoading = false) }
                onComplete()
            }
        }
    }

    private fun saveToDeviceGallery(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        val resolver = context.contentResolver
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ProductCards")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let { u ->
                    resolver.openOutputStream(u)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(u, contentValues, null, null)
                }
                uri
            } else {
                val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val cardFolder = File(imagesDir, "ProductCards").apply { if (!exists()) mkdirs() }
                val imageFile = File(cardFolder, "$fileName.png")
                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, imageFile.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                }
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
