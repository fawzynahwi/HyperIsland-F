package io.github.hyperisland.utils

/** Keys for the island brackets "( )" feature, shared by settings and the SystemUI hook. */
internal object CameraBracketsConfig {
    /** Draw "( )" around the island whenever it is showing. */
    const val ENABLED_KEY = "pref_camera_brackets_enabled"
    /** Keep "( )" around the camera hole even when no island is showing. */
    const val ALWAYS_KEY = "pref_camera_brackets_always"
    /** Only meaningful with [ALWAYS_KEY]: slowly shift and dim idle brackets. */
    const val BURN_IN_KEY = "pref_camera_brackets_burn_in"

    const val GAP_DP = 5f
    const val STROKE_DP = 2f
    const val SWEEP_DEG = 90f
    /** Idle brackets move to a new position this often. */
    const val SHIFT_INTERVAL_MS = 60_000L
    const val SHIFT_MAX_DP = 2
    const val IDLE_ALPHA = 0.6f
}
