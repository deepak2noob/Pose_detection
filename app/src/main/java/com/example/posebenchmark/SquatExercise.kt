package com.example.posebenchmark

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min


enum class SquatExercisePhase {
    STANDING,
    DESCENDING,
    BOTTOM,
    ASCENDING
}


data class SquatExerciseResult(
    val bodyVisible: Boolean,
    val phase: SquatExercisePhase,
    val repCount: Int,

    val leftKneeAngle: Double,
    val rightKneeAngle: Double,
    val averageKneeAngle: Double,

    val leftHipAngle: Double,
    val rightHipAngle: Double,

    val torsoLeanAngle: Double,

    val postureGood: Boolean,
    val feedback: String
)


class SquatExercise {

    companion object {

        // =====================================================
        // MEDIAPIPE POSE LANDMARK INDEXES
        // =====================================================

        private const val LEFT_SHOULDER = 11
        private const val RIGHT_SHOULDER = 12

        private const val LEFT_HIP = 23
        private const val RIGHT_HIP = 24

        private const val LEFT_KNEE = 25
        private const val RIGHT_KNEE = 26

        private const val LEFT_ANKLE = 27
        private const val RIGHT_ANKLE = 28


        // =====================================================
        // LANDMARK QUALITY
        // =====================================================

        /*
         * Posture analysis should be stricter than the visual
         * skeleton overlay.
         */
        private const val MIN_VISIBILITY = 0.60f


        /*
         * Do not destroy an in-progress rep because of one or two
         * noisy MediaPipe frames.
         *
         * At ~15-20 pose FPS, 4 frames is only a short grace period.
         */
        private const val POSE_LOST_GRACE_FRAMES = 4


        // =====================================================
        // REP DETECTION THRESHOLDS
        // =====================================================

        /*
         * Start of a squat.
         */
        private const val START_DESCENT_ANGLE = 150.0


        /*
         * A rep is valid if the LOWEST smoothed knee angle seen
         * during that rep reaches this value or lower.
         *
         * This is intentionally more forgiving than the old 110°
         * requirement so a single missed inference frame does not
         * lose the whole rep.
         */
        private const val REP_DEPTH_ANGLE = 125.0


        /*
         * User must return close to standing before the rep can finish.
         */
        private const val STANDING_ANGLE = 155.0


        /*
         * This is for coaching only.
         * It is NOT required merely to count the rep.
         */
        private const val GOOD_DEPTH_ANGLE = 105.0


        // =====================================================
        // POSTURE THRESHOLDS
        // =====================================================

        private const val MAX_TORSO_LEAN = 35.0

        private const val MAX_KNEE_DIFFERENCE = 18.0


        // =====================================================
        // SMOOTHING / MOVEMENT
        // =====================================================

        private const val SMOOTHING_ALPHA = 0.35

        private const val MOVEMENT_EPSILON = 0.8

        /*
         * A posture warning must remain present for a few pose
         * results before we show it.
         */
        private const val BAD_POSTURE_CONFIRM_FRAMES = 3
    }


    // =========================================================
    // SESSION STATE
    // =========================================================

    private var phase =
        SquatExercisePhase.STANDING


    private var repCount =
        0


    private var smoothedKneeAngle: Double? =
        null


    private var previousKneeAngle: Double? =
        null


    /*
     * Lowest smoothed knee angle observed during the current rep.
     *
     * This is the key change that makes rep counting more robust.
     */
    private var lowestKneeAngleThisRep =
        Double.POSITIVE_INFINITY


    private var reachedRepDepth =
        false


    private var lastRepWasShallow =
        false


    private var badTorsoFrames =
        0


    private var badSymmetryFrames =
        0


    private var missingPoseFrames =
        0


    // =========================================================
    // PUBLIC ANALYSIS
    // =========================================================

    fun analyze(
        landmarks: List<NormalizedLandmark>
    ): SquatExerciseResult {

        if (
            landmarks.size <=
            RIGHT_ANKLE
        ) {

            registerPoseMissing()

            return invalidResult(
                "Move into frame so your full body is visible"
            )
        }


        val leftShoulder =
            landmarks[LEFT_SHOULDER]

        val rightShoulder =
            landmarks[RIGHT_SHOULDER]


        val leftHip =
            landmarks[LEFT_HIP]

        val rightHip =
            landmarks[RIGHT_HIP]


        val leftKnee =
            landmarks[LEFT_KNEE]

        val rightKnee =
            landmarks[RIGHT_KNEE]


        val leftAnkle =
            landmarks[LEFT_ANKLE]

        val rightAnkle =
            landmarks[RIGHT_ANKLE]


        // =====================================================
        // BODY VISIBILITY
        // =====================================================

        val bodyVisible =
            isUsable(leftShoulder) &&
                    isUsable(rightShoulder) &&
                    isUsable(leftHip) &&
                    isUsable(rightHip) &&
                    isUsable(leftKnee) &&
                    isUsable(rightKnee) &&
                    isUsable(leftAnkle) &&
                    isUsable(rightAnkle)


        if (!bodyVisible) {

            /*
             * Important:
             *
             * Do NOT immediately reset the current squat.
             * A few bad frames are tolerated.
             */
            registerPoseMissing()

            return invalidResult(
                "Keep shoulders, hips, knees and ankles visible"
            )
        }


        /*
         * Valid pose again.
         */
        missingPoseFrames =
            0


        // =====================================================
        // JOINT ANGLES
        // =====================================================

        val leftKneeAngle =
            calculateAngle(
                leftHip,
                leftKnee,
                leftAnkle
            )


        val rightKneeAngle =
            calculateAngle(
                rightHip,
                rightKnee,
                rightAnkle
            )


        val leftHipAngle =
            calculateAngle(
                leftShoulder,
                leftHip,
                leftKnee
            )


        val rightHipAngle =
            calculateAngle(
                rightShoulder,
                rightHip,
                rightKnee
            )


        /*
         * Keep the working V1 behavior:
         *
         * use BOTH knees and average them.
         */
        val rawAverageKneeAngle =
            (
                    leftKneeAngle +
                            rightKneeAngle
                    ) / 2.0


        val averageKneeAngle =
            smoothKneeAngle(
                rawAverageKneeAngle
            )


        val torsoLeanAngle =
            calculateTorsoLean(
                leftShoulder,
                rightShoulder,
                leftHip,
                rightHip
            )


        val kneeDifference =
            abs(
                leftKneeAngle -
                        rightKneeAngle
            )


        // =====================================================
        // REP STATE MACHINE
        // =====================================================

        updatePhase(
            averageKneeAngle
        )


        // =====================================================
        // POSTURE CHECKS
        // =====================================================

        val torsoBad =
            torsoLeanAngle >
                    MAX_TORSO_LEAN


        badTorsoFrames =
            if (torsoBad) {

                badTorsoFrames + 1

            } else {

                0
            }


        val symmetryBad =
            kneeDifference >
                    MAX_KNEE_DIFFERENCE


        badSymmetryFrames =
            if (symmetryBad) {

                badSymmetryFrames + 1

            } else {

                0
            }


        val confirmedTorsoProblem =
            badTorsoFrames >=
                    BAD_POSTURE_CONFIRM_FRAMES


        val confirmedSymmetryProblem =
            badSymmetryFrames >=
                    BAD_POSTURE_CONFIRM_FRAMES


        // =====================================================
        // FEEDBACK PRIORITY
        // =====================================================

        val feedback =

            when {

                confirmedTorsoProblem ->

                    "Keep your chest more upright"


                confirmedSymmetryProblem ->

                    "Keep both legs balanced"


                phase ==
                        SquatExercisePhase.STANDING &&
                        lastRepWasShallow ->

                    "Last rep was shallow - go lower"


                phase ==
                        SquatExercisePhase.STANDING ->

                    "Ready - squat down"


                phase ==
                        SquatExercisePhase.DESCENDING ->

                    "Lower with control"


                phase ==
                        SquatExercisePhase.BOTTOM &&
                        averageKneeAngle <=
                        GOOD_DEPTH_ANGLE ->

                    "Good depth - drive up"


                phase ==
                        SquatExercisePhase.BOTTOM ->

                    "Depth reached - drive up"


                phase ==
                        SquatExercisePhase.ASCENDING ->

                    "Drive up"


                else ->

                    "Hold position"
            }


        val postureGood =
            !confirmedTorsoProblem &&
                    !confirmedSymmetryProblem


        return SquatExerciseResult(
            bodyVisible = true,
            phase = phase,
            repCount = repCount,

            leftKneeAngle = leftKneeAngle,
            rightKneeAngle = rightKneeAngle,
            averageKneeAngle = averageKneeAngle,

            leftHipAngle = leftHipAngle,
            rightHipAngle = rightHipAngle,

            torsoLeanAngle = torsoLeanAngle,

            postureGood = postureGood,
            feedback = feedback
        )
    }


    // =========================================================
    // POSE LOST HANDLING
    // =========================================================

    /*
     * MainActivity already calls onPoseLost() whenever MediaPipe
     * temporarily returns no pose.
     *
     * V1 reset immediately.
     *
     * V3 instead gives a short grace period.
     */
    fun onPoseLost() {

        registerPoseMissing()
    }


    private fun registerPoseMissing() {

        missingPoseFrames++


        if (
            missingPoseFrames >
            POSE_LOST_GRACE_FRAMES
        ) {

            resetMovementState()
        }
    }


    /*
     * Keep the completed rep count.
     *
     * Only clear the current movement when pose has really been
     * missing for several consecutive frames.
     */
    private fun resetMovementState() {

        phase =
            SquatExercisePhase.STANDING

        smoothedKneeAngle =
            null

        previousKneeAngle =
            null

        lowestKneeAngleThisRep =
            Double.POSITIVE_INFINITY

        reachedRepDepth =
            false

        badTorsoFrames =
            0

        badSymmetryFrames =
            0
    }


    fun resetSession() {

        phase =
            SquatExercisePhase.STANDING

        repCount =
            0

        smoothedKneeAngle =
            null

        previousKneeAngle =
            null

        lowestKneeAngleThisRep =
            Double.POSITIVE_INFINITY

        reachedRepDepth =
            false

        lastRepWasShallow =
            false

        badTorsoFrames =
            0

        badSymmetryFrames =
            0

        missingPoseFrames =
            0
    }


    // =========================================================
    // ROBUST SQUAT STATE MACHINE
    // =========================================================

    private fun updatePhase(
        kneeAngle: Double
    ) {

        val previous =
            previousKneeAngle


        val delta =

            if (previous == null) {

                0.0

            } else {

                kneeAngle -
                        previous
            }


        val movingDown =
            delta <
                    -MOVEMENT_EPSILON


        val movingUp =
            delta >
                    MOVEMENT_EPSILON


        when (phase) {

            // -------------------------------------------------
            // STANDING
            // -------------------------------------------------

            SquatExercisePhase.STANDING -> {

                if (
                    kneeAngle <
                    START_DESCENT_ANGLE
                ) {

                    phase =
                        SquatExercisePhase.DESCENDING


                    lowestKneeAngleThisRep =
                        kneeAngle


                    reachedRepDepth =
                        kneeAngle <=
                                REP_DEPTH_ANGLE
                }
            }


            // -------------------------------------------------
            // DESCENDING
            // -------------------------------------------------

            SquatExercisePhase.DESCENDING -> {

                lowestKneeAngleThisRep =
                    min(
                        lowestKneeAngleThisRep,
                        kneeAngle
                    )


                if (
                    lowestKneeAngleThisRep <=
                    REP_DEPTH_ANGLE
                ) {

                    reachedRepDepth =
                        true
                }


                when {

                    /*
                     * We reached valid rep depth.
                     */
                    reachedRepDepth -> {

                        phase =
                            SquatExercisePhase.BOTTOM
                    }


                    /*
                     * User starts coming back up before reaching
                     * valid depth.
                     */
                    movingUp -> {

                        phase =
                            SquatExercisePhase.ASCENDING
                    }


                    /*
                     * Noise / quick return to standing.
                     */
                    kneeAngle >=
                            STANDING_ANGLE -> {

                        finishCurrentRep()
                    }
                }
            }


            // -------------------------------------------------
            // BOTTOM
            // -------------------------------------------------

            SquatExercisePhase.BOTTOM -> {

                lowestKneeAngleThisRep =
                    min(
                        lowestKneeAngleThisRep,
                        kneeAngle
                    )


                if (
                    movingUp &&
                    kneeAngle >
                    REP_DEPTH_ANGLE + 3.0
                ) {

                    phase =
                        SquatExercisePhase.ASCENDING
                }
            }


            // -------------------------------------------------
            // ASCENDING
            // -------------------------------------------------

            SquatExercisePhase.ASCENDING -> {

                lowestKneeAngleThisRep =
                    min(
                        lowestKneeAngleThisRep,
                        kneeAngle
                    )


                if (
                    lowestKneeAngleThisRep <=
                    REP_DEPTH_ANGLE
                ) {

                    reachedRepDepth =
                        true
                }


                when {

                    /*
                     * User dips back down again.
                     */
                    movingDown &&
                            kneeAngle <
                            START_DESCENT_ANGLE -> {

                        phase =
                            SquatExercisePhase.DESCENDING
                    }


                    /*
                     * Rep finishes only after returning close
                     * to standing.
                     */
                    kneeAngle >=
                            STANDING_ANGLE -> {

                        finishCurrentRep()
                    }
                }
            }
        }


        previousKneeAngle =
            kneeAngle
    }


    private fun finishCurrentRep() {

        if (reachedRepDepth) {

            repCount++

            lastRepWasShallow =
                false

        } else {

            lastRepWasShallow =
                true
        }


        phase =
            SquatExercisePhase.STANDING


        lowestKneeAngleThisRep =
            Double.POSITIVE_INFINITY


        reachedRepDepth =
            false
    }


    // =========================================================
    // LANDMARK QUALITY
    // =========================================================

    private fun isUsable(
        landmark: NormalizedLandmark
    ): Boolean {

        val visibility =
            landmark
                .visibility()
                .orElse(0f)


        return visibility >=
                MIN_VISIBILITY &&
                landmark.x() in
                0f..1f &&
                landmark.y() in
                0f..1f
    }


    // =========================================================
    // ANGLE CALCULATIONS
    // =========================================================

    /*
     * Returns angle ABC in degrees.
     *
     * A = first point
     * B = joint / vertex
     * C = third point
     */
    private fun calculateAngle(
        a: NormalizedLandmark,
        b: NormalizedLandmark,
        c: NormalizedLandmark
    ): Double {

        val radians =
            atan2(
                (c.y() - b.y()).toDouble(),
                (c.x() - b.x()).toDouble()
            ) -
                    atan2(
                        (a.y() - b.y()).toDouble(),
                        (a.x() - b.x()).toDouble()
                    )


        var angle =
            abs(
                Math.toDegrees(
                    radians
                )
            )


        if (
            angle >
            180.0
        ) {

            angle =
                360.0 -
                        angle
        }


        return angle
    }


    /*
     * 0 degrees  = torso approximately vertical
     * 90 degrees = torso approximately horizontal
     *
     * Uses the midpoint of both shoulders and both hips.
     */
    private fun calculateTorsoLean(
        leftShoulder: NormalizedLandmark,
        rightShoulder: NormalizedLandmark,
        leftHip: NormalizedLandmark,
        rightHip: NormalizedLandmark
    ): Double {

        val shoulderX =
            (
                    leftShoulder.x() +
                            rightShoulder.x()
                    ) / 2f


        val shoulderY =
            (
                    leftShoulder.y() +
                            rightShoulder.y()
                    ) / 2f


        val hipX =
            (
                    leftHip.x() +
                            rightHip.x()
                    ) / 2f


        val hipY =
            (
                    leftHip.y() +
                            rightHip.y()
                    ) / 2f


        val horizontalMovement =
            (
                    shoulderX -
                            hipX
                    ).toDouble()


        val verticalMovement =
            (
                    hipY -
                            shoulderY
                    ).toDouble()


        return abs(
            Math.toDegrees(
                atan2(
                    horizontalMovement,
                    verticalMovement
                )
            )
        )
    }


    // =========================================================
    // SMOOTHING
    // =========================================================

    private fun smoothKneeAngle(
        value: Double
    ): Double {

        val previous =
            smoothedKneeAngle


        val smoothed =

            if (
                previous ==
                null
            ) {

                value

            } else {

                (
                        SMOOTHING_ALPHA *
                                value
                        ) +
                        (
                                (1.0 -
                                        SMOOTHING_ALPHA) *
                                        previous
                                )
            }


        smoothedKneeAngle =
            smoothed


        return smoothed
    }


    // =========================================================
    // INVALID RESULT
    // =========================================================

    private fun invalidResult(
        message: String
    ): SquatExerciseResult {

        return SquatExerciseResult(
            bodyVisible = false,

            /*
             * During the grace period this preserves the current
             * phase instead of pretending the rep immediately ended.
             */
            phase = phase,

            repCount = repCount,

            leftKneeAngle = 0.0,
            rightKneeAngle = 0.0,
            averageKneeAngle = 0.0,

            leftHipAngle = 0.0,
            rightHipAngle = 0.0,

            torsoLeanAngle = 0.0,

            postureGood = false,
            feedback = message
        )
    }
}
