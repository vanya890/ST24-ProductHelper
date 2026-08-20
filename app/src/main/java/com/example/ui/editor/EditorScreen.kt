package com.example.ui.editor

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    imageUriStr: String,
    viewModel: EditorViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToGallery: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(imageUriStr) {
        val decoded = URLDecoder.decode(imageUriStr, StandardCharsets.UTF_8.toString())
        viewModel.init(decoded)
    }
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            viewModel.setBrandLogo(bitmap)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Редактор товара", 
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.resetProductPosition() }) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = "По центру и масштабировать")
                    }
                    IconButton(onClick = onNavigateToGallery) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Галерея")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 4.dp
            ) {
                Button(
                    onClick = {
                        viewModel.saveProduct {
                            onNavigateToGallery()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Сохранить", modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Сохранить в галерею", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    ) { padding ->
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Прецизионная обработка и вырезание фона...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (maxWidth >= 600.dp) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            state.finalBitmap?.let { bitmap ->
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Превью",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(20.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                                        .background(Color.White)
                                        .pointerInput(Unit) {
                                            detectTransformGestures { _, pan, zoom, _ ->
                                                viewModel.updateProductTransform(zoom, pan.x, pan.y)
                                            }
                                        }
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(end = 16.dp, top = 16.dp, bottom = 16.dp)
                        ) {
                            EditorControls(state, viewModel, launcher)
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        state.finalBitmap?.let { bitmap ->
                            Box(
                                modifier = Modifier
                                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                                    .fillMaxWidth()
                                    .heightIn(max = 380.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Превью",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(20.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                                        .background(Color.White)
                                        .pointerInput(Unit) {
                                            detectTransformGestures { _, pan, zoom, _ ->
                                                viewModel.updateProductTransform(zoom, pan.x, pan.y)
                                            }
                                        }
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            EditorControls(state, viewModel, launcher)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorControls(
    state: EditorState,
    viewModel: EditorViewModel,
    launcher: androidx.activity.compose.ManagedActivityResultLauncher<String, Uri?>
) {
    // 1. Template Style
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("СТИЛЬ ШАБЛОНА", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BackgroundOption(
                    selected = state.templateStyle == TemplateStyle.ST24_DARK,
                    onClick = { viewModel.updateTemplateStyle(TemplateStyle.ST24_DARK) },
                    label = "ST24 Темный",
                    modifier = Modifier.weight(1f)
                )
                BackgroundOption(
                    selected = state.templateStyle == TemplateStyle.MODERN_LIGHT,
                    onClick = { viewModel.updateTemplateStyle(TemplateStyle.MODERN_LIGHT) },
                    label = "Светлый",
                    modifier = Modifier.weight(1f)
                )
                BackgroundOption(
                    selected = state.templateStyle == TemplateStyle.CLASSIC,
                    onClick = { viewModel.updateTemplateStyle(TemplateStyle.CLASSIC) },
                    label = "Классик",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // 2. Format
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ФОРМАТ КАРТОЧКИ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                FilledTonalButton(
                    onClick = { viewModel.resetProductPosition() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("По центру", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BackgroundOption(
                    selected = state.format == AspectRatioFormat.PORTRAIT_9_16,
                    onClick = { viewModel.setFormat(AspectRatioFormat.PORTRAIT_9_16) },
                    label = "9:16",
                    modifier = Modifier.weight(1f)
                )
                BackgroundOption(
                    selected = state.format == AspectRatioFormat.SQUARE_1_1,
                    onClick = { viewModel.setFormat(AspectRatioFormat.SQUARE_1_1) },
                    label = "1:1",
                    modifier = Modifier.weight(1f)
                )
                BackgroundOption(
                    selected = state.format == AspectRatioFormat.LANDSCAPE_16_9,
                    onClick = { viewModel.setFormat(AspectRatioFormat.LANDSCAPE_16_9) },
                    label = "16:9",
                    modifier = Modifier.weight(1f)
                )
                BackgroundOption(
                    selected = state.format == AspectRatioFormat.ORIGINAL,
                    onClick = { viewModel.setFormat(AspectRatioFormat.ORIGINAL) },
                    label = "Авто",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
    
    Spacer(modifier = Modifier.height(12.dp))
    
    // 3. Background Settings
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("ФОН СТУДИИ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BackgroundOption(
                    selected = state.backgroundType == BackgroundType.WHITE,
                    onClick = { viewModel.setBackgroundType(BackgroundType.WHITE) },
                    label = "Белый",
                    modifier = Modifier.weight(1f)
                )
                BackgroundOption(
                    selected = state.backgroundType == BackgroundType.ST24_LOGO,
                    onClick = { viewModel.setBackgroundType(BackgroundType.ST24_LOGO) },
                    label = "Лого ST24",
                    modifier = Modifier.weight(1f)
                )
                BackgroundOption(
                    selected = state.backgroundType == BackgroundType.BRAND_WALL,
                    onClick = { 
                        if (state.brandLogoBitmap == null) {
                            launcher.launch("image/*")
                        } else {
                            viewModel.setBackgroundType(BackgroundType.BRAND_WALL)
                        }
                    },
                    label = "Свой лого",
                    modifier = Modifier.weight(1f)
                )
            }
            
            // Brand Wall & ST24 Controls (Available for both!)
            if (state.backgroundType == BackgroundType.BRAND_WALL || state.backgroundType == BackgroundType.ST24_LOGO) {
                Spacer(modifier = Modifier.height(14.dp))
                
                if (state.backgroundType == BackgroundType.BRAND_WALL) {
                    Button(
                        onClick = { launcher.launch("image/*") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Photo, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Загрузить файл логотипа")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text(
                    "НАСТРОЙКА БРЕНД-ВОЛЛА", 
                    style = MaterialTheme.typography.labelSmall, 
                    fontWeight = FontWeight.Bold, 
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Density / Skip Selector
                Text("Плотность логотипов (пропуски):", style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val skipOptions = listOf(
                        1 to "100%",
                        2 to "50% (x2)",
                        3 to "33% (x3)",
                        4 to "Сетка"
                    )
                    skipOptions.forEach { (skipVal, label) ->
                        BackgroundOption(
                            selected = state.brandLogoSkip == skipVal,
                            onClick = { 
                                viewModel.updateBrandWallSettings(
                                    size = state.brandLogoSize,
                                    spacing = state.brandLogoSpacing,
                                    offsetX = state.brandLogoOffsetX,
                                    offsetY = state.brandLogoOffsetY,
                                    isCheckerboard = state.isCheckerboard,
                                    skip = skipVal
                                )
                            },
                            label = label,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(10.dp))
                
                // Size Slider
                Text("Размер логотипов: ${state.brandLogoSize.toInt()}px", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = state.brandLogoSize,
                    onValueChange = { 
                        viewModel.updateBrandWallSettings(
                            size = it, 
                            spacing = state.brandLogoSpacing, 
                            offsetX = state.brandLogoOffsetX, 
                            offsetY = state.brandLogoOffsetY, 
                            isCheckerboard = state.isCheckerboard
                        ) 
                    },
                    valueRange = 30f..400f
                )
                
                // Spacing Slider
                Text("Отступы между логотипами: ${state.brandLogoSpacing.toInt()}px", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = state.brandLogoSpacing,
                    onValueChange = { 
                        viewModel.updateBrandWallSettings(
                            size = state.brandLogoSize, 
                            spacing = it, 
                            offsetX = state.brandLogoOffsetX, 
                            offsetY = state.brandLogoOffsetY, 
                            isCheckerboard = state.isCheckerboard
                        ) 
                    },
                    valueRange = 5f..180f
                )

                // Offset X Slider
                Text("Смещение по горизонтали (X): ${state.brandLogoOffsetX.toInt()}px", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = state.brandLogoOffsetX,
                    onValueChange = { 
                        viewModel.updateBrandWallSettings(
                            size = state.brandLogoSize, 
                            spacing = state.brandLogoSpacing, 
                            offsetX = it, 
                            offsetY = state.brandLogoOffsetY, 
                            isCheckerboard = state.isCheckerboard
                        ) 
                    },
                    valueRange = -200f..200f
                )

                // Offset Y Slider
                Text("Смещение по вертикали (Y): ${state.brandLogoOffsetY.toInt()}px", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = state.brandLogoOffsetY,
                    onValueChange = { 
                        viewModel.updateBrandWallSettings(
                            size = state.brandLogoSize, 
                            spacing = state.brandLogoSpacing, 
                            offsetX = state.brandLogoOffsetX, 
                            offsetY = it, 
                            isCheckerboard = state.isCheckerboard
                        ) 
                    },
                    valueRange = -200f..200f
                )

                // Halo Gradient Radius Slider
                Text("Радиус градиентного размытия около товара: ${state.brandLogoHaloRadius.toInt()}px", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = state.brandLogoHaloRadius,
                    onValueChange = { 
                        viewModel.updateBrandWallSettings(
                            size = state.brandLogoSize, 
                            spacing = state.brandLogoSpacing, 
                            offsetX = state.brandLogoOffsetX, 
                            offsetY = state.brandLogoOffsetY, 
                            isCheckerboard = state.isCheckerboard,
                            haloRadius = it
                        ) 
                    },
                    valueRange = 0f..400f
                )

                // Halo Gradient Intensity Slider
                Text("Сила исчезновения логотипов у товара: ${(state.brandLogoHaloIntensity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = state.brandLogoHaloIntensity,
                    onValueChange = { 
                        viewModel.updateBrandWallSettings(
                            size = state.brandLogoSize, 
                            spacing = state.brandLogoSpacing, 
                            offsetX = state.brandLogoOffsetX, 
                            offsetY = state.brandLogoOffsetY, 
                            isCheckerboard = state.isCheckerboard,
                            haloIntensity = it
                        ) 
                    },
                    valueRange = 0f..1f
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = state.isCheckerboard,
                        onCheckedChange = { 
                            viewModel.updateBrandWallSettings(
                                size = state.brandLogoSize, 
                                spacing = state.brandLogoSpacing, 
                                offsetX = state.brandLogoOffsetX, 
                                offsetY = state.brandLogoOffsetY, 
                                isCheckerboard = it
                            ) 
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Шахматный порядок со смещением", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    
    Spacer(modifier = Modifier.height(12.dp))
    
    // 4. Product Info Text Fields
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("ИНФОРМАЦИЯ О ТОВАРЕ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            
            // Store Name
            OutlinedTextField(
                value = state.storeName,
                onValueChange = { viewModel.updateStoreName(it) },
                label = { Text("Хедер / Название магазина") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Checkbox(checked = state.showStoreName, onCheckedChange = { viewModel.toggleStoreName(it) })
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(10.dp))
            
            // Product Name
            OutlinedTextField(
                value = state.name,
                onValueChange = { viewModel.updateName(it) },
                label = { Text("Название товара") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Checkbox(checked = state.showName, onCheckedChange = { viewModel.toggleName(it) })
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Price
            OutlinedTextField(
                value = state.price,
                onValueChange = { viewModel.updatePrice(it) },
                label = { Text("Цена") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Checkbox(checked = state.showPrice, onCheckedChange = { viewModel.togglePrice(it) })
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Phone
            OutlinedTextField(
                value = state.phone,
                onValueChange = { viewModel.updatePhone(it) },
                label = { Text("Телефон") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Checkbox(checked = state.showPhone, onCheckedChange = { viewModel.togglePhone(it) })
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Link
            OutlinedTextField(
                value = state.link,
                onValueChange = { viewModel.updateLink(it) },
                label = { Text("Ссылка на сайт (QR-код)") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Checkbox(checked = state.showLink, onCheckedChange = { viewModel.toggleLink(it) })
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                singleLine = true
            )
        }
    }
    
    Spacer(modifier = Modifier.height(24.dp))
}

@Composable
fun BackgroundOption(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (selected) 2.dp else 1.dp
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
    
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(borderWidth, borderColor, RoundedCornerShape(12.dp))
            .background(containerColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, 
            style = MaterialTheme.typography.labelSmall, 
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
