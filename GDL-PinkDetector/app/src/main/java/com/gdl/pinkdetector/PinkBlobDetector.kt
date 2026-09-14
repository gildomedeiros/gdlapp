package com.gdl.pinkdetector

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max

/** Fluorescent-pink evidence detector used by the independent pink test. */
object PinkBlobDetector {
    data class Blob(val rect: Rect, val pinkPixelCount: Int, val strengthScore: Double)

    fun find(source: Bitmap): List<Blob> {
        val width = source.width
        val height = source.height
        val bitmap = source

        try {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val strengths = FloatArray(pixels.size)
            val accepted = BooleanArray(pixels.size)
            val hsv = FloatArray(3)
            for (i in pixels.indices) {
                Color.colorToHSV(pixels[i], hsv)
                val strength = pinkStrength(hsv[0], hsv[1], hsv[2])
                strengths[i] = strength
                accepted[i] = DjiCameraSearchArea.contains(i % width, width) && strength > 0f
            }

            val visited = BooleanArray(accepted.size)
            val queue = IntArray(accepted.size)
            val blobs = ArrayList<Blob>()
            for (start in accepted.indices) {
                if (!accepted[start] || visited[start]) continue
                var head = 0
                var tail = 0
                var count = 0
                var score = 0.0
                var left = width
                var top = height
                var right = 0
                var bottom = 0
                queue[tail++] = start
                visited[start] = true
                while (head < tail) {
                    val index = queue[head++]
                    val x = index % width
                    val y = index / width
                    count++
                    score += strengths[index]
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)

                    // Radius 2 joins pink fragments separated by a one-pixel
                    // compression gap, matching the validated high-resolution test.
                    for (dy in -2..2) for (dx in -2..2) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        val next = ny * width + nx
                        if (accepted[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
                if (count < 2) continue
                val sx = source.width.toFloat() / width
                val sy = source.height.toFloat() / height
                blobs += Blob(
                    Rect((left * sx).toInt(), (top * sy).toInt(),
                        ((right + 1) * sx).toInt(), ((bottom + 1) * sy).toInt()),
                    count, score)
            }
            // For the independent pink test, "strongest" means the object
            // containing the most accepted pink pixels. Colour strength is
            // only the tie-breaker, so a tiny saturated prop cannot beat a
            // substantially larger rashguard merely by being brighter.
            return blobs.sortedWith(compareByDescending<Blob> { it.pinkPixelCount }
                .thenByDescending { it.strengthScore })
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }

    fun pinkStrength(hue: Float, saturation: Float, value: Float): Float {
        if (hue !in 285f..355f || saturation < 0.35f || value < 0.34f) return 0f
        val hueScore = (1f - abs(hue - 330f) / 55f).coerceIn(0f, 1f)
        val saturationScore = ((saturation - 0.35f) / 0.65f).coerceIn(0f, 1f)
        val valueScore = ((value - 0.34f) / 0.66f).coerceIn(0f, 1f)
        return 0.45f * hueScore + 0.35f * saturationScore + 0.20f * valueScore
    }
}
