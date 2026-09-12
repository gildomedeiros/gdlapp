package com.gdl.pinkdetector

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

/** Best-effort OCR for DJI's two small bottom-left metre readouts. */
object DjiTelemetryReader {
    data class Values(val altitude: String, val distance: String) {
        companion object { val UNKNOWN = Values("UNKNOWN", "UNKNOWN") }
    }

    private val recognizerDelegate = lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val recognizer by recognizerDelegate
    private val metreValue = Regex(
        """(?<![0-9])[-−]?\s*[0-9]{1,3}\s*[.,°·•']\s*[0-9]\s*m(?!\s*/\s*s)\b""",
        RegexOption.IGNORE_CASE)

    /** Caller transfers ownership of [crop]; it is always recycled here. */
    fun recognize(crop: Bitmap, result: (Values) -> Unit) {
        // Scaling and OCR run on GDL's telemetry executor, not the high-rate
        // detector executor. Only the small native-resolution crop is made in
        // the capture path.
        val input = try {
            Bitmap.createScaledBitmap(crop, crop.width * 3, crop.height * 3, true)
        } catch (_: Throwable) {
            crop.recycle()
            result(Values.UNKNOWN)
            return
        }
        if (input !== crop) crop.recycle()
        recognizer.process(InputImage.fromBitmap(input, 0))
            .addOnSuccessListener { text ->
                val first = parse(text)
                if (first.altitude != "UNKNOWN" && first.distance != "UNKNOWN") {
                    result(first)
                    input.recycle()
                } else {
                    recognizeEnhanced(input, first, result)
                }
            }
            .addOnFailureListener { recognizeEnhanced(input, Values.UNKNOWN, result) }
    }

    fun cropTelemetry(source: Bitmap): Bitmap {
        val left = (source.width * 0.105f).toInt().coerceIn(0, source.width - 1)
        val top = (source.height * 0.80f).toInt().coerceIn(0, source.height - 1)
        // End before DJI's adjacent m/s columns. Only the left-hand altitude
        // and distance metre values belong in evidence filenames.
        // Keep additional context around the metre values. The parser still
        // explicitly rejects the adjacent m/s readouts.
        val right = (source.width * 0.22f).toInt().coerceIn(left + 1, source.width)
        val bottom = (source.height * 0.98f).toInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun recognizeEnhanced(
        original: Bitmap,
        first: Values,
        result: (Values) -> Unit
    ) {
        val enhanced = enhanceLocalContrast(original)
        recognizer.process(InputImage.fromBitmap(enhanced, 0))
            .addOnSuccessListener { text ->
                val second = parse(text)
                result(Values(
                    altitude = if (first.altitude != "UNKNOWN") first.altitude
                        else second.altitude,
                    distance = if (first.distance != "UNKNOWN") first.distance
                        else second.distance))
            }
            .addOnFailureListener { result(first) }
            .addOnCompleteListener {
                enhanced.recycle()
                original.recycle()
            }
    }

    /**
     * Strengthens DJI's white glyph edges and black shadow on bright scenery.
     * This is an OCR-only copy and never enters the detector/touch hot path.
     */
    private fun enhanceLocalContrast(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val histogram = IntArray(256)
        for (pixel in pixels) {
            val y = (Color.red(pixel) * 77 + Color.green(pixel) * 150 +
                Color.blue(pixel) * 29) shr 8
            histogram[y]++
        }
        val trim = (pixels.size * 0.02f).toInt()
        var low = 0
        var accumulated = 0
        while (low < 255 && accumulated + histogram[low] <= trim) {
            accumulated += histogram[low++]
        }
        var high = 255
        accumulated = 0
        while (high > low && accumulated + histogram[high] <= trim) {
            accumulated += histogram[high--]
        }
        val range = (high - low).coerceAtLeast(24)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val y = (Color.red(pixel) * 77 + Color.green(pixel) * 150 +
                Color.blue(pixel) * 29) shr 8
            val stretched = ((y - low) * 255 / range).coerceIn(0, 255)
            pixels[index] = Color.rgb(stretched, stretched, stretched)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun parse(text: Text): Values {
        val matches = text.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val match = metreValue.find(line.text) ?: return@mapNotNull null
                val y = line.boundingBox?.centerY() ?: Int.MAX_VALUE
                y to filenameValue(match.value)
            }
            .sortedBy { it.first }
        return Values(
            altitude = matches.getOrNull(0)?.second ?: "UNKNOWN",
            distance = matches.getOrNull(1)?.second ?: "UNKNOWN")
    }

    private fun filenameValue(value: String): String {
        val normalized = value.lowercase(Locale.US)
            .replace("m", "")
            .replace("−", "-")
            .replace(",", ".")
            .replace("°", ".")
            .replace("·", ".")
            .replace("•", ".")
            .replace("'", ".")
            .replace(" ", "")
        // DJI displays these readouts with one decimal place. If OCR loses
        // the decimal (for example 1.1 -> 11), do not invent telemetry.
        if (!normalized.contains('.')) return "UNKNOWN"
        val numeric = normalized.toFloatOrNull() ?: return "UNKNOWN"
        return String.format(Locale.US, "%.1fm", numeric).replace('.', 'p')
    }

    fun close() {
        if (recognizerDelegate.isInitialized()) recognizer.close()
    }
}
