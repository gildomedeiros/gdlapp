package com.gdl.pinkdetector

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale
import kotlin.math.roundToLong

class MainActivity : AppCompatActivity() {
    private lateinit var accessibilityStatus: TextView
    private lateinit var testStatus: TextView
    private lateinit var flowExplanation: TextView
    private lateinit var toggleButton: Button
    private lateinit var presetSpinner: Spinner
    private lateinit var foregroundSpinner: Spinner
    private lateinit var candidateSpinner: Spinner
    private lateinit var actionSpinner: Spinner
    private lateinit var activeTrackActionSpinner: Spinner
    private lateinit var saveSpinner: Spinner
    private lateinit var requireDjiFullscreen: Switch
    private lateinit var detectPlus: Switch
    private lateinit var detectPink: Switch
    private lateinit var saveGreenMask: Switch
    private lateinit var hardSaveEveryFrame: Switch
    private lateinit var expandCollapsedControls: Switch
    private lateinit var saveActiveTrackEvidence: Switch
    private lateinit var autoRearmSimulatedActiveTrack: Switch
    private lateinit var gimbalRecoveryEnabled: Switch
    private lateinit var captureInterval: EditText
    private lateinit var retryCooldown: EditText
    private lateinit var tapDuration: EditText
    private lateinit var minimumPink: EditText
    private lateinit var pinkRadius: EditText
    private lateinit var activeTrackRetryInterval: EditText
    private lateinit var activeTrackCycleTimeout: EditText
    private lateinit var maxActiveTrackTaps: EditText
    private lateinit var maxChevronTaps: EditText
    private lateinit var noGreenPlusGimbalTimeout: EditText
    private var loadingUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) GdlTestSettings.setEnabled(this, false)
        setContentView(R.layout.activity_main)
        bindViews()
        configureSpinners()

        findViewById<Button>(R.id.openAccessibilitySettingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        presetSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            if (!loadingUi) showSettings(GdlTestSettings.applyPreset(this, ReacquirePreset.values()[position]))
        }
        configureExplanationUpdates()
        toggleButton.setOnClickListener {
            if (GdlTestSettings.isEnabled(this)) {
                GdlTestSettings.setEnabled(this, false)
            } else if (readAndSaveSettings(showConfirmation = false)) {
                GdlTestSettings.beginRun(this)
                GdlTestSettings.setEnabled(this, true)
            }
            updateStatus()
        }
        showSettings(GdlTestSettings.load(this))
    }

    override fun onResume() { super.onResume(); updateStatus() }

    private fun bindViews() {
        accessibilityStatus = findViewById(R.id.accessibilityStatusText)
        testStatus = findViewById(R.id.testStatusText)
        flowExplanation = findViewById(R.id.flowExplanationText)
        toggleButton = findViewById(R.id.toggleTestButton)
        presetSpinner = findViewById(R.id.presetSpinner)
        foregroundSpinner = findViewById(R.id.foregroundSpinner)
        candidateSpinner = findViewById(R.id.candidateSpinner)
        actionSpinner = findViewById(R.id.actionSpinner)
        activeTrackActionSpinner = findViewById(R.id.activeTrackActionSpinner)
        saveSpinner = findViewById(R.id.saveSpinner)
        requireDjiFullscreen = findViewById(R.id.requireDjiFullscreenSwitch)
        detectPlus = findViewById(R.id.detectPlusSwitch)
        detectPink = findViewById(R.id.detectPinkSwitch)
        saveGreenMask = findViewById(R.id.saveGreenMaskSwitch)
        hardSaveEveryFrame = findViewById(R.id.hardSaveEveryFrameSwitch)
        expandCollapsedControls = findViewById(R.id.expandCollapsedControlsSwitch)
        saveActiveTrackEvidence = findViewById(R.id.saveActiveTrackEvidenceSwitch)
        autoRearmSimulatedActiveTrack =
            findViewById(R.id.autoRearmSimulatedActiveTrackSwitch)
        gimbalRecoveryEnabled = findViewById(R.id.gimbalRecoveryEnabledSwitch)
        captureInterval = findViewById(R.id.captureIntervalEdit)
        retryCooldown = findViewById(R.id.retryCooldownEdit)
        tapDuration = findViewById(R.id.tapDurationEdit)
        minimumPink = findViewById(R.id.minimumPinkEdit)
        pinkRadius = findViewById(R.id.pinkRadiusEdit)
        activeTrackRetryInterval = findViewById(R.id.activeTrackRetryIntervalEdit)
        activeTrackCycleTimeout = findViewById(R.id.activeTrackCycleTimeoutEdit)
        maxActiveTrackTaps = findViewById(R.id.maxActiveTrackTapsEdit)
        maxChevronTaps = findViewById(R.id.maxChevronTapsEdit)
        noGreenPlusGimbalTimeout = findViewById(R.id.noGreenPlusGimbalTimeoutEdit)
    }

    private fun configureSpinners() {
        setup(presetSpinner, ReacquirePreset.values().map { it.label })
        setup(foregroundSpinner, ForegroundTarget.values().map { it.label })
        setup(candidateSpinner, CandidateRule.values().map { it.label })
        setup(actionSpinner, ReacquireAction.values().map { it.label })
        setup(activeTrackActionSpinner, ActiveTrackAction.values().map { it.label })
        setup(saveSpinner, SavePolicy.values().map { it.label })
    }

    private fun setup(spinner: Spinner, labels: List<String>) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    }

    private fun showSettings(value: ReacquireSettings) {
        loadingUi = true
        presetSpinner.setSelection(value.preset.ordinal)
        foregroundSpinner.setSelection(value.foreground.ordinal)
        candidateSpinner.setSelection(value.candidateRule.ordinal)
        actionSpinner.setSelection(value.action.ordinal)
        activeTrackActionSpinner.setSelection(value.activeTrackAction.ordinal)
        saveSpinner.setSelection(value.savePolicy.ordinal)
        requireDjiFullscreen.isChecked = value.requireDjiFullscreen
        detectPlus.isChecked = value.detectGreenPlus
        detectPink.isChecked = value.detectPink
        saveGreenMask.isChecked = value.saveGreenMask
        hardSaveEveryFrame.isChecked = value.hardSaveEveryCapturedFrame
        expandCollapsedControls.isChecked = value.expandCollapsedControls
        saveActiveTrackEvidence.isChecked = value.saveActiveTrackEvidence
        autoRearmSimulatedActiveTrack.isChecked = value.autoRearmSimulatedActiveTrack
        gimbalRecoveryEnabled.isChecked = value.gimbalRecoveryEnabled
        val reacquireFps = 1_000.0 / value.captureIntervalMs
        captureInterval.setText(
            if (reacquireFps % 1.0 == 0.0) String.format(Locale.US, "%.0f", reacquireFps)
            else String.format(Locale.US, "%.1f", reacquireFps))
        retryCooldown.setText(value.retryCooldownMs.toString())
        tapDuration.setText(value.tapDurationMs.toString())
        minimumPink.setText(value.minimumPinkPixels.toString())
        pinkRadius.setText(value.pinkSearchRadiusMultiplier.toString())
        activeTrackRetryInterval.setText(value.activeTrackRetryIntervalMs.toString())
        activeTrackCycleTimeout.setText(value.activeTrackCycleTimeoutMs.toString())
        maxActiveTrackTaps.setText(value.maxActiveTrackTaps.toString())
        maxChevronTaps.setText(value.maxChevronTaps.toString())
        noGreenPlusGimbalTimeout.setText(value.noGreenPlusGimbalTimeoutMs.toString())
        loadingUi = false
        updateStatus()
        updateFlowExplanation(value)
    }

    private fun readAndSaveSettings(showConfirmation: Boolean): Boolean {
        val value = settingsFromUi() ?: run {
            Toast.makeText(this, "Check the numeric settings.", Toast.LENGTH_LONG).show()
                        return false
        }
        if (value.action != ReacquireAction.OFF &&
            value.activeTrackAction != ActiveTrackAction.OFF &&
            value.preset != ReacquirePreset.COMBINED_REAL) {
            Toast.makeText(
                this,
                "Only the combined preset may run Re-acquire and ActiveTrack together.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        if (value.activeTrackAction == ActiveTrackAction.REAL_TAP &&
            (value.foreground != ForegroundTarget.DJI_FLY || !value.requireDjiFullscreen)) {
            Toast.makeText(
                this,
                "Real ActiveTrack requires DJI Fly and the full-screen guard.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        if (value.action == ReacquireAction.REAL_TAP && value.foreground != ForegroundTarget.DJI_FLY) {
            Toast.makeText(this, "Real tap is allowed only with the DJI Fly foreground guard.", Toast.LENGTH_LONG).show()
            return false
        }
        if (value.gimbalRecoveryEnabled &&
            value.preset != ReacquirePreset.PRODUCTION &&
            value.preset != ReacquirePreset.COMBINED_REAL) {
            Toast.makeText(
                this,
                "Gimbal recovery is available only in the two real DJI Re-acquire presets.",
                Toast.LENGTH_LONG).show()
            return false
        }
        if (value.gimbalRecoveryEnabled &&
            (value.foreground != ForegroundTarget.DJI_FLY || !value.requireDjiFullscreen)) {
            Toast.makeText(
                this, "Gimbal recovery requires DJI Fly and the full-screen guard.",
                Toast.LENGTH_LONG).show()
            return false
        }
        if (value.action == ReacquireAction.REAL_TAP &&
            value.candidateRule == CandidateRule.STRONGEST_PINK_BLOB) {
            Toast.makeText(this, "Real tap requires a validated green + candidate.", Toast.LENGTH_LONG).show()
            return false
        }
        if (value.action != ReacquireAction.OFF &&
            value.preset != ReacquirePreset.RAW_DJI &&
            value.candidateRule == CandidateRule.STRONGEST_PINK_BLOB && !value.detectPink) {
            Toast.makeText(this, "Strongest pink blob requires Pink detection.", Toast.LENGTH_LONG).show()
            return false
        }
        if (value.action != ReacquireAction.OFF &&
            value.preset != ReacquirePreset.RAW_DJI &&
            value.candidateRule != CandidateRule.STRONGEST_PINK_BLOB && !value.detectGreenPlus) {
            Toast.makeText(this, "This candidate rule requires green + detection.", Toast.LENGTH_LONG).show()
            return false
        }
        if (value.action != ReacquireAction.OFF &&
            value.preset != ReacquirePreset.RAW_DJI &&
            value.candidateRule == CandidateRule.STRONGEST_PINK_PLUS && !value.detectPink) {
            Toast.makeText(this, "Strongest pink + requires Pink detection.", Toast.LENGTH_LONG).show()
            return false
        }
        GdlTestSettings.save(this, value)
        showSettings(value)
        if (showConfirmation) Toast.makeText(this, "Re-acquire settings applied.", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun settingsFromUi(): ReacquireSettings? = try {
        ReacquireSettings(
            ReacquirePreset.values()[presetSpinner.selectedItemPosition],
            ForegroundTarget.values()[foregroundSpinner.selectedItemPosition],
            requireDjiFullscreen.isChecked,
            detectPlus.isChecked, detectPink.isChecked,
            CandidateRule.values()[candidateSpinner.selectedItemPosition],
            ReacquireAction.values()[actionSpinner.selectedItemPosition],
            SavePolicy.values()[saveSpinner.selectedItemPosition],
            (1_000.0 / captureInterval.text.toString().toDouble().coerceIn(0.2, 5.0))
                .roundToLong().coerceIn(200L, 5_000L),
            retryCooldown.text.toString().toLong().coerceIn(0L, 30_000L),
            tapDuration.text.toString().toLong().coerceIn(50L, 2_000L),
            minimumPink.text.toString().toInt().coerceIn(1, 10_000),
            pinkRadius.text.toString().toFloat().coerceIn(1f, 10f),
            saveGreenMask.isChecked,
            ActiveTrackAction.values()[activeTrackActionSpinner.selectedItemPosition],
            expandCollapsedControls.isChecked,
            activeTrackRetryInterval.text.toString().toLong().coerceIn(250L, 5_000L),
            saveActiveTrackEvidence.isChecked,
            activeTrackCycleTimeout.text.toString().toLong().coerceIn(1_000L, 30_000L),
            maxActiveTrackTaps.text.toString().toInt().coerceIn(1, 10),
            maxChevronTaps.text.toString().toInt().coerceIn(1, 10),
            autoRearmSimulatedActiveTrack.isChecked,
            hardSaveEveryFrame.isChecked,
            gimbalRecoveryEnabled.isChecked,
            noGreenPlusGimbalTimeout.text.toString().toLong().coerceIn(1_000L, 300_000L))
    } catch (_: RuntimeException) { null }

    private fun configureExplanationUpdates() {
        val spinnerListener = SimpleItemSelectedListener {
            if (!loadingUi) updateFlowExplanation()
        }
        foregroundSpinner.onItemSelectedListener = spinnerListener
        candidateSpinner.onItemSelectedListener = SimpleItemSelectedListener {
            if (!loadingUi) updateFlowExplanation()
        }
        actionSpinner.onItemSelectedListener = SimpleItemSelectedListener {
            if (!loadingUi) updateFlowExplanation()
        }
        activeTrackActionSpinner.onItemSelectedListener = SimpleItemSelectedListener {
            if (!loadingUi) updateFlowExplanation()
        }
        saveSpinner.onItemSelectedListener = SimpleItemSelectedListener {
            if (!loadingUi) updateFlowExplanation()
        }
        listOf(
            requireDjiFullscreen, detectPlus, detectPink, saveGreenMask,
            hardSaveEveryFrame,
            expandCollapsedControls, saveActiveTrackEvidence,
            autoRearmSimulatedActiveTrack, gimbalRecoveryEnabled
        ).forEach { control ->
            control.setOnCheckedChangeListener { _, _ ->
                if (!loadingUi) updateFlowExplanation()
            }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!loadingUi) updateFlowExplanation()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        listOf(
            captureInterval, retryCooldown, tapDuration, minimumPink, pinkRadius,
            activeTrackRetryInterval, activeTrackCycleTimeout,
            maxActiveTrackTaps, maxChevronTaps, noGreenPlusGimbalTimeout
        )
            .forEach { it.addTextChangedListener(watcher) }
    }

    private fun updateFlowExplanation(value: ReacquireSettings? = settingsFromUi()) {
        if (value == null) {
            flowExplanation.text = "CURRENT FLOW\nComplete all numeric settings to preview this configuration."
            return
        }
        if (value.preset == ReacquirePreset.GIMBAL_TEST) {
            flowExplanation.text = """
                CURRENT FLOW — GIMBAL MOVEMENT TEST

                1. Guard — DJI Fly must be foreground, full-screen, with Android navigation controls hidden.

                2. Countdown — Once the guard first passes, GDL waits 10 seconds. Losing the guard resets this countdown. No manual trigger is required.

                3. Down movement — GDL presses the fixed DJI gimbal lane at 79.1% across and 45% down for 2 seconds and, without releasing, drags to 79% down over 6 seconds. Only the red bottom-limit knob is detected; white/green knob states and the dashed line are not processed.

                4. Repeat — After release, GDL waits 6 seconds so DJI's previous gimbal wheel disappears, rechecks the guard, then repeats if the red limit was not confirmed.

                5. Lower limit — Red is checked before gesture and interval states. Two consecutive red detections in the fixed bottom-limit box produce S24_GIMBAL_BOTTOM_LIMIT_REACHED and permanently stop this test's gestures.

                6. Evidence — The test captures at 1 FPS. Every Start creates a new GIMBAL_TEST folder; hard-save remains supported. Stage filenames cover countdown, command, gesture, interval, red confirmation and completion.

                Press Start and switch to full-screen DJI Fly during the normal three-second arm delay. The separate 10-second gimbal countdown starts only after the guard passes.
            """.trimIndent()
            return
        }
        val fps = String.format(Locale.US, "%.1f", 1_000.0 / value.captureIntervalMs)
        val foreground = when (value.foreground) {
            ForegroundTarget.DJI_FLY -> "Only DJI Fly is accepted as the foreground app."
            ForegroundTarget.SAMSUNG_GALLERY -> "Only the Samsung Gallery video viewer is accepted."
            ForegroundTarget.ANY_APP -> "Any foreground app except GDL is accepted (test mode)."
        }
        val fullscreen = when {
            value.foreground != ForegroundTarget.DJI_FLY ->
                "The DJI full-screen guard is not applicable to this foreground choice."
            value.requireDjiFullscreen ->
                "DJI Fly must be the foreground app and Android navigation controls must be hidden. A failed guard shows a yellow message and blocks capture, saving and tapping."
            else -> "DJI full-screen checking is disabled."
        }
        val detection = when {
            value.action == ReacquireAction.OFF ->
                "Re-acquire detection is disabled. Its detector settings remain visible and saved for later presets."
            value.preset == ReacquirePreset.RAW_DJI ->
                "Raw diagnostic mode bypasses candidate selection and saves the captured screen."
            value.detectGreenPlus && value.detectPink ->
                "Validated DJI green + detection and fluorescent-pink detection are enabled. X shapes remain rejected."
            value.detectGreenPlus ->
                "Validated DJI green + detection is enabled; fluorescent-pink detection is disabled. X shapes remain rejected."
            value.detectPink ->
                "Fluorescent-pink blob detection is enabled; green + detection is disabled."
            else -> "Both candidate detectors are disabled."
        }
        val selection = if (value.action == ReacquireAction.OFF) {
            "No Re-acquire candidate selection is performed."
        } else {
            when (value.candidateRule) {
                CandidateRule.STRONGEST_PINK_BLOB ->
                    "Select the qualifying pink blob with the greatest accepted pink-pixel count (minimum ${value.minimumPinkPixels}px)."
                CandidateRule.STRONGEST_PINK_PLUS ->
                    "Validate every DJI +, measure pink within ${value.pinkSearchRadiusMultiplier}× its size, and select the strongest association having at least ${value.minimumPinkPixels} pink pixels."
                CandidateRule.VALIDATED_PLUS_NO_PINK ->
                    "Select the structurally highest-confidence validated +. Pink is not required or used for selection."
            }
        }
        val action = when (value.action) {
            ReacquireAction.OFF ->
                "Re-acquire is Off: its settings are retained, but this module does not evaluate candidates or send a touch."
            ReacquireAction.DETECTION_ONLY ->
                "Detection only: mark the decision in evidence; never send a touch."
            ReacquireAction.SIMULATED_TAP ->
                "Simulated tap: show a blue marker at the selected coordinate; never send an Android touch."
            ReacquireAction.REAL_TAP ->
                "Real tap: dispatch a ${value.tapDurationMs} ms Accessibility touch at the selected validated +, then wait ${value.retryCooldownMs} ms before searching again. DJI Fly and all enabled guards are rechecked immediately before touching."
        }
        val activeTrack = when (value.activeTrackAction) {
            ActiveTrackAction.OFF ->
                "Select ActiveTrack is Off. Existing Re-acquire presets therefore behave exactly as before."
            ActiveTrackAction.DETECTION_ONLY ->
                "Every ${value.activeTrackRetryIntervalMs} ms, classify the controls as expanded Spotlight panel, ActiveTrack running, collapsed chevron, protected red control, or unknown. Mark detected regions but send no touch."
            ActiveTrackAction.SIMULATED_TAP -> {
                val collapsed = if (value.expandCollapsedControls) {
                    "For a collapsed panel, mark the proposed upward-chevron coordinate while outlining the red X exclusion."
                } else {
                    "For a collapsed panel, report it but propose no chevron tap because expansion is disabled."
                }
                val rearm = if (value.autoRearmSimulatedActiveTrack) {
                    "After a successful cycle and at least one non-expanded frame, a later expanded Spotlight panel automatically starts a fresh simulated cycle."
                } else {
                    "Automatic simulated-cycle rearming is disabled; restart GDL for another completed test cycle."
                }
                "Every ${value.activeTrackRetryIntervalMs} ms, inspect the fixed DJI control region. For an expanded Spotlight panel, mark the proposed ActiveTrack coordinate. $collapsed ActiveTrack running, protected red controls and unknown states produce no proposed tap. $rearm No Android touch is sent."
            }
            ActiveTrackAction.REAL_TAP -> {
                val restart = if (value.preset == ReacquirePreset.COMBINED_REAL) {
                    "After success, timeout or a tap limit, the old + must be absent for at least one processed frame; only a later newly validated + starts another combined cycle."
                } else {
                    "After success, timeout or a tap limit, stop and restart GDL to start another standalone cycle."
                }
                "Every ${value.activeTrackRetryIntervalMs} ms for at most ${value.activeTrackCycleTimeoutMs} ms, inspect the fixed DJI control region. An expanded Spotlight panel may send a ${value.tapDurationMs} ms touch to ActiveTrack (maximum ${value.maxActiveTrackTaps}). A collapsed panel may touch only the upward chevron (maximum ${value.maxChevronTaps}). The combined Manual/Parallel + yellow ActiveTrack + red Stop pattern proves success and locks all further touches. Unknown waits for the next check. $restart Both the red X and red Stop boxes are mandatory forbidden areas."
            }
        }
        val gimbalRecovery = if (value.gimbalRecoveryEnabled) {
            "Recovery starts disarmed and arms only after the first successful real tap on a validated pink-associated green +. After it is armed, ${value.noGreenPlusGimbalTimeoutMs} ms without another validated pink-associated green + causes GDL to hold the fixed DJI gimbal lane at 79.1% across for 2 seconds, drag down for 6 seconds and wait 6 seconds for the wheel to disappear. Green + is intentionally ignored during recovery. Repeat until two red bottom-limit frames, then remain at the lower limit and resume maximum-speed green + and pink-association searching."
        } else "Production gimbal recovery is disabled."
        val evidence = (if (value.hardSaveEveryCapturedFrame) {
            "Hard-saving is ON: every successfully captured screenshot is saved as a stage-named RAW image for every preset. Detector evidence is also stage-named."
        } else when (value.savePolicy) {
            SavePolicy.NONE -> "Save no evidence images."
            SavePolicy.DECISIONS -> "Save only frames that produce a selected candidate or tap decision."
            SavePolicy.EVERY_FRAME -> "Save every processed frame with candidates, scores and the selected result annotated."
        }) + if (value.saveGreenMask) " Also save the green-threshold mask." else ""
        val warning = when {
            value.action != ReacquireAction.OFF &&
                value.activeTrackAction != ActiveTrackAction.OFF &&
                value.preset != ReacquirePreset.COMBINED_REAL ->
                "\n\n⚠ INVALID: only the combined preset may run both modules."
            value.action == ReacquireAction.REAL_TAP && value.foreground != ForegroundTarget.DJI_FLY ->
                "\n\n⚠ INVALID: a real tap requires the DJI Fly foreground guard."
            value.activeTrackAction == ActiveTrackAction.REAL_TAP &&
                (value.foreground != ForegroundTarget.DJI_FLY || !value.requireDjiFullscreen) ->
                "\n\n⚠ INVALID: real ActiveTrack requires DJI Fly and its full-screen guard."
            value.gimbalRecoveryEnabled &&
                (value.preset != ReacquirePreset.PRODUCTION &&
                    value.preset != ReacquirePreset.COMBINED_REAL) ->
                "\n\n⚠ INVALID: gimbal recovery is available only in real DJI Re-acquire presets."
            value.gimbalRecoveryEnabled &&
                (value.foreground != ForegroundTarget.DJI_FLY || !value.requireDjiFullscreen) ->
                "\n\n⚠ INVALID: gimbal recovery requires DJI Fly and its full-screen guard."
            value.action == ReacquireAction.REAL_TAP && value.candidateRule == CandidateRule.STRONGEST_PINK_BLOB ->
                "\n\n⚠ INVALID: a real tap must target a validated DJI +, not a free-standing pink blob."
            value.action != ReacquireAction.OFF &&
                value.preset != ReacquirePreset.RAW_DJI &&
                value.candidateRule == CandidateRule.STRONGEST_PINK_BLOB && !value.detectPink ->
                "\n\n⚠ INVALID: strongest-pink selection requires pink detection."
            value.action != ReacquireAction.OFF &&
                value.preset != ReacquirePreset.RAW_DJI &&
                value.candidateRule != CandidateRule.STRONGEST_PINK_BLOB && !value.detectGreenPlus ->
                "\n\n⚠ INVALID: this selection rule requires validated green + detection."
            value.action != ReacquireAction.OFF &&
                value.preset != ReacquirePreset.RAW_DJI &&
                value.candidateRule == CandidateRule.STRONGEST_PINK_PLUS && !value.detectPink ->
                "\n\n⚠ INVALID: pink-associated + selection requires pink detection."
            else -> ""
        }
        flowExplanation.text = """
            CURRENT FLOW

            1. Guard — $foreground $fullscreen

            2. Capture — Re-acquire searches every ${value.captureIntervalMs} ms (approximately $fps FPS). This setting applies to every stage that must keep watching for a green +, including combined ActiveTrack confirmation. The ActiveTrack panel classifier itself, standalone ActiveTrack, Gimbal, and completed-cycle waiting run at 1 FPS. The central camera area is searched; the left and right rails are ignored and the top remains searchable.

            3. Detect — $detection

            4. Select — $selection

            5. Act — $action

            6. Lost-subject gimbal recovery — $gimbalRecovery

            7. Select ActiveTrack — $activeTrack

            8. Evidence — Every Start creates a new timestamped preset folder and writes its selected settings. $evidence${if (value.saveActiveTrackEvidence) " ActiveTrack evidence is enabled." else " ActiveTrack evidence is disabled."}

            ${if (value.preset == ReacquirePreset.COMBINED_REAL) "Combined sequence: keep revalidating and retrying a visible + without an attempt limit; a + seen while awaiting ActiveTrack immediately returns to Re-acquire. After the + clears, run the bounded ActiveTrack selector and confirm Manual/Parallel/Stop. After a terminal result, the old + must disappear before a later validated + can start another cycle." else "The selected modules remain independent."}

            Press Start to validate and save exactly these displayed settings, then arm after the three-second safety delay.$warning
        """.trimIndent()
    }

    private fun updateStatus() {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expectedClass = GdlAccessibilityService::class.java.name
        val serviceEnabled = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.name == expectedClass }
        accessibilityStatus.text = if (serviceEnabled) "GDL Accessibility Service: ENABLED ✓"
        else "GDL Accessibility Service: DISABLED"

        val enabled = GdlTestSettings.isEnabled(this)
        val current = GdlTestSettings.load(this)
        toggleButton.text = if (enabled) "STOP ${current.preset.label.uppercase()}" else "START ${current.preset.label.uppercase()}"
        testStatus.text = if (enabled) {
            if (current.preset == ReacquirePreset.GIMBAL_TEST) {
                "ARMED ✓  DJI Fly • 10-second countdown starts after full-screen guard"
            } else {
                "ARMED ✓  ${current.foreground.label} • Re-acquire: ${current.action.label} • ActiveTrack: ${current.activeTrackAction.label} • ${current.savePolicy.label}${if (current.hardSaveEveryCapturedFrame) " • HARD-SAVE ON" else ""}"
            }
        } else "Stopped. Settings remain saved; GDL will not capture or tap."
    }
}

private class SimpleItemSelectedListener(
    private val selected: (Int) -> Unit
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = selected(position)
    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}
