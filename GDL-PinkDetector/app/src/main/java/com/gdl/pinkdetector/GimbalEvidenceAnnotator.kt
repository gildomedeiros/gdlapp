package com.gdl.pinkdetector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.util.Locale
import kotlin.math.max

/** Draws only diagnostic evidence; these pixels never feed the detector. */
object GimbalEvidenceAnnotator {
    fun annotate(
        source: Bitmap,
        decision: GimbalKnobDetector.Decision,
        stage: String,
        confirmationCount: Int,
        confirmationRequired: Int,
        holdMs: Long,
        dragMs: Long,
        commandStartX: Float? = null,
        commandStartY: Float? = null,
        commandEndY: Float? = null
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val scale = max(0.7f, source.width / 2048f)
        val stroke = 4f * scale
        val strip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00BFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when (decision.color) {
                GimbalKnobDetector.KnobColor.RED -> Color.RED
                GimbalKnobDetector.KnobColor.WHITE -> Color.WHITE
                else -> Color.GREEN
            }
            style = Paint.Style.STROKE
            strokeWidth = 6f * scale
        }
        val command = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00BFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 7f * scale
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 29f * scale
            isFakeBoldText = true
        }
        val background = Paint().apply { color = 0xBB000000.toInt() }

        val left = source.width * (GimbalKnobDetector.FIXED_X -
            GimbalKnobDetector.STRIP_HALF_WIDTH)
        val right = source.width * (GimbalKnobDetector.FIXED_X +
            GimbalKnobDetector.STRIP_HALF_WIDTH)
        val top = source.height * GimbalKnobDetector.SEARCH_TOP
        val bottom = source.height * GimbalKnobDetector.SEARCH_BOTTOM
        canvas.drawRect(RectF(left, top, right, bottom), strip)
        canvas.drawRect(
            RectF(left, source.height * GimbalKnobDetector.RED_LIMIT_TOP,
                right, source.height * GimbalKnobDetector.RED_LIMIT_BOTTOM), red)

        decision.knobRect?.let { canvas.drawRect(it, knob) }
        if (decision.knobX != null && decision.knobY != null) {
            canvas.drawCircle(
                decision.knobX, decision.knobY,
                max(18f * scale, (decision.knobRect?.width() ?: 8f) * 1.5f), knob)
        }

        if (commandStartX != null && commandStartY != null && commandEndY != null) {
            canvas.drawCircle(commandStartX, commandStartY, 15f * scale, command)
            canvas.drawLine(commandStartX, commandStartY, commandStartX, commandEndY, command)
            canvas.drawCircle(commandStartX, commandEndY, 15f * scale, command)
        }

        val colour = decision.color?.name ?: "NONE"
        val coordinate = if (decision.knobX != null && decision.knobY != null) {
            String.format(Locale.US, "x=%.1f y=%.1f", decision.knobX, decision.knobY)
        } else "x=UNKNOWN y=UNKNOWN"
        val action = when {
            decision.redLowerLimit -> "BOTTOM LIMIT • NO MOVEMENT"
            commandStartX != null -> "HOLD ${holdMs}ms + DRAG ${dragMs}ms"
            else -> "NO DRAG"
        }
        val lines = listOf(
            stage,
            "KNOB $colour • $coordinate • confirm $confirmationCount/$confirmationRequired",
            "FIXED X ${(GimbalKnobDetector.FIXED_X * 100).toInt()}% • $action")
        val lineHeight = 39f * scale
        canvas.drawRect(0f, 0f, source.width.toFloat(), 18f * scale +
            lineHeight * lines.size, background)
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, 20f * scale, 35f * scale + index * lineHeight, text)
        }
        return output
    }
}
