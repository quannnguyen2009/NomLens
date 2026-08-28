package com.nomna.nomlens.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class YoloDetector(context: Context, modelPath: String = "detect_model.tflite") {

    private val interpreter: Interpreter

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        val modelBuffer = loadModelFile(context, modelPath)
        interpreter = Interpreter(modelBuffer, options)
    }

    private fun loadModelFile(context: Context, modelPath: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun detect(
        bitmap: Bitmap,
        confThreshold: Float = 0.15f,
        iouThreshold: Float = 0.45f
    ): List<BoundingBox> {
        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()

        // 1. Prepare Letterboxed 640x640 Bitmap
        val inputSize = 640
        val scale = min(inputSize / origW, inputSize / origH)
        val newW = (origW * scale).toInt()
        val newH = (origH * scale).toInt()
        val padX = (inputSize - newW) / 2f
        val padY = (inputSize - newH) / 2f

        val letterboxBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114)) // YOLO default letterbox fill

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, max(1, newW), max(1, newH), true)
        canvas.drawBitmap(scaledBitmap, padX, padY, null)

        // 2. Preprocess to Float32 ByteBuffer in NCHW [1, 3, 640, 640]
        val inputBuffer = ByteBuffer.allocateDirect(1 * 3 * inputSize * inputSize * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        val pixels = IntArray(inputSize * inputSize)
        letterboxBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        // Fill Red channel
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16 and 0xFF) / 255.0f
            inputBuffer.putFloat(r)
        }
        // Fill Green channel
        for (i in pixels.indices) {
            val g = (pixels[i] shr 8 and 0xFF) / 255.0f
            inputBuffer.putFloat(g)
        }
        // Fill Blue channel
        for (i in pixels.indices) {
            val b = (pixels[i] and 0xFF) / 255.0f
            inputBuffer.putFloat(b)
        }

        // 3. Run Inference: Output shape [1, 5, 8400]
        val outputArray = Array(1) { Array(5) { FloatArray(8400) } }
        interpreter.run(inputBuffer, outputArray)

        // 4. Parse Candidates
        val candidates = mutableListOf<BoundingBox>()
        val output = outputArray[0]

        for (i in 0 until 8400) {
            val score = output[4][i]
            if (score >= confThreshold) {
                // Model output cx, cy, w, h are normalized [0.0, 1.0] relative to 640
                val cxInLetterbox = output[0][i] * inputSize
                val cyInLetterbox = output[1][i] * inputSize
                val wInLetterbox = output[2][i] * inputSize
                val hInLetterbox = output[3][i] * inputSize

                // Map 640x640 letterbox coords back to original image
                val x1InLetterbox = cxInLetterbox - wInLetterbox / 2f
                val y1InLetterbox = cyInLetterbox - hInLetterbox / 2f
                val x2InLetterbox = cxInLetterbox + wInLetterbox / 2f
                val y2InLetterbox = cyInLetterbox + hInLetterbox / 2f

                val xMin = max(0f, (x1InLetterbox - padX) / scale)
                val yMin = max(0f, (y1InLetterbox - padY) / scale)
                val xMax = min(origW, (x2InLetterbox - padX) / scale)
                val yMax = min(origH, (y2InLetterbox - padY) / scale)

                if (xMax > xMin && yMax > yMin) {
                    candidates.add(BoundingBox(xMin, yMin, xMax, yMax, score))
                }
            }
        }

        // 5. Apply Non-Maximum Suppression (NMS)
        val selectedBoxes = nms(candidates, iouThreshold)

        // 6. Sort detected columns Right-to-Left (Vertical Sino-Vietnamese reading order)
        return selectedBoxes.sortedWith(
            compareByDescending<BoundingBox> { it.centerX }
                .thenBy { it.yMin }
        )
    }

    private fun nms(boxes: List<BoundingBox>, iouThreshold: Float): List<BoundingBox> {
        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val selected = mutableListOf<BoundingBox>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)

            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (calculateIoU(best, next) >= iouThreshold) {
                    iterator.remove()
                }
            }
        }
        return selected
    }

    private fun calculateIoU(a: BoundingBox, b: BoundingBox): Float {
        val xMin = max(a.xMin, b.xMin)
        val yMin = max(a.yMin, b.yMin)
        val xMax = min(a.xMax, b.xMax)
        val yMax = min(a.yMax, b.yMax)

        val intersectionWidth = max(0f, xMax - xMin)
        val intersectionHeight = max(0f, yMax - yMin)
        val intersectionArea = intersectionWidth * intersectionHeight

        val areaA = a.width * a.height
        val areaB = b.width * b.height
        val unionArea = areaA + areaB - intersectionArea

        return if (unionArea > 0f) intersectionArea / unionArea else 0f
    }

    fun close() {
        interpreter.close()
    }
}
