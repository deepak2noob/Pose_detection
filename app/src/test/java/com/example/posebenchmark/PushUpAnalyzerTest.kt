package com.example.posebenchmark

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.junit.Assert.*
import org.junit.Test
import java.util.Optional

class PushUpAnalyzerTest {
    private val analyzer = PushUpAnalyzer()
    private fun landmark(x: Float, y: Float, visibility: Float? = 0.9f) =
        NormalizedLandmark.create(x, y, 0f, Optional.ofNullable(visibility), Optional.of(1f))

    private fun pose(hipY: Float = 0.4f, mirrored: Boolean = false): MutableList<NormalizedLandmark> {
        val points = MutableList(33) { landmark(0f, 0f, 0f) }
        fun set(index: Int, x: Float, y: Float) {
            points[index] = landmark(if (mirrored) 1f - x else x, y)
        }
        set(11, 0.2f, 0.4f)
        set(23, 0.5f, hipY)
        set(25, 0.65f, 0.4f)
        set(27, 0.8f, 0.4f)
        set(13, 0.2f, 0.55f)
        set(15, 0.2f, 0.7f)
        return points
    }
    private fun analyze(points: List<NormalizedLandmark>) = analyzer.analyze(points, 1000, 1000)

    @Test fun straightBodyIsReadyAndGreen() {
        val result = analyze(pose())
        assertEquals(ExerciseTrackingState.READY, result.trackingState)
        assertEquals(BodySide.LEFT, result.side)
        assertEquals(0f, result.hipDeviation!!, 0.0001f)
        assertEquals(PostureVisualState.GOOD, result.guidance.joints.single().state)
        assertTrue(result.guidance.connections.all { it.state == PostureVisualState.GOOD })
        assertNull(result.guidance.joints.single().direction)
        assertNull(result.guidance.joints.single().target)
    }

    @Test fun sagAndRaisedHipsCorrectInBothFacingDirections() {
        for (mirrored in listOf(false, true)) {
            for ((hipY, direction) in listOf(0.5f to CorrectionDirection.UP, 0.3f to CorrectionDirection.DOWN)) {
                val result = analyze(pose(hipY, mirrored))
                val joint = result.guidance.joints.single()
                assertEquals(23, joint.landmarkIndex)
                assertEquals(PostureVisualState.WRONG, joint.state)
                assertEquals(direction, joint.direction)
                assertEquals(0.4f, joint.target!!.y, 0.0001f)
                assertTrue(result.guidance.connections.all { it.state == PostureVisualState.WRONG })
            }
        }
    }

    @Test fun moderateDeviationIsWarningInBothDirections() {
        for (hipY in listOf(0.425f, 0.375f)) {
            assertEquals(PostureVisualState.WARNING, analyze(pose(hipY)).guidance.joints.single().state)
        }
        assertEquals(PostureVisualState.GOOD, analyze(pose(0.41f)).guidance.joints.single().state)
    }

    @Test fun kneeFallbackAndAnkleWithoutKneeAreSupported() {
        val points = pose()
        points[27] = landmark(0.8f, 0.4f, 0f)
        assertEquals(ExerciseTrackingState.READY, analyze(points).trackingState)
        assertEquals(2, analyze(points).guidance.connections.size)
        points[27] = landmark(0.8f, 0.4f)
        points[25] = landmark(0.65f, 0.4f, 0f)
        assertEquals(ExerciseTrackingState.READY, analyze(points).trackingState)
        assertEquals(1, analyze(points).guidance.connections.size)
        points[27] = landmark(0.8f, 0.4f, 0f)
        assertEquals(ExerciseTrackingState.NOT_READY, analyze(points).trackingState)
    }

    @Test fun choosesMoreConfidentSideAndAcceptsRightSideAlone() {
        val points = pose()
        for (index in intArrayOf(11, 13, 15, 23, 25, 27)) {
            points[index + 1] = landmark(points[index].x(), points[index].y(), 0.99f)
        }
        assertEquals(BodySide.RIGHT, analyze(points).side)
        for (index in intArrayOf(11, 13, 15, 23, 25, 27)) points[index] = landmark(0f, 0f, 0f)
        assertEquals(BodySide.RIGHT, analyze(points).side)
        assertEquals(24, analyze(points).guidance.joints.single().landmarkIndex)
    }

    @Test fun unreliableRequiredLandmarksNeverProduceGuidance() {
        for (index in intArrayOf(11, 13, 15, 23)) {
            for (bad in listOf(landmark(0.5f, 0.5f, 0.59f), landmark(0.5f, 0.5f, null),
                landmark(Float.NaN, 0.5f), landmark(1.2f, 0.5f), landmark(0.5f, 0.5f, Float.NaN))) {
                val points = pose(0.5f)
                points[index] = bad
                val result = analyze(points)
                assertEquals(ExerciseTrackingState.NOT_READY, result.trackingState)
                assertTrue(result.guidance.joints.isEmpty())
                assertTrue(result.guidance.connections.isEmpty())
            }
        }
    }

    @Test fun lostPartialUprightAndDegeneratePosesAreNotReady() {
        assertEquals(ExerciseTrackingState.NOT_READY, analyze(emptyList()).trackingState)
        assertEquals(ExerciseTrackingState.NOT_READY, analyze(pose().take(16)).trackingState)
        assertEquals(ExerciseTrackingState.NOT_READY, analyzer.analyze(pose(), 0, 1000).trackingState)
        val upright = pose().map { landmark(it.y(), it.x(), it.visibility().orElse(0f)) }
        assertEquals(ExerciseTrackingState.NOT_READY, analyze(upright).trackingState)
        val points = pose()
        points[27] = points[11]
        assertEquals(ExerciseTrackingState.NOT_READY, analyze(points).trackingState)
    }

    @Test fun correctionClearsOnRecoveryAndLoss() {
        assertEquals(PostureVisualState.WRONG, analyze(pose(0.5f)).guidance.joints.single().state)
        assertNull(analyze(pose()).guidance.joints.single().direction)
        assertTrue(analyze(emptyList()).guidance.joints.isEmpty())
    }

    @Test fun geometryUsesFrameAspectRatio() {
        val points = pose(0.5f)
        val tall = points.map { landmark(it.x(), it.y() / 2f, it.visibility().orElse(0f)) }
        val squareResult = analyze(points)
        val tallResult = analyzer.analyze(tall, 1000, 2000)
        assertEquals(squareResult.hipDeviation!!, tallResult.hipDeviation!!, 0.0001f)
        assertEquals(squareResult.guidance.joints.single().state, tallResult.guidance.joints.single().state)
        assertEquals(squareResult.guidance.joints.single().target!!.y / 2f,
            tallResult.guidance.joints.single().target!!.y, 0.0001f)
    }

    @Test fun slopedStraightLineRemainsGreen() {
        val points = pose()
        for (index in intArrayOf(11, 13, 15, 23, 25, 27)) {
            val p = points[index]
            points[index] = landmark(p.x(), p.y() + (p.x() - 0.2f) * 0.2f)
        }
        assertEquals(PostureVisualState.GOOD, analyze(points).guidance.joints.single().state)
    }
}
