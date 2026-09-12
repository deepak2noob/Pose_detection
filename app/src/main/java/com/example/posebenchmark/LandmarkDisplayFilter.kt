package com.example.posebenchmark

/** Per-joint display history. Updated once per camera result, never once per draw. */
class LandmarkDisplayFilter {
    companion object {
        const val SHOW_THRESHOLD = 0.60f
        const val HIDE_THRESHOLD = 0.45f
        const val LANDMARK_HIDE_FRAMES = 3
        const val SMOOTHING_ALPHA = 0.35f
        const val FRAME_TOLERANCE = 0.05f
    }

    var visible = false
        private set
    var x = 0f
        private set
    var y = 0f
        private set
    private var lowConfidenceFrames = 0

    fun update(newX: Float, newY: Float, visibility: Float) {
        // Off-frame/invalid coordinates are never held as plausible body parts.
        if (!visibility.isFinite() ||
            newX !in -FRAME_TOLERANCE..(1f + FRAME_TOLERANCE) ||
            newY !in -FRAME_TOLERANCE..(1f + FRAME_TOLERANCE)) {
            reset()
            return
        }
        if (!visible) {
            if (visibility < SHOW_THRESHOLD) return
            visible = true
            x = newX
            y = newY
            lowConfidenceFrames = 0
            return
        }
        if (visibility < HIDE_THRESHOLD) {
            lowConfidenceFrames++
            if (lowConfidenceFrames >= LANDMARK_HIDE_FRAMES) reset()
            // Hold the last trustworthy position during the brief grace period.
            return
        }
        lowConfidenceFrames = 0
        x += SMOOTHING_ALPHA * (newX - x)
        y += SMOOTHING_ALPHA * (newY - y)
    }

    fun reset() {
        visible = false
        lowConfidenceFrames = 0
        x = 0f
        y = 0f
    }
}
