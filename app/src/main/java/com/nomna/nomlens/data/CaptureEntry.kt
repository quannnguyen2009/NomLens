package com.nomna.nomlens.data

import com.nomna.nomlens.ml.DetectedColumn

/**
 * Represents a single saved OCR capture stored on disk, including full recognized column details.
 */
data class CaptureEntry(
    val id: String,
    val imagePath: String,              // Absolute path to the saved JPEG image
    val timestamp: Long,
    val columns: List<DetectedColumn>,  // Full recognized column detections and text
    val columnCount: Int = columns.size
) {
    val columnTexts: List<String> get() = columns.map { it.text }

    val displayDate: String get() {
        val sdf = java.text.SimpleDateFormat("dd/MM/yyyy  HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    val previewText: String get() {
        return columnTexts.filter { it.isNotBlank() }.take(2).joinToString("  │  ").let {
            if (it.length > 40) it.take(37) + "…" else it
        }
    }
}

