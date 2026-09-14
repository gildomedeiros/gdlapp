package com.gdl.pinkdetector

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

enum class ForegroundTarget(val label: String) {
    DJI_FLY("DJI Fly"), SAMSUNG_GALLERY("Samsung Gallery video"),
    ANY_APP("Any foreground app (test only)")
}

enum class CandidateRule(val label: String) {
    STRONGEST_PINK_BLOB("Strongest pink blob"),
    STRONGEST_PINK_PLUS("+ with strongest pink association"),
    VALIDATED_PLUS_NO_PINK("Validated + — no pink required")
}

enum class ReacquireAction(val label: String) {
    DETECTION_ONLY("Detection only"), SIMULATED_TAP("Simulated tap marker"),
    REAL_TAP("Real Accessibility tap"), OFF("Off")
}

enum class ActiveTrackAction(val label: String) {
    OFF("Off"), DETECTION_ONLY("Detection only"),
    SIMULATED_TAP("Simulated tap marker"),
    REAL_TAP("Real Accessibility tap")
}

enum class SavePolicy(val label: String) {
    NONE("Save nothing"), DECISIONS("Save selected/tap frames"),
    EVERY_FRAME("Save every annotated frame")
}

enum class ReacquirePreset(val label: String) {
    PINK_DETECTOR("Pink detector test"),
    PINK_PLUS_SIMULATION("Pink-to-+ association test"),
    PRODUCTION("DJI reacquire (real tap)"), RAW_DJI("Raw DJI diagnostic"),
    ACTIVE_TRACK_PANEL("ActiveTrack panel test"),
    ACTIVE_TRACK_REAL("DJI ActiveTrack (real tap)"),
    COMBINED_REAL("DJI reacquire + Spotlight (real tap)"),
    GIMBAL_TEST("Gimbal movement test")
}

data class ReacquireSettings(
    val preset: ReacquirePreset,
    val foreground: ForegroundTarget,
    val requireDjiFullscreen: Boolean,
    val detectGreenPlus: Boolean,
    val detectPink: Boolean,
    val candidateRule: CandidateRule,
    val action: ReacquireAction,
    val savePolicy: SavePolicy,
    val captureIntervalMs: Long,
    val retryCooldownMs: Long,
    val tapDurationMs: Long,
    val minimumPinkPixels: Int,
    val pinkSearchRadiusMultiplier: Float,
    val saveGreenMask: Boolean,
    val activeTrackAction: ActiveTrackAction,
    val expandCollapsedControls: Boolean,
    val activeTrackRetryIntervalMs: Long,
    val saveActiveTrackEvidence: Boolean,
    val activeTrackCycleTimeoutMs: Long,
    val maxActiveTrackTaps: Int,
    val maxChevronTaps: Int,
    val autoRearmSimulatedActiveTrack: Boolean,
    val hardSaveEveryCapturedFrame: Boolean,
    val gimbalRecoveryEnabled: Boolean,
    val noGreenPlusGimbalTimeoutMs: Long,
    val gimbalDragDurationMs: Long = 6_000L,
    val pinPersistenceMs: Long = 800L,
    val noPinkInBoxMs: Long = 3_000L
)

object GdlTestSettings {
    const val APP_VERSION = "0.15.12.11"
    const val PREFS_NAME = "gdl_reacquire_settings"
    const val KEY_ENABLED = "reacquire_enabled"
    const val KEY_NOT_BEFORE_MS = "reacquire_not_before_ms"
    const val ARM_SAFETY_DELAY_MS = 3_000L

    private const val KEY_PRESET = "preset"
    private const val KEY_FOREGROUND = "foreground"
    private const val KEY_REQUIRE_DJI_FULLSCREEN = "require_dji_fullscreen"
    private const val KEY_DETECT_PLUS = "detect_plus"
    private const val KEY_DETECT_PINK = "detect_pink"
    private const val KEY_CANDIDATE_RULE = "candidate_rule"
    private const val KEY_ACTION = "action"
    private const val KEY_SAVE_POLICY = "save_policy"
    private const val KEY_CAPTURE_INTERVAL = "capture_interval_ms"
    private const val KEY_RETRY_COOLDOWN = "retry_cooldown_ms"
    private const val KEY_TAP_DURATION = "tap_duration_ms"
    private const val KEY_MIN_PINK = "minimum_pink_pixels"
    private const val KEY_PINK_RADIUS = "pink_radius_multiplier"
    private const val KEY_SAVE_GREEN_MASK = "save_green_mask"
    private const val KEY_ACTIVE_TRACK_ACTION = "active_track_action"
    private const val KEY_EXPAND_COLLAPSED_CONTROLS = "expand_collapsed_controls"
    private const val KEY_ACTIVE_TRACK_RETRY_INTERVAL = "active_track_retry_interval_ms"
    private const val KEY_SAVE_ACTIVE_TRACK_EVIDENCE = "save_active_track_evidence"
    private const val KEY_ACTIVE_TRACK_CYCLE_TIMEOUT = "active_track_cycle_timeout_ms"
    private const val KEY_MAX_ACTIVE_TRACK_TAPS = "max_active_track_taps"
    private const val KEY_MAX_CHEVRON_TAPS = "max_chevron_taps"
    private const val KEY_AUTO_REARM_SIMULATED_ACTIVE_TRACK =
        "auto_rearm_simulated_active_track"
    private const val KEY_HARD_SAVE_EVERY_CAPTURED_FRAME =
        "hard_save_every_captured_frame"
    private const val KEY_GIMBAL_RECOVERY_ENABLED = "gimbal_recovery_enabled"
    private const val KEY_NO_GREEN_PLUS_GIMBAL_TIMEOUT =
        "no_green_plus_gimbal_timeout_ms"
    private const val KEY_RUN_TOKEN = "run_token"
    private const val KEY_RUN_STARTED_AT_MS = "run_started_at_ms"

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun defaults(preset: ReacquirePreset): ReacquireSettings = when (preset) {
        ReacquirePreset.PINK_DETECTOR -> ReacquireSettings(
            preset, ForegroundTarget.SAMSUNG_GALLERY, true, false, true,
            CandidateRule.STRONGEST_PINK_BLOB, ReacquireAction.DETECTION_ONLY,
            SavePolicy.EVERY_FRAME, 1_000L, 3_000L, 120L, 8, 3f, false,
            ActiveTrackAction.OFF, false, 400L, false, 8_000L, 2, 2, false, false,
            false, 15_000L)
        ReacquirePreset.PINK_PLUS_SIMULATION -> ReacquireSettings(
            preset, ForegroundTarget.SAMSUNG_GALLERY, true, true, true,
            CandidateRule.STRONGEST_PINK_PLUS, ReacquireAction.SIMULATED_TAP,
            SavePolicy.EVERY_FRAME, 1_000L, 3_000L, 120L, 8, 3f, false,
            ActiveTrackAction.OFF, false, 400L, false, 8_000L, 2, 2, false, false,
            false, 15_000L)
        ReacquirePreset.PRODUCTION -> ReacquireSettings(
            preset, ForegroundTarget.DJI_FLY, true, true, true,
            CandidateRule.STRONGEST_PINK_PLUS, ReacquireAction.REAL_TAP,
            SavePolicy.DECISIONS, 400L, 3_000L, 120L, 8, 3f, false,
            ActiveTrackAction.OFF, false, 400L, false, 8_000L, 2, 2, false, false,
            false, 15_000L)
        ReacquirePreset.RAW_DJI -> ReacquireSettings(
            preset, ForegroundTarget.DJI_FLY, true, false, false,
            CandidateRule.STRONGEST_PINK_BLOB, ReacquireAction.DETECTION_ONLY,
            SavePolicy.EVERY_FRAME, 400L, 3_000L, 120L, 8, 3f, true,
            ActiveTrackAction.OFF, false, 400L, false, 8_000L, 2, 2, false, false,
            false, 15_000L)
        ReacquirePreset.ACTIVE_TRACK_PANEL -> ReacquireSettings(
            preset, ForegroundTarget.SAMSUNG_GALLERY, true, false, false,
            CandidateRule.STRONGEST_PINK_BLOB, ReacquireAction.OFF,
            SavePolicy.EVERY_FRAME, 400L, 3_000L, 120L, 8, 3f, false,
            ActiveTrackAction.SIMULATED_TAP, true, 1_000L, true, 8_000L, 2, 2, true, false,
            false, 15_000L)
        ReacquirePreset.ACTIVE_TRACK_REAL -> ReacquireSettings(
            preset, ForegroundTarget.DJI_FLY, true, false, false,
            CandidateRule.STRONGEST_PINK_BLOB, ReacquireAction.OFF,
            SavePolicy.DECISIONS, 400L, 3_000L, 120L, 8, 3f, false,
            ActiveTrackAction.REAL_TAP, true, 1_000L, true, 8_000L, 2, 2, false, false,
            false, 15_000L)
        ReacquirePreset.COMBINED_REAL -> ReacquireSettings(
            preset, ForegroundTarget.DJI_FLY, true, true, true,
            CandidateRule.STRONGEST_PINK_PLUS, ReacquireAction.REAL_TAP,
            SavePolicy.DECISIONS, 400L, 1000L, 120L, 8, 3f, false,
            ActiveTrackAction.OFF, false, 1_000L, true, 8_000L, 2, 2, false, false,
            true, 15_000L)
        ReacquirePreset.GIMBAL_TEST -> ReacquireSettings(
            preset, ForegroundTarget.DJI_FLY, true, false, false,
            CandidateRule.STRONGEST_PINK_BLOB, ReacquireAction.OFF,
            SavePolicy.EVERY_FRAME, 400L, 3_000L, 120L, 8, 3f, false,
            ActiveTrackAction.OFF, false, 1_000L, false, 8_000L, 2, 2, false, false,
            false, 15_000L)
    }

    fun applyPreset(context: Context, preset: ReacquirePreset): ReacquireSettings =
        defaults(preset).also { save(context, it) }

    fun save(context: Context, value: ReacquireSettings) {
        preferences(context).edit()
            .putString(KEY_PRESET, value.preset.name)
            .putString(KEY_FOREGROUND, value.foreground.name)
            .putBoolean(KEY_REQUIRE_DJI_FULLSCREEN, true)
            .putBoolean(KEY_DETECT_PLUS, value.detectGreenPlus)
            .putBoolean(KEY_DETECT_PINK, value.detectPink)
            .putString(KEY_CANDIDATE_RULE, value.candidateRule.name)
            .putString(KEY_ACTION, value.action.name)
            .putString(KEY_SAVE_POLICY, value.savePolicy.name)
            .putLong(KEY_CAPTURE_INTERVAL, value.captureIntervalMs)
            .putLong(KEY_RETRY_COOLDOWN, value.retryCooldownMs)
            .putLong(KEY_TAP_DURATION, value.tapDurationMs)
            .putInt(KEY_MIN_PINK, value.minimumPinkPixels)
            .putFloat(KEY_PINK_RADIUS, value.pinkSearchRadiusMultiplier)
            .putBoolean(KEY_SAVE_GREEN_MASK, value.saveGreenMask)
            .putString(KEY_ACTIVE_TRACK_ACTION, value.activeTrackAction.name)
            .putBoolean(KEY_EXPAND_COLLAPSED_CONTROLS, value.expandCollapsedControls)
            .putLong(KEY_ACTIVE_TRACK_RETRY_INTERVAL, value.activeTrackRetryIntervalMs)
            .putBoolean(KEY_SAVE_ACTIVE_TRACK_EVIDENCE, value.saveActiveTrackEvidence)
            .putLong(KEY_ACTIVE_TRACK_CYCLE_TIMEOUT, value.activeTrackCycleTimeoutMs)
            .putInt(KEY_MAX_ACTIVE_TRACK_TAPS, value.maxActiveTrackTaps)
            .putInt(KEY_MAX_CHEVRON_TAPS, value.maxChevronTaps)
            .putBoolean(
                KEY_AUTO_REARM_SIMULATED_ACTIVE_TRACK,
                value.autoRearmSimulatedActiveTrack)
            .putBoolean(KEY_HARD_SAVE_EVERY_CAPTURED_FRAME,
                value.hardSaveEveryCapturedFrame)
            .putLong("pin_persistence_ms", value.pinPersistenceMs)
            .putLong("no_pink_in_box_ms", value.noPinkInBoxMs)
            .putLong("gimbal_drag_duration_ms", value.gimbalDragDurationMs.coerceIn(1_000L, 30_000L))
            .putBoolean(KEY_GIMBAL_RECOVERY_ENABLED, value.gimbalRecoveryEnabled)
            .putLong(KEY_NO_GREEN_PLUS_GIMBAL_TIMEOUT,
                value.noGreenPlusGimbalTimeoutMs)
            .apply()
    }

    fun load(context: Context): ReacquireSettings {
        val prefs = preferences(context)
        // Migrate only the selection; retain the user's saved timing/settings.
        if (prefs.getString(KEY_PRESET, null) == "PRODUCTION") {
            prefs.edit().putString(KEY_PRESET, ReacquirePreset.COMBINED_REAL.name).apply()
        }
        // Removed legacy single-plus configuration requires a complete preset.
        if (prefs.getString(KEY_PRESET, null) == "GREEN_PLUS" ||
            prefs.getString(KEY_CANDIDATE_RULE, null) == "SINGLE_PLUS") {
            return defaults(ReacquirePreset.COMBINED_REAL).also { save(context, it) }
        }
        val preset = enumValue(prefs.getString(KEY_PRESET, null), ReacquirePreset.COMBINED_REAL)
        val base = defaults(preset)
        return ReacquireSettings(
            preset, enumValue(prefs.getString(KEY_FOREGROUND, null), base.foreground),
            true, // Mandatory, including for previously saved configurations.
            prefs.getBoolean(KEY_DETECT_PLUS, base.detectGreenPlus),
            prefs.getBoolean(KEY_DETECT_PINK, base.detectPink),
            enumValue(prefs.getString(KEY_CANDIDATE_RULE, null), base.candidateRule),
            enumValue(prefs.getString(KEY_ACTION, null), base.action),
            enumValue(prefs.getString(KEY_SAVE_POLICY, null), base.savePolicy),
            prefs.getLong(KEY_CAPTURE_INTERVAL, base.captureIntervalMs).coerceIn(200L, 5_000L),
            prefs.getLong(KEY_RETRY_COOLDOWN, base.retryCooldownMs).coerceIn(0L, 30_000L),
            prefs.getLong(KEY_TAP_DURATION, base.tapDurationMs).coerceIn(50L, 2_000L),
            prefs.getInt(KEY_MIN_PINK, base.minimumPinkPixels).coerceIn(1, 10_000),
            prefs.getFloat(KEY_PINK_RADIUS, base.pinkSearchRadiusMultiplier).coerceIn(1f, 10f),
            prefs.getBoolean(KEY_SAVE_GREEN_MASK, base.saveGreenMask),
            if (preset == ReacquirePreset.COMBINED_REAL) ActiveTrackAction.OFF else
                enumValue(prefs.getString(KEY_ACTIVE_TRACK_ACTION, null), base.activeTrackAction),
            prefs.getBoolean(KEY_EXPAND_COLLAPSED_CONTROLS, base.expandCollapsedControls),
            prefs.getLong(KEY_ACTIVE_TRACK_RETRY_INTERVAL, base.activeTrackRetryIntervalMs)
                .coerceIn(250L, 5_000L),
            prefs.getBoolean(KEY_SAVE_ACTIVE_TRACK_EVIDENCE, base.saveActiveTrackEvidence),
            prefs.getLong(KEY_ACTIVE_TRACK_CYCLE_TIMEOUT, base.activeTrackCycleTimeoutMs)
                .coerceIn(1_000L, 30_000L),
            prefs.getInt(KEY_MAX_ACTIVE_TRACK_TAPS, base.maxActiveTrackTaps).coerceIn(1, 10),
            prefs.getInt(KEY_MAX_CHEVRON_TAPS, base.maxChevronTaps).coerceIn(1, 10),
            prefs.getBoolean(
                KEY_AUTO_REARM_SIMULATED_ACTIVE_TRACK,
                base.autoRearmSimulatedActiveTrack),
            prefs.getBoolean(KEY_HARD_SAVE_EVERY_CAPTURED_FRAME,
                base.hardSaveEveryCapturedFrame),
            prefs.getBoolean(KEY_GIMBAL_RECOVERY_ENABLED, base.gimbalRecoveryEnabled),
            prefs.getLong(KEY_NO_GREEN_PLUS_GIMBAL_TIMEOUT,
                base.noGreenPlusGimbalTimeoutMs).coerceIn(1_000L, 300_000L),
            prefs.getLong("gimbal_drag_duration_ms", 6_000L).coerceIn(1_000L, 30_000L),
            prefs.getLong("pin_persistence_ms", 800L).coerceIn(100L, 30_000L),
            prefs.getLong("no_pink_in_box_ms", 3_000L).coerceIn(100L, 30_000L))
    }

    fun beginRun(context: Context) {
        preferences(context).edit()
            .putString(KEY_RUN_TOKEN, UUID.randomUUID().toString())
            .putLong(KEY_RUN_STARTED_AT_MS, System.currentTimeMillis())
            .apply()
    }

    fun runToken(context: Context): String? =
        preferences(context).getString(KEY_RUN_TOKEN, null)

    fun runStartedAtMs(context: Context): Long =
        preferences(context).getLong(KEY_RUN_STARTED_AT_MS, 0L)

    fun isEnabled(context: Context) = preferences(context).getBoolean(KEY_ENABLED, false)

    fun isReady(context: Context) = isEnabled(context) &&
        System.currentTimeMillis() >= preferences(context).getLong(KEY_NOT_BEFORE_MS, Long.MAX_VALUE)

    fun setEnabled(context: Context, enabled: Boolean) {
        val edit = preferences(context).edit().putBoolean(KEY_ENABLED, enabled)
        if (enabled) edit.putLong(KEY_NOT_BEFORE_MS, System.currentTimeMillis() + ARM_SAFETY_DELAY_MS)
        else edit.remove(KEY_NOT_BEFORE_MS)
        edit.apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
