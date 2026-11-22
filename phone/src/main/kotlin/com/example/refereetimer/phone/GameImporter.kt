package com.example.refereetimer.phone

import android.content.Context
import android.net.Uri
import com.example.refereetimer.shared.GameConfirmation
import com.example.refereetimer.shared.GameConfirmationParser
import com.example.refereetimer.shared.OcrLine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * NEW: turns game confirmation screenshots (or shared email text) into a GameConfirmation.
 * Several screenshots of the same game (e.g. top and bottom of a long page) are read as one,
 * stacked in the order picked.
 */
object GameImporter {

    suspend fun fromImages(context: Context, uris: List<Uri>): GameConfirmation {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val lines = mutableListOf<OcrLine>()
        var offset = 0
        var width = 0
        try {
            for (uri in uris) {
                val image = InputImage.fromFilePath(context, uri)
                val result = recognizer.process(image).await()
                for (block in result.textBlocks) for (line in block.lines) {
                    val b = line.boundingBox ?: continue
                    lines += OcrLine(line.text, b.left, b.top + offset, b.right, b.bottom + offset)
                }
                width = maxOf(width, image.width)
                offset += image.height + 40
            }
        } finally {
            recognizer.close()
        }
        return GameConfirmationParser.parse(lines, width)
    }

    fun fromText(text: String): GameConfirmation = GameConfirmationParser.parseText(text)
}
