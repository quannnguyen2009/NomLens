package com.nomna.nomlens.ml

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class NomPipeline(private val context: Context) {

    private var detector: YoloDetector? = null
    private var recognizer: NomRecognizer? = null

    @Synchronized
    private fun ensureModelsLoaded() {
        if (detector == null) {
            detector = YoloDetector(context)
        }
        if (recognizer == null) {
            recognizer = NomRecognizer(context)
        }
    }

    fun processImage(
        bitmap: Bitmap,
        config: PipelineConfig = PipelineConfig()
    ): Flow<ProcessingState> = flow {
        emit(ProcessingState.Processing(ProcessingProgress("Loading TFLite models...", 0, 100)))

        ensureModelsLoaded()

        emit(ProcessingState.Processing(ProcessingProgress("Detecting Han-Nom text columns...", 10, 100)))
        val currentDetector = detector!!
        val currentRecognizer = recognizer!!

        val boxes = currentDetector.detect(
            bitmap = bitmap,
            confThreshold = config.confThreshold,
            iouThreshold = config.iouThreshold
        )

        if (boxes.isEmpty()) {
            emit(ProcessingState.Success(bitmap, emptyList(), config))
            return@flow
        }

        val columns = mutableListOf<DetectedColumn>()
        val total = boxes.size

        for (idx in boxes.indices) {
            val box = boxes[idx]
            val progressPercent = 20 + ((idx + 1).toFloat() / total * 80).toInt()
            emit(
                ProcessingState.Processing(
                    ProcessingProgress(
                        "Recognizing column ${idx + 1} of $total...",
                        progressPercent,
                        100
                    )
                )
            )

            val (text, characters) = currentRecognizer.recognizeColumn(bitmap, box)
            
            // Crop column bitmap preview
            val cropBitmap = try {
                val xMin = Math.max(0, box.xMin.toInt())
                val yMin = Math.max(0, box.yMin.toInt())
                val w = Math.min(bitmap.width - xMin, box.width.toInt())
                val h = Math.min(bitmap.height - yMin, box.height.toInt())
                if (w > 0 && h > 0) Bitmap.createBitmap(bitmap, xMin, yMin, w, h) else null
            } catch (e: Exception) {
                null
            }

            columns.add(
                DetectedColumn(
                    id = idx + 1,
                    box = box,
                    text = text,
                    characters = characters,
                    cropBitmap = cropBitmap
                )
            )
        }

        emit(ProcessingState.Success(bitmap, columns, config))
    }.flowOn(Dispatchers.Default)

    fun close() {
        detector?.close()
        detector = null
        recognizer?.close()
        recognizer = null
    }
}
