package com.nomna.nomlens.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nomna.nomlens.ml.BoundingBox
import com.nomna.nomlens.ml.DetectedColumn
import com.nomna.nomlens.ml.RecognizedCharacter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Disk-backed repository that persists OCR captures.
 * Images are stored as JPEGs; full OCR column results are stored in JSON.
 * All I/O is performed on Dispatchers.IO.
 */
class CaptureRepository(context: Context) {

    private val capturesDir: File = File(context.filesDir, "captures").also { it.mkdirs() }
    private val metadataFile: File = File(capturesDir, "index.json")

    private val _entries = MutableStateFlow<List<CaptureEntry>>(emptyList())
    val entries: StateFlow<List<CaptureEntry>> = _entries.asStateFlow()

    init {
        _entries.value = loadFromDisk()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Save a new capture with its full detected columns and OCR text.
     * Returns the created [CaptureEntry], or null if an error occurs.
     */
    suspend fun save(
        bitmap: Bitmap,
        columns: List<DetectedColumn>
    ): CaptureEntry? = withContext(Dispatchers.IO) {
        try {
            val id = System.currentTimeMillis().toString()
            val imageFile = File(capturesDir, "$id.jpg")

            // Scale to thumbnail max 1280px on longest side for fast loading and great detail
            val savedBitmap = scaleBitmap(bitmap, 1280)
            val scaleX = savedBitmap.width.toFloat() / bitmap.width
            val scaleY = savedBitmap.height.toFloat() / bitmap.height

            FileOutputStream(imageFile).use { out ->
                savedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            // Adjust bounding boxes to match savedBitmap dimensions if scaled
            val scaledColumns = if (scaleX != 1f || scaleY != 1f) {
                columns.map { col ->
                    val origBox = col.box
                    val scaledBox = BoundingBox(
                        xMin = origBox.xMin * scaleX,
                        yMin = origBox.yMin * scaleY,
                        xMax = origBox.xMax * scaleX,
                        yMax = origBox.yMax * scaleY,
                        score = origBox.score
                    )
                    val crop = createCrop(savedBitmap, scaledBox)
                    col.copy(box = scaledBox, cropBitmap = crop)
                }
            } else {
                columns.map { col ->
                    if (col.cropBitmap != null) col else col.copy(cropBitmap = createCrop(savedBitmap, col.box))
                }
            }

            if (savedBitmap != bitmap) {
                savedBitmap.recycle()
            }

            val entry = CaptureEntry(
                id = id,
                imagePath = imageFile.absolutePath,
                timestamp = id.toLong(),
                columns = scaledColumns,
                columnCount = scaledColumns.size
            )

            val updated = listOf(entry) + _entries.value
            _entries.value = updated
            saveToDisk(updated)
            entry
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Load the bitmap and reconstruct all column crop bitmaps for a given [CaptureEntry].
     */
    suspend fun loadBitmapAndColumns(entry: CaptureEntry): Pair<Bitmap, List<DetectedColumn>>? = withContext(Dispatchers.IO) {
        try {
            val bitmap = BitmapFactory.decodeFile(entry.imagePath) ?: return@withContext null
            val columnsWithCrops = entry.columns.map { col ->
                val crop = createCrop(bitmap, col.box)
                col.copy(cropBitmap = crop)
            }
            Pair(bitmap, columnsWithCrops)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Load just the bitmap for a given [CaptureEntry] (e.g. for thumbnail display).
     */
    suspend fun loadBitmap(entry: CaptureEntry): Bitmap? = withContext(Dispatchers.IO) {
        try {
            BitmapFactory.decodeFile(entry.imagePath)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Delete a capture (image file + metadata).
     */
    suspend fun delete(entry: CaptureEntry) = withContext(Dispatchers.IO) {
        try {
            File(entry.imagePath).delete()
            val updated = _entries.value.filter { it.id != entry.id }
            _entries.value = updated
            saveToDisk(updated)
        } catch (_: Exception) {}
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Disk I/O & Serialization
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveToDisk(entries: List<CaptureEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            val obj = JSONObject().apply {
                put("id", e.id)
                put("imagePath", e.imagePath)
                put("timestamp", e.timestamp)
                put("columnCount", e.columnCount)

                val colsArr = JSONArray()
                e.columns.forEach { col ->
                    val colObj = JSONObject().apply {
                        put("id", col.id)
                        put("text", col.text)

                        val boxObj = JSONObject().apply {
                            put("xMin", col.box.xMin.toDouble())
                            put("yMin", col.box.yMin.toDouble())
                            put("xMax", col.box.xMax.toDouble())
                            put("yMax", col.box.yMax.toDouble())
                            put("score", col.box.score.toDouble())
                        }
                        put("box", boxObj)

                        val charsArr = JSONArray()
                        col.characters.forEach { ch ->
                            val chObj = JSONObject().apply {
                                put("char", ch.char)
                                put("index", ch.index)
                                put("confidence", ch.confidence.toDouble())
                            }
                            charsArr.put(chObj)
                        }
                        put("characters", charsArr)
                    }
                    colsArr.put(colObj)
                }
                put("columns", colsArr)
            }
            arr.put(obj)
        }
        metadataFile.writeText(arr.toString())
    }

    private fun loadFromDisk(): List<CaptureEntry> {
        if (!metadataFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(metadataFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val imgPath = obj.getString("imagePath")
                if (!File(imgPath).exists()) return@mapNotNull null

                val columnsList = mutableListOf<DetectedColumn>()
                if (obj.has("columns")) {
                    val colsArr = obj.getJSONArray("columns")
                    for (c in 0 until colsArr.length()) {
                        val colObj = colsArr.getJSONObject(c)
                        val id = colObj.optInt("id", c + 1)
                        val text = colObj.optString("text", "")

                        val boxObj = colObj.optJSONObject("box")
                        val box = if (boxObj != null) {
                            BoundingBox(
                                xMin = boxObj.optDouble("xMin", 0.0).toFloat(),
                                yMin = boxObj.optDouble("yMin", 0.0).toFloat(),
                                xMax = boxObj.optDouble("xMax", 0.0).toFloat(),
                                yMax = boxObj.optDouble("yMax", 0.0).toFloat(),
                                score = boxObj.optDouble("score", 1.0).toFloat()
                            )
                        } else {
                            BoundingBox(0f, 0f, 0f, 0f, 1f)
                        }

                        val charsList = mutableListOf<RecognizedCharacter>()
                        if (colObj.has("characters")) {
                            val charsArr = colObj.getJSONArray("characters")
                            for (k in 0 until charsArr.length()) {
                                val chObj = charsArr.getJSONObject(k)
                                charsList.add(
                                    RecognizedCharacter(
                                        char = chObj.optString("char", ""),
                                        index = chObj.optInt("index", k),
                                        confidence = chObj.optDouble("confidence", 1.0).toFloat()
                                    )
                                )
                            }
                        }

                        columnsList.add(
                            DetectedColumn(
                                id = id,
                                box = box,
                                text = text,
                                characters = charsList,
                                cropBitmap = null
                            )
                        )
                    }
                } else if (obj.has("columnTexts")) {
                    // Fallback for older format
                    val texts = obj.getJSONArray("columnTexts")
                    for (t in 0 until texts.length()) {
                        columnsList.add(
                            DetectedColumn(
                                id = t + 1,
                                box = BoundingBox(0f, 0f, 0f, 0f, 1f),
                                text = texts.getString(t),
                                characters = emptyList(),
                                cropBitmap = null
                            )
                        )
                    }
                }

                CaptureEntry(
                    id = obj.getString("id"),
                    imagePath = imgPath,
                    timestamp = obj.getLong("timestamp"),
                    columns = columnsList,
                    columnCount = if (columnsList.isNotEmpty()) columnsList.size else obj.optInt("columnCount", 0)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun createCrop(bitmap: Bitmap, box: BoundingBox): Bitmap? {
        return try {
            val xMin = Math.max(0, box.xMin.toInt())
            val yMin = Math.max(0, box.yMin.toInt())
            val w = Math.min(bitmap.width - xMin, box.width.toInt())
            val h = Math.min(bitmap.height - yMin, box.height.toInt())
            if (w > 0 && h > 0) {
                Bitmap.createBitmap(bitmap, xMin, yMin, w, h)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val scale = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true)
    }
}
