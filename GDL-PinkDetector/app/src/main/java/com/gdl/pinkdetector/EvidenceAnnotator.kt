package com.gdl.pinkdetector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

/** Produces visual evidence: every candidate is scored and the winner is green. */
object EvidenceAnnotator {
    fun annotate(source: Bitmap, decision: ReacquireDecision, action: ReacquireAction): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val scale = (output.width / 2048f).coerceAtLeast(0.55f)
        val rejected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 5f * scale
        }
        val selected = Paint(rejected).apply { color = Color.GREEN; strokeWidth = 8f * scale }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 28f * scale; isFakeBoldText = true
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
        }

        decision.pinkBlobs.forEachIndexed { index, blob ->
            val chosen = decision.selectedRect == blob.rect
            canvas.drawRect(blob.rect, if (chosen) selected else rejected)
            label(canvas, blob.rect, "P${index + 1}: ${blob.pinkPixelCount}px${if (chosen) " SELECTED" else ""}", text)
        }
        if (decision.associations.isNotEmpty()) {
            decision.associations.forEachIndexed { index, association ->
                val chosen = decision.selectedRect == association.plus.rect
                canvas.drawRect(association.plus.rect, if (chosen) selected else rejected)
                label(canvas, association.plus.rect,
                    "+${index + 1}: pink ${association.pinkPixelCount} score ${association.weightedPinkScore.toInt()}${if (chosen) " SELECTED" else ""}", text)
            }
        } else if (decision.pluses.isNotEmpty()) {
            decision.pluses.forEachIndexed { index, plus ->
                val chosen = decision.selectedRect == plus.rect
                canvas.drawRect(plus.rect, if (chosen) selected else rejected)
                label(canvas, plus.rect,
                    "+${index + 1}: ${plus.confidencePercent}%${if (chosen) " SELECTED" else ""}", text)
            }
        }
        if (decision.selected && action == ReacquireAction.SIMULATED_TAP) {
            val x = decision.selectedX ?: 0f
            val y = decision.selectedY ?: 0f
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF00BFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 8f * scale
            }
            canvas.drawCircle(x, y, 48f * scale, ring)
            canvas.drawCircle(x, y, 10f * scale, ring)
        }
        text.textSize = 32f * scale
        canvas.drawText(decision.status, 24f * scale, 50f * scale, text)
        return output
    }

    private fun label(canvas: Canvas, rect: Rect, value: String, paint: Paint) {
        val y = (rect.top - 8f).coerceAtLeast(paint.textSize + 4f)
        canvas.drawText(value, rect.left.toFloat(), y, paint)
    }
}
