package com.nomna.nomlens.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class NomRecognizer(
    context: Context,
    modelPath: String = "recognise_model.tflite",
    vocabPath: String = "vocab.txt"
) {

    private val interpreter: Interpreter
    private val vocab: List<String>

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        val modelBuffer = loadModelFile(context, modelPath)
        interpreter = Interpreter(modelBuffer, options)
        vocab = loadVocabFile(context, vocabPath)
    }

    private fun loadModelFile(context: Context, modelPath: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun loadVocabFile(context: Context, vocabPath: String): List<String> {
        val list = mutableListOf<String>()
        context.assets.open(vocabPath).use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    list.add(line)
                    line = reader.readLine()
                }
            }
        }
        return list
    }

    fun recognizeColumn(originalBitmap: Bitmap, box: BoundingBox): Pair<String, List<RecognizedCharacter>> {
        val crop = cropBox(originalBitmap, box)
        val processedInput = preprocessColumnImage(crop)

        // Preprocess to Float32 ByteBuffer [1, 432, 48, 3]
        val targetH = 432
        val targetW = 48
        val inputBuffer = ByteBuffer.allocateDirect(1 * targetH * targetW * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()

        val pixels = IntArray(targetW * targetH)
        processedInput.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16 and 0xFF) / 255.0f
            val g = (color shr 8 and 0xFF) / 255.0f
            val b = (color and 0xFF) / 255.0f

            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }

        // Run Inference: Output shape [1, 54, 7481]
        val outputArray = Array(1) { Array(54) { FloatArray(7481) } }
        interpreter.run(inputBuffer, outputArray)

        // Greedy CTC Decoding
        val logitsTimesteps = outputArray[0]
        val characters = mutableListOf<RecognizedCharacter>()
        var prevIndex = -1
        val fullTextBuilder = StringBuilder()

        for (t in 0 until 54) {
            val logits = logitsTimesteps[t]
            var bestIndex = 0
            var maxLogit = logits[0]

            for (c in 1 until 7481) {
                if (logits[c] > maxLogit) {
                    maxLogit = logits[c]
                    bestIndex = c
                }
            }

            // Collapse consecutive duplicates and skip blank token (0 = [PAD])
            if (bestIndex != prevIndex) {
                prevIndex = bestIndex
                if (bestIndex != 0) {
                    val rawChar = vocab.getOrNull(bestIndex) ?: "[UNK]"
                    val displayChar = if (rawChar == "[UNK]") "?" else rawChar
                    val confidence = computeConfidence(logits, bestIndex)

                    characters.add(
                        RecognizedCharacter(
                            char = displayChar,
                            index = bestIndex,
                            confidence = confidence
                        )
                    )
                    fullTextBuilder.append(displayChar)
                }
            }
        }

        return Pair(fullTextBuilder.toString(), characters)
    }

    private fun cropBox(bitmap: Bitmap, box: BoundingBox): Bitmap {
        val marginX = (box.width * 0.03f).toInt()
        val marginY = (box.height * 0.02f).toInt()

        val xMin = max(0, (box.xMin - marginX).toInt())
        val yMin = max(0, (box.yMin - marginY).toInt())
        val xMax = min(bitmap.width, (box.xMax + marginX).toInt())
        val yMax = min(bitmap.height, (box.yMax + marginY).toInt())

        val w = max(1, xMax - xMin)
        val h = max(1, yMax - yMin)

        return Bitmap.createBitmap(bitmap, xMin, yMin, w, h)
    }

    private fun preprocessColumnImage(crop: Bitmap): Bitmap {
        val targetH = 432
        val targetW = 48

        val origW = crop.width.toFloat()
        val origH = crop.height.toFloat()
        val aspectRatio = origW / origH

        val newW: Int
        val newH: Int

        if ((targetW.toFloat() / targetH) > aspectRatio) {
            newH = targetH
            newW = max(1, (aspectRatio * targetH).toInt())
        } else {
            newW = targetW
            newH = max(1, (targetW / aspectRatio).toInt())
        }

        val resizedCrop = Bitmap.createScaledBitmap(crop, min(targetW, newW), min(targetH, newH), true)

        val targetBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(targetBitmap)
        canvas.drawColor(Color.WHITE) // White padding for text background

        val leftPad = max(0, (targetW - resizedCrop.width) / 2)
        val topPad = 0 // Top-aligned vertical text

        canvas.drawBitmap(resizedCrop, leftPad.toFloat(), topPad.toFloat(), null)
        return targetBitmap
    }

    private fun computeConfidence(logits: FloatArray, maxIndex: Int): Float {
        // Approximate softmax confidence for winning logit
        var sumExp = 0.0
        val maxVal = logits[maxIndex]
        for (i in logits.indices) {
            sumExp += exp((logits[i] - maxVal).toDouble())
        }
        return (1.0 / sumExp).toFloat()
    }

    fun close() {
        interpreter.close()
    }
}
