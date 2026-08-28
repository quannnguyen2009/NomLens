package com.nomna.nomlens.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nomna.nomlens.data.CaptureEntry
import com.nomna.nomlens.data.CaptureRepository
import com.nomna.nomlens.ml.DetectedColumn
import com.nomna.nomlens.ml.NomPipeline
import com.nomna.nomlens.ml.PipelineConfig
import com.nomna.nomlens.ml.ProcessingState
import com.nomna.nomlens.ui.components.CameraCaptureView
import com.nomna.nomlens.ui.components.ImageCanvas
import com.nomna.nomlens.ui.components.ResultSheet
import com.nomna.nomlens.ui.components.SettingsDialog
import com.nomna.nomlens.ui.theme.NomNaTongFontFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

// ─────────────────────────────────────────────────────────────────────────────
// Navigation state
// ─────────────────────────────────────────────────────────────────────────────
private sealed class Screen {
    object Camera : Screen()
    object Gallery : Screen()
    data class Results(
        val bitmap: Bitmap,
        val columns: List<DetectedColumn>,
        val fromGalleryEntry: CaptureEntry? = null
    ) : Screen()
    data class Processing(val bitmap: Bitmap) : Screen()
}

// ─────────────────────────────────────────────────────────────────────────────
// Root Composable
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NomLensApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pipeline = remember { NomPipeline(context) }
    DisposableEffect(Unit) { onDispose { pipeline.close() } }

    val repository = remember { CaptureRepository(context) }
    val galleryEntries by repository.entries.collectAsState()

    var pipelineConfig by remember { mutableStateOf(PipelineConfig()) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var selectedColumnId by remember { mutableStateOf<Int?>(null) }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Camera) }

    // ── Permission ────────────────────────────────────────────────────────────
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(
            context, "Cần quyền Camera để chụp ảnh.", Toast.LENGTH_LONG
        ).show()
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ── Gallery (system) image picker ────────────────────────────────────────
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val bmp = decodeBitmapFromUri(context, it)
            if (bmp != null) startProcessing(
                bitmap = bmp,
                pipeline = pipeline,
                config = pipelineConfig,
                scope = scope,
                onProcessing = { currentScreen = Screen.Processing(bmp) },
                onSuccess = { columns ->
                    // Auto-save image and full OCR results
                    scope.launch { repository.save(bmp, columns) }
                    currentScreen = Screen.Results(bmp, columns)
                },
                onError = { msg ->
                    Toast.makeText(context, "Lỗi: $msg", Toast.LENGTH_LONG).show()
                    currentScreen = Screen.Camera
                }
            ) else Toast.makeText(context, "Không thể đọc ảnh.", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Back handling ─────────────────────────────────────────────────────────
    BackHandler(enabled = currentScreen !is Screen.Camera) {
        currentScreen = when (currentScreen) {
            is Screen.Gallery -> Screen.Camera
            is Screen.Results -> Screen.Camera
            is Screen.Processing -> Screen.Camera
            else -> Screen.Camera
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        // Camera always lives as the base layer to keep it warm
        CameraCaptureView(
            onImageCaptured = { bitmap ->
                startProcessing(
                    bitmap = bitmap,
                    pipeline = pipeline,
                    config = pipelineConfig,
                    scope = scope,
                    onProcessing = { currentScreen = Screen.Processing(bitmap) },
                    onSuccess = { columns ->
                        scope.launch { repository.save(bitmap, columns) }
                        currentScreen = Screen.Results(bitmap, columns)
                    },
                    onError = { msg ->
                        Toast.makeText(context, "Lỗi: $msg", Toast.LENGTH_LONG).show()
                        currentScreen = Screen.Camera
                    }
                )
            },
            onOpenGallery = { imagePickerLauncher.launch("image/*") },
            onOpenCaptures = { currentScreen = Screen.Gallery },
            onSettings = { showSettingsDialog = true },
            pipelineConfig = pipelineConfig
        )

        // Animated screen stack
        AnimatedContent(
            targetState = currentScreen,
            transitionSpec = {
                when {
                    targetState is Screen.Gallery ->
                        (slideInHorizontally(tween(350)) { it } + fadeIn(tween(250))) togetherWith
                                (slideOutHorizontally(tween(350)) { -it / 3 } + fadeOut(tween(200)))
                    initialState is Screen.Gallery ->
                        (slideInHorizontally(tween(350)) { -it / 3 } + fadeIn(tween(250))) togetherWith
                                (slideOutHorizontally(tween(350)) { it } + fadeOut(tween(200)))
                    targetState is Screen.Camera ->
                        (fadeIn(tween(300))) togetherWith
                                (slideOutVertically(tween(350)) { it } + fadeOut(tween(250)))
                    else ->
                        (slideInVertically(tween(350)) { it } + fadeIn(tween(250))) togetherWith
                                (fadeOut(tween(200)))
                }
            },
            label = "screen_nav"
        ) { screen ->
            when (screen) {
                is Screen.Camera -> Box(modifier = Modifier.fillMaxSize()) // transparent — camera shows through
                is Screen.Gallery -> GalleryScreen(
                    entries = galleryEntries,
                    repository = repository,
                    onOpenCapture = { entry, bitmap, columns ->
                        currentScreen = Screen.Results(bitmap, columns, fromGalleryEntry = entry)
                    },
                    onBack = { currentScreen = Screen.Camera }
                )
                is Screen.Processing -> ProcessingScreen(
                    bitmap = screen.bitmap,
                    onCancel = { currentScreen = Screen.Camera }
                )
                is Screen.Results -> ResultsScreen(
                    bitmap = screen.bitmap,
                    columns = screen.columns,
                    selectedColumnId = selectedColumnId,
                    onColumnSelect = { selectedColumnId = it },
                    onBack = { currentScreen = Screen.Camera }
                )
            }
        }

        // Settings dialog (always on top)
        if (showSettingsDialog) {
            SettingsDialog(
                currentConfig = pipelineConfig,
                onDismiss = { showSettingsDialog = false },
                onApply = { newConfig ->
                    pipelineConfig = newConfig
                    showSettingsDialog = false
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProcessingScreen(bitmap: Bitmap, onCancel: () -> Unit) {
    val processingStateFlow = remember { MutableStateFlow<ProcessingState>(ProcessingState.Idle) }
    // Note: processingStateFlow is driven externally — this screen just shows progress
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ResultsTopBar(title = "Đang xử lý…", onBack = onCancel)
            ImageCanvas(
                bitmap = bitmap,
                columns = emptyList(),
                selectedColumnId = null,
                onColumnSelect = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Nhận dạng chữ Nôm…",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun ResultsScreen(
    bitmap: Bitmap,
    columns: List<DetectedColumn>,
    selectedColumnId: Int?,
    onColumnSelect: (Int?) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ResultsTopBar(title = "Kết quả nhận dạng", onBack = onBack)
        ImageCanvas(
            bitmap = bitmap,
            columns = columns,
            selectedColumnId = selectedColumnId,
            onColumnSelect = { col -> onColumnSelect(col.id) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        if (columns.isNotEmpty()) {
            ResultSheet(
                columns = columns,
                selectedColumnId = selectedColumnId,
                onColumnSelect = { col -> onColumnSelect(col.id) }
            )
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Không tìm thấy cột chữ Nôm trong ảnh này.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared top bar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ResultsTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Quay lại",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "喃鏡 NomLens",
                fontFamily = NomNaTongFontFamily,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Processing helper
// ─────────────────────────────────────────────────────────────────────────────
private fun startProcessing(
    bitmap: Bitmap,
    pipeline: NomPipeline,
    config: PipelineConfig,
    scope: kotlinx.coroutines.CoroutineScope,
    onProcessing: () -> Unit,
    onSuccess: (List<DetectedColumn>) -> Unit,
    onError: (String) -> Unit
) {
    onProcessing()
    scope.launch {
        pipeline.processImage(bitmap, config).collect { state ->
            when (state) {
                is ProcessingState.Success -> onSuccess(state.columns)
                is ProcessingState.Error -> onError(state.message)
                else -> {}
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Utilities
// ─────────────────────────────────────────────────────────────────────────────
private fun decodeBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    val original = try {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) { null } ?: return null

    var orientation = ExifInterface.ORIENTATION_NORMAL
    try {
        context.contentResolver.openInputStream(uri)?.use {
            orientation = ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        }
    } catch (_: Exception) {}

    val rotation = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    return if (rotation != 0f) {
        val matrix = Matrix().apply { postRotate(rotation) }
        Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            .also { if (it != original) original.recycle() }
    } else original
}
