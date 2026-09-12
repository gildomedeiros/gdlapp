package com.gdl.pinkdetector

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max

/** Scores the fluorescent-pink pixels surrounding each accepted DJI plus. */
object PinkAssociationDetector {
    const val MIN_ASSOCIATED_PINK_PIXELS = 8

    data class Association(
        val plus: DjiGreenPlusDetector.Detection,
        val pinkPixelCount: Int,
        val weightedPinkScore: Double
    )

    fun rankByStrongestPink(
        source: Bitmap,
        pluses: List<DjiGreenPlusDetector.Detection>,
        searchRadiusMultiplier: Float = 3f
    ): List<Association> {
        if (pluses.isEmpty()) return emptyList()

        val targetWidth = 1280
        val scale = if (source.width > targetWidth) targetWidth.toFloat() / source.width else 1f
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        val bitmap = if (width != source.width || height != source.height) {
            Bitmap.createScaledBitmap(source, width, height, true)
        } else {
            source
        }

        try {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val pinkStrength = FloatArray(pixels.size)
            val hsv = FloatArray(3)

            for (i in pixels.indices) {
                if (!DjiCameraSearchArea.contains(i % width, width)) continue
                Color.colorToHSV(pixels[i], hsv)
                val hue = hsv[0]
                val saturation = hsv[1]
                val value = hsv[2]
                if (hue !in 285f..355f || saturation < 0.35f || value < 0.34f) continue

                val hueScore = (1f - abs(hue - 330f) / 55f).coerceIn(0f, 1f)
                val saturationScore = ((saturation - 0.35f) / 0.65f).coerceIn(0f, 1f)
                val valueScore = ((value - 0.34f) / 0.66f).coerceIn(0f, 1f)
                pinkStrength[i] = (
                    0.45f * hueScore +
                        0.35f * saturationScore +
                        0.20f * valueScore
                    )
            }

            val sx = width.toFloat() / source.width.coerceAtLeast(1)
            val sy = height.toFloat() / source.height.coerceAtLeast(1)
            return pluses.map { plus ->
                val centerX = plus.centerX * sx
                val centerY = plus.centerY * sy
                val plusWidth = max(5f, plus.rect.width() * sx)
                val plusHeight = max(5f, plus.rect.height() * sy)
                val halfSearchWidth = plusWidth * searchRadiusMultiplier
                val halfSearchHeight = plusHeight * searchRadiusMultiplier
                val left = (centerX - halfSearchWidth).toInt().coerceIn(0, width - 1)
                val right = (centerX + halfSearchWidth).toInt().coerceIn(0, width - 1)
                val top = (centerY - halfSearchHeight).toInt().coerceIn(0, height - 1)
                val bottom = (centerY + halfSearchHeight).toInt().coerceIn(0, height - 1)

                var pinkPixelCount = 0
                var weightedScore = 0.0
                for (y in top..bottom) {
                    val dy = abs(y - centerY) / halfSearchHeight
                    for (x in left..right) {
                        val strength = pinkStrength[y * width + x]
                        if (strength <= 0f) continue
                        val dx = abs(x - centerX) / halfSearchWidth
                        val proximity = (1f - max(dx, dy)).coerceIn(0f, 1f)
                        pinkPixelCount++
                        // Pink nearest the DJI plus contributes most, while all
                        // pink inside the torso-sized region still contributes.
                        weightedScore += strength * (0.25f + 0.75f * proximity)
                    }
                }
                Association(plus, pinkPixelCount, weightedScore)
            }.sortedWith(
                compareByDescending<Association> { it.weightedPinkScore }
                    .thenByDescending { it.pinkPixelCount }
            )
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }
}
