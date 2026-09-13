package com.example.posebenchmark

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt


enum class PushUpPhase {
    TOP,
    DESCENDING,
    BOTTOM,
    ASCENDING
}


enum class PushUpSide {
    LEFT,
    RIGHT
}


data class PushUpExerciseResult(
    val bodyVisible: Boolean,
    val activeSide: PushUpSide?,
    val phase: PushUpPhase,
    val repCount: Int,

    val leftElbowAngle: Double,
    val rightElbowAngle: Double,

    /*
     * Kept for MainActivity compatibility.
     *
     * In this hybrid detector this is the ACTIVE arm's
     * smoothed elbow angle.
     */
    val averageElbowAngle: Double,

    val elbowDifference: Double,
    val shoulderTiltRatio: Double,
    val bodyLineAngle: Double,

    val postureGood: Boolean,
    val feedback: String
)


class PushUpExercise {

    companion object {

        // =====================================================
        // MEDIAPIPE LANDMARK INDEXES
        // =====================================================

        private const val LEFT_SHOULDER = 11
        private const val RIGHT_SHOULDER = 12

        private const val LEFT_ELBOW = 13
        private const val RIGHT_ELBOW = 14

        private const val LEFT_WRIST = 15
        private const val RIGHT_WRIST = 16

        private const val LEFT_HIP = 23
        private const val RIGHT_HIP = 24

        private const val LEFT_ANKLE = 27
        private const val RIGHT_ANKLE = 28


        // =====================================================
        // LANDMARK QUALITY
        // =====================================================

        private const val MIN_VISIBILITY = 0.55f

        private const val MIN_SYMMETRY_VISIBILITY = 0.68f

        private const val POSE_LOST_GRACE_FRAMES = 5


        // =====================================================
        // ADAPTIVE REP DETECTION
        // =====================================================

        /*
         * We first learn the user's "top" elbow angle.
         *
         * It does NOT need to be exactly 160-180 degrees because
         * camera perspective can make MediaPipe report a smaller
         * angle.
         */
        private const val MIN_TOP_REFERENCE_ANGLE = 130.0


        /*
         * Start the push-up after the elbow bends this much
         * from the learned top position.
         */
        private const val START_BEND_FROM_TOP = 12.0


        /*
         * Main rep-depth rule:
         *
         * If elbow bends at least this much compared with the
         * learned TOP reference, the rep is deep enough.
         */
        private const val MIN_REP_BEND = 32.0


        /*
         * Absolute fallback.
         *
         * Useful if the top reference is not perfect.
         */
        private const val ABSOLUTE_DEPTH_FALLBACK = 125.0


        /*
         * "Good depth" coaching only.
         * Not required for the rep to count.
         */
        private const val GOOD_DEPTH_ANGLE = 100.0


        /*
         * The elbow only needs to return close to its learned
         * top angle to finish the rep.
         */
        private const val TOP_RETURN_TOLERANCE = 12.0


        /*
         * Absolute top fallback.
         */
        private const val ABSOLUTE_TOP_FALLBACK = 145.0


        // =====================================================
        // MOVEMENT
        // =====================================================

        private const val MOVEMENT_EPSILON = 0.7

        private const val SMOOTHING_ALPHA = 0.40


        // =====================================================
        // POSTURE THRESHOLDS
        // =====================================================

        private const val MAX_ELBOW_DIFFERENCE = 24.0

        private const val MAX_SHOULDER_TILT_RATIO = 0.18

        private const val MIN_BODY_LINE_ANGLE = 155.0

        private const val BAD_POSTURE_CONFIRM_FRAMES = 3
    }


    // =========================================================
    // SESSION STATE
    // =========================================================

    private var phase =
        PushUpPhase.TOP


    private var repCount =
        0


    /*
     * Best arm used for rep counting.
     *
     * Locked while a rep is in progress.
     */
    private var activeSide: PushUpSide? =
        null


    private var smoothedActiveElbowAngle: Double? =
        null


    private var previousActiveElbowAngle: Double? =
        null


    /*
     * Learned arm-extension angle at the top of the push-up.
     */
    private var topReferenceAngle: Double? =
        null


    /*
     * Lowest elbow angle seen during current rep.
     */
    private var lowestElbowAngleThisRep =
        Double.POSITIVE_INFINITY


    private var reachedRepDepth =
        false


    private var lastRepWasShallow =
        false


    private var badSymmetryFrames =
        0


    private var badShoulderFrames =
        0


    private var badBodyLineFrames =
        0


    private var missingPoseFrames =
        0


    // =========================================================
    // PUBLIC ANALYSIS
    // =========================================================

    fun analyze(
        landmarks: List<NormalizedLandmark>
    ): PushUpExerciseResult {

        if (
            landmarks.size <=
            RIGHT_ANKLE
        ) {

            registerPoseMissing()

            return invalidResult(
                "Move into frame so your upper body is visible"
            )
        }


        // =====================================================
        // LANDMARKS
        // =====================================================

        val leftShoulder =
            landmarks[LEFT_SHOULDER]

        val rightShoulder =
            landmarks[RIGHT_SHOULDER]


        val leftElbow =
            landmarks[LEFT_ELBOW]

        val rightElbow =
            landmarks[RIGHT_ELBOW]


        val leftWrist =
            landmarks[LEFT_WRIST]

        val rightWrist =
            landmarks[RIGHT_WRIST]


        val leftHip =
            landmarks[LEFT_HIP]

        val rightHip =
            landmarks[RIGHT_HIP]


        val leftAnkle =
            landmarks[LEFT_ANKLE]

        val rightAnkle =
            landmarks[RIGHT_ANKLE]


        // =====================================================
        // ARM VISIBILITY
        // =====================================================

        val leftArmUsable =
            isArmUsable(
                leftShoulder,
                leftElbow,
                leftWrist
            )


        val rightArmUsable =
            isArmUsable(
                rightShoulder,
                rightElbow,
                rightWrist
            )


        if (
            !leftArmUsable &&
            !rightArmUsable
        ) {

            registerPoseMissing()

            return invalidResult(
                "Keep at least one shoulder, elbow and wrist visible"
            )
        }


        // =====================================================
        // ACTIVE ARM
        // =====================================================

        val selectedSide =
            chooseActiveSide(
                leftArmUsable = leftArmUsable,
                rightArmUsable = rightArmUsable,

                leftShoulder = leftShoulder,
                leftElbow = leftElbow,
                leftWrist = leftWrist,

                rightShoulder = rightShoulder,
                rightElbow = rightElbow,
                rightWrist = rightWrist
            )


        if (
            selectedSide ==
            null
        ) {

            registerPoseMissing()

            return invalidResult(
                "Keep your active arm visible"
            )
        }


        missingPoseFrames =
            0


        if (
            activeSide !=
            selectedSide
        ) {

            activeSide =
                selectedSide


            smoothedActiveElbowAngle =
                null


            previousActiveElbowAngle =
                null


            /*
             * New arm = learn a fresh top reference.
             */
            topReferenceAngle =
                null
        }


        // =====================================================
        // RAW LEFT / RIGHT ANGLES
        // =====================================================

        val leftElbowAngle =

            if (leftArmUsable) {

                calculateAngle(
                    leftShoulder,
                    leftElbow,
                    leftWrist
                )

            } else {

                0.0
            }


        val rightElbowAngle =

            if (rightArmUsable) {

                calculateAngle(
                    rightShoulder,
                    rightElbow,
                    rightWrist
                )

            } else {

                0.0
            }


        // =====================================================
        // ACTIVE-SIDE LANDMARKS
        // =====================================================

        val activeShoulder: NormalizedLandmark
        val activeElbow: NormalizedLandmark
        val activeWrist: NormalizedLandmark
        val activeHip: NormalizedLandmark
        val activeAnkle: NormalizedLandmark


        if (
            selectedSide ==
            PushUpSide.LEFT
        ) {

            activeShoulder =
                leftShoulder

            activeElbow =
                leftElbow

            activeWrist =
                leftWrist

            activeHip =
                leftHip

            activeAnkle =
                leftAnkle

        } else {

            activeShoulder =
                rightShoulder

            activeElbow =
                rightElbow

            activeWrist =
                rightWrist

            activeHip =
                rightHip

            activeAnkle =
                rightAnkle
        }


        // =====================================================
        // ACTIVE ELBOW ANGLE
        // =====================================================

        val rawActiveElbowAngle =
            calculateAngle(
                activeShoulder,
                activeElbow,
                activeWrist
            )


        val activeElbowAngle =
            smoothActiveElbowAngle(
                rawActiveElbowAngle
            )


        // =====================================================
        // LEARN TOP REFERENCE
        // =====================================================

        /*
         * While at TOP, continuously remember the largest
         * believable extension angle.
         *
         * Example:
         *
         * 148 -> 153 -> 157
         *
         * topReference becomes 157.
         */
        if (
            phase ==
            PushUpPhase.TOP &&
            activeElbowAngle >=
            MIN_TOP_REFERENCE_ANGLE
        ) {

            topReferenceAngle =
                if (
                    topReferenceAngle ==
                    null
                ) {

                    activeElbowAngle

                } else {

                    max(
                        topReferenceAngle!!,
                        activeElbowAngle
                    )
                }
        }


        // =====================================================
        // REP TRACKER
        // =====================================================

        updatePhase(
            activeElbowAngle
        )


        // =====================================================
        // POSTURE - BOTH ARMS
        // =====================================================

        val bothArmsReliable =
            isArmReliableForSymmetry(
                leftShoulder,
                leftElbow,
                leftWrist
            ) &&
                    isArmReliableForSymmetry(
                        rightShoulder,
                        rightElbow,
                        rightWrist
                    )


        val elbowDifference =

            if (bothArmsReliable) {

                abs(
                    leftElbowAngle -
                            rightElbowAngle
                )

            } else {

                0.0
            }


        val shoulderTiltRatio =

            if (bothArmsReliable) {

                calculateShoulderTiltRatio(
                    leftShoulder,
                    rightShoulder
                )

            } else {

                0.0
            }


        // =====================================================
        // POSTURE - SIDE VIEW BODY LINE
        // =====================================================

        val canCheckBodyLine =
            !bothArmsReliable &&
                    isUsable(activeShoulder) &&
                    isUsable(activeHip) &&
                    isUsable(activeAnkle)


        val bodyLineAngle =

            if (canCheckBodyLine) {

                calculateAngle(
                    activeShoulder,
                    activeHip,
                    activeAnkle
                )

            } else {

                180.0
            }


        // =====================================================
        // PHASE-SPECIFIC POSTURE
        // =====================================================

        val shouldCheckPosture =
            phase ==
                    PushUpPhase.DESCENDING ||
                    phase ==
                    PushUpPhase.BOTTOM ||
                    phase ==
                    PushUpPhase.ASCENDING


        val symmetryBad =
            shouldCheckPosture &&
                    bothArmsReliable &&
                    elbowDifference >
                    MAX_ELBOW_DIFFERENCE


        badSymmetryFrames =

            if (symmetryBad) {

                badSymmetryFrames + 1

            } else {

                0
            }


        val shoulderBalanceBad =
            shouldCheckPosture &&
                    bothArmsReliable &&
                    shoulderTiltRatio >
                    MAX_SHOULDER_TILT_RATIO


        badShoulderFrames =

            if (shoulderBalanceBad) {

                badShoulderFrames + 1

            } else {

                0
            }


        val bodyLineBad =
            shouldCheckPosture &&
                    canCheckBodyLine &&
                    bodyLineAngle <
                    MIN_BODY_LINE_ANGLE


        badBodyLineFrames =

            if (bodyLineBad) {

                badBodyLineFrames + 1

            } else {

                0
            }


        val confirmedSymmetryProblem =
            badSymmetryFrames >=
                    BAD_POSTURE_CONFIRM_FRAMES


        val confirmedShoulderProblem =
            badShoulderFrames >=
                    BAD_POSTURE_CONFIRM_FRAMES


        val confirmedBodyLineProblem =
            badBodyLineFrames >=
                    BAD_POSTURE_CONFIRM_FRAMES


        // =====================================================
        // FEEDBACK
        // =====================================================

        val reference =
            topReferenceAngle


        val feedback =

            when {

                /*
                 * Before counting reps reliably, first learn
                 * the user's extended-arm position.
                 */
                reference ==
                        null ->

                    "Extend your arm at the top"


                confirmedBodyLineProblem ->

                    "Keep your body straight"


                confirmedSymmetryProblem ->

                    "Lower both arms evenly"


                confirmedShoulderProblem ->

                    "Keep your shoulders level"


                phase ==
                        PushUpPhase.TOP &&
                        lastRepWasShallow ->

                    "Last rep was shallow - go lower"


                phase ==
                        PushUpPhase.TOP ->

                    "Ready - lower your body"


                phase ==
                        PushUpPhase.DESCENDING ->

                    "Lower with control"


                phase ==
                        PushUpPhase.BOTTOM &&
                        activeElbowAngle <=
                        GOOD_DEPTH_ANGLE ->

                    "Good depth - push up"


                phase ==
                        PushUpPhase.BOTTOM ->

                    "Depth reached - push up"


                phase ==
                        PushUpPhase.ASCENDING ->

                    "Push up"


                else ->

                    "Hold position"
            }


        val postureGood =
            !confirmedSymmetryProblem &&
                    !confirmedShoulderProblem &&
                    !confirmedBodyLineProblem


        return PushUpExerciseResult(
            bodyVisible = true,
            activeSide = selectedSide,
            phase = phase,
            repCount = repCount,

            leftElbowAngle = leftElbowAngle,
            rightElbowAngle = rightElbowAngle,

            averageElbowAngle = activeElbowAngle,

            elbowDifference = elbowDifference,
            shoulderTiltRatio = shoulderTiltRatio,
            bodyLineAngle = bodyLineAngle,

            postureGood = postureGood,
            feedback = feedback
        )
    }


    // =========================================================
    // ACTIVE SIDE SELECTION
    // =========================================================

    private fun chooseActiveSide(
        leftArmUsable: Boolean,
        rightArmUsable: Boolean,

        leftShoulder: NormalizedLandmark,
        leftElbow: NormalizedLandmark,
        leftWrist: NormalizedLandmark,

        rightShoulder: NormalizedLandmark,
        rightElbow: NormalizedLandmark,
        rightWrist: NormalizedLandmark
    ): PushUpSide? {

        /*
         * LOCK selected side while a rep is in progress.
         */
        if (
            phase !=
            PushUpPhase.TOP
        ) {

            return when (activeSide) {

                PushUpSide.LEFT ->

                    if (leftArmUsable) {
                        PushUpSide.LEFT
                    } else {
                        null
                    }


                PushUpSide.RIGHT ->

                    if (rightArmUsable) {
                        PushUpSide.RIGHT
                    } else {
                        null
                    }


                null ->

                    null
            }
        }


        /*
         * At TOP we can select whichever arm is clearer.
         */

        if (
            leftArmUsable &&
            !rightArmUsable
        ) {

            return PushUpSide.LEFT
        }


        if (
            rightArmUsable &&
            !leftArmUsable
        ) {

            return PushUpSide.RIGHT
        }


        if (
            !leftArmUsable &&
            !rightArmUsable
        ) {

            return null
        }


        val leftScore =
            armVisibilityScore(
                leftShoulder,
                leftElbow,
                leftWrist
            )


        val rightScore =
            armVisibilityScore(
                rightShoulder,
                rightElbow,
                rightWrist
            )


        return if (
            leftScore >=
            rightScore
        ) {

            PushUpSide.LEFT

        } else {

            PushUpSide.RIGHT
        }
    }


    // =========================================================
    // ADAPTIVE PUSH-UP STATE MACHINE
    // =========================================================

    private fun updatePhase(
        elbowAngle: Double
    ) {

        val previous =
            previousActiveElbowAngle


        val delta =

            if (
                previous ==
                null
            ) {

                0.0

            } else {

                elbowAngle -
                        previous
            }


        val movingDown =
            delta <
                    -MOVEMENT_EPSILON


        val movingUp =
            delta >
                    MOVEMENT_EPSILON


        val reference =
            topReferenceAngle


        when (phase) {

            // -------------------------------------------------
            // TOP
            // -------------------------------------------------

            PushUpPhase.TOP -> {

                /*
                 * We need a learned top reference before starting
                 * a new rep.
                 */
                if (
                    reference !=
                    null
                ) {

                    val bendFromTop =
                        reference -
                                elbowAngle


                    if (
                        movingDown &&
                        bendFromTop >=
                        START_BEND_FROM_TOP
                    ) {

                        phase =
                            PushUpPhase.DESCENDING


                        lowestElbowAngleThisRep =
                            elbowAngle


                        reachedRepDepth =
                            isRepDeepEnough(
                                reference,
                                lowestElbowAngleThisRep
                            )
                    }
                }
            }


            // -------------------------------------------------
            // DESCENDING
            // -------------------------------------------------

            PushUpPhase.DESCENDING -> {

                lowestElbowAngleThisRep =
                    min(
                        lowestElbowAngleThisRep,
                        elbowAngle
                    )


                if (
                    reference !=
                    null &&
                    isRepDeepEnough(
                        reference,
                        lowestElbowAngleThisRep
                    )
                ) {

                    reachedRepDepth =
                        true
                }


                if (
                    reachedRepDepth
                ) {

                    /*
                     * We know enough depth was reached.
                     * Mark BOTTOM even before direction reversal.
                     */
                    phase =
                        PushUpPhase.BOTTOM

                } else if (
                    movingUp
                ) {

                    /*
                     * Reversed too early -> shallow rep.
                     */
                    phase =
                        PushUpPhase.ASCENDING
                }
            }


            // -------------------------------------------------
            // BOTTOM
            // -------------------------------------------------

            PushUpPhase.BOTTOM -> {

                lowestElbowAngleThisRep =
                    min(
                        lowestElbowAngleThisRep,
                        elbowAngle
                    )


                if (
                    movingUp &&
                    elbowAngle >
                    lowestElbowAngleThisRep +
                    4.0
                ) {

                    phase =
                        PushUpPhase.ASCENDING
                }
            }


            // -------------------------------------------------
            // ASCENDING
            // -------------------------------------------------

            PushUpPhase.ASCENDING -> {

                lowestElbowAngleThisRep =
                    min(
                        lowestElbowAngleThisRep,
                        elbowAngle
                    )


                if (
                    reference !=
                    null &&
                    isRepDeepEnough(
                        reference,
                        lowestElbowAngleThisRep
                    )
                ) {

                    reachedRepDepth =
                        true
                }


                if (
                    movingDown &&
                    elbowAngle <
                    previous.orZero()
                ) {

                    /*
                     * User dipped downward again before finishing.
                     */
                    phase =
                        PushUpPhase.DESCENDING

                } else if (
                    reference !=
                    null &&
                    hasReturnedToTop(
                        reference,
                        elbowAngle
                    )
                ) {

                    finishCurrentRep(
                        elbowAngle
                    )
                }
            }
        }


        previousActiveElbowAngle =
            elbowAngle
    }


    private fun isRepDeepEnough(
        topAngle: Double,
        lowestAngle: Double
    ): Boolean {

        val bendAmount =
            topAngle -
                    lowestAngle


        return bendAmount >=
                MIN_REP_BEND ||
                lowestAngle <=
                ABSOLUTE_DEPTH_FALLBACK
    }


    private fun hasReturnedToTop(
        topAngle: Double,
        elbowAngle: Double
    ): Boolean {

        return elbowAngle >=
                topAngle -
                TOP_RETURN_TOLERANCE ||
                elbowAngle >=
                ABSOLUTE_TOP_FALLBACK
    }


    private fun finishCurrentRep(
        finishingAngle: Double
    ) {

        if (
            reachedRepDepth
        ) {

            repCount++

            lastRepWasShallow =
                false

        } else {

            lastRepWasShallow =
                true
        }


        phase =
            PushUpPhase.TOP


        lowestElbowAngleThisRep =
            Double.POSITIVE_INFINITY


        reachedRepDepth =
            false


        /*
         * The user's real top may be slightly different each rep.
         * Update the reference upward, never downward.
         */
        if (
            finishingAngle >=
            MIN_TOP_REFERENCE_ANGLE
        ) {

            topReferenceAngle =

                if (
                    topReferenceAngle ==
                    null
                ) {

                    finishingAngle

                } else {

                    max(
                        topReferenceAngle!!,
                        finishingAngle
                    )
                }
        }
    }


    // =========================================================
    // POSE LOST
    // =========================================================

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


    private fun resetMovementState() {

        phase =
            PushUpPhase.TOP


        activeSide =
            null


        smoothedActiveElbowAngle =
            null


        previousActiveElbowAngle =
            null


        topReferenceAngle =
            null


        lowestElbowAngleThisRep =
            Double.POSITIVE_INFINITY


        reachedRepDepth =
            false


        badSymmetryFrames =
            0


        badShoulderFrames =
            0


        badBodyLineFrames =
            0
    }


    fun resetSession() {

        phase =
            PushUpPhase.TOP


        repCount =
            0


        activeSide =
            null


        smoothedActiveElbowAngle =
            null


        previousActiveElbowAngle =
            null


        topReferenceAngle =
            null


        lowestElbowAngleThisRep =
            Double.POSITIVE_INFINITY


        reachedRepDepth =
            false


        lastRepWasShallow =
            false


        badSymmetryFrames =
            0


        badShoulderFrames =
            0


        badBodyLineFrames =
            0


        missingPoseFrames =
            0
    }


    // =========================================================
    // ARM QUALITY
    // =========================================================

    private fun isArmUsable(
        shoulder: NormalizedLandmark,
        elbow: NormalizedLandmark,
        wrist: NormalizedLandmark
    ): Boolean {

        return isUsable(shoulder) &&
                isUsable(elbow) &&
                isUsable(wrist)
    }


    private fun isArmReliableForSymmetry(
        shoulder: NormalizedLandmark,
        elbow: NormalizedLandmark,
        wrist: NormalizedLandmark
    ): Boolean {

        return visibilityOf(shoulder) >=
                MIN_SYMMETRY_VISIBILITY &&
                visibilityOf(elbow) >=
                MIN_SYMMETRY_VISIBILITY &&
                visibilityOf(wrist) >=
                MIN_SYMMETRY_VISIBILITY &&
                isInsideFrame(shoulder) &&
                isInsideFrame(elbow) &&
                isInsideFrame(wrist)
    }


    private fun armVisibilityScore(
        shoulder: NormalizedLandmark,
        elbow: NormalizedLandmark,
        wrist: NormalizedLandmark
    ): Float {

        return (
                visibilityOf(shoulder) +
                        visibilityOf(elbow) +
                        visibilityOf(wrist)
                ) / 3f
    }


    // =========================================================
    // LANDMARK QUALITY
    // =========================================================

    private fun visibilityOf(
        landmark: NormalizedLandmark
    ): Float {

        return landmark
            .visibility()
            .orElse(0f)
    }


    private fun isInsideFrame(
        landmark: NormalizedLandmark
    ): Boolean {

        return landmark.x() in
                0f..1f &&
                landmark.y() in
                0f..1f
    }


    private fun isUsable(
        landmark: NormalizedLandmark
    ): Boolean {

        return visibilityOf(
            landmark
        ) >= MIN_VISIBILITY &&
                isInsideFrame(
                    landmark
                )
    }


    // =========================================================
    // POSTURE MEASUREMENTS
    // =========================================================

    private fun calculateShoulderTiltRatio(
        leftShoulder: NormalizedLandmark,
        rightShoulder: NormalizedLandmark
    ): Double {

        val shoulderWidth =
            distance(
                leftShoulder,
                rightShoulder
            )


        if (
            shoulderWidth <=
            0.0001
        ) {

            return 0.0
        }


        val verticalDifference =
            abs(
                leftShoulder.y() -
                        rightShoulder.y()
            ).toDouble()


        return verticalDifference /
                shoulderWidth
    }


    // =========================================================
    // ANGLE
    // =========================================================

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


    // =========================================================
    // DISTANCE
    // =========================================================

    private fun distance(
        a: NormalizedLandmark,
        b: NormalizedLandmark
    ): Double {

        val dx =
            (
                    a.x() -
                            b.x()
                    ).toDouble()


        val dy =
            (
                    a.y() -
                            b.y()
                    ).toDouble()


        return sqrt(
            dx * dx +
                    dy * dy
        )
    }


    // =========================================================
    // SMOOTHING
    // =========================================================

    private fun smoothActiveElbowAngle(
        value: Double
    ): Double {

        val previous =
            smoothedActiveElbowAngle


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


        smoothedActiveElbowAngle =
            smoothed


        return smoothed
    }


    // =========================================================
    // SMALL HELPER
    // =========================================================

    private fun Double?.orZero(): Double {

        return this ?: 0.0
    }


    // =========================================================
    // INVALID RESULT
    // =========================================================

    private fun invalidResult(
        message: String
    ): PushUpExerciseResult {

        return PushUpExerciseResult(
            bodyVisible = false,
            activeSide = activeSide,
            phase = phase,
            repCount = repCount,

            leftElbowAngle = 0.0,
            rightElbowAngle = 0.0,
            averageElbowAngle = 0.0,

            elbowDifference = 0.0,
            shoulderTiltRatio = 0.0,
            bodyLineAngle = 0.0,

            postureGood = false,
            feedback = message
        )
    }
}
