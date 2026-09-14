package com.gdl.pinkdetector

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native-pixel solid selection rails and inward corners; dotted pin boxes fail. */
object TrackingBoxDetector {
    /** Confirm the green cancel circle and dark X before using its centre. */
    fun cancelTarget(bitmap: Bitmap, box: Rect): Rect? {
        val pixels = NativeFramePixels.read(bitmap)
        val w = bitmap.width; val h = bitmap.height
        fun green(x: Int, y: Int): Boolean {
            if (x !in 0 until w || y !in 0 until h) return false
            val c = pixels[y * w + x]
            return Color.green(c) > 135 && Color.green(c) > Color.red(c) * 1.45 &&
                Color.blue(c) > 45 && Color.blue(c) < Color.green(c) * .95
        }
        val radius = maxOf(8, (w * .012).toInt())
        for (cy in box.top - radius..box.top + radius) {
            for (cx in box.left - radius / 2..box.left + radius / 2) {
                val ring = radius * 3 / 4
                if (!green(cx-ring,cy) || !green(cx+ring,cy) ||
                    !green(cx,cy-ring) || !green(cx,cy+ring)) continue
                var dark = 0
                for (d in -radius/3..radius/3) {
                    for (sign in listOf(-1, 1)) {
                        val x = cx+d; val y = cy+sign*d
                        if (x in 0 until w && y in 0 until h) {
                            val c = pixels[y*w+x]
                            if (Color.green(c) < 150 && Color.red(c) < 120 && Color.blue(c) < 150) dark++
                        }
                    }
                }
                if (dark >= (2 * (2*(radius/3)+1)) * .75)
                    return Rect(cx-2,cy-2,cx+3,cy+3)
            }
        }
        return null
    }
    data class Result(val rect: Rect, val pinkPixels: Int)
    private data class Rail(val x: Int, val top: Int, val bottom: Int)

    fun detect(bitmap: Bitmap): Result? {
        val w = bitmap.width; val h = bitmap.height
        val pixels = NativeFramePixels.read(bitmap)
        fun green(x: Int, y: Int): Boolean {
            if (x !in 0 until w || y !in 0 until h) return false
            val c = pixels[y * w + x]
            val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            return g > 135 && g > r * 1.45 && b > 45 && b < g * .95
        }
        val minHeight = (h * .09).toInt()
        val rails = mutableListOf<Rail>()
        // Every native column is examined; no image resampling.
        for (x in (w * .11).toInt() until (w * .92).toInt()) {
            var start = -1; var last = -1
            for (y in (h * .10).toInt() until (h * .94).toInt()) {
                if (green(x, y)) {
                    if (start < 0) start = y
                    last = y
                } else if (start >= 0 && y - last > 2) {
                    if (last - start >= minHeight) {
                        rails.add(Rail(x, start, last))
                        // Excessive candidates are ambiguous; bound pairing work.
                        if (rails.size > 256) return null
                    }
                    start = -1
                }
            }
            // Runs touching the search edge are unknown, not complete boxes.
        }
        val candidates = mutableListOf<Rect>()
        val tolerance = maxOf(5, (h * .008).toInt())
        fun corner(x: Int, y: Int, direction: Int, length: Int): Boolean {
            var hits = 0
            for (dx in 3..length) {
                if ((-tolerance..tolerance).any { green(x + direction * dx, y + it) }) hits++
            }
            return hits >= (length - 2) * .8
        }
        for (left in rails) for (right in rails) {
            val width = right.x - left.x
            if (width < w * .025 || width > w * .45) continue
            if (abs(left.top - right.top) > tolerance * 3 ||
                abs(left.bottom - right.bottom) > tolerance * 2) continue
            val top = minOf(left.top, right.top); val bottom = maxOf(left.bottom, right.bottom)
            if ((bottom - top).toFloat() / width !in .5f..6f) continue
            val arm = maxOf(8, minOf((width * .12).toInt(), (w * .015).toInt()))
            if (!corner(left.x, left.bottom, 1, arm) || !corner(right.x, right.bottom, -1, arm) ||
                !corner(right.x, right.top, -1, arm)) continue
            val rect = Rect(left.x, top, right.x, bottom)
            if (candidates.none { abs(it.left - rect.left) < tolerance && abs(it.right - rect.right) < tolerance && abs(it.top - rect.top) < tolerance * 3 }) candidates.add(rect)
        }
        // Ambiguous multiple selections must not create absence evidence.
        val box = candidates.singleOrNull() ?: return null
        val hsv = FloatArray(3)
        var count = 0
        for (y in box.top + 3 until box.bottom - 3) for (x in box.left + 3 until box.right - 3) {
            Color.colorToHSV(pixels[y * w + x], hsv)
            if (hsv[0] in 285f..355f && hsv[1] >= .35f && hsv[2] >= .34f) count++
        }
        val scale = minOf(1.0, 1280.0 / w)
        return Result(box, (count * scale * scale).roundToInt())
    }
}
