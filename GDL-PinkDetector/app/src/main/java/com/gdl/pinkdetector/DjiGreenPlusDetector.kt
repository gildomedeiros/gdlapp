package com.gdl.pinkdetector

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Fast structural detector for DJI Fly's subject-scanning green plus icon. */
object DjiGreenPlusDetector {
    data class Detection(
        val rect: Rect,
        val centerX: Float,
        val centerY: Float,
        val confidencePercent: Int
    )

    private data class Component(
        val count: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private data class Structure(
        val top: Float,
        val bottom: Float,
        val left: Float,
        val right: Float,
        val horizontal: Float,
        val vertical: Float,
        val diagonalDown: Float,
        val diagonalUp: Float
    )

    fun findGreenPluses(source: Bitmap): List<Detection> {
        val targetWidth = 1280
        val scale = if (source.width > targetWidth) targetWidth.toFloat() / source.width else 1f
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        val bitmap = if (width != source.width) {
            Bitmap.createScaledBitmap(source, width, height, true)
        } else {
            source
        }

        try {
            val green = createGreenBooleanMask(bitmap)
            val components = connectedComponents(green, width, height)
            val minSide = minOf(width, height)
            val minSize = max(5, (minSide * 0.004f).roundToInt())
            val maxSize = max(48, (minSide * 0.075f).roundToInt())
            val results = ArrayList<Detection>()

            for (component in components) {
                val boxWidth = component.right - component.left + 1
                val boxHeight = component.bottom - component.top + 1
                if (component.count < 12) continue
                if (boxWidth !in minSize..maxSize || boxHeight !in minSize..maxSize) continue

                val aspect = boxWidth.toFloat() / boxHeight
                if (aspect !in 0.65f..1.45f) continue

                val density = component.count.toFloat() / (boxWidth * boxHeight)
                // DJI's X is a filled green circle. The plus is a hollow square
                // frame with an orthogonal cross and has much lower fill density.
                if (density !in 0.16f..0.60f) continue

                val centerX = (component.left + component.right + 1) / 2f
                val centerY = (component.top + component.bottom + 1) / 2f
                if (isFixedDjiUiRegion(centerX, centerY, width, height)) continue

                val structure = measureStructure(
                    green,
                    width,
                    component.left,
                    component.top,
                    boxWidth,
                    boxHeight
                )
                val borderMinimum = minOf(
                    minOf(structure.top, structure.bottom),
                    minOf(structure.left, structure.right)
                )
                val axisMinimum = minOf(structure.horizontal, structure.vertical)
                val diagonalMaximum = maxOf(structure.diagonalDown, structure.diagonalUp)
                val axisAverage = (structure.horizontal + structure.vertical) / 2f
                val diagonalAverage = (structure.diagonalDown + structure.diagonalUp) / 2f

                // A plus must have a square outline and dominant horizontal and
                // vertical centre strokes. An X has dominant diagonal strokes;
                // vegetation generally has neither the border nor this symmetry.
                if (borderMinimum < 0.45f) continue
                if (axisMinimum < 0.65f) continue
                if (diagonalMaximum > 0.60f) continue
                if (axisAverage - diagonalAverage < 0.25f) continue

                val borderAverage = (
                    structure.top + structure.bottom + structure.left + structure.right
                    ) / 4f
                val confidence = (100f * (
                    0.45f * axisAverage +
                        0.35f * borderAverage +
                        0.20f * (1f - diagonalAverage)
                    )).roundToInt().coerceIn(1, 99)

                val sx = source.width.toFloat() / width
                val sy = source.height.toFloat() / height
                val rect = Rect(
                    (component.left * sx).toInt(),
                    (component.top * sy).toInt(),
                    ((component.right + 1) * sx).toInt(),
                    ((component.bottom + 1) * sy).toInt()
                )
                results += Detection(rect, rect.exactCenterX(), rect.exactCenterY(), confidence)
            }

            // DJI sometimes renders the hollow square and its centre plus as
            // separate green components. Small changes introduced by video
            // scaling can also break one side of the square just below the
            // strict legacy border threshold. Pair the two explicit shapes
            // instead of depending on compression to join them.
            for (frame in components) {
                val frameWidth = frame.right - frame.left + 1
                val frameHeight = frame.bottom - frame.top + 1
                if (frame.count < 12) continue
                if (frameWidth !in minSize..maxSize || frameHeight !in minSize..maxSize) continue
                val frameAspect = frameWidth.toFloat() / frameHeight
                if (frameAspect !in 0.70f..1.40f) continue
                val frameDensity = frame.count.toFloat() / (frameWidth * frameHeight)
                if (frameDensity !in 0.12f..0.62f) continue

                val frameCenterX = (frame.left + frame.right + 1) / 2f
                val frameCenterY = (frame.top + frame.bottom + 1) / 2f
                if (isFixedDjiUiRegion(frameCenterX, frameCenterY, width, height)) continue
                val frameStructure = measureStructure(
                    green, width, frame.left, frame.top, frameWidth, frameHeight
                )
                val strongBorderSides = listOf(
                    frameStructure.top,
                    frameStructure.bottom,
                    frameStructure.left,
                    frameStructure.right
                ).count { it >= 0.30f }
                val frameBorderAverage = (
                    frameStructure.top + frameStructure.bottom +
                        frameStructure.left + frameStructure.right
                    ) / 4f
                // Small moving icons frequently lose one or two sides through
                // scaling/compression. Two recognisable sides are enough when
                // a centred orthogonal plus independently passes below.
                if (strongBorderSides < 2) continue

                for (inside in components) {
                    if (inside === frame || inside.count < 8) continue
                    val insideWidth = inside.right - inside.left + 1
                    val insideHeight = inside.bottom - inside.top + 1
                    if (inside.left < frame.left || inside.right > frame.right ||
                        inside.top < frame.top || inside.bottom > frame.bottom) continue
                    val widthRatio = insideWidth.toFloat() / frameWidth
                    val heightRatio = insideHeight.toFloat() / frameHeight
                    if (widthRatio !in 0.20f..0.75f || heightRatio !in 0.20f..0.75f) continue
                    val insideAspect = insideWidth.toFloat() / insideHeight
                    if (insideAspect !in 0.55f..1.55f) continue

                    val insideCenterX = (inside.left + inside.right + 1) / 2f
                    val insideCenterY = (inside.top + inside.bottom + 1) / 2f
                    if (abs(insideCenterX - frameCenterX) > frameWidth * 0.18f ||
                        abs(insideCenterY - frameCenterY) > frameHeight * 0.18f) continue

                    val insideStructure = measureStructure(
                        green, width, inside.left, inside.top, insideWidth, insideHeight
                    )
                    val insideAxisMinimum = minOf(
                        insideStructure.horizontal, insideStructure.vertical
                    )
                    val insideAxisAverage = (
                        insideStructure.horizontal + insideStructure.vertical
                    ) / 2f
                    val insideDiagonalAverage = (
                        insideStructure.diagonalDown + insideStructure.diagonalUp
                    ) / 2f
                    // A plus has both orthogonal axes and they remain stronger
                    // than its incidental diagonal samples. A tracked-state X
                    // has the opposite relationship and is not inside a hollow
                    // square component in any case.
                    if (insideAxisMinimum < 0.65f) continue
                    if (insideAxisAverage - insideDiagonalAverage < 0.18f) continue

                    val confidence = (100f * (
                        0.45f * insideAxisAverage +
                            0.35f * frameBorderAverage +
                            0.20f * (1f - insideDiagonalAverage)
                        )).roundToInt().coerceIn(1, 99)
                    val sx = source.width.toFloat() / width
                    val sy = source.height.toFloat() / height
                    val rect = Rect(
                        (frame.left * sx).toInt(),
                        (frame.top * sy).toInt(),
                        ((frame.right + 1) * sx).toInt(),
                        ((frame.bottom + 1) * sy).toInt()
                    )
                    results += Detection(rect, rect.exactCenterX(), rect.exactCenterY(), confidence)
                    break
                }
            }

            return results.sortedByDescending { it.confidencePercent }
                .fold(ArrayList<Detection>()) { accepted, candidate ->
                    val duplicate = accepted.any {
                        abs(it.centerX - candidate.centerX) <= max(it.rect.width(), candidate.rect.width()) &&
                            abs(it.centerY - candidate.centerY) <= max(it.rect.height(), candidate.rect.height())
                    }
                    if (!duplicate) accepted += candidate
                    accepted
                }
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }

    /** Diagnostic image: accepted DJI-green pixels are white; everything else is black. */
    fun createGreenMask(source: Bitmap): Bitmap {
        val green = createGreenBooleanMask(source)
        val pixels = IntArray(green.size) { if (green[it]) Color.WHITE else Color.BLACK }
        return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
    }

    private fun createGreenBooleanMask(source: Bitmap): BooleanArray {
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val green = BooleanArray(pixels.size)
        val hsv = FloatArray(3)
        for (i in pixels.indices) {
            Color.colorToHSV(pixels[i], hsv)
            green[i] = hsv[0] in 100f..182f && hsv[1] >= 0.32f && hsv[2] >= 0.28f
        }
        return green
    }

    private fun isFixedDjiUiRegion(x: Float, y: Float, width: Int, height: Int): Boolean {
        // y and height are deliberately unused: the full camera height,
        // including DJI's status row, remains searchable.
        return !DjiCameraSearchArea.contains(x, width)
    }

    private fun measureStructure(
        mask: BooleanArray,
        imageWidth: Int,
        left: Int,
        top: Int,
        boxWidth: Int,
        boxHeight: Int
    ): Structure {
        var topHits = 0; var topTotal = 0
        var bottomHits = 0; var bottomTotal = 0
        var leftHits = 0; var leftTotal = 0
        var rightHits = 0; var rightTotal = 0
        var horizontalHits = 0; var horizontalTotal = 0
        var verticalHits = 0; var verticalTotal = 0
        var diagonalDownHits = 0; var diagonalDownTotal = 0
        var diagonalUpHits = 0; var diagonalUpTotal = 0

        for (dy in 0 until boxHeight) {
            val ny = (dy + 0.5f) / boxHeight
            for (dx in 0 until boxWidth) {
                val nx = (dx + 0.5f) / boxWidth
                val on = mask[(top + dy) * imageWidth + left + dx]
                val inner = nx in 0.22f..0.78f && ny in 0.22f..0.78f

                if (ny < 0.16f && nx > 0.15f && nx < 0.85f) {
                    topTotal++; if (on) topHits++
                }
                if (ny > 0.84f && nx > 0.15f && nx < 0.85f) {
                    bottomTotal++; if (on) bottomHits++
                }
                if (nx < 0.16f && ny > 0.15f && ny < 0.85f) {
                    leftTotal++; if (on) leftHits++
                }
                if (nx > 0.84f && ny > 0.15f && ny < 0.85f) {
                    rightTotal++; if (on) rightHits++
                }
                if (inner && abs(ny - 0.5f) <= 0.08f) {
                    horizontalTotal++; if (on) horizontalHits++
                }
                if (inner && abs(nx - 0.5f) <= 0.08f) {
                    verticalTotal++; if (on) verticalHits++
                }
                if (inner && abs(nx - ny) <= 0.08f) {
                    diagonalDownTotal++; if (on) diagonalDownHits++
                }
                if (inner && abs(nx + ny - 1f) <= 0.08f) {
                    diagonalUpTotal++; if (on) diagonalUpHits++
                }
            }
        }

        fun ratio(hits: Int, total: Int) = if (total == 0) 0f else hits.toFloat() / total
        return Structure(
            ratio(topHits, topTotal),
            ratio(bottomHits, bottomTotal),
            ratio(leftHits, leftTotal),
            ratio(rightHits, rightTotal),
            ratio(horizontalHits, horizontalTotal),
            ratio(verticalHits, verticalTotal),
            ratio(diagonalDownHits, diagonalDownTotal),
            ratio(diagonalUpHits, diagonalUpTotal)
        )
    }

    private fun connectedComponents(mask: BooleanArray, width: Int, height: Int): List<Component> {
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        val output = ArrayList<Component>()
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            var count = 0
            queue[tail++] = start
            visited[start] = true
            var left = width
            var right = 0
            var top = height
            var bottom = 0
            while (head < tail) {
                val index = queue[head++]
                count++
                val x = index % width
                val y = index / width
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (mask[next] && !visited[next]) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
            }
            output += Component(count, left, top, right, bottom)
        }
        return output
    }
}
