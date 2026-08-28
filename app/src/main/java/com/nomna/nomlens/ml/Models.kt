package com.nomna.nomlens.ml

import android.graphics.Bitmap

data class BoundingBox(
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float,
    val score: Float
) {
    val width: Float get() = xMax - xMin
    val height: Float get() = yMax - yMin
    val centerX: Float get() = (xMin + xMax) / 2f
    val centerY: Float get() = (yMin + yMax) / 2f
}

data class RecognizedCharacter(
    val char: String,
    val index: Int,
    val confidence: Float
)

data class DetectedColumn(
    val id: Int,
    val box: BoundingBox,
    val text: String,
    val characters: List<RecognizedCharacter>,
    val cropBitmap: Bitmap? = null
)

data class PipelineConfig(
    val confThreshold: Float = 0.15f,
    val iouThreshold: Float = 0.45f
)

data class ProcessingProgress(
    val stepMessage: String,
    val current: Int,
    val total: Int
)

sealed class ProcessingState {
    object Idle : ProcessingState()
    data class Processing(val progress: ProcessingProgress) : ProcessingState()
    data class Success(
        val originalBitmap: Bitmap,
        val columns: List<DetectedColumn>,
        val configUsed: PipelineConfig
    ) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
}
