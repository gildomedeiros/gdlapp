package com.gdl.pinkdetector

import android.graphics.Bitmap
import android.graphics.Rect

data class ReacquireDecision(
    val pluses: List<DjiGreenPlusDetector.Detection> = emptyList(),
    val pinkBlobs: List<PinkBlobDetector.Blob> = emptyList(),
    val associations: List<PinkAssociationDetector.Association> = emptyList(),
    val selectedRect: Rect? = null,
    val selectedX: Float? = null,
    val selectedY: Float? = null,
    val selected: Boolean = false,
    val status: String
)

/** Composes independently testable stages without changing the validated detectors. */
object ReacquirePipeline {
    fun evaluate(bitmap: Bitmap, settings: ReacquireSettings): ReacquireDecision {
        val pluses = if (settings.detectGreenPlus) DjiGreenPlusDetector.findGreenPluses(bitmap)
        else emptyList()
        val blobs = if (settings.detectPink && settings.candidateRule == CandidateRule.STRONGEST_PINK_BLOB) {
            PinkBlobDetector.find(bitmap).filter {
                it.pinkPixelCount >= settings.minimumPinkPixels
            }
        } else emptyList()

        return when (settings.candidateRule) {
            CandidateRule.STRONGEST_PINK_BLOB -> {
                val winner = blobs.firstOrNull()
                ReacquireDecision(
                    pluses, blobs, selectedRect = winner?.rect,
                    selectedX = winner?.rect?.exactCenterX(), selectedY = winner?.rect?.exactCenterY(),
                    selected = winner != null,
                    status = if (winner == null) "pink blobs: ${blobs.size} • no winner"
                    else "pink blobs: ${blobs.size} • SELECTED ${winner.pinkPixelCount}px")
            }
            CandidateRule.STRONGEST_PINK_PLUS -> {
                val associations = if (settings.detectPink) {
                    PinkAssociationDetector.rankByStrongestPink(
                        bitmap, pluses, settings.pinkSearchRadiusMultiplier)
                } else emptyList()
                val winner = associations.firstOrNull {
                    it.pinkPixelCount >= settings.minimumPinkPixels
                }
                ReacquireDecision(
                    pluses, associations = associations, selectedRect = winner?.plus?.rect,
                    selectedX = winner?.plus?.centerX, selectedY = winner?.plus?.centerY,
                    selected = winner != null,
                    status = when {
                        pluses.isEmpty() -> "no validated green +"
                        winner == null -> "+: ${pluses.size} • no associated pink"
                        else -> "+: ${pluses.size} • SELECTED pink ${winner.pinkPixelCount}px"
                    })
            }
            CandidateRule.VALIDATED_PLUS_NO_PINK -> {
                // findGreenPluses() is already sorted by structural confidence.
                // Pink detection and pink association are deliberately bypassed.
                val winner = pluses.firstOrNull()
                ReacquireDecision(
                    pluses, selectedRect = winner?.rect,
                    selectedX = winner?.centerX, selectedY = winner?.centerY,
                    selected = winner != null,
                    status = if (winner == null) "no validated green +"
                    else "+: ${pluses.size} • SELECTED ${winner.confidencePercent}% • no pink required"
                )
            }
        }
    }
}
