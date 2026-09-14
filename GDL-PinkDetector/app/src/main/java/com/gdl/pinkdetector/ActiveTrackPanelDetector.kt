package com.gdl.pinkdetector

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.max

/** Independent detector for DJI's FocusTrack mode controls. */
object ActiveTrackPanelDetector {
    enum class State {
        EXPANDED_PANEL,
        ACTIVE_TRACK_ALREADY_SELECTED,
        ACTIVE_TRACK_RUNNING,
        COLLAPSED_CHEVRON,
        UNKNOWN
    }

    enum class RedGlyph { X, STOP, UNKNOWN }

    private data class RedControl(val rect: Rect, val glyph: RedGlyph)

    data class Decision(
        val state: State,
        val targetRect: Rect? = null,
        val targetX: Float? = null,
        val targetY: Float? = null,
        val exclusionRects: List<Rect> = emptyList(),
        val darkPanelRatio: Float = 0f,
        val yellowLeftRatio: Float = 0f,
        val yellowMiddleRatio: Float = 0f,
        val runningPanelRatio: Float = 0f,
        val redGlyph: RedGlyph? = null,
        val status: String
    ) {
        val hasProposedTap: Boolean get() = targetX != null && targetY != null
    }

    fun evaluate(source: Bitmap, expandCollapsedControls: Boolean): Decision {
        val width = source.width
        val height = source.height
        val bitmap = source

        try {
            val pixels = NativeFramePixels.read(bitmap)

            val panel = normalizedRect(width, height, 0.385f, 0.745f, 0.650f, 0.890f)
            val leftSection = normalizedRect(width, height, 0.385f, 0.745f, 0.473f, 0.890f)
            val middleSection = normalizedRect(width, height, 0.473f, 0.745f, 0.560f, 0.890f)
            val darkRatio = ratio(pixels, width, panel, ::isPanelDark)
            val yellowLeft = ratio(pixels, width, leftSection, ::isDjiYellow)
            val yellowMiddle = ratio(pixels, width, middleSection, ::isDjiYellow)

            // The successful running UI is a different, two-row layout. Its red
            // Stop button occupies nearly the same place as the collapsed red X,
            // so the red rectangle can never be used as the deciding signal.
            val runningUpper = normalizedRect(width, height, 0.385f, 0.675f, 0.650f, 0.790f)
            val runningLeft = normalizedRect(width, height, 0.385f, 0.790f, 0.473f, 0.955f)
            val runningMiddle = normalizedRect(width, height, 0.473f, 0.790f, 0.560f, 0.955f)
            val runningDark = ratio(pixels, width, runningUpper, ::isPanelDark)
            val runningYellowLeft = ratio(pixels, width, runningLeft, ::isDjiYellow)
            val runningYellowMiddle = ratio(pixels, width, runningMiddle, ::isDjiYellow)
            val redControl = findRedControl(pixels, width, height)
            val sourceRedControl = redControl?.let {
                inflateWithin(
                    toSource(it.rect, source.width, source.height, width, height),
                    source.width, source.height,
                    max(8, (source.width * 0.006f).toInt()))
            }

            val runningPattern = redControl?.glyph == RedGlyph.STOP &&
                // 0.30 is grounded in the unobstructed running reference frame
                // 279 (0.33). The earlier 0.89 sample was darkened by DJI's
                // temporary obstacle-warning overlay.
                runningDark >= 0.30f &&
                runningYellowLeft >= 0.002f &&
                runningYellowLeft > runningYellowMiddle * 1.25f
            if (runningPattern) {
                return Decision(
                    State.ACTIVE_TRACK_RUNNING,
                    exclusionRects = listOfNotNull(sourceRedControl),
                    darkPanelRatio = darkRatio,
                    yellowLeftRatio = runningYellowLeft,
                    yellowMiddleRatio = runningYellowMiddle,
                    runningPanelRatio = runningDark,
                    redGlyph = RedGlyph.STOP,
                    status = "ACTIVETRACK RUNNING • Stop protected • no tap"
                )
            }

            // A word-shaped Stop glyph is always untouchable even if glare or an
            // animation prevents the full running pattern from validating.
            if (redControl?.glyph == RedGlyph.STOP) {
                return Decision(
                    State.UNKNOWN,
                    exclusionRects = listOfNotNull(sourceRedControl),
                    darkPanelRatio = darkRatio,
                    yellowLeftRatio = runningYellowLeft,
                    yellowMiddleRatio = runningYellowMiddle,
                    runningPanelRatio = runningDark,
                    redGlyph = RedGlyph.STOP,
                    status = "RED STOP PROTECTED • running pattern incomplete • no tap"
                )
            }

            if (darkRatio >= 0.42f && max(yellowLeft, yellowMiddle) >= 0.002f) {
                if (yellowLeft >= 0.002f && yellowLeft > yellowMiddle * 1.25f) {
                    return Decision(
                        State.ACTIVE_TRACK_ALREADY_SELECTED,
                        darkPanelRatio = darkRatio,
                        yellowLeftRatio = yellowLeft,
                        yellowMiddleRatio = yellowMiddle,
                        runningPanelRatio = runningDark,
                        redGlyph = redControl?.glyph,
                        status = "ACTIVETRACK ALREADY SELECTED • no tap"
                    )
                }
                val activeTrackSection = normalizedRect(
                    source.width, source.height, 0.385f, 0.745f, 0.473f, 0.890f)
                return Decision(
                    State.EXPANDED_PANEL,
                    targetRect = activeTrackSection,
                    targetX = activeTrackSection.exactCenterX(),
                    targetY = activeTrackSection.exactCenterY(),
                    darkPanelRatio = darkRatio,
                    yellowLeftRatio = yellowLeft,
                    yellowMiddleRatio = yellowMiddle,
                    runningPanelRatio = runningDark,
                    redGlyph = redControl?.glyph,
                    status = "EXPANDED PANEL • propose ActiveTrack"
                )
            }

            if (redControl?.glyph == RedGlyph.X && sourceRedControl != null) {
                if (!expandCollapsedControls) {
                    return Decision(
                        State.COLLAPSED_CHEVRON,
                        exclusionRects = listOf(sourceRedControl),
                        darkPanelRatio = darkRatio,
                        yellowLeftRatio = yellowLeft,
                        yellowMiddleRatio = yellowMiddle,
                        runningPanelRatio = runningDark,
                        redGlyph = RedGlyph.X,
                        status = "COLLAPSED PANEL • expansion disabled • no tap"
                    )
                }
                val redHeight = sourceRedControl.height().toFloat()
                val chevronWidth = sourceRedControl.width() * 0.78f
                val chevronHeight = redHeight * 0.30f
                val chevronBottom = sourceRedControl.top - redHeight * 0.08f
                val chevron = Rect(
                    (sourceRedControl.exactCenterX() - chevronWidth / 2f).toInt(),
                    (chevronBottom - chevronHeight).toInt(),
                    (sourceRedControl.exactCenterX() + chevronWidth / 2f).toInt(),
                    chevronBottom.toInt()
                )
                return Decision(
                    State.COLLAPSED_CHEVRON,
                    targetRect = chevron,
                    targetX = chevron.exactCenterX(),
                    targetY = chevron.exactCenterY(),
                    exclusionRects = listOf(sourceRedControl),
                    darkPanelRatio = darkRatio,
                    yellowLeftRatio = yellowLeft,
                    yellowMiddleRatio = yellowMiddle,
                    runningPanelRatio = runningDark,
                    redGlyph = RedGlyph.X,
                    status = "COLLAPSED PANEL • propose upward chevron"
                )
            }

            if (redControl != null && sourceRedControl != null) {
                return Decision(
                    State.UNKNOWN,
                    exclusionRects = listOf(sourceRedControl),
                    darkPanelRatio = darkRatio,
                    yellowLeftRatio = yellowLeft,
                    yellowMiddleRatio = yellowMiddle,
                    runningPanelRatio = runningDark,
                    redGlyph = RedGlyph.UNKNOWN,
                    status = "RED CONTROL GLYPH UNKNOWN • protected • no tap"
                )
            }

            return Decision(
                State.UNKNOWN,
                darkPanelRatio = darkRatio,
                yellowLeftRatio = yellowLeft,
                yellowMiddleRatio = yellowMiddle,
                runningPanelRatio = runningDark,
                status = "ACTIVETRACK CONTROLS NOT FOUND • no tap"
            )
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }

    /** Spotlight monitoring only: red X control plus a visible upward chevron.
     * Reuses the existing red-control extraction; never proposes or dispatches a tap.
     */
    fun isSpotlightControlVisible(source: Bitmap): Boolean = spotlightControlRect(source) != null

    fun spotlightControlRect(source: Bitmap): Rect? {
        val width = source.width
        val height = source.height
        val bitmap = source
        try {
            val pixels = NativeFramePixels.read(bitmap)
            val control = findRedControl(pixels, width, height) ?: return null
            if (control.glyph != RedGlyph.X) return null
            val r = control.rect
            val cx = r.exactCenterX().toInt()
            val half = max(3, (r.width() * 0.075f).toInt())
            val top = max(0, r.top - (r.height() * 0.50f).toInt())
            val bottom = (r.top - r.height() * 0.05f).toInt()
            fun bright(x: Int, y: Int): Boolean {
                if (x !in 0 until width || y !in 0 until height) return false
                val p = pixels[y * width + x]
                val red = Color.red(p); val green = Color.green(p); val blue = Color.blue(p)
                return minOf(red, green, blue) >= 120 && maxOf(red, green, blue) - minOf(red, green, blue) <= 55
            }
            // Look for both rising/falling arms, with small tolerance for antialiasing.
            for (apexY in top until bottom - half) {
                var leftHits = 0; var rightHits = 0
                for (dx in 0..half) {
                    if ((-2..2).any { bright(cx - dx, apexY + dx + it) }) leftHits++
                    if ((-2..2).any { bright(cx + dx, apexY + dx + it) }) rightHits++
                }
                if (leftHits >= (half + 1) * 0.70f && rightHits >= (half + 1) * 0.70f &&
                    !bright(cx, apexY + half)) return r
            }
            return null
        } finally {
            if (bitmap !== source) bitmap.recycle()
        }
    }

    private fun findRedControl(pixels: IntArray, width: Int, height: Int): RedControl? {
        val roi = normalizedRect(width, height, 0.430f, 0.680f, 0.600f, 0.960f)
        val accepted = BooleanArray(pixels.size)
        val hsv = FloatArray(3)
        for (y in roi.top until roi.bottom) for (x in roi.left until roi.right) {
            Color.colorToHSV(pixels[y * width + x], hsv)
            accepted[y * width + x] =
                (hsv[0] <= 16f || hsv[0] >= 344f) && hsv[1] >= 0.55f && hsv[2] >= 0.45f
        }

        val visited = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var best: RedControl? = null
        var bestCount = 0
        for (startY in roi.top until roi.bottom) for (startX in roi.left until roi.right) {
            val start = startY * width + startX
            if (!accepted[start] || visited[start]) continue
            var head = 0
            var tail = 0
            var count = 0
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
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
                for (direction in 0..3) {
                    val next = when (direction) { 0 -> index - 1; 1 -> index + 1; 2 -> index - width; else -> index + width }
                    if (next !in pixels.indices || visited[next] || !accepted[next]) continue
                    val nx = next % width
                    val ny = next / width
                    if (nx !in roi.left until roi.right || ny !in roi.top until roi.bottom) continue
                    if (kotlin.math.abs(nx - x) + kotlin.math.abs(ny - y) != 1) continue
                    visited[next] = true
                    queue[tail++] = next
                }
            }
            val box = Rect(left, top, right + 1, bottom + 1)
            val boxArea = box.width() * box.height()
            val aspect = box.width().toFloat() / box.height().coerceAtLeast(1)
            val fill = count.toFloat() / boxArea.coerceAtLeast(1)
            val widthRatio = box.width().toFloat() / width
            val heightRatio = box.height().toFloat() / height
            val whiteRatio = ratio(pixels, width, box, ::isBrightNeutral)
            val valid = widthRatio in 0.055f..0.115f &&
                heightRatio in 0.080f..0.180f &&
                aspect in 0.65f..1.45f &&
                fill >= 0.28f &&
                whiteRatio >= 0.006f
            if (valid && count > bestCount) {
                best = RedControl(box, classifyRedGlyph(pixels, width, box))
                bestCount = count
            }
        }
        return best
    }

    private fun classifyRedGlyph(pixels: IntArray, width: Int, box: Rect): RedGlyph {
        var left = box.right
        var top = box.bottom
        var right = box.left
        var bottom = box.top
        val points = ArrayList<Pair<Int, Int>>()
        val hsv = FloatArray(3)
        for (y in box.top until box.bottom) for (x in box.left until box.right) {
            Color.colorToHSV(pixels[y * width + x], hsv)
            if (isBrightNeutral(hsv[0], hsv[1], hsv[2])) {
                points.add(Pair(x, y))
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        if (points.size < 8 || right <= left || bottom <= top) return RedGlyph.UNKNOWN
        val glyphWidth = right - left + 1
        val glyphHeight = bottom - top + 1
        val aspect = glyphWidth.toFloat() / glyphHeight

        // The supplied DJI samples give a nearly square diagonal X and a wide
        // horizontal Stop word. Require both diagonals for X; aspect alone is
        // sufficient only to lock a wide word-shaped control as Stop.
        var mainDiagonal = 0
        var antiDiagonal = 0
        points.forEach { (x, y) ->
            val nx = (x - left).toFloat() / (glyphWidth - 1).coerceAtLeast(1)
            val ny = (y - top).toFloat() / (glyphHeight - 1).coerceAtLeast(1)
            if (kotlin.math.abs(ny - nx) <= 0.22f) mainDiagonal++
            if (kotlin.math.abs(ny - (1f - nx)) <= 0.22f) antiDiagonal++
        }
        val mainRatio = mainDiagonal.toFloat() / points.size
        val antiRatio = antiDiagonal.toFloat() / points.size
        return when {
            aspect >= 1.70f -> RedGlyph.STOP
            aspect in 0.65f..1.45f && mainRatio >= 0.40f && antiRatio >= 0.40f -> RedGlyph.X
            else -> RedGlyph.UNKNOWN
        }
    }

    private fun ratio(
        pixels: IntArray,
        width: Int,
        rect: Rect,
        predicate: (Float, Float, Float) -> Boolean
    ): Float {
        val hsv = FloatArray(3)
        var accepted = 0
        var total = 0
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
            Color.colorToHSV(pixels[y * width + x], hsv)
            if (predicate(hsv[0], hsv[1], hsv[2])) accepted++
            total++
        }
        return accepted.toFloat() / total.coerceAtLeast(1)
    }

    private fun isPanelDark(h: Float, s: Float, v: Float) = v <= 0.32f
    private fun isDjiYellow(h: Float, s: Float, v: Float) =
        h in 32f..70f && s >= 0.50f && v >= 0.45f
    private fun isBrightNeutral(h: Float, s: Float, v: Float) = s <= 0.22f && v >= 0.68f

    private fun normalizedRect(
        width: Int, height: Int, left: Float, top: Float, right: Float, bottom: Float
    ) = Rect(
        (width * left).toInt().coerceIn(0, width - 1),
        (height * top).toInt().coerceIn(0, height - 1),
        (width * right).toInt().coerceIn(1, width),
        (height * bottom).toInt().coerceIn(1, height)
    )

    private fun toSource(rect: Rect, sw: Int, sh: Int, width: Int, height: Int) = Rect(
        (rect.left * sw.toFloat() / width).toInt(),
        (rect.top * sh.toFloat() / height).toInt(),
        (rect.right * sw.toFloat() / width).toInt(),
        (rect.bottom * sh.toFloat() / height).toInt()
    )

    private fun inflateWithin(rect: Rect, width: Int, height: Int, amount: Int) = Rect(
        (rect.left - amount).coerceAtLeast(0),
        (rect.top - amount).coerceAtLeast(0),
        (rect.right + amount).coerceAtMost(width),
        (rect.bottom + amount).coerceAtMost(height)
    )
}
