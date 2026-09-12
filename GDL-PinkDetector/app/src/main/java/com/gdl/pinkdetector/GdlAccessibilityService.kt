package com.gdl.pinkdetector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Configurable screenshot -> detector stages -> decision -> action -> evidence pipeline. */
class GdlAccessibilityService : AccessibilityService() {
    private enum class CombinedPhase { REACQUIRE, ACTIVE_TRACK, COMPLETE }

    companion object {
        private const val DJI_FLY_PACKAGE = "dji.go.v5"
        private const val SAMSUNG_GALLERY_PACKAGE = "com.sec.android.gallery3d"
        private const val OVERLAY_HIDE_DELAY_MS = 50L
        private const val GIMBAL_KNOB_HOLD_MS = 2_000L
        private const val GIMBAL_DOWN_DRAG_MS = 6_000L
        private const val GIMBAL_BETWEEN_DRAGS_MS = 6_000L
        private const val GIMBAL_KNOB_CONFIRMATIONS_REQUIRED = 2
        private const val GIMBAL_TEST_COUNTDOWN_MS = 10_000L
        private const val SLOW_STAGE_CAPTURE_INTERVAL_MS = 1_000L
        private const val TELEMETRY_INTERVAL_MS = 1_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val telemetryWorker = Executors.newSingleThreadExecutor()
    private val screenshotInFlight = AtomicBoolean(false)
    private lateinit var windowManager: WindowManager
    private var overlayView: DetectionOverlayView? = null
    private var sessionFolder = "Pictures/GDL/PENDING"
    private var activeRunToken: String? = null
    private var hardSavedFrame = -1
    private var frameCount = 0
    private var savedCount = 0
    private var cooldownUntilMs = 0L
    private var activeTrackLastEvaluationMs = 0L
    private var activeTrackCooldownUntilMs = 0L
    private var activeTrackCycleStartedMs = 0L
    private var activeTrackTapCount = 0
    private var chevronTapCount = 0
    private var activeTrackCycleLocked = false
    private var activeTrackCycleResult = "IDLE"
    private var activeTrackTerminalElapsedMs: Long? = null
    private var activeTrackSawNonExpandedAfterSuccess = false
    private var combinedPhase = CombinedPhase.REACQUIRE
    private var combinedSawNoPlusSinceComplete = true
    private var gimbalTestCountdownStartedMs = 0L
    private var gimbalNextDragAllowedMs = 0L
    private var gimbalKnobConfirmations = 0
    private var gimbalBottomReached = false
    private var reacquireNoPlusStartedMs = 0L
    private var productionGimbalRecoveryArmed = false
    private var productionGimbalRecoveryActive = false
    private var productionGimbalBottomReached = false
    private var productionGimbalRedConfirmations = 0
    @Volatile private var productionGimbalPendingOutcomeStage: String? = null
    private val gimbalGestureInFlight = AtomicBoolean(false)
    private val latestTelemetry = AtomicReference(DjiTelemetryReader.Values.UNKNOWN)
    private val telemetryInFlight = AtomicBoolean(false)
    private var telemetryLastScheduledMs = 0L

    private val captureLoop = object : Runnable {
        override fun run() {
            captureIfReady()
            val settings = GdlTestSettings.load(this@GdlAccessibilityService)
            val interval = currentCaptureIntervalMs(settings)
            mainHandler.postDelayed(this, interval)
        }
    }

    private fun currentCaptureIntervalMs(settings: ReacquireSettings): Long = when {
        settings.preset == ReacquirePreset.GIMBAL_TEST -> SLOW_STAGE_CAPTURE_INTERVAL_MS
        settings.preset == ReacquirePreset.ACTIVE_TRACK_PANEL -> SLOW_STAGE_CAPTURE_INTERVAL_MS
        settings.preset == ReacquirePreset.ACTIVE_TRACK_REAL -> SLOW_STAGE_CAPTURE_INTERVAL_MS
        settings.preset == ReacquirePreset.COMBINED_REAL &&
            combinedPhase == CombinedPhase.COMPLETE -> SLOW_STAGE_CAPTURE_INTERVAL_MS
        // The combined coordinator keeps the high Re-acquire rate because a
        // validated + must interrupt ActiveTrack waiting immediately. Its
        // stable ActiveTrack panel classifier is independently throttled.
        else -> settings.captureIntervalMs
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
        overlayView?.showMessage("GDL RE-ACQUIRE READY • arm in app", Color.WHITE)
        mainHandler.post(captureLoop)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        telemetryWorker.shutdownNow()
        DjiTelemetryReader.close()
        removeOverlay()
        super.onDestroy()
    }

    private fun captureIfReady() {
        if (!GdlTestSettings.isEnabled(this)) {
            cooldownUntilMs = 0L
            activeTrackLastEvaluationMs = 0L
            activeTrackCooldownUntilMs = 0L
            resetActiveTrackCycle()
            combinedPhase = CombinedPhase.REACQUIRE
            combinedSawNoPlusSinceComplete = true
            resetGimbalTest()
            overlayView?.showMessage("GDL RE-ACQUIRE STOPPED", Color.WHITE)
            return
        }
        val settings = GdlTestSettings.load(this)
        ensureRunSession(settings)
        if (!GdlTestSettings.isReady(this)) {
            cooldownUntilMs = 0L
            activeTrackLastEvaluationMs = 0L
            activeTrackCooldownUntilMs = 0L
            resetActiveTrackCycle()
            combinedPhase = CombinedPhase.REACQUIRE
            combinedSawNoPlusSinceComplete = true
            resetGimbalTest()
            overlayView?.showMessage("GDL ARMING • three-second safety delay", Color.YELLOW)
            return
        }
        if (!foregroundMatches(settings.foreground)) {
            resetGuardDependentGimbalCountdown(settings)
            overlayView?.showMessage("ARMED • waiting for ${settings.foreground.label}", Color.YELLOW)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            overlayView?.showMessage("GDL: Android 11+ required", Color.RED)
            return
        }
        djiFullscreenGuardFailure(settings)?.let { message ->
            resetGuardDependentGimbalCountdown(settings)
            overlayView?.showMessage(message, Color.YELLOW)
            return
        }
        if (!screenshotInFlight.compareAndSet(false, true)) return
        overlayView?.visibility = View.INVISIBLE
        mainHandler.postDelayed({ captureOnAndroid11Plus() }, OVERLAY_HIDE_DELAY_MS)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureOnAndroid11Plus() {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                val bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                buffer.close()
                if (bitmap == null) {
                    finishCapture("GDL: screenshot conversion failed", Color.RED)
                    return
                }
                worker.execute {
                    val retained = try { processScreenshot(bitmap) } catch (error: Throwable) {
                        saveHardRaw(
                            bitmap, GdlTestSettings.load(this@GdlAccessibilityService),
                            "S90_PIPELINE_ERROR")
                        postMessage("PIPELINE ERROR • ${error.javaClass.simpleName}", Color.RED)
                        false
                    }
                    if (!retained) bitmap.recycle()
                    screenshotInFlight.set(false)
                }
            }

            override fun onFailure(errorCode: Int) = finishCapture("SCREENSHOT FAILED • $errorCode", Color.RED)
        })
    }

    /** True means the asynchronous real-tap callback owns the bitmap. */
    private fun processScreenshot(bitmap: Bitmap): Boolean {
        val settings = GdlTestSettings.load(this)
        frameCount++
        scheduleTelemetryRead(bitmap)
        if (settings.preset == ReacquirePreset.GIMBAL_TEST) {
            return processGimbalTest(bitmap, settings, SystemClock.elapsedRealtime())
        }
        if (settings.preset == ReacquirePreset.RAW_DJI) {
            saveRawFrame(bitmap, settings.saveGreenMask, "S00_RAW_DIAGNOSTIC")
            return false
        }

        val now = SystemClock.elapsedRealtime()
        if ((productionGimbalRecoveryActive ||
                productionGimbalPendingOutcomeStage != null) &&
            isProductionGimbalPreset(settings)) {
            return processProductionGimbalRecovery(bitmap, settings, now)
        }
        val combined = settings.preset == ReacquirePreset.COMBINED_REAL
        if (combined && combinedPhase != CombinedPhase.ACTIVE_TRACK) {
            return processCombinedReacquire(bitmap, settings, now)
        }
        if (settings.activeTrackAction != ActiveTrackAction.OFF) {
            if (now - activeTrackLastEvaluationMs < settings.activeTrackRetryIntervalMs) {
                if (combined) {
                    val plusDecision = ReacquirePipeline.evaluate(bitmap, settings)
                    if (plusDecision.selected) {
                        if (now < cooldownUntilMs) {
                            saveHardRaw(bitmap, settings, "S03_PLUS_RETRY_COOLDOWN")
                            postMessage("VALIDATED + STILL VISIBLE • retry cooldown", Color.YELLOW)
                            return false
                        }
                        resetActiveTrackCycle()
                        combinedPhase = CombinedPhase.REACQUIRE
                        return processCombinedReacquire(bitmap, settings, now, plusDecision)
                    }
                }
                saveHardRaw(bitmap, settings, "S04_AT_CHECK_INTERVAL_WAIT")
                postMessage("ACTIVETRACK PANEL TEST • waiting for next check", Color.YELLOW)
                return false
            }
            if (settings.activeTrackAction == ActiveTrackAction.REAL_TAP &&
                (settings.foreground != ForegroundTarget.DJI_FLY ||
                    !settings.requireDjiFullscreen)) {
                saveHardRaw(bitmap, settings, "S90_AT_GUARD_BLOCKED")
                postMessage("ACTIVETRACK TAP BLOCKED • DJI full-screen required", Color.RED)
                return false
            }
            activeTrackLastEvaluationMs = now
            val activeTrackDecision = ActiveTrackPanelDetector.evaluate(
                bitmap, settings.expandCollapsedControls)

            // In the combined flow a validated + always means reacquire again.
            // ACTIVE_TRACK_RUNNING has priority because it is the positive
            // terminal proof; otherwise the unchanged ReacquirePipeline keeps
            // validating + throughout ActiveTrack confirmation.
            if (combined && activeTrackDecision.state !=
                ActiveTrackPanelDetector.State.ACTIVE_TRACK_RUNNING) {
                val plusDecision = ReacquirePipeline.evaluate(bitmap, settings)
                if (plusDecision.selected) {
                    if (now < cooldownUntilMs) {
                        saveHardRaw(bitmap, settings, "S03_PLUS_RETRY_COOLDOWN")
                        postMessage("VALIDATED + STILL VISIBLE • retry cooldown", Color.YELLOW)
                        return false
                    }
                    resetActiveTrackCycle()
                    combinedPhase = CombinedPhase.REACQUIRE
                    return processCombinedReacquire(bitmap, settings, now, plusDecision)
                }
                // The ActiveTrack timeout starts only after the + has cleared.
                if (activeTrackCycleStartedMs == 0L) {
                    activeTrackCycleStartedMs = now
                    activeTrackCycleResult = "RUNNING"
                }
            }

            // The non-touching Gallery test may run repeatedly in one session.
            // A successful running frame itself supplies the required
            // non-expanded transition; only a later expanded panel rearms it.
            val shouldAutoRearmSimulation =
                settings.activeTrackAction == ActiveTrackAction.SIMULATED_TAP &&
                settings.autoRearmSimulatedActiveTrack &&
                activeTrackCycleLocked && activeTrackCycleResult == "SUCCESS" &&
                activeTrackSawNonExpandedAfterSuccess &&
                activeTrackDecision.state == ActiveTrackPanelDetector.State.EXPANDED_PANEL
            if (shouldAutoRearmSimulation) resetActiveTrackCycle()

            val actionableState = activeTrackDecision.state ==
                ActiveTrackPanelDetector.State.EXPANDED_PANEL ||
                activeTrackDecision.state == ActiveTrackPanelDetector.State.COLLAPSED_CHEVRON
            if (settings.activeTrackAction != ActiveTrackAction.DETECTION_ONLY &&
                actionableState && activeTrackDecision.hasProposedTap &&
                activeTrackCycleStartedMs == 0L && !activeTrackCycleLocked) {
                activeTrackCycleStartedMs = now
                activeTrackCycleResult = "RUNNING"
            }
            val liveElapsedMs = if (activeTrackCycleStartedMs == 0L) 0L
                else now - activeTrackCycleStartedMs
            if (activeTrackDecision.state == ActiveTrackPanelDetector.State.ACTIVE_TRACK_RUNNING) {
                activeTrackCycleLocked = true
                activeTrackCycleResult = "SUCCESS"
                if (activeTrackTerminalElapsedMs == null) {
                    activeTrackTerminalElapsedMs = liveElapsedMs
                }
                activeTrackSawNonExpandedAfterSuccess = true
                if (combined) {
                    combinedPhase = CombinedPhase.COMPLETE
                    combinedSawNoPlusSinceComplete = false
                }
            }
            if (!activeTrackCycleLocked && activeTrackCycleStartedMs != 0L &&
                liveElapsedMs >= settings.activeTrackCycleTimeoutMs) {
                activeTrackCycleLocked = true
                activeTrackCycleResult = "TIMEOUT"
                activeTrackTerminalElapsedMs = liveElapsedMs
                if (combined) {
                    combinedPhase = CombinedPhase.COMPLETE
                    combinedSawNoPlusSinceComplete = false
                }
            }

            val tapKind = when (activeTrackDecision.state) {
                ActiveTrackPanelDetector.State.EXPANDED_PANEL -> "ACTIVETRACK"
                ActiveTrackPanelDetector.State.COLLAPSED_CHEVRON -> "CHEVRON"
                else -> null
            }
            val limitReached = when (tapKind) {
                "ACTIVETRACK" -> activeTrackTapCount >= settings.maxActiveTrackTaps
                "CHEVRON" -> chevronTapCount >= settings.maxChevronTaps
                else -> false
            }
            if (!activeTrackCycleLocked && limitReached) {
                activeTrackCycleLocked = true
                activeTrackCycleResult = "${tapKind}_LIMIT"
                activeTrackTerminalElapsedMs = liveElapsedMs
                if (combined) {
                    combinedPhase = CombinedPhase.COMPLETE
                    combinedSawNoPlusSinceComplete = false
                }
            }
            val elapsedMs = activeTrackTerminalElapsedMs ?: liveElapsedMs
            val cooldownActive = now < activeTrackCooldownUntilMs
            val allowProposedTap = activeTrackDecision.hasProposedTap &&
                !activeTrackCycleLocked && !limitReached && !cooldownActive
            if (settings.activeTrackAction == ActiveTrackAction.SIMULATED_TAP && allowProposedTap) {
                if (tapKind == "ACTIVETRACK") activeTrackTapCount++
                if (tapKind == "CHEVRON") chevronTapCount++
            }
            val cycleStatus = when {
                cooldownActive && !activeTrackCycleLocked -> "TAP_COOLDOWN"
                else -> activeTrackCycleResult
            }
            val stage = activeTrackStage(activeTrackDecision, cycleStatus)
            saveHardRaw(bitmap, settings, stage)
            val shouldSaveActiveTrack =
                (settings.saveActiveTrackEvidence || settings.hardSaveEveryCapturedFrame) &&
                (settings.hardSaveEveryCapturedFrame ||
                    settings.savePolicy == SavePolicy.EVERY_FRAME ||
                    (settings.savePolicy == SavePolicy.DECISIONS &&
                        (activeTrackDecision.state != ActiveTrackPanelDetector.State.UNKNOWN ||
                            activeTrackCycleLocked)))
            if (shouldSaveActiveTrack) {
                saveActiveTrackEvidence(
                    bitmap, activeTrackDecision, settings.activeTrackAction,
                    cycleStatus, elapsedMs, activeTrackTapCount, chevronTapCount,
                    settings.maxActiveTrackTaps, settings.maxChevronTaps,
                    allowProposedTap, stage)
            }
            if (settings.activeTrackAction == ActiveTrackAction.REAL_TAP &&
                allowProposedTap) {
                val sourceX = activeTrackDecision.targetX ?: return false
                val sourceY = activeTrackDecision.targetY ?: return false
                val forbidden = activeTrackDecision.exclusionRects.any {
                    it.contains(sourceX.toInt(), sourceY.toInt())
                }
                if (forbidden) {
                    activeTrackCycleLocked = true
                    activeTrackCycleResult = "EXCLUSION_BLOCK"
                    postMessage("ACTIVETRACK TAP BLOCKED • red X/Stop exclusion", Color.RED)
                    return false
                }
                activeTrackCooldownUntilMs =
                    now + maxOf(800L, settings.activeTrackRetryIntervalMs)
                mainHandler.post {
                    overlayView?.visibility = View.VISIBLE
                    val current = GdlTestSettings.load(this)
                    val appStillMatches = foregroundMatches(ForegroundTarget.DJI_FLY)
                    val fullscreenFailure = if (appStillMatches &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        djiFullscreenGuardFailure(current)
                    } else "GDL: Android 11+ required"
                    val stillAllowed = GdlTestSettings.isReady(this) &&
                        current.activeTrackAction == ActiveTrackAction.REAL_TAP &&
                        current.foreground == ForegroundTarget.DJI_FLY &&
                        current.requireDjiFullscreen &&
                        appStillMatches && fullscreenFailure == null
                    if (!stillAllowed) {
                        activeTrackCooldownUntilMs = 0L
                        bitmap.recycle()
                        overlayView?.showMessage(
                            fullscreenFailure ?: "ACTIVETRACK TAP CANCELLED",
                            Color.RED)
                        return@post
                    }
                    val point = overlayView?.mapSourcePointToView(
                        sourceX, sourceY, bitmap.width, bitmap.height)
                    if (point == null) {
                        activeTrackCooldownUntilMs = 0L
                        bitmap.recycle()
                        overlayView?.showMessage("ACTIVETRACK COORDINATE MAP FAILED", Color.RED)
                        return@post
                    }
                    val accepted = dispatchTap(
                        point.first, point.second, current.tapDurationMs) { completed ->
                        if (!completed) activeTrackCooldownUntilMs = 0L
                        bitmap.recycle()
                        postMessage(
                            if (completed) "ACTIVETRACK TAP ✓ • ${activeTrackDecision.status}"
                            else "ACTIVETRACK TAP FAILED",
                            if (completed) 0xFF00BFFF.toInt() else Color.RED)
                    }
                    if (accepted) {
                        if (tapKind == "ACTIVETRACK") activeTrackTapCount++
                        if (tapKind == "CHEVRON") chevronTapCount++
                        overlayView?.showTarget(
                            activeTrackDecision.targetRect,
                            bitmap.width, bitmap.height,
                            point.first, point.second,
                            "PRESSING • ${activeTrackDecision.status}", true)
                    } else {
                        activeTrackCooldownUntilMs = 0L
                        bitmap.recycle()
                        overlayView?.showMessage("ACTIVETRACK TAP REJECTED", Color.RED)
                    }
                }
                return true
            }
            postActiveTrackDecision(
                activeTrackDecision, settings.activeTrackAction, bitmap.width, bitmap.height,
                allowProposedTap, cycleStatus, elapsedMs, settings)
            return false
        }

        if (settings.action == ReacquireAction.REAL_TAP && now < cooldownUntilMs) {
            saveHardRaw(bitmap, settings, "S03_PLUS_TAP_COOLDOWN")
            postMessage("${settings.foreground.label} ✓ • tap cooldown", Color.YELLOW)
            return false
        }

        val decision = ReacquirePipeline.evaluate(bitmap, settings)
        if (!decision.selected && processNoPlusGimbalTimeout(bitmap, settings, now)) {
            return false
        }
        if (decision.selected) resetProductionGimbalRecovery(rearm = true)
        val stage = reacquireStage(settings, decision)
        saveHardRaw(bitmap, settings, stage)
        val shouldSave = settings.hardSaveEveryCapturedFrame ||
            settings.savePolicy == SavePolicy.EVERY_FRAME ||
            (settings.savePolicy == SavePolicy.DECISIONS && decision.selected)

        when (settings.action) {
            ReacquireAction.OFF -> {
                postMessage("RE-ACQUIRE OFF", Color.WHITE)
                return false
            }
            ReacquireAction.DETECTION_ONLY -> {
                if (shouldSave) saveEvidence(bitmap, decision, settings.action, stage)
                postDecision(decision, settings.action, bitmap.width, bitmap.height)
                return false
            }
            ReacquireAction.SIMULATED_TAP -> {
                if (shouldSave) saveEvidence(bitmap, decision, settings.action, stage)
                postDecision(decision, settings.action, bitmap.width, bitmap.height)
                return false
            }
            ReacquireAction.REAL_TAP -> {
                // Defence in depth if preferences are edited outside the UI:
                // a real touch may target only a decision produced from the
                // validated DJI plus detector, never a free-standing pink blob.
                if (settings.candidateRule == CandidateRule.STRONGEST_PINK_BLOB) {
                    postMessage("REAL TAP BLOCKED • validated + required", Color.RED)
                    return false
                }
                if (!decision.selected) {
                    if (shouldSave) saveEvidence(bitmap, decision, settings.action, stage)
                    postDecision(decision, settings.action, bitmap.width, bitmap.height)
                    return false
                }
                val sourceX = decision.selectedX ?: return false
                val sourceY = decision.selectedY ?: return false
                val frameFolder = sessionFolder
                val frameNumber = frameCount
                cooldownUntilMs = now + settings.retryCooldownMs
                mainHandler.post {
                    overlayView?.visibility = View.VISIBLE
                    val appStillMatches = foregroundMatches(settings.foreground)
                    val fullscreenFailure = if (appStillMatches &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        djiFullscreenGuardFailure(settings)
                    } else "GDL: Android 11+ required"
                    if (!GdlTestSettings.isReady(this) || !appStillMatches ||
                        fullscreenFailure != null) {
                        cooldownUntilMs = 0L
                        bitmap.recycle()
                        overlayView?.showMessage(
                            if (!appStillMatches) "APP CHANGED • tap cancelled"
                            else fullscreenFailure ?: "TAP CANCELLED",
                            if (!appStillMatches) Color.RED else Color.YELLOW)
                        return@post
                    }
                    val point = overlayView?.mapSourcePointToView(
                        sourceX, sourceY, bitmap.width, bitmap.height)
                    if (point == null) {
                        cooldownUntilMs = 0L
                        bitmap.recycle()
                        overlayView?.showMessage("COORDINATE MAP FAILED", Color.RED)
                        return@post
                    }
                    val accepted = dispatchTap(point.first, point.second, settings.tapDurationMs) { completed ->
                        if (!completed) cooldownUntilMs = 0L
                        worker.execute {
                            if (completed && shouldSave) saveEvidence(
                                bitmap, decision, settings.action,
                                "S03_PLUS_TAP_COMPLETED", frameFolder, frameNumber)
                            if (completed && isValidatedGreenPlusWithPink(decision, settings)) {
                                productionGimbalRecoveryArmed = true
                            }
                            bitmap.recycle()
                            postMessage(
                                if (completed) "REAL TAP ✓ • ${decision.status}" else "REAL TAP FAILED",
                                if (completed) 0xFF00BFFF.toInt() else Color.RED)
                        }
                    }
                    if (accepted) {
                        overlayView?.showTarget(decision.selectedRect, bitmap.width, bitmap.height,
                            point.first, point.second, "PRESSING SELECTED +", true)
                    } else {
                        cooldownUntilMs = 0L
                        bitmap.recycle()
                        overlayView?.showMessage("REAL TAP REJECTED", Color.RED)
                    }
                }
                return true
            }
        }
    }

    /**
     * v0.15.5 coordinator. It reuses the unchanged ReacquirePipeline, retries
     * a persistent + without a fixed attempt cap, and starts the independent
     * ActiveTrack state machine only after a completed + tap.
     */
    private fun processCombinedReacquire(
        bitmap: Bitmap,
        settings: ReacquireSettings,
        now: Long,
        suppliedDecision: ReacquireDecision? = null
    ): Boolean {
        if (now < cooldownUntilMs) {
            saveHardRaw(bitmap, settings, "S03_PLUS_RETRY_COOLDOWN")
            postMessage("COMBINED • waiting for validated + • tap cooldown", Color.YELLOW)
            return false
        }
        val decision = suppliedDecision ?: ReacquirePipeline.evaluate(bitmap, settings)
        if (!decision.selected) {
            if (combinedPhase == CombinedPhase.COMPLETE) {
                combinedSawNoPlusSinceComplete = true
            }
            val phase = if (combinedPhase == CombinedPhase.COMPLETE) {
                "${combinedTerminalLabel()} • waiting for a new validated +"
            } else {
                "RE-ACQUIRE • waiting for validated +"
            }
            val stage = if (combinedPhase == CombinedPhase.COMPLETE) {
                "S11_WAITING_FOR_NEXT_VALIDATED_PLUS"
            } else "S01_REACQUIRE_WAITING"
            // The timer is inert in initial S01 because production recovery is
            // not armed until a validated pink-associated + tap completes.
            // Once armed, a raw + without the required pink association must
            // not prevent the lost-subject recovery timer in S11.
            if (processNoPlusGimbalTimeout(bitmap, settings, now)) return false
            saveHardRaw(bitmap, settings, stage)
            if (settings.hardSaveEveryCapturedFrame ||
                settings.savePolicy == SavePolicy.EVERY_FRAME) {
                saveEvidence(bitmap, decision, settings.action, stage)
            }
            postMessage("$phase • ${decision.status}", Color.YELLOW)
            return false
        }

        resetProductionGimbalRecovery(rearm = true)

        if (combinedPhase == CombinedPhase.COMPLETE && !combinedSawNoPlusSinceComplete) {
            val stage = "S10_EXISTING_PLUS_MUST_CLEAR"
            saveHardRaw(bitmap, settings, stage)
            if (settings.hardSaveEveryCapturedFrame ||
                settings.savePolicy != SavePolicy.NONE) {
                saveEvidence(bitmap, decision, settings.action, stage)
            }
            postMessage(
                "${combinedTerminalLabel()} • existing + must clear before a new cycle",
                Color.YELLOW)
            return false
        }

        val sourceX = decision.selectedX ?: return false
        val sourceY = decision.selectedY ?: return false
        val frameFolder = sessionFolder
        val frameNumber = frameCount
        // Combined + retries are intentionally unlimited. The configurable
        // retry cooldown controls their pace; the v0.15.5 combined preset
        // defaults it to 800 ms.
        cooldownUntilMs = now + settings.retryCooldownMs
        saveHardRaw(bitmap, settings, "S02_PLUS_SELECTED")
        mainHandler.post {
            overlayView?.visibility = View.VISIBLE
            val current = GdlTestSettings.load(this)
            val appStillMatches = foregroundMatches(ForegroundTarget.DJI_FLY)
            val fullscreenFailure = if (appStillMatches &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                djiFullscreenGuardFailure(current)
            } else "GDL: Android 11+ required"
            val stillAllowed = GdlTestSettings.isReady(this) &&
                current.preset == ReacquirePreset.COMBINED_REAL &&
                current.action == ReacquireAction.REAL_TAP &&
                current.activeTrackAction == ActiveTrackAction.REAL_TAP &&
                appStillMatches && fullscreenFailure == null
            if (!stillAllowed) {
                cooldownUntilMs = 0L
                bitmap.recycle()
                overlayView?.showMessage(
                    fullscreenFailure ?: "COMBINED + TAP CANCELLED", Color.RED)
                return@post
            }
            val point = overlayView?.mapSourcePointToView(
                sourceX, sourceY, bitmap.width, bitmap.height)
            if (point == null) {
                cooldownUntilMs = 0L
                bitmap.recycle()
                overlayView?.showMessage("COMBINED COORDINATE MAP FAILED", Color.RED)
                return@post
            }
            val accepted = dispatchTap(
                point.first, point.second, current.tapDurationMs) { completed ->
                worker.execute {
                    if (completed) {
                        if (current.savePolicy != SavePolicy.NONE ||
                            current.hardSaveEveryCapturedFrame) {
                            saveEvidence(
                                bitmap, decision, current.action,
                                "S03_PLUS_TAP_COMPLETED", frameFolder, frameNumber)
                        }
                        // Production recovery must never start before take-off/setup.
                        // Arm it only after a real tap completed on a + that was
                        // selected by the configured green-plus + pink association.
                        if (isValidatedGreenPlusWithPink(decision, current)) {
                            productionGimbalRecoveryArmed = true
                        }
                        resetActiveTrackCycle()
                        activeTrackCycleResult = "WAITING_FOR_PLUS_CLEAR"
                        combinedPhase = CombinedPhase.ACTIVE_TRACK
                        combinedSawNoPlusSinceComplete = false
                    } else {
                        cooldownUntilMs = 0L
                    }
                    bitmap.recycle()
                    postMessage(
                        if (completed) "COMBINED + TAP ✓ • selecting ActiveTrack"
                        else "COMBINED + TAP FAILED • waiting for validated +",
                        if (completed) 0xFF00BFFF.toInt() else Color.RED)
                }
            }
            if (accepted) {
                overlayView?.showTarget(
                    decision.selectedRect, bitmap.width, bitmap.height,
                    point.first, point.second,
                    "PRESSING VALIDATED + • next: ActiveTrack", true)
            } else {
                cooldownUntilMs = 0L
                bitmap.recycle()
                overlayView?.showMessage("COMBINED + TAP REJECTED", Color.RED)
            }
        }
        return true
    }

    /** Isolated automatic down-only gimbal test. Normal knob/scale detection is not a gate. */
    private fun processGimbalTest(
        bitmap: Bitmap,
        settings: ReacquireSettings,
        now: Long
    ): Boolean {
        if (gimbalBottomReached) {
            saveGimbalFrame(bitmap, settings, "S24_GIMBAL_BOTTOM_LIMIT_REACHED")
            postMessage("GIMBAL BOTTOM LIMIT REACHED • no further movement", Color.YELLOW)
            return false
        }
        val decision = GimbalKnobDetector.evaluate(bitmap)
        if (decision.redLowerLimit) {
            gimbalKnobConfirmations++
            val confirmed = gimbalKnobConfirmations >= GIMBAL_KNOB_CONFIRMATIONS_REQUIRED
            if (confirmed) gimbalBottomReached = true
            val stage = if (confirmed) "S24_GIMBAL_BOTTOM_LIMIT_REACHED"
                else "S23_GIMBAL_BOTTOM_LIMIT_CONFIRMING"
            saveGimbalFrame(bitmap, settings, stage, decision)
            postMessage(
                if (confirmed) "GIMBAL BOTTOM LIMIT REACHED • no further movement"
                else "GIMBAL RED LIMIT • confirming 1/2",
                Color.YELLOW)
            return false
        }
        gimbalKnobConfirmations = 0

        if (gimbalTestCountdownStartedMs == 0L) gimbalTestCountdownStartedMs = now
        val countdownRemaining = GIMBAL_TEST_COUNTDOWN_MS - (now - gimbalTestCountdownStartedMs)
        if (countdownRemaining > 0L) {
            saveGimbalFrame(bitmap, settings, "S20_GIMBAL_TEN_SECOND_COUNTDOWN")
            postMessage("GIMBAL TEST • starts in ${countdownRemaining}ms", Color.YELLOW)
            return false
        }

        if (gimbalGestureInFlight.get()) {
            saveGimbalFrame(bitmap, settings, "S26_GIMBAL_GESTURE_IN_PROGRESS")
            postMessage("GIMBAL TEST • gesture in progress", 0xFF00BFFF.toInt())
            return false
        }

        if (now < gimbalNextDragAllowedMs) {
            saveGimbalFrame(bitmap, settings, "S26_GIMBAL_SIX_SECOND_INTERVAL")
            postMessage("GIMBAL TEST • waiting for DJI wheel to disappear", Color.YELLOW)
            return false
        }

        // Keep the continuous hold/drag on DJI's dedicated gimbal lane. The
        // former 50% start could overlap central landing/RTH controls.
        val startX = bitmap.width * GimbalKnobDetector.GESTURE_START_X
        val startY = bitmap.height * GimbalKnobDetector.GESTURE_START_Y
        val endY = bitmap.height * GimbalKnobDetector.DRAG_BOTTOM_Y
        val commandDecision = decision.copy(status = "AUTOMATIC HOLD + DOWN DRAG")

        saveGimbalFrame(
            bitmap, settings, "S22_GIMBAL_HOLD_THEN_DRAG_DOWN", commandDecision,
            startX, startY, endY)
        dispatchGimbalDrag(
            startX, startY, startX, endY,
            bitmap.width, bitmap.height, GIMBAL_DOWN_DRAG_MS, "DOWN 6s",
            productionRecovery = false)
        return false
    }

    private fun isProductionGimbalPreset(settings: ReacquireSettings): Boolean =
        settings.gimbalRecoveryEnabled &&
            (settings.preset == ReacquirePreset.PRODUCTION ||
                settings.preset == ReacquirePreset.COMBINED_REAL)

    /** Returns true once this frame belongs to the recovery state machine. */
    private fun processNoPlusGimbalTimeout(
        bitmap: Bitmap,
        settings: ReacquireSettings,
        now: Long
    ): Boolean {
        if (!isProductionGimbalPreset(settings) || !productionGimbalRecoveryArmed ||
            productionGimbalBottomReached) return false
        if (reacquireNoPlusStartedMs == 0L) reacquireNoPlusStartedMs = now
        if (now - reacquireNoPlusStartedMs < settings.noGreenPlusGimbalTimeoutMs) return false
        productionGimbalRecoveryActive = true
        processProductionGimbalRecovery(bitmap, settings, now)
        return true
    }

    private fun processProductionGimbalRecovery(
        bitmap: Bitmap,
        settings: ReacquireSettings,
        now: Long
    ): Boolean {
        productionGimbalPendingOutcomeStage?.let { outcomeStage ->
            productionGimbalPendingOutcomeStage = null
            val message = when {
                outcomeStage.contains("GUARD_FAILED") ->
                    "GIMBAL DRAG CANCELLED • guard failed"
                outcomeStage.contains("MAP_FAILED") ->
                    "GIMBAL DRAG CANCELLED • coordinate map failed"
                outcomeStage.contains("REJECTED") ->
                    "GIMBAL DRAG REJECTED"
                outcomeStage.contains("FAILED") ->
                    "GIMBAL DRAG FAILED"
                else -> "GIMBAL DRAG COMPLETE • rest 6s"
            }
            val outcomeDecision = GimbalKnobDetector.evaluate(bitmap).copy(status = message)
            saveGimbalFrame(bitmap, settings, outcomeStage, outcomeDecision)
            postMessage(message,
                if (outcomeStage.contains("COMPLETED")) 0xFF00BFFF.toInt() else Color.RED)
            return false
        }

        val decision = GimbalKnobDetector.evaluate(bitmap)
        if (decision.redLowerLimit) {
            productionGimbalRedConfirmations++
            val confirmed = productionGimbalRedConfirmations >=
                GIMBAL_KNOB_CONFIRMATIONS_REQUIRED
            if (confirmed) {
                productionGimbalBottomReached = true
                // A dispatched Accessibility gesture cannot be shortened safely.
                // Keep ignoring green + until its callback reports completion.
                if (!gimbalGestureInFlight.get()) productionGimbalRecoveryActive = false
                reacquireNoPlusStartedMs = 0L
            }
            val stage = if (confirmed) "S15_REACQUIRE_GIMBAL_BOTTOM_LIMIT_REACHED"
                else "S14_REACQUIRE_GIMBAL_BOTTOM_LIMIT_CONFIRMING"
            saveGimbalFrame(
                bitmap, settings, stage, decision,
                confirmationCount = productionGimbalRedConfirmations)
            postMessage(
                if (confirmed) "GIMBAL AT -90° • searching for validated green +"
                else "GIMBAL RED LIMIT • confirming 1/2",
                Color.YELLOW)
            return false
        }
        productionGimbalRedConfirmations = 0

        if (gimbalGestureInFlight.get()) {
            saveGimbalFrame(bitmap, settings, "S13_REACQUIRE_GIMBAL_GESTURE_IN_PROGRESS")
            postMessage("GIMBAL RECOVERY • gesture in progress", 0xFF00BFFF.toInt())
            return false
        }
        if (now < gimbalNextDragAllowedMs) {
            saveGimbalFrame(bitmap, settings, "S16_REACQUIRE_GIMBAL_SIX_SECOND_INTERVAL")
            postMessage("GIMBAL RECOVERY • waiting for DJI wheel to disappear", Color.YELLOW)
            return false
        }

        val startX = bitmap.width * GimbalKnobDetector.GESTURE_START_X
        val startY = bitmap.height * GimbalKnobDetector.GESTURE_START_Y
        val endY = bitmap.height * GimbalKnobDetector.DRAG_BOTTOM_Y
        val commandDecision = decision.copy(status = "PRODUCTION GIMBAL RECOVERY")
        saveGimbalFrame(
            bitmap, settings, "S12_REACQUIRE_GIMBAL_GESTURE_REQUESTED",
            commandDecision, startX, startY, endY)
        dispatchGimbalDrag(
            startX, startY, startX, endY, bitmap.width, bitmap.height,
            GIMBAL_DOWN_DRAG_MS, "RECOVERY DOWN 6s",
            productionRecovery = true)
        return false
    }

    private fun resetGimbalKnobConfirmation() {
        gimbalKnobConfirmations = 0
    }

    private fun dispatchGimbalDrag(
        sourceStartX: Float,
        sourceStartY: Float,
        sourceEndX: Float,
        sourceEndY: Float,
        sourceWidth: Int,
        sourceHeight: Int,
        durationMs: Long,
        label: String,
        productionRecovery: Boolean
    ) {
        if (!gimbalGestureInFlight.compareAndSet(false, true)) return
        // Reserve the continuous hold, drag and mandatory six-second rest.
        gimbalNextDragAllowedMs = SystemClock.elapsedRealtime() +
            GIMBAL_KNOB_HOLD_MS + durationMs + GIMBAL_BETWEEN_DRAGS_MS
        mainHandler.post {
            overlayView?.visibility = View.VISIBLE
            val settings = GdlTestSettings.load(this)
            if (!gimbalGestureAllowed(settings)) {
                gimbalGestureInFlight.set(false)
                gimbalNextDragAllowedMs = 0L
                if (productionRecovery) {
                    productionGimbalRecoveryActive = false
                    reacquireNoPlusStartedMs = 0L
                    productionGimbalPendingOutcomeStage =
                        "S18_REACQUIRE_GIMBAL_GESTURE_CANCELLED_GUARD_FAILED"
                }
                overlayView?.showMessage("GIMBAL DRAG CANCELLED • guard failed", Color.RED)
                return@post
            }
            val start = overlayView?.mapSourcePointToView(
                sourceStartX, sourceStartY, sourceWidth, sourceHeight)
            val end = overlayView?.mapSourcePointToView(
                sourceEndX, sourceEndY, sourceWidth, sourceHeight)
            if (start == null || end == null) {
                gimbalGestureInFlight.set(false)
                gimbalNextDragAllowedMs = 0L
                if (productionRecovery) {
                    productionGimbalRecoveryActive = false
                    reacquireNoPlusStartedMs = 0L
                    productionGimbalPendingOutcomeStage =
                        "S18_REACQUIRE_GIMBAL_GESTURE_CANCELLED_MAP_FAILED"
                }
                overlayView?.showMessage("GIMBAL DRAG MAP FAILED", Color.RED)
                return@post
            }
            val accepted = dispatchHoldThenDrag(
                start.first, start.second, end.first, end.second,
                GIMBAL_KNOB_HOLD_MS, durationMs) { completed ->
                gimbalGestureInFlight.set(false)
                if (productionRecovery) {
                    productionGimbalPendingOutcomeStage = if (completed) {
                        "S17_REACQUIRE_GIMBAL_GESTURE_COMPLETED"
                    } else {
                        "S17_REACQUIRE_GIMBAL_GESTURE_FAILED"
                    }
                }
                if (productionGimbalBottomReached) {
                    productionGimbalRecoveryActive = false
                    gimbalNextDragAllowedMs = 0L
                } else {
                    gimbalNextDragAllowedMs = SystemClock.elapsedRealtime() +
                        GIMBAL_BETWEEN_DRAGS_MS
                }
                postMessage(
                    if (productionGimbalBottomReached) {
                        "GIMBAL AT -90° • searching for validated green +"
                    } else if (completed) "GIMBAL $label COMPLETE • rest 6s"
                    else "GIMBAL $label FAILED • rest 6s",
                    if (completed) 0xFF00BFFF.toInt() else Color.RED)
            }
            if (!accepted) {
                gimbalGestureInFlight.set(false)
                if (productionRecovery) {
                    gimbalNextDragAllowedMs = 0L
                    productionGimbalRecoveryActive = false
                    reacquireNoPlusStartedMs = 0L
                    productionGimbalPendingOutcomeStage =
                        "S18_REACQUIRE_GIMBAL_GESTURE_REJECTED"
                    overlayView?.showMessage("GIMBAL $label REJECTED", Color.RED)
                } else {
                    gimbalNextDragAllowedMs = SystemClock.elapsedRealtime() +
                        GIMBAL_BETWEEN_DRAGS_MS
                    overlayView?.showMessage("GIMBAL $label REJECTED • rest 6s", Color.RED)
                }
            } else {
                overlayView?.showMessage(
                    "GIMBAL HOLDING 2s THEN DRAGGING $label", 0xFF00BFFF.toInt())
            }
        }
    }

    private fun gimbalGestureAllowed(settings: ReacquireSettings): Boolean {
        val allowedPreset = settings.preset == ReacquirePreset.GIMBAL_TEST ||
            (isProductionGimbalPreset(settings) && productionGimbalRecoveryActive &&
                (settings.preset != ReacquirePreset.COMBINED_REAL ||
                    combinedPhase != CombinedPhase.ACTIVE_TRACK))
        if (!GdlTestSettings.isReady(this) || !allowedPreset ||
            settings.foreground != ForegroundTarget.DJI_FLY ||
            !settings.requireDjiFullscreen ||
            !foregroundMatches(ForegroundTarget.DJI_FLY) ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return djiFullscreenGuardFailure(settings) == null
    }

    private fun saveGimbalFrame(
        bitmap: Bitmap,
        settings: ReacquireSettings,
        stage: String,
        decision: GimbalKnobDetector.Decision? = null,
        commandStartX: Float? = null,
        commandStartY: Float? = null,
        commandEndY: Float? = null,
        confirmationCount: Int = gimbalKnobConfirmations
    ) {
        val saveRaw = settings.savePolicy == SavePolicy.EVERY_FRAME ||
            settings.hardSaveEveryCapturedFrame
        val saveAnnotated = decision != null &&
            (settings.savePolicy != SavePolicy.NONE || settings.hardSaveEveryCapturedFrame)
        if (!saveRaw && !saveAnnotated) return
        if (hardSavedFrame == frameCount) return
        hardSavedFrame = frameCount
        val sequence = String.format(Locale.US, "%05d", frameCount)
        if (saveRaw && saveBitmap(bitmap, "F${sequence}_${stage}_RAW.jpg",
                "image/jpeg", Bitmap.CompressFormat.JPEG, 96)) savedCount++
        if (saveAnnotated) {
            val annotated = GimbalEvidenceAnnotator.annotate(
                bitmap, decision!!, stage,
                confirmationCount.coerceAtMost(GIMBAL_KNOB_CONFIRMATIONS_REQUIRED),
                GIMBAL_KNOB_CONFIRMATIONS_REQUIRED,
                GIMBAL_KNOB_HOLD_MS, GIMBAL_DOWN_DRAG_MS,
                commandStartX, commandStartY, commandEndY)
            try {
                if (saveBitmap(annotated, "F${sequence}_${stage}_ANNOTATED.jpg",
                        "image/jpeg", Bitmap.CompressFormat.JPEG, 95)) savedCount++
            } finally {
                annotated.recycle()
            }
        }
    }

    private fun scheduleTelemetryRead(bitmap: Bitmap) {
        val now = SystemClock.elapsedRealtime()
        if (now - telemetryLastScheduledMs < TELEMETRY_INTERVAL_MS ||
            !telemetryInFlight.compareAndSet(false, true)) return
        telemetryLastScheduledMs = now
        val crop = try {
            DjiTelemetryReader.cropTelemetry(bitmap)
        } catch (_: Throwable) {
            telemetryInFlight.set(false)
            return
        }
        try {
            telemetryWorker.execute {
                DjiTelemetryReader.recognize(crop) { values ->
                    latestTelemetry.set(values)
                    telemetryInFlight.set(false)
                }
            }
        } catch (_: Throwable) {
            crop.recycle()
            telemetryInFlight.set(false)
        }
    }

    private fun evidenceNameWithTelemetry(name: String): String {
        if (name.startsWith("F00000_")) return name
        val values = latestTelemetry.get()
        val suffix = "_ALT_${values.altitude}_DIST_${values.distance}"
        val marker = listOf("_RAW.", "_ANNOTATED.", "_MASK.")
            .firstOrNull { name.contains(it) }
        return if (marker != null) name.replace(marker, "$suffix$marker")
        else {
            val dot = name.lastIndexOf('.')
            if (dot > 0) name.substring(0, dot) + suffix + name.substring(dot)
            else name + suffix
        }
    }

    private fun resetGimbalTest() {
        gimbalTestCountdownStartedMs = 0L
        gimbalNextDragAllowedMs = 0L
        resetGimbalKnobConfirmation()
        gimbalBottomReached = false
        gimbalGestureInFlight.set(false)
        productionGimbalRecoveryArmed = false
        productionGimbalPendingOutcomeStage = null
        resetProductionGimbalRecovery(rearm = false)
    }

    private fun isValidatedGreenPlusWithPink(
        decision: ReacquireDecision,
        settings: ReacquireSettings
    ): Boolean = decision.selected && decision.pluses.isNotEmpty() &&
        settings.detectGreenPlus && settings.detectPink &&
        settings.candidateRule == CandidateRule.STRONGEST_PINK_PLUS &&
        decision.associations.any { it.pinkPixelCount >= settings.minimumPinkPixels }

    private fun resetProductionGimbalRecovery(rearm: Boolean) {
        reacquireNoPlusStartedMs = 0L
        productionGimbalRecoveryActive = false
        productionGimbalBottomReached = false
        productionGimbalRedConfirmations = 0
        if (rearm) gimbalNextDragAllowedMs = 0L
    }

    private fun resetGuardDependentGimbalCountdown(settings: ReacquireSettings) {
        if (settings.preset == ReacquirePreset.GIMBAL_TEST &&
            !gimbalGestureInFlight.get() && !gimbalBottomReached) {
            gimbalTestCountdownStartedMs = 0L
        }
        if (isProductionGimbalPreset(settings) && !productionGimbalRecoveryActive) {
            reacquireNoPlusStartedMs = 0L
        }
    }

    private fun postDecision(
        decision: ReacquireDecision,
        action: ReacquireAction,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        mainHandler.post {
            overlayView?.visibility = View.VISIBLE
            if (decision.selected && action == ReacquireAction.SIMULATED_TAP) {
                val point = overlayView?.mapSourcePointToView(
                    decision.selectedX ?: 0f, decision.selectedY ?: 0f,
                    sourceWidth, sourceHeight)
                if (point != null) overlayView?.showTarget(
                    decision.selectedRect, sourceWidth, sourceHeight,
                    point.first, point.second, "SIMULATED TAP • ${decision.status}", true)
            } else {
                if (decision.selected) overlayView?.showTarget(
                    decision.selectedRect, sourceWidth, sourceHeight, 0f, 0f,
                    decision.status, false)
                else overlayView?.showMessage(decision.status, Color.YELLOW)
            }
        }
    }

    private fun postActiveTrackDecision(
        decision: ActiveTrackPanelDetector.Decision,
        action: ActiveTrackAction,
        sourceWidth: Int,
        sourceHeight: Int,
        allowProposedTap: Boolean,
        cycleStatus: String,
        elapsedMs: Long,
        settings: ReacquireSettings
    ) {
        mainHandler.post {
            overlayView?.visibility = View.VISIBLE
            val cycle = "cycle $cycleStatus ${elapsedMs}/${settings.activeTrackCycleTimeoutMs}ms " +
                "AT $activeTrackTapCount/${settings.maxActiveTrackTaps} " +
                "CH $chevronTapCount/${settings.maxChevronTaps}"
            val message = "${decision.status} • $cycle"
            if (action == ActiveTrackAction.SIMULATED_TAP && allowProposedTap) {
                val point = overlayView?.mapSourcePointToView(
                    decision.targetX ?: 0f, decision.targetY ?: 0f, sourceWidth, sourceHeight)
                if (point != null) {
                    overlayView?.showTarget(
                        decision.targetRect, sourceWidth, sourceHeight,
                        point.first, point.second,
                        "SIMULATED • $message", true)
                }
            } else if (decision.targetRect != null) {
                overlayView?.showTarget(
                    decision.targetRect, sourceWidth, sourceHeight,
                    0f, 0f, message, false)
            } else {
                overlayView?.showMessage(message, Color.YELLOW)
            }
        }
    }

    private fun dispatchTap(x: Float, y: Float, durationMs: Long, result: (Boolean) -> Unit): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0L, durationMs)).build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = result(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = result(false)
        }, null)
    }

    private fun dispatchDrag(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        result: (Boolean) -> Unit
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0L, durationMs)).build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = result(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = result(false)
        }, null)
    }

    /**
     * Keeps one pointer down across both strokes: first stationary on DJI's
     * detected knob, then moving vertically. A pair of independent gestures
     * would release between the hold and drag and DJI could interpret the
     * second gesture as a subject-selection box.
     */
    private fun dispatchHoldThenDrag(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        holdMs: Long,
        dragMs: Long,
        result: (Boolean) -> Unit
    ): Boolean {
        val holdPath = Path().apply { moveTo(startX, startY) }
        val holdStroke = GestureDescription.StrokeDescription(
            holdPath, 0L, holdMs, true)
        val holdGesture = GestureDescription.Builder().addStroke(holdStroke).build()
        return dispatchGesture(holdGesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                val dragPath = Path().apply {
                    moveTo(startX, startY)
                    lineTo(endX, endY)
                }
                val dragStroke = holdStroke.continueStroke(
                    dragPath, 0L, dragMs, false)
                val dragGesture = GestureDescription.Builder().addStroke(dragStroke).build()
                val accepted = dispatchGesture(
                    dragGesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) =
                            result(true)

                        override fun onCancelled(gestureDescription: GestureDescription?) =
                            result(false)
                    },
                    null)
                if (!accepted) result(false)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) = result(false)
        }, null)
    }

    private fun saveEvidence(
        bitmap: Bitmap,
        decision: ReacquireDecision,
        action: ReacquireAction,
        stage: String,
        folder: String = sessionFolder,
        frameNumber: Int = frameCount
    ) {
        val annotated = EvidenceAnnotator.annotate(bitmap, decision, action)
        try {
            val sequence = String.format(Locale.US, "%05d", frameNumber)
            if (saveBitmap(annotated, "F${sequence}_${stage}_ANNOTATED.jpg",
                    "image/jpeg", Bitmap.CompressFormat.JPEG, 95, folder)) savedCount++
            if (GdlTestSettings.load(this).saveGreenMask) {
                val mask = DjiGreenPlusDetector.createGreenMask(bitmap)
                try { saveBitmap(mask, "F${sequence}_${stage}_MASK.png",
                    "image/png", Bitmap.CompressFormat.PNG, 100, folder) }
                finally { mask.recycle() }
            }
        } finally { annotated.recycle() }
    }

    private fun saveActiveTrackEvidence(
        bitmap: Bitmap,
        decision: ActiveTrackPanelDetector.Decision,
        action: ActiveTrackAction,
        cycleStatus: String,
        elapsedMs: Long,
        activeTrackTaps: Int,
        chevronTaps: Int,
        maxActiveTrackTaps: Int,
        maxChevronTaps: Int,
        markTap: Boolean,
        stage: String
    ) {
        val annotated = ActiveTrackEvidenceAnnotator.annotate(
            bitmap, decision, action, cycleStatus, elapsedMs,
            activeTrackTaps, chevronTaps, maxActiveTrackTaps, maxChevronTaps, markTap)
        try {
            val sequence = String.format(Locale.US, "%05d", frameCount)
            if (saveBitmap(
                    annotated, "F${sequence}_${stage}_ANNOTATED.jpg",
                    "image/jpeg", Bitmap.CompressFormat.JPEG, 95
                )) savedCount++
        } finally {
            annotated.recycle()
        }
    }

    private fun saveRawFrame(bitmap: Bitmap, withMask: Boolean, stage: String) {
        val sequence = String.format(Locale.US, "%05d", frameCount)
        val rawSaved = saveBitmap(bitmap, "F${sequence}_${stage}_RAW.jpg",
            "image/jpeg", Bitmap.CompressFormat.JPEG, 96)
        var maskSaved = true
        if (withMask) {
            val mask = DjiGreenPlusDetector.createGreenMask(bitmap)
            maskSaved = try { saveBitmap(mask, "F${sequence}_${stage}_MASK.png",
                "image/png", Bitmap.CompressFormat.PNG, 100) } finally { mask.recycle() }
        }
        if (rawSaved) savedCount++
        postMessage("RAW frame $frameCount • saved $savedCount${if (withMask && !maskSaved) " • mask failed" else ""}",
            if (rawSaved && maskSaved) 0xFF00BFFF.toInt() else Color.RED)
    }

    private fun saveHardRaw(bitmap: Bitmap, settings: ReacquireSettings, stage: String) {
        if (!settings.hardSaveEveryCapturedFrame || hardSavedFrame == frameCount) return
        hardSavedFrame = frameCount
        val sequence = String.format(Locale.US, "%05d", frameCount)
        if (saveBitmap(bitmap, "F${sequence}_${stage}_RAW.jpg",
                "image/jpeg", Bitmap.CompressFormat.JPEG, 96)) savedCount++
    }

    private fun activeTrackStage(
        decision: ActiveTrackPanelDetector.Decision,
        cycleStatus: String
    ): String {
        return when (cycleStatus) {
            "TIMEOUT" -> "S09_AT_TIMEOUT"
            "ACTIVETRACK_LIMIT" -> "S09_ACTIVE_TRACK_SECTION_TAP_LIMIT"
            "CHEVRON_LIMIT" -> "S09_CHEVRON_TAP_LIMIT"
            "EXCLUSION_BLOCK" -> "S90_AT_EXCLUSION_BLOCK"
            else -> when (decision.state) {
                ActiveTrackPanelDetector.State.EXPANDED_PANEL ->
                    if (cycleStatus == "TAP_COOLDOWN") "S05_AT_EXPANDED_TAP_COOLDOWN"
                    else "S05_AT_EXPANDED"
                ActiveTrackPanelDetector.State.COLLAPSED_CHEVRON ->
                    if (cycleStatus == "TAP_COOLDOWN")
                        "S06_AT_COLLAPSED_CHEVRON_TAP_COOLDOWN"
                    else "S06_AT_COLLAPSED_CHEVRON"
                ActiveTrackPanelDetector.State.ACTIVE_TRACK_ALREADY_SELECTED ->
                    "S07_AT_ALREADY_SELECTED"
                ActiveTrackPanelDetector.State.ACTIVE_TRACK_RUNNING ->
                    "S08_AT_RUNNING_CONFIRMED"
                ActiveTrackPanelDetector.State.UNKNOWN -> "S04_AT_UNKNOWN"
            }
        }
    }

    private fun reacquireStage(
        settings: ReacquireSettings,
        decision: ReacquireDecision
    ): String = when (settings.preset) {
        ReacquirePreset.PINK_DETECTOR ->
            if (decision.selected) "S02_PINK_SELECTED" else "S01_PINK_WAITING"
        ReacquirePreset.PINK_PLUS_SIMULATION ->
            if (decision.selected) "S02_PINK_PLUS_SELECTED" else "S01_PINK_PLUS_WAITING"
        else ->
            if (decision.selected) "S02_PLUS_SELECTED" else "S01_REACQUIRE_WAITING"
    }

    private fun combinedTerminalLabel(): String = when (activeTrackCycleResult) {
        "SUCCESS" -> "ACTIVETRACK SUCCESS"
        "TIMEOUT" -> "ACTIVETRACK TIMEOUT"
        "ACTIVETRACK_LIMIT" -> "ACTIVE TRACK SECTION TAP LIMIT"
        "CHEVRON_LIMIT" -> "CHEVRON TAP LIMIT"
        else -> "ACTIVETRACK COMPLETE"
    }

    private fun saveSettingsSnapshot(
        settings: ReacquireSettings,
        startedAtMs: Long,
        folder: String
    ) {
        val started = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
            .format(Date(startedAtMs))
        val text = buildString {
            appendLine("GDL selected settings")
            appendLine("version=${GdlTestSettings.APP_VERSION}")
            appendLine("started=$started")
            appendLine("folder=$folder")
            appendLine("preset=${settings.preset.name} (${settings.preset.label})")
            appendLine("foreground=${settings.foreground.name}")
            appendLine("requireDjiFullscreen=${settings.requireDjiFullscreen}")
            appendLine("detectGreenPlus=${settings.detectGreenPlus}")
            appendLine("detectPink=${settings.detectPink}")
            appendLine("candidateRule=${settings.candidateRule.name}")
            appendLine("reacquireAction=${settings.action.name}")
            appendLine("savePolicy=${settings.savePolicy.name}")
            appendLine("hardSaveEveryCapturedFrame=${settings.hardSaveEveryCapturedFrame}")
            appendLine("reacquireSearchIntervalMs=${settings.captureIntervalMs}")
            appendLine("reacquireSearchFps=${String.format(Locale.US, "%.1f", 1_000.0 / settings.captureIntervalMs)}")
            appendLine("activeTrackCaptureIntervalMs=$SLOW_STAGE_CAPTURE_INTERVAL_MS")
            appendLine("gimbalCaptureIntervalMs=$SLOW_STAGE_CAPTURE_INTERVAL_MS")
            appendLine("retryCooldownMs=${settings.retryCooldownMs}")
            appendLine("tapDurationMs=${settings.tapDurationMs}")
            appendLine("minimumPinkPixels=${settings.minimumPinkPixels}")
            appendLine("pinkSearchRadiusMultiplier=${settings.pinkSearchRadiusMultiplier}")
            appendLine("saveGreenMask=${settings.saveGreenMask}")
            appendLine("activeTrackAction=${settings.activeTrackAction.name}")
            appendLine("expandCollapsedControls=${settings.expandCollapsedControls}")
            appendLine("activeTrackRetryIntervalMs=${settings.activeTrackRetryIntervalMs}")
            appendLine("activeTrackCycleTimeoutMs=${settings.activeTrackCycleTimeoutMs}")
            appendLine("maxActiveTrackTaps=${settings.maxActiveTrackTaps}")
            appendLine("maxChevronTaps=${settings.maxChevronTaps}")
            appendLine("saveActiveTrackEvidence=${settings.saveActiveTrackEvidence}")
            appendLine("autoRearmSimulatedActiveTrack=${settings.autoRearmSimulatedActiveTrack}")
            appendLine("gimbalRecoveryEnabled=${settings.gimbalRecoveryEnabled}")
            appendLine("noGreenPlusGimbalTimeoutMs=${settings.noGreenPlusGimbalTimeoutMs}")
            appendLine("gimbalGestureStartNormalized=${GimbalKnobDetector.GESTURE_START_X},${GimbalKnobDetector.GESTURE_START_Y}")
            appendLine("gimbalGestureEndYNormalized=${GimbalKnobDetector.DRAG_BOTTOM_Y}")
            appendLine("gimbalHoldMs=$GIMBAL_KNOB_HOLD_MS")
            appendLine("gimbalDownDragMs=$GIMBAL_DOWN_DRAG_MS")
            appendLine("gimbalBetweenDragsMs=$GIMBAL_BETWEEN_DRAGS_MS")
            appendLine("gimbalNormalKnobDetection=false")
            appendLine("gimbalRedLimitConfirmations=$GIMBAL_KNOB_CONFIRMATIONS_REQUIRED")
            appendLine("telemetryCropNormalizedX=0.105..0.22")
            appendLine("telemetryBrightBackgroundFallback=true")
            appendLine("telemetryRejectSpeedValues=true")
            appendLine("telemetryRequireDecimal=true")
            if (settings.preset == ReacquirePreset.GIMBAL_TEST) {
                appendLine("gimbalInitialTrigger=AUTOMATIC_AFTER_GUARD_COUNTDOWN")
                appendLine("gimbalGuardCountdownMs=$GIMBAL_TEST_COUNTDOWN_MS")
                appendLine("gimbalControlXNormalized=0.791")
                appendLine("gimbalDashedLineDetection=false")
                appendLine("gimbalGesture=CONTINUOUS_HOLD_THEN_DRAG")
                appendLine("gimbalRedLimitAction=STOP_NO_MOVEMENT")
            }
        }
        if (!saveTextFile("F00000_S00_SELECTED_SETTINGS.txt", text, folder)) {
            saveSettingsImage(text, folder)
        }
    }

    private fun saveTextFile(name: String, text: String, folder: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try { contentResolver.insert(collection, values) } catch (_: Throwable) { null }
            ?: return false
        return try {
            val written = contentResolver.openOutputStream(uri)?.use {
                it.write(text.toByteArray(Charsets.UTF_8)); true
            } ?: false
            if (written) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                true
            } else {
                contentResolver.delete(uri, null, null)
                false
            }
        } catch (_: Throwable) {
            contentResolver.delete(uri, null, null)
            false
        }
    }

    private fun saveSettingsImage(text: String, folder: String) {
        val lines = text.lines()
        val bitmap = Bitmap.createBitmap(1600, maxOf(900, 70 + lines.size * 46),
            Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
        }
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, 35f, 55f + index * 46f, paint)
        }
        try {
            saveBitmap(bitmap, "F00000_S00_SELECTED_SETTINGS.jpg",
                "image/jpeg", Bitmap.CompressFormat.JPEG, 95, folder)
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveBitmap(bitmap: Bitmap, name: String, mime: String,
                           format: Bitmap.CompressFormat, quality: Int,
                           folder: String = sessionFolder): Boolean {
        val finalName = evidenceNameWithTelemetry(name)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, finalName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, folder)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val written = contentResolver.openOutputStream(uri)?.use { bitmap.compress(format, quality, it) } ?: false
            if (written) {
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null); true
            } else { contentResolver.delete(uri, null, null); false }
        } catch (_: Throwable) { contentResolver.delete(uri, null, null); false }
    }

    private fun foregroundMatches(target: ForegroundTarget): Boolean {
        val packageName = rootInActiveWindow?.packageName?.toString()
        return when (target) {
            ForegroundTarget.DJI_FLY -> packageName == DJI_FLY_PACKAGE
            ForegroundTarget.SAMSUNG_GALLERY -> packageName == SAMSUNG_GALLERY_PACKAGE
            ForegroundTarget.ANY_APP -> !packageName.isNullOrBlank() && packageName != packageNameOfGdl()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun djiFullscreenGuardFailure(settings: ReacquireSettings): String? {
        if (!settings.requireDjiFullscreen || settings.foreground != ForegroundTarget.DJI_FLY) {
            return null
        }

        val metrics = windowManager.currentWindowMetrics
        if (metrics.windowInsets.isVisible(WindowInsets.Type.navigationBars())) {
            return "DJI Fly not full-screen • hide Android navigation bar"
        }

        // DJI Fly's accessibility window/root bounds are not the visual app bounds:
        // on Samsung devices they can omit DJI's own left and right control rails.
        // Comparing those bounds with the physical display therefore rejects a
        // genuinely full-screen DJI Fly window. Foreground package matching is
        // performed immediately before this method; hidden Android navigation
        // controls are the reliable full-screen signal available to the service.
        return null
    }

    private fun packageNameOfGdl() = applicationContext.packageName

    private fun resetActiveTrackCycle() {
        activeTrackCooldownUntilMs = 0L
        activeTrackCycleStartedMs = 0L
        activeTrackTapCount = 0
        chevronTapCount = 0
        activeTrackCycleLocked = false
        activeTrackCycleResult = "IDLE"
        activeTrackTerminalElapsedMs = null
        activeTrackSawNonExpandedAfterSuccess = false
    }

    private fun ensureRunSession(settings: ReacquireSettings) {
        val token = GdlTestSettings.runToken(this) ?: return
        if (token == activeRunToken) return
        activeRunToken = token
        val startedAt = GdlTestSettings.runStartedAtMs(this).takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val session = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(startedAt))
        sessionFolder = "Pictures/GDL/${session}_${settings.preset.name}"
        frameCount = 0
        savedCount = 0
        hardSavedFrame = -1
        latestTelemetry.set(DjiTelemetryReader.Values.UNKNOWN)
        telemetryLastScheduledMs = 0L
        cooldownUntilMs = 0L
        activeTrackLastEvaluationMs = 0L
        activeTrackCooldownUntilMs = 0L
        resetActiveTrackCycle()
        resetGimbalTest()
        combinedPhase = CombinedPhase.REACQUIRE
        combinedSawNoPlusSinceComplete = true
        val folder = sessionFolder
        worker.execute { saveSettingsSnapshot(settings, startedAt, folder) }
    }

    private fun finishCapture(message: String, color: Int) {
        screenshotInFlight.set(false)
        overlayView?.visibility = View.VISIBLE
        overlayView?.showMessage(message, color)
    }

    private fun postMessage(message: String, color: Int) = mainHandler.post {
        overlayView?.visibility = View.VISIBLE; overlayView?.showMessage(message, color)
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val view = DetectionOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(view, params); overlayView = view
    }

    private fun removeOverlay() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }; overlayView = null
    }

    private class DetectionOverlayView(context: android.content.Context) : View(context) {
        private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 6f
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 30f; isFakeBoldText = true
        }
        private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA000000.toInt() }
        private val tap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF00BFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 7f
        }
        private var target: Rect? = null
        var lastSourceWidth = 1
        var lastSourceHeight = 1
        private var message = "GDL READY"
        private var messageColor = Color.WHITE
        private var tapX = 0f
        private var tapY = 0f
        private var tapVisible = false

        fun showMessage(value: String, color: Int) {
            target = null; tapVisible = false; message = value; messageColor = color; invalidate()
        }

        fun showTarget(rect: Rect?, sourceWidth: Int, sourceHeight: Int,
                       x: Float, y: Float, value: String, showTap: Boolean) {
            target = rect?.let { Rect(it) }
            lastSourceWidth = sourceWidth.coerceAtLeast(1)
            lastSourceHeight = sourceHeight.coerceAtLeast(1)
            tapX = x; tapY = y; tapVisible = showTap
            message = value; messageColor = 0xFF00BFFF.toInt(); invalidate()
            if (showTap) postDelayed({ tapVisible = false; invalidate() }, 1_200L)
        }

        fun mapSourcePointToView(x: Float, y: Float, sourceWidth: Int, sourceHeight: Int) = Pair(
            x * width.toFloat() / sourceWidth.coerceAtLeast(1),
            y * height.toFloat() / sourceHeight.coerceAtLeast(1))

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val sx = width.toFloat() / lastSourceWidth
            val sy = height.toFloat() / lastSourceHeight
            target?.let { canvas.drawRect(it.left * sx, it.top * sy, it.right * sx, it.bottom * sy, box) }
            if (tapVisible) {
                canvas.drawCircle(tapX, tapY, 48f, tap)
                canvas.drawCircle(tapX, tapY, 11f, tap)
            }
            text.color = messageColor
            val labelWidth = text.measureText(message)
            canvas.drawRoundRect(12f, 12f, labelWidth + 42f, 72f, 12f, 12f, background)
            canvas.drawText(message, 26f, 53f, text)
        }
    }
}
