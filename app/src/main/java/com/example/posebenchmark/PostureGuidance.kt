package com.example.posebenchmark

enum class PostureVisualState { NORMAL, GOOD, WARNING, WRONG }

// Directions are relative to the displayed preview. NONE preserves the existing analyzer API.
enum class CorrectionDirection { NONE, UP, DOWN, LEFT, RIGHT }

// Target coordinates use the same normalized camera frame as MediaPipe landmarks.
data class TargetJointPosition(val x: Float, val y: Float)

data class JointCorrection(
    val landmarkIndex: Int,
    val state: PostureVisualState,
    val direction: CorrectionDirection? = null,
    val target: TargetJointPosition? = null
)

// Endpoint order does not matter when guidance is applied.
data class ConnectionCorrection(
    val startIndex: Int,
    val endIndex: Int,
    val state: PostureVisualState
)

data class PostureGuidance(
    val joints: List<JointCorrection> = emptyList(),
    val connections: List<ConnectionCorrection> = emptyList()
)
