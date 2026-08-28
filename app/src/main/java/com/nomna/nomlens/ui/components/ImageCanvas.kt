package com.nomna.nomlens.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nomna.nomlens.ml.DetectedColumn
import com.nomna.nomlens.ui.theme.ColumnBoxSelected
import com.nomna.nomlens.ui.theme.ColumnBoxUnselected
import kotlin.math.max
import kotlin.math.min

@Composable
fun ImageCanvas(
    bitmap: Bitmap,
    columns: List<DetectedColumn>,
    selectedColumnId: Int?,
    onColumnSelect: (DetectedColumn) -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = max(0.5f, min(scale * zoom, 5f))
                    offset += pan
                }
            }
            .pointerInput(bitmap, columns, scale, offset) {
                detectTapGestures { tapOffset ->
                    val imageBitmapWidth = bitmap.width.toFloat()
                    val imageBitmapHeight = bitmap.height.toFloat()

                    val canvasWidth = size.width.toFloat()
                    val canvasHeight = size.height.toFloat()

                    val baseScale = min(canvasWidth / imageBitmapWidth, canvasHeight / imageBitmapHeight)
                    val currentScale = baseScale * scale

                    val fitWidth = imageBitmapWidth * baseScale
                    val fitHeight = imageBitmapHeight * baseScale
                    val baseLeft = (canvasWidth - fitWidth) / 2f
                    val baseTop = (canvasHeight - fitHeight) / 2f

                    val imgX = (tapOffset.x - baseLeft - offset.x) / currentScale
                    val imgY = (tapOffset.y - baseTop - offset.y) / currentScale

                    // Find tapped column
                    val clicked = columns.firstOrNull { col ->
                        imgX >= col.box.xMin && imgX <= col.box.xMax &&
                                imgY >= col.box.yMin && imgY <= col.box.yMax
                    }
                    if (clicked != null) {
                        onColumnSelect(clicked)
                    }
                }
            }
    ) {
        val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height
            val imgW = imageBitmap.width.toFloat()
            val imgH = imageBitmap.height.toFloat()

            val baseScale = min(canvasWidth / imgW, canvasHeight / imgH)
            val currentScale = baseScale * scale

            val fitW = imgW * baseScale
            val fitH = imgH * baseScale
            val baseLeft = (canvasWidth - fitW) / 2f
            val baseTop = (canvasHeight - fitH) / 2f

            val drawLeft = baseLeft + offset.x
            val drawTop = baseTop + offset.y
            val drawW = fitW * scale
            val drawH = fitH * scale

            // 1. Draw original image
            drawImage(
                image = imageBitmap,
                dstOffset = androidx.compose.ui.unit.IntOffset(drawLeft.toInt(), drawTop.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(drawW.toInt(), drawH.toInt())
            )

            // 2. Overlay Bounding Boxes & Badges
            for (col in columns) {
                val isSelected = col.id == selectedColumnId

                val boxLeft = drawLeft + col.box.xMin * currentScale
                val boxTop = drawTop + col.box.yMin * currentScale
                val boxWidth = col.box.width * currentScale
                val boxHeight = col.box.height * currentScale

                val boxColor = if (isSelected) ColumnBoxSelected else ColumnBoxUnselected
                val strokeWidth = if (isSelected) 4.dp.toPx() else 2.dp.toPx()

                // Draw column bounding box rectangle
                drawRect(
                    color = boxColor,
                    topLeft = Offset(boxLeft, boxTop),
                    size = Size(boxWidth, boxHeight),
                    style = Stroke(
                        width = strokeWidth,
                        pathEffect = if (!isSelected) PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f) else null
                    )
                )

                // Fill semi-transparent background for selected column
                if (isSelected) {
                    drawRect(
                        color = boxColor.copy(alpha = 0.2f),
                        topLeft = Offset(boxLeft, boxTop),
                        size = Size(boxWidth, boxHeight)
                    )
                }

                // Draw Column Badge (e.g. ①, ②, ③)
                val badgeRadius = 14.dp.toPx()
                val badgeCenter = Offset(boxLeft + boxWidth / 2f, max(boxTop - badgeRadius - 4.dp.toPx(), badgeRadius + 4.dp.toPx()))

                drawCircle(
                    color = if (isSelected) ColumnBoxSelected else Color(0xFFD84315),
                    radius = badgeRadius,
                    center = badgeCenter
                )

                val badgeText = "${col.id}"
                val textResult = textMeasurer.measure(
                    text = badgeText,
                    style = TextStyle(
                        color = if (isSelected) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                )

                drawText(
                    textLayoutResult = textResult,
                    topLeft = Offset(
                        badgeCenter.x - textResult.size.width / 2f,
                        badgeCenter.y - textResult.size.height / 2f
                    )
                )
            }
        }
    }
}
