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
        val red = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
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

        if (commandStartX != null && commandStartY != null && commandEndY != null) {
            canvas.drawCircle(commandStartX, commandStartY, 15f * scale, command)
            canvas.drawLine(commandStartX, commandStartY, commandStartX, commandEndY, command)
            canvas.drawCircle(commandStartX, commandEndY, 15f * scale, command)
        }

        val result = if (decision.redLowerLimit) "FOUND" else "NONE"
        val coordinate = if (decision.knobX != null && decision.knobY != null) {
            String.format(Locale.US, "x=%.1f y=%.1f", decision.knobX, decision.knobY)
        } else "x=UNKNOWN y=UNKNOWN"
        val action = when {
            decision.redLowerLimit -> "BOTTOM LIMIT • NO MOVEMENT"
            commandStartX != null -> String.format(
                Locale.US,
                "GESTURE X %.1f%% • Y %.1f%%->%.1f%% • HOLD %dms + DRAG %dms",
                commandStartX / source.width * 100f,
                commandStartY!! / source.height * 100f,
                commandEndY!! / source.height * 100f,
                holdMs,
                dragMs)
            else -> decision.status
        }
        val lines = listOf(
            stage,
            "RED LIMIT $result • $coordinate • confirm $confirmationCount/$confirmationRequired",
            action)
        val lineHeight = 39f * scale
        canvas.drawRect(0f, 0f, source.width.toFloat(), 18f * scale +
            lineHeight * lines.size, background)
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, 20f * scale, 35f * scale + index * lineHeight, text)
        }
        return output
    }
}
