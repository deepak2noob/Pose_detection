package com.example.posebenchmark

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.hypot

enum class ExerciseTrackingState { NOT_READY, READY }
enum class BodySide { LEFT, RIGHT }

data class PushUpAnalysis(
    val trackingState: ExerciseTrackingState,
    val side: BodySide? = null,
    // Signed perpendicular distance divided by shoulder-to-anchor length; positive is sag.
    val hipDeviation: Float? = null,
    val guidance: PostureGuidance = PostureGuidance()
)

/** Push-up mode V1: side-view body alignment only, with no phase or rep detection. */
class PushUpAnalyzer {
    companion object {
        const val HIP_WARNING_THRESHOLD = 0.03f
        const val HIP_WRONG_THRESHOLD = 0.06f
        const val MIN_VISIBILITY = 0.60f
        const val FRAME_TOLERANCE = 0.05f
        const val MAX_BODY_SLOPE = 0.6f
        private const val MIN_BODY_LENGTH_PIXELS = 1f
        private val NOT_READY = PushUpAnalysis(ExerciseTrackingState.NOT_READY)
    }

    private data class Side(
        val name: BodySide, val shoulder: Int, val elbow: Int, val wrist: Int,
        val hip: Int, val knee: Int, val ankle: Int
    )
    private val sides = arrayOf(
        Side(BodySide.LEFT, 11, 13, 15, 23, 25, 27),
        Side(BodySide.RIGHT, 12, 14, 16, 24, 26, 28)
    )

    fun analyze(landmarks: List<NormalizedLandmark>, frameWidth: Int, frameHeight: Int): PushUpAnalysis {
        if (frameWidth <= 0 || frameHeight <= 0) return NOT_READY
        var selected: Side? = null
        var selectedAnchor = -1
        var bestConfidence = -1f
        for (side in sides) {
            if (!reliable(landmarks, side.shoulder) || !reliable(landmarks, side.elbow) ||
                !reliable(landmarks, side.wrist) || !reliable(landmarks, side.hip)) continue
            // Prefer the longer baseline; allow a cropped ankle via the knee fallback.
            val anchor = when {
                reliable(landmarks, side.ankle) -> side.ankle
                reliable(landmarks, side.knee) -> side.knee
                else -> continue
            }
            val confidence = (confidence(landmarks, side.shoulder) + confidence(landmarks, side.elbow) +
                confidence(landmarks, side.wrist) + confidence(landmarks, side.hip) +
                confidence(landmarks, anchor)) / 5f
            if (confidence > bestConfidence) {
                bestConfidence = confidence
                selected = side
                selectedAnchor = anchor
            }
        }
        val side = selected ?: return NOT_READY
        val shoulder = landmarks[side.shoulder]
        val hip = landmarks[side.hip]
        val anchor = landmarks[selectedAnchor]
        val sx = shoulder.x() * frameWidth
        val sy = shoulder.y() * frameHeight
        val dx = (anchor.x() - shoulder.x()) * frameWidth
        val dy = (anchor.y() - shoulder.y()) * frameHeight
        val length = hypot(dx, dy)
        // Readiness guards only: upright/degenerate bodies cannot define this side-view rule.
        if (length < MIN_BODY_LENGTH_PIXELS || abs(dx) < MIN_BODY_LENGTH_PIXELS ||
            abs(dy) > abs(dx) * MAX_BODY_SLOPE) return NOT_READY
        val wristY = landmarks[side.wrist].y() * frameHeight
        if (wristY - sy < length * 0.08f) return NOT_READY

        val hx = hip.x() * frameWidth
        val hy = hip.y() * frameHeight
        val hipPosition = (hx - sx) / dx
        if (hipPosition !in 0.1f..0.9f) return NOT_READY
        val idealHipY = sy + hipPosition * dy
        // Normalize perpendicular distance by body length. Sign stays the same when facing left.
        val deviation = (hy - idealHipY) * abs(dx) / (length * length)
        val state = when {
            abs(deviation) >= HIP_WRONG_THRESHOLD -> PostureVisualState.WRONG
            abs(deviation) >= HIP_WARNING_THRESHOLD -> PostureVisualState.WARNING
            else -> PostureVisualState.GOOD
        }
        val needsCorrection = state != PostureVisualState.GOOD
        val direction = if (!needsCorrection) null else if (deviation > 0f)
            CorrectionDirection.UP else CorrectionDirection.DOWN
        val target = if (needsCorrection) TargetJointPosition(hip.x(), idealHipY / frameHeight) else null
        val connections = ArrayList<ConnectionCorrection>(3)
        connections.add(ConnectionCorrection(side.shoulder, side.hip, state))
        if (reliable(landmarks, side.knee)) {
            connections.add(ConnectionCorrection(side.hip, side.knee, state))
            if (selectedAnchor == side.ankle) {
                connections.add(ConnectionCorrection(side.knee, side.ankle, state))
            }
        }
        return PushUpAnalysis(
            ExerciseTrackingState.READY, side.name, deviation,
            PostureGuidance(listOf(JointCorrection(side.hip, state, direction, target)), connections)
        )
    }

    private fun confidence(landmarks: List<NormalizedLandmark>, index: Int): Float =
        landmarks[index].visibility().orElse(0f)

    private fun reliable(landmarks: List<NormalizedLandmark>, index: Int): Boolean {
        val point = landmarks.getOrNull(index) ?: return false
        val visibility = point.visibility().orElse(0f)
        return visibility.isFinite() && visibility >= MIN_VISIBILITY &&
            point.x() in -FRAME_TOLERANCE..(1f + FRAME_TOLERANCE) &&
            point.y() in -FRAME_TOLERANCE..(1f + FRAME_TOLERANCE)
    }
}
