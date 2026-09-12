package com.gdl.pinkdetector

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max

/**
 * Detects DJI's red -90 degree gimbal knob in its fixed bottom-limit region.
 *
 * Deliberately does not search for the white dashed scale or normal white/green
 * knob states. Production recovery only needs the red bottom-limit result.
 * Coordinates are normalized from the verified DJI Fly full-screen layout.
 */
object GimbalKnobDetector {
    const val FIXED_X = 0.791f
    const val STRIP_HALF_WIDTH = 0.018f
    const val RED_LIMIT_TOP = 0.72f
    const val RED_LIMIT_BOTTOM = 0.84f
    const val RED_SEARCH_MARGIN = 0.02f
    const val GESTURE_START_X = FIXED_X
    const val GESTURE_START_Y = 0.45f
    const val DRAG_BOTTOM_Y = 0.79f

    data class Decision(
        val knobFound: Boolean,
        val knobX: Float? = null,
        val knobY: Float? = null,
        val knobRect: RectF? = null,
        val dragBottomY: Float? = null,
        val redLowerLimit: Boolean = false,
        val status: String
    )

    fun evaluate(source: Bitmap): Decision {
        val targetWidth = 720
        val resizeScale = if (source.width > targetWidth) {
            targetWidth.toFloat() / source.width
        } else 1f
        val width = max(1, (source.width * resizeScale).toInt())
        val height = max(1, (source.height * resizeScale).toInt())
        val bitmap = if (width != source.width || height != source.height) {
            Bitmap.createScaledBitmap(source, width, height, true)
        } else source

        try {
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val fixedX = (width * FIXED_X).toInt()
            val halfWidth = max(8, (width * STRIP_HALF_WIDTH).toInt())
            val left = (fixedX - halfWidth).coerceAtLeast(0)
            val right = (fixedX + halfWidth).coerceAtMost(width - 1)
            val top = (height * (RED_LIMIT_TOP - RED_SEARCH_MARGIN))
                .toInt().coerceAtLeast(0)
            val bottom = (height * (RED_LIMIT_BOTTOM + RED_SEARCH_MARGIN))
                .toInt().coerceAtMost(height - 1)

            val redTop = height * RED_LIMIT_TOP
            val redBottom = height * RED_LIMIT_BOTTOM
            val knob = findComponents(pixels, width, height, left, right, top, bottom)
                .filter { it.cy in redTop..redBottom }
                .maxByOrNull { it.area }
                ?: return Decision(
                    knobFound = false,
                    status = "RED GIMBAL BOTTOM LIMIT NOT CONFIRMED")

            val sx = source.width.toFloat() / width
            val sy = source.height.toFloat() / height
            return Decision(
                knobFound = true,
                knobX = knob.cx * sx,
                knobY = knob.cy * sy,
                knobRect = RectF(
                    knob.minX * sx,
                    knob.minY * sy,
                    (knob.maxX + 1) * sx,
                    (knob.maxY + 1) * sy),
                dragBottomY = source.height * DRAG_BOTTOM_Y,
                redLowerLimit = true,
                status = "GIMBAL BOTTOM LIMIT REACHED • red knob"
            )
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }

    private data class Component(
        val cx: Float,
        val cy: Float,
        val area: Int,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    )

    private fun findComponents(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int
    ): List<Component> {
        val accepted = BooleanArray(width * height)
        for (y in top until bottom) for (x in left..right) {
            val index = y * width + x
            accepted[index] = isRed(pixels[index])
        }

        val visited = BooleanArray(width * height)
        val queue = IntArray((right - left + 1) * (bottom - top))
        val result = mutableListOf<Component>()
        for (startY in top until bottom) for (startX in left..right) {
            val start = startY * width + startX
            if (!accepted[start] || visited[start]) continue
            var head = 0
            var tail = 0
            var count = 0
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            var sumX = 0L
            var sumY = 0L
            queue[tail++] = start
            visited[start] = true
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                count++
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
                sumX += x; sumY += y
                val neighbours = intArrayOf(index - 1, index + 1, index - width, index + width)
                for (next in neighbours) {
                    if (next !in pixels.indices || visited[next] || !accepted[next]) continue
                    val nx = next % width
                    val ny = next / width
                    if (nx !in left..right || ny !in top until bottom) continue
                    if (abs(nx - x) + abs(ny - y) != 1) continue
                    visited[next] = true
                    queue[tail++] = next
                }
            }

            val boxWidth = maxX - minX + 1
            val boxHeight = maxY - minY + 1
            val minSize = max(3, (width * 0.004f).toInt())
            val maxSize = max(14, (width * 0.028f).toInt())
            val aspect = boxWidth.toFloat() / boxHeight.coerceAtLeast(1)
            val fill = count.toFloat() / (boxWidth * boxHeight).coerceAtLeast(1)
            if (boxWidth in minSize..maxSize && boxHeight in minSize..maxSize &&
                aspect in 0.55f..1.80f && fill >= 0.60f && count >= 10) {
                result += Component(
                    sumX.toFloat() / count,
                    sumY.toFloat() / count,
                    count,
                    minX, minY, maxX, maxY)
            }
        }
        return result
    }

    private fun isRed(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return r >= 175 && r >= g * 1.45f && r >= b * 1.35f
    }
}
