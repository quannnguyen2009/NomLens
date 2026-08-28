package com.nomna.nomlens.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.nomna.nomlens.ml.PipelineConfig
import com.nomna.nomlens.ui.theme.NomNaTongFontFamily

@Composable
fun CameraCaptureView(
    onImageCaptured: (Bitmap) -> Unit,
    onOpenGallery: () -> Unit,      // system image picker
    onOpenCaptures: () -> Unit,     // in-app saved captures gallery
    onSettings: () -> Unit,
    pipelineConfig: PipelineConfig,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var isFlashOn by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(lensFacing) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageCapture
                )
                camera?.cameraControl?.enableTorch(isFlashOn)
            } catch (exc: Exception) {
                Log.e("CameraCaptureView", "Use case binding failed", exc)
                Toast.makeText(context, "Lỗi máy ảnh: ${exc.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    LaunchedEffect(isFlashOn) {
        camera?.cameraControl?.enableTorch(isFlashOn)
    }

    fun capturePhoto() {
        if (isCapturing) return
        isCapturing = true
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val rotation = image.imageInfo.rotationDegrees
                        val bitmap = image.toBitmap()
                        val finalBitmap = if (rotation != 0) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        } else bitmap
                        onImageCaptured(finalBitmap)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Lỗi xử lý ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        image.close()
                        isCapturing = false
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing = false
                    Toast.makeText(context, "Chụp ảnh thất bại: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── Full-screen camera preview ────────────────────────────────────────
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // ── Gradient scrim top ────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xCC000000), Color.Transparent)
                    )
                )
        )

        // ── Gradient scrim bottom ─────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xCC000000))
                    )
                )
        )

        // ── TOP BAR: App title | Settings | Gallery | Flash | Flip ───────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Name
            Column {
                Text(
                    text = "NomLens",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    lineHeight = 22.sp
                )
                Text(
                    text = "喃鏡",
                    fontFamily = NomNaTongFontFamily,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Action icons: Settings | Captures Gallery | Upload | Flash | Flip
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopBarIconButton(
                    onClick = onSettings,
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Cài đặt", tint = Color.White, modifier = Modifier.size(22.dp)) }
                )
                // In-app captures gallery
                TopBarIconButton(
                    onClick = onOpenCaptures,
                    icon = { Icon(Icons.Default.Collections, contentDescription = "Ảnh đã chụp", tint = Color.White, modifier = Modifier.size(22.dp)) }
                )
                TopBarIconButton(
                    onClick = { isFlashOn = !isFlashOn },
                    icon = {
                        Icon(
                            if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Flash",
                            tint = if (isFlashOn) Color(0xFFFFD54F) else Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                )
                TopBarIconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                            CameraSelector.LENS_FACING_FRONT
                        else
                            CameraSelector.LENS_FACING_BACK
                    },
                    icon = { Icon(Icons.Default.Cameraswitch, contentDescription = "Đổi camera", tint = Color.White, modifier = Modifier.size(22.dp)) }
                )
            }
        }

        // ── BOTTOM CONTROLS: Upload | Shutter | (spacer mirror) ──────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 36.dp, start = 32.dp, end = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Hint label
            Surface(
                color = Color(0x66000000),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.padding(bottom = 20.dp)
            ) {
                Text(
                    text = "Căn chỉnh văn bản vào khung",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Upload from gallery
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color(0x55000000))
                            .clickable(onClick = onOpenGallery),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = "Tải ảnh lên",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "Tải lên", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                }

                // Shutter button — large, primary, Material You styled
                ShutterButton(
                    isCapturing = isCapturing,
                    onClick = { capturePhoto() }
                )

                // Placeholder to balance layout
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(56.dp)) // invisible spacer
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun TopBarIconButton(
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0x44000000))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

@Composable
private fun ShutterButton(
    isCapturing: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = tween(100),
        label = "shutter_scale"
    )
    val ringColor by animateColorAsState(
        targetValue = if (isCapturing) Color.Gray else Color.White,
        animationSpec = tween(200),
        label = "shutter_ring"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.scale(scale)
    ) {
        // Outer ring
        Box(
            modifier = Modifier
                .size(88.dp)
                .border(4.dp, ringColor, CircleShape)
        )

        // Inner filled circle
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    if (isCapturing) Color.DarkGray
                    else MaterialTheme.colorScheme.primary
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    enabled = !isCapturing
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isCapturing) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(30.dp),
                    strokeWidth = 3.dp
                )
            }
        }
    }
}
