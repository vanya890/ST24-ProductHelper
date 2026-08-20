package com.example.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.utils.ImageEnhancer
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    onImageCaptured: (String) -> Unit,
    onNavigateToGallery: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)
    val coroutineScope = rememberCoroutineScope()

    var isTorchOn by remember { mutableStateOf(false) }
    var isHdrEnabled by remember { mutableStateOf(true) }
    var showGrid by remember { mutableStateOf(true) }
    var isCapturing by remember { mutableStateOf(false) }
    
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    
    // Tap-to-focus animation state
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusAnimTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    // CRITICAL: Ensure torch is automatically shut off when leaving CameraScreen
    DisposableEffect(Unit) {
        onDispose {
            try {
                cameraControl?.enableTorch(false)
            } catch (e: Exception) {
                Log.e("CameraScreen", "Error disabling torch on dispose", e)
            }
        }
    }

    // Reactively update torch state on camera
    LaunchedEffect(isTorchOn, cameraControl) {
        try {
            cameraControl?.enableTorch(isTorchOn)
        } catch (e: Exception) {
            Log.e("CameraScreen", "Failed to toggle torch", e)
        }
    }

    if (cameraPermissionState.status.isGranted) {
        val executor = remember { ContextCompat.getMainExecutor(context) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Camera Preview View
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val extensionsManagerFuture = ExtensionsManager.getInstanceAsync(ctx, cameraProvider)
                            
                            extensionsManagerFuture.addListener({
                                try {
                                    val extensionsManager = extensionsManagerFuture.get()
                                    
                                    val resolutionSelector = ResolutionSelector.Builder()
                                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(1080, 1920),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                            )
                                        )
                                        .build()

                                    val preview = Preview.Builder()
                                        .setResolutionSelector(resolutionSelector)
                                        .build()
                                        .also {
                                            it.setSurfaceProvider(previewView.surfaceProvider)
                                        }

                                    // ImageCapture with safe memory size (1920x1080/4K target rather than raw 108MP)
                                    val capture = ImageCapture.Builder()
                                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                        .setJpegQuality(95)
                                        .build()
                                    imageCapture = capture

                                    var baseCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                                    
                                    val cameraSelector = if (extensionsManager.isExtensionAvailable(baseCameraSelector, ExtensionMode.HDR)) {
                                        extensionsManager.getExtensionEnabledCameraSelector(baseCameraSelector, ExtensionMode.HDR)
                                    } else if (extensionsManager.isExtensionAvailable(baseCameraSelector, ExtensionMode.NIGHT)) {
                                        extensionsManager.getExtensionEnabledCameraSelector(baseCameraSelector, ExtensionMode.NIGHT)
                                    } else if (extensionsManager.isExtensionAvailable(baseCameraSelector, ExtensionMode.AUTO)) {
                                        extensionsManager.getExtensionEnabledCameraSelector(baseCameraSelector, ExtensionMode.AUTO)
                                    } else {
                                        baseCameraSelector
                                    }

                                    cameraProvider.unbindAll()
                                    val camera = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        cameraSelector,
                                        preview,
                                        capture
                                    )
                                    cameraControl = camera.cameraControl
                                } catch (exc: Exception) {
                                    Log.e("CameraScreen", "Extensions or binding failed, trying basic binding", exc)
                                    try {
                                        cameraProvider.unbindAll()
                                        val basicCapture = ImageCapture.Builder().build()
                                        imageCapture = basicCapture
                                        val basicPreview = Preview.Builder().build().also {
                                            it.setSurfaceProvider(previewView.surfaceProvider)
                                        }
                                        val camera = cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            basicPreview,
                                            basicCapture
                                        )
                                        cameraControl = camera.cameraControl
                                    } catch (e2: Exception) {
                                        Log.e("CameraScreen", "Basic camera bind fallback failed", e2)
                                    }
                                }
                            }, executor)
                        } catch (e: Exception) {
                            Log.e("CameraScreen", "Camera provider error", e)
                        }
                    }, executor)

                    // Tap to focus listener
                    previewView.setOnTouchListener { v, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            val factory = previewView.meteringPointFactory
                            val point = factory.createPoint(event.x, event.y)
                            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            cameraControl?.startFocusAndMetering(action)
                            focusPoint = Offset(event.x, event.y)
                            focusAnimTrigger++
                            v.performClick()
                            true
                        } else {
                            true
                        }
                    }

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Grid Overlay for Product Centering
            if (showGrid) {
                GridOverlay(modifier = Modifier.fillMaxSize())
            }

            // Focus Reticle Indicator
            focusPoint?.let { pos ->
                key(focusAnimTrigger) {
                    FocusReticle(position = pos)
                }
            }

            // Top Header Bar Overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Flashlight / Torch Toggle Button
                Surface(
                    shape = CircleShape,
                    color = if (isTorchOn) Color(0xFFFFB300) else Color(0x66000000),
                    contentColor = if (isTorchOn) Color.Black else Color.White,
                    modifier = Modifier.size(46.dp)
                ) {
                    IconButton(
                        onClick = { isTorchOn = !isTorchOn },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = if (isTorchOn) "Выключить фонарик" else "Включить фонарик",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // HDR / Night badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isHdrEnabled) Color(0xDD1E1E1E) else Color(0x66000000),
                    border = BorderStroke(1.dp, if (isHdrEnabled) Color(0xFFFF9800) else Color(0x44FFFFFF)),
                    modifier = Modifier.clickable { isHdrEnabled = !isHdrEnabled }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = null,
                            tint = if (isHdrEnabled) Color(0xFFFF9800) else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isHdrEnabled) "HDR Ночной: ВКЛ" else "HDR: ВЫКЛ",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Grid Toggle
                Surface(
                    shape = CircleShape,
                    color = if (showGrid) Color(0x99000000) else Color(0x66000000),
                    contentColor = if (showGrid) Color(0xFFFF9800) else Color.White,
                    modifier = Modifier.size(46.dp)
                ) {
                    IconButton(
                        onClick = { showGrid = !showGrid },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridOn,
                            contentDescription = "Сетка",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Bottom Shutter & Gallery Controls
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 32.dp, start = 24.dp, end = 24.dp)
            ) {
                // Shutter Button
                ShutterButton(
                    isCapturing = isCapturing,
                    onClick = {
                        if (!isCapturing && imageCapture != null) {
                            isCapturing = true
                            // Turn off torch during exit to editor
                            isTorchOn = false
                            try {
                                cameraControl?.enableTorch(false)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            
                            takePhotoWithHdrEnhancement(
                                imageCapture = imageCapture!!,
                                executor = executor,
                                context = context,
                                isHdrNightEnabled = isHdrEnabled,
                                onImageSaved = { uri ->
                                    isCapturing = false
                                    onImageCaptured(uri.toString())
                                },
                                onError = {
                                    isCapturing = false
                                }
                            )
                        }
                    },
                    modifier = Modifier.align(Alignment.Center)
                )

                // Gallery Button
                Surface(
                    shape = CircleShape,
                    color = Color(0x66000000),
                    contentColor = Color.White,
                    border = BorderStroke(1.dp, Color(0x44FFFFFF)),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(56.dp)
                ) {
                    IconButton(
                        onClick = {
                            isTorchOn = false
                            try {
                                cameraControl?.enableTorch(false)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            onNavigateToGallery()
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = "Галерея товаров",
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            // Loading / Processing Overlay
            if (isCapturing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xEE1E1E1E),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = Color(0xFFFF9800))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "HDR Обработка снимка...",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Требуется разрешение на камеру для съемки товаров",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                    Text("Предоставить доступ")
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(
    isCapturing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (isCapturing) 0.88f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "shutterScale"
    )

    Box(
        modifier = modifier
            .size(84.dp)
            .scale(scale)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White,
                radius = size.minDimension / 2f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx())
            )
        }

        Surface(
            modifier = Modifier.size(66.dp),
            shape = CircleShape,
            color = if (isCapturing) Color(0xFFFF9800) else Color.White
        ) {}
    }
}

@Composable
private fun FocusReticle(position: Offset) {
    val scale = remember { Animatable(1.5f) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
        )
        kotlinx.coroutines.delay(800)
        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 300)
        )
    }

    if (alpha.value > 0f) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val boxSizePx = with(density) { 72.dp.toPx() }
        Box(
            modifier = Modifier
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        (position.x - boxSizePx / 2f).toInt(),
                        (position.y - boxSizePx / 2f).toInt()
                    )
                }
                .size(72.dp)
                .scale(scale.value)
                .border(2.dp, Color(0xFFFFB300).copy(alpha = alpha.value), RoundedCornerShape(8.dp))
        )
    }
}

@Composable
private fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val lineColor = Color.White.copy(alpha = 0.25f)
        val strokeW = 1.dp.toPx()

        drawLine(lineColor, Offset(w / 3f, 0f), Offset(w / 3f, h), strokeWidth = strokeW)
        drawLine(lineColor, Offset(2 * w / 3f, 0f), Offset(2 * w / 3f, h), strokeWidth = strokeW)

        drawLine(lineColor, Offset(0f, h / 3f), Offset(w, h / 3f), strokeWidth = strokeW)
        drawLine(lineColor, Offset(0f, 2 * h / 3f), Offset(w, 2 * h / 3f), strokeWidth = strokeW)

        val crossSize = 24.dp.toPx()
        val cX = w / 2f
        val cY = h / 2f
        val crossColor = Color(0xFFFF9800).copy(alpha = 0.4f)
        drawLine(crossColor, Offset(cX - crossSize, cY), Offset(cX + crossSize, cY), strokeWidth = 2.dp.toPx())
        drawLine(crossColor, Offset(cX, cY - crossSize), Offset(cX, cY + crossSize), strokeWidth = 2.dp.toPx())
    }
}

private fun takePhotoWithHdrEnhancement(
    imageCapture: ImageCapture,
    executor: Executor,
    context: Context,
    isHdrNightEnabled: Boolean,
    onImageSaved: (Uri) -> Unit,
    onError: () -> Unit
) {
    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rawBitmap = imageProxyToBitmap(image)
                    image.close()

                    if (rawBitmap == null) {
                        Log.e("CameraScreen", "Failed to decode image from proxy")
                        onError()
                        return
                    }

                    kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
                        try {
                            val finalBitmap = if (isHdrNightEnabled) {
                                ImageEnhancer.enhanceImage(rawBitmap, true)
                            } else {
                                rawBitmap
                            }

                            val file = File(context.cacheDir, "temp_capture_${System.currentTimeMillis()}.jpg")
                            withContext(Dispatchers.IO) {
                                FileOutputStream(file).use { out ->
                                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                                }
                            }

                            withContext(Dispatchers.Main) {
                                onImageSaved(Uri.fromFile(file))
                            }
                        } catch (e: Throwable) {
                            Log.e("CameraScreen", "Error during HDR processing coroutine", e)
                            // Fallback to saving rawBitmap directly
                            try {
                                val file = File(context.cacheDir, "temp_capture_${System.currentTimeMillis()}.jpg")
                                withContext(Dispatchers.IO) {
                                    FileOutputStream(file).use { out ->
                                        rawBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    onImageSaved(Uri.fromFile(file))
                                }
                            } catch (e2: Throwable) {
                                Log.e("CameraScreen", "Fallback saving failed", e2)
                                withContext(Dispatchers.Main) {
                                    onError()
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("CameraScreen", "Error in onCaptureSuccess", t)
                    try { image.close() } catch (ignored: Exception) {}
                    onError()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e("CameraScreen", "Photo capture failed: ${exception.message}", exception)
                onError()
            }
        }
    )
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    return try {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // Memory-safe downsample decode if incoming photo is excessively large (> 2560px)
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

        val maxDim = 2560
        var sampleSize = 1
        val w = boundsOptions.outWidth
        val h = boundsOptions.outHeight
        if (w > maxDim || h > maxDim) {
            val halfW = w / 2
            val halfH = h / 2
            while ((halfW / sampleSize) >= maxDim || (halfH / sampleSize) >= maxDim) {
                sampleSize *= 2
            }
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null

        val rotation = image.imageInfo.rotationDegrees.toFloat()
        if (rotation != 0f) {
            val matrix = Matrix()
            matrix.postRotate(rotation)
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    } catch (e: Throwable) {
        Log.e("CameraScreen", "Error decoding ImageProxy to bitmap", e)
        null
    }
}
