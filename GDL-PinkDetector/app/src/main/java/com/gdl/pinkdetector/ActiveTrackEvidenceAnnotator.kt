package com.gdl.pinkdetector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Annotates only the new ActiveTrack test; existing evidence remains unchanged. */
object ActiveTrackEvidenceAnnotator {
    fun annotate(
        source: Bitmap,
        decision: ActiveTrackPanelDetector.Decision,
        action: ActiveTrackAction,
        cycleStatus: String,
        elapsedMs: Long,
        activeTrackTaps: Int,
        chevronTaps: Int,
        maxActiveTrackTaps: Int,
        maxChevronTaps: Int,
        markTap: Boolean
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val scale = (source.width / 2048f).coerceAtLeast(0.5f)
        val target = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 6f * scale
        }
        val excluded = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 8f * scale
        }
        val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00BFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 7f * scale
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 30f * scale; isFakeBoldText = true
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }

        decision.targetRect?.let { canvas.drawRect(it, target) }
        decision.exclusionRects.forEach { canvas.drawRect(it, excluded) }
        if ((action == ActiveTrackAction.SIMULATED_TAP ||
                action == ActiveTrackAction.REAL_TAP) && decision.hasProposedTap && markTap) {
            canvas.drawCircle(decision.targetX!!, decision.targetY!!, 48f * scale, marker)
            canvas.drawCircle(decision.targetX!!, decision.targetY!!, 11f * scale, marker)
        }
        canvas.drawText(decision.status, 26f, 52f * scale, text)
        val metrics = "dark %.2f • yellow L %.3f M %.3f".format(
            decision.darkPanelRatio, decision.yellowLeftRatio, decision.yellowMiddleRatio)
        canvas.drawText(metrics, 26f, 88f * scale, text)
        val running = "running panel %.2f • red glyph %s".format(
            decision.runningPanelRatio, decision.redGlyph?.name ?: "NONE")
        canvas.drawText(running, 26f, 124f * scale, text)
        val cycle = "cycle $cycleStatus • ${elapsedMs}ms • AT $activeTrackTaps/$maxActiveTrackTaps • CH $chevronTaps/$maxChevronTaps"
        canvas.drawText(cycle, 26f, 160f * scale, text)
        return output
    }
}
