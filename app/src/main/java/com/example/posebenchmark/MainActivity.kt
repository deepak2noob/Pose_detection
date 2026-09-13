package com.example.posebenchmark

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class MainActivity : ComponentActivity() {

    // =========================================================
    // EXERCISE MODE
    // =========================================================

    private enum class ExerciseMode {
        SQUAT,
        PUSH_UP
    }


    /*
     * Volatile because the selector is changed on the UI thread,
     * while pose results may arrive on another thread.
     */
    @Volatile
    private var selectedExercise =
        ExerciseMode.SQUAT


    /*
     * Protect analyzer state while switching exercise modes.
     */
    @Volatile
    private var exerciseSession = 0L

    private val exerciseLock =
        Any()


    // =========================================================
    // UI
    // =========================================================

    private lateinit var previewView: PreviewView

    private lateinit var skeletonOverlay: SkeletonOverlay

    private lateinit var statusText: TextView

    private lateinit var exerciseText: TextView

    private lateinit var squatButton: Button

    private lateinit var pushUpButton: Button


    // =========================================================
    // EXERCISE ANALYZERS
    // =========================================================

    private val squatExercise =
        SquatExercise()


    private val pushUpExercise =
        PushUpExercise()


    private var lastExerciseUiUpdate =
        0L


    // =========================================================
    // CAMERA / MEDIAPIPE
    // =========================================================

    private lateinit var cameraExecutor: ExecutorService

    private var poseLandmarker: PoseLandmarker? = null
    private val pushUpAnalyzer = PushUpAnalyzer()




    // =========================================================
    // FPS COUNTER
    // =========================================================

    private var poseImageWidth =
        0

    private var poseImageHeight =
        0

    private var poseFrameCount =
        0

    private var fpsWindowStart =
        0L


    companion object {

        private const val TAG =
            "PoseBenchmark"


        private const val MODEL_NAME =
            "pose_landmarker_full.task"
    }


    // =========================================================
    // CAMERA PERMISSION
    // =========================================================

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                startCamera()

            } else {

                Toast.makeText(
                    this,
                    "Camera permission is required",
                    Toast.LENGTH_LONG
                ).show()
            }
        }


    // =========================================================
    // ON CREATE
    // =========================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )


        // =====================================================
        // ROOT
        // =====================================================

        val root =
            FrameLayout(this)


        // =====================================================
        // CAMERA PREVIEW
        // =====================================================

        previewView =
            PreviewView(this)


        previewView.scaleType =
            PreviewView.ScaleType.FIT_CENTER


        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )


        // =====================================================
        // SKELETON OVERLAY
        // =====================================================

        skeletonOverlay =
            SkeletonOverlay(this)


        root.addView(
            skeletonOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )


        // =====================================================
        // FPS / MEDIAPIPE STATUS
        // =====================================================

        statusText =
            TextView(this).apply {

                text =
                    "MediaPipe Full\nLoading model..."


                setTextColor(
                    Color.WHITE
                )


                textSize =
                    16f


                setBackgroundColor(
                    Color.argb(
                        150,
                        0,
                        0,
                        0
                    )
                )


                setPadding(
                    24,
                    16,
                    24,
                    16
                )
            }


        val statusParams =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {

                gravity =
                    Gravity.TOP or
                            Gravity.START


                leftMargin =
                    20


                topMargin =
                    20
            }


        root.addView(
            statusText,
            statusParams
        )


        // =====================================================
        // EXERCISE SELECTOR
        // =====================================================

        squatButton =
            Button(this).apply {

                text =
                    "SQUAT"


                isAllCaps =
                    false


                setOnClickListener {

                    selectExercise(
                        ExerciseMode.SQUAT
                    )
                }
            }


        pushUpButton =
            Button(this).apply {

                text =
                    "PUSH-UP"


                isAllCaps =
                    false


                setOnClickListener {

                    selectExercise(
                        ExerciseMode.PUSH_UP
                    )
                }
            }


        val selectorLayout =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL


                gravity =
                    Gravity.CENTER


                setPadding(
                    12,
                    8,
                    12,
                    8
                )


                setBackgroundColor(
                    Color.argb(
                        135,
                        0,
                        0,
                        0
                    )
                )


                addView(
                    squatButton
                )


                addView(
                    pushUpButton
                )
            }


        val selectorParams =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {

                gravity =
                    Gravity.TOP or
                            Gravity.CENTER_HORIZONTAL


                topMargin =
                    28
            }


        root.addView(
            selectorLayout,
            selectorParams
        )


        // =====================================================
        // EXERCISE INFORMATION UI
        // =====================================================

        exerciseText =
            TextView(this).apply {

                text =
                    "SQUAT\n" +
                            "Stand with your full body visible"


                setTextColor(
                    Color.WHITE
                )


                textSize =
                    18f


                gravity =
                    Gravity.CENTER


                setBackgroundColor(
                    Color.argb(
                        175,
                        0,
                        0,
                        0
                    )
                )


                setPadding(
                    28,
                    18,
                    28,
                    18
                )
            }


        val exerciseParams =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {

                gravity =
                    Gravity.BOTTOM or
                            Gravity.CENTER_HORIZONTAL


                leftMargin =
                    20


                rightMargin =
                    20


                bottomMargin =
                    40
            }


        root.addView(
            exerciseText,
            exerciseParams
        )


        setContentView(
            root
        )


        /*
         * SQUAT is selected by default.
         */
        skeletonOverlay.setGuidance(PostureGuidance())
        updateSelectorUi()


        // =====================================================
        // BACKGROUND THREAD
        // =====================================================

        cameraExecutor =
            Executors.newSingleThreadExecutor()


        cameraExecutor.execute {

            setupPoseLandmarker()
        }


        // =====================================================
        // CAMERA PERMISSION
        // =====================================================

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) ==
            PackageManager.PERMISSION_GRANTED
        ) {

            startCamera()

        } else {

            cameraPermissionLauncher.launch(
                Manifest.permission.CAMERA
            )
        }
    }


    // =========================================================
    // EXERCISE SELECTOR
    // =========================================================

    private fun selectExercise(
        newExercise: ExerciseMode
    ) {

        if (
            selectedExercise ==
            newExercise
        ) {

            return
        }


        synchronized(
            exerciseLock
        ) {

            /*
             * Switching exercises starts a clean session.
             *
             * This prevents an unfinished squat/push-up state
             * from being carried into another exercise.
             */
            exerciseSession++
            squatExercise.resetSession()

            pushUpExercise.resetSession()


            selectedExercise =
                newExercise
        }


        lastExerciseUiUpdate =
            0L


        skeletonOverlay.setGuidance(PostureGuidance())
        updateSelectorUi()


        exerciseText.setTextColor(
            Color.WHITE
        )


        exerciseText.text =

            when (newExercise) {

                ExerciseMode.SQUAT ->

                    "SQUAT\n" +
                            "Stand with your full body visible"


                ExerciseMode.PUSH_UP ->

                    "PUSH-UP\n" +
                            "Turn sideways and keep your body visible"
            }


        Log.d(
            TAG,
            "Exercise selected: $newExercise"
        )
    }


    private fun updateSelectorUi() {

        val squatSelected =
            selectedExercise ==
                    ExerciseMode.SQUAT


        squatButton.alpha =
            if (squatSelected) {
                1.0f
            } else {
                0.50f
            }


        pushUpButton.alpha =
            if (squatSelected) {
                0.50f
            } else {
                1.0f
            }


        squatButton.isEnabled =
            !squatSelected


        pushUpButton.isEnabled =
            squatSelected
    }


    // =========================================================
    // MEDIAPIPE SETUP
    // =========================================================

    private fun setupPoseLandmarker() {

        try {

            val baseOptions =
                BaseOptions.builder()

                    .setDelegate(
                        Delegate.CPU
                    )

                    .setModelAssetPath(
                        MODEL_NAME
                    )

                    .build()


            val options =
                PoseLandmarker
                    .PoseLandmarkerOptions
                    .builder()

                    .setBaseOptions(
                        baseOptions
                    )

                    /*
                     * ONE PERSON ONLY.
                     */
                    .setNumPoses(
                        1
                    )

                    .setMinPoseDetectionConfidence(
                        0.5f
                    )

                    .setMinPosePresenceConfidence(
                        0.5f
                    )

                    .setMinTrackingConfidence(
                        0.5f
                    )

                    .setRunningMode(
                        RunningMode.LIVE_STREAM
                    )

                    .setResultListener { result, _ ->

                        onPoseResult(
                            result
                        )
                    }

                    .setErrorListener { error ->

                        Log.e(
                            TAG,
                            "MediaPipe error",
                            error
                        )
                    }

                    .build()


            poseLandmarker =
                PoseLandmarker.createFromOptions(
                    this,
                    options
                )


            fpsWindowStart =
                SystemClock.elapsedRealtime()


            runOnUiThread {

                statusText.text =
                    "MediaPipe Full\nWaiting for pose..."
            }


            Log.d(
                TAG,
                "MediaPipe Pose Landmarker loaded"
            )


        } catch (e: Exception) {

            Log.e(
                TAG,
                "Could not load Pose Landmarker",
                e
            )


            runOnUiThread {

                statusText.text =
                    "MediaPipe model failed to load"
            }
        }
    }


    // =========================================================
    // CAMERAX
    // =========================================================

    private fun startCamera() {

        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(
                this
            )


        cameraProviderFuture.addListener({

            try {

                val cameraProvider =
                    cameraProviderFuture.get()


                // -------------------------------------------------
                // Preview
                // -------------------------------------------------

                val preview =
                    Preview.Builder()
                        .build()
                        .also {

                            it.setSurfaceProvider(
                                previewView.surfaceProvider
                            )
                        }


                // -------------------------------------------------
                // Image Analysis
                // -------------------------------------------------

                val imageAnalysis =
                    ImageAnalysis.Builder()

                        .setBackpressureStrategy(
                            ImageAnalysis
                                .STRATEGY_KEEP_ONLY_LATEST
                        )

                        .setOutputImageFormat(
                            ImageAnalysis
                                .OUTPUT_IMAGE_FORMAT_RGBA_8888
                        )

                        .build()


                imageAnalysis.setAnalyzer(
                    cameraExecutor
                ) { imageProxy ->

                    analyzeFrame(
                        imageProxy
                    )
                }


                // -------------------------------------------------
                // Bind camera
                // -------------------------------------------------

                cameraProvider.unbindAll()


                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )


            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Camera failed",
                    e
                )


                Toast.makeText(
                    this,
                    "Unable to start camera",
                    Toast.LENGTH_LONG
                ).show()
            }


        }, ContextCompat.getMainExecutor(this))
    }


    // =========================================================
    // CAMERA FRAME -> MEDIAPIPE
    // =========================================================

    private fun analyzeFrame(
        imageProxy: ImageProxy
    ) {

        val landmarker =
            poseLandmarker


        if (
            landmarker ==
            null
        ) {

            imageProxy.close()

            return
        }


        val frameTime =
            SystemClock.uptimeMillis()


        val rotationDegrees =
            imageProxy
                .imageInfo
                .rotationDegrees


        // -------------------------------------------------
        // RGBA camera frame -> bitmap
        // -------------------------------------------------

        val bitmapBuffer =
            Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )


        try {

            val buffer =
                imageProxy
                    .planes[0]
                    .buffer


            buffer.rewind()


            bitmapBuffer.copyPixelsFromBuffer(
                buffer
            )


        } catch (e: Exception) {

            Log.e(
                TAG,
                "Frame conversion failed",
                e
            )


            return


        } finally {

            imageProxy.close()
        }


        // -------------------------------------------------
        // Rotate frame
        // -------------------------------------------------

        val matrix =
            Matrix().apply {

                postRotate(
                    rotationDegrees.toFloat()
                )
            }


        val rotatedBitmap =
            Bitmap.createBitmap(
                bitmapBuffer,
                0,
                0,
                bitmapBuffer.width,
                bitmapBuffer.height,
                matrix,
                true
            )


        poseImageWidth =
            rotatedBitmap.width


        poseImageHeight =
            rotatedBitmap.height


        // -------------------------------------------------
        // Bitmap -> MediaPipe image
        // -------------------------------------------------

        val mpImage =
            BitmapImageBuilder(
                rotatedBitmap
            ).build()


        // -------------------------------------------------
        // Async inference
        // -------------------------------------------------

        try {

            landmarker.detectAsync(
                mpImage,
                frameTime
            )


        } catch (e: Exception) {

            Log.e(
                TAG,
                "Pose detection failed",
                e
            )
        }
    }


    // =========================================================
    // MEDIAPIPE RESULT
    // =========================================================

    private fun onPoseResult(
        result: PoseLandmarkerResult
    ) {

        poseFrameCount++


        val currentTime =
            SystemClock.elapsedRealtime()


        val elapsed =
            currentTime -
                    fpsWindowStart


        val poseDetected =
            result
                .landmarks()
                .isNotEmpty()


        /*
         * Only ONE of these will contain a result per frame.
         */
        var squatResult: SquatExerciseResult? =
            null


        var pushUpResult: PushUpExerciseResult? =
            null


        /*
         * Remember which exercise was used for this specific
         * pose result.
         */
        val activeExercise: ExerciseMode
        val activeSession: Long


        // =====================================================
        // SKELETON
        // =====================================================

        if (poseDetected) {
            val landmarks = result.landmarks()[0]
            val frameWidth = poseImageWidth
            val frameHeight = poseImageHeight
            val guidance: PostureGuidance

            synchronized(exerciseLock) {
                activeExercise = selectedExercise
                activeSession = exerciseSession
                guidance = when (activeExercise) {
                    ExerciseMode.SQUAT -> {
                        squatResult = squatExercise.analyze(landmarks)
                        PostureGuidance()
                    }
                    ExerciseMode.PUSH_UP -> {
                        pushUpResult = pushUpExercise.analyze(landmarks)
                        pushUpAnalyzer.analyze(landmarks, frameWidth, frameHeight).guidance
                    }
                }
            }

            runOnUiThread {
                // Reject queued results from a previous session, including A -> B -> A switches.
                if (exerciseSession == activeSession) {
                    skeletonOverlay.setLandmarks(landmarks, frameWidth, frameHeight)
                    // NOT_READY supplies empty guidance without hiding partial landmarks.
                    skeletonOverlay.setGuidance(guidance)
                }
            }
        } else {
            synchronized(exerciseLock) {
                activeExercise = selectedExercise
                activeSession = exerciseSession
                when (activeExercise) {
                    ExerciseMode.SQUAT -> squatExercise.onPoseLost()
                    ExerciseMode.PUSH_UP -> pushUpExercise.onPoseLost()
                }
            }
            runOnUiThread {
                if (exerciseSession == activeSession) skeletonOverlay.clear()
            }
        }

        // =====================================================
        // EXERCISE UI
        // =====================================================

        if (
            currentTime -
            lastExerciseUiUpdate >=
            100
        ) {

            lastExerciseUiUpdate =
                currentTime


            val exerciseDisplay: String

            val exerciseColor: Int


            when (activeExercise) {

                // =============================================
                // SQUAT UI
                // =============================================

                ExerciseMode.SQUAT -> {

                    exerciseDisplay =

                        if (
                            squatResult ==
                            null
                        ) {

                            "SQUAT\nNo pose detected"


                        } else if (
                            !squatResult!!.bodyVisible
                        ) {

                            "SQUAT\n" +
                                    squatResult!!.feedback


                        } else {

                            String.format(
                                Locale.US,

                                "SQUAT  |  Reps: %d\n" +
                                        "Phase: %s\n" +
                                        "Knee: %.0f°\n" +
                                        "Torso: %.0f°\n" +
                                        "%s",

                                squatResult!!.repCount,

                                squatResult!!.phase.name,

                                squatResult!!.averageKneeAngle,

                                squatResult!!.torsoLeanAngle,

                                squatResult!!.feedback
                            )
                        }


                    exerciseColor =

                        if (
                            squatResult !=
                            null &&
                            squatResult!!.bodyVisible &&
                            squatResult!!.postureGood
                        ) {

                            Color.rgb(
                                120,
                                255,
                                120
                            )

                        } else {

                            Color.WHITE
                        }
                }


                // =============================================
                // PUSH-UP UI
                // =============================================

                ExerciseMode.PUSH_UP -> {

                    exerciseDisplay =

                        if (
                            pushUpResult ==
                            null
                        ) {

                            "PUSH-UP\nNo pose detected"


                        } else if (
                            !pushUpResult!!.bodyVisible
                        ) {

                            "PUSH-UP\n" +
                                    pushUpResult!!.feedback


                        } else {

                            String.format(
                                Locale.US,

                                "PUSH-UP  |  Reps: %d\n" +
                                        "Phase: %s\n" +
                                        "Elbow: %.0f°\n" +
                                        "L/R Difference: %.0f°\n" +
                                        "%s",

                                pushUpResult!!.repCount,

                                pushUpResult!!.phase.name,

                                pushUpResult!!.averageElbowAngle,

                                pushUpResult!!.elbowDifference,

                                pushUpResult!!.feedback
                            )
                        }


                    exerciseColor =

                        if (
                            pushUpResult !=
                            null &&
                            pushUpResult!!.bodyVisible &&
                            pushUpResult!!.postureGood
                        ) {

                            Color.rgb(
                                120,
                                255,
                                120
                            )

                        } else {

                            Color.WHITE
                        }
                }
            }


            runOnUiThread {

                /*
                 * Avoid displaying a stale result from the exercise
                 * that was selected just before the user switched.
                 */
                if (
                    exerciseSession == activeSession
                ) {

                    exerciseText.text =
                        exerciseDisplay


                    exerciseText.setTextColor(
                        exerciseColor
                    )
                }
            }
        }


        // =====================================================
        // FPS
        // =====================================================

        val landmarkCount =

            if (poseDetected) {

                result
                    .landmarks()[0]
                    .size

            } else {

                0
            }


        val latency =
            SystemClock.uptimeMillis() -
                    result.timestampMs()


        if (
            elapsed >=
            1000
        ) {

            val fps =
                poseFrameCount *
                        1000f /
                        elapsed


            poseFrameCount =
                0


            fpsWindowStart =
                currentTime


            val poseText =

                if (poseDetected) {

                    "YES"

                } else {

                    "NO"
                }


            val displayText =
                String.format(
                    Locale.US,

                    "MediaPipe Full\n" +
                            "Pose FPS: %.1f\n" +
                            "Pose: %s\n" +
                            "Landmarks: %d\n" +
                            "Latency: %d ms",

                    fps,
                    poseText,
                    landmarkCount,
                    latency
                )


            runOnUiThread {

                statusText.text =
                    displayText
            }


            // =================================================
            // DEBUG LOG FOR SELECTED EXERCISE
            // =================================================

            when (activeExercise) {

                ExerciseMode.SQUAT -> {

                    if (
                        squatResult !=
                        null
                    ) {

                        Log.d(
                            TAG,

                            "Exercise=SQUAT " +
                                    "FPS=${"%.1f".format(fps)} " +
                                    "Pose=$poseText " +
                                    "Reps=${squatResult!!.repCount} " +
                                    "Phase=${squatResult!!.phase} " +
                                    "Knee=${"%.1f".format(squatResult!!.averageKneeAngle)} " +
                                    "Torso=${"%.1f".format(squatResult!!.torsoLeanAngle)} " +
                                    "Feedback=${squatResult!!.feedback}"
                        )
                    }
                }


                ExerciseMode.PUSH_UP -> {

                    if (
                        pushUpResult !=
                        null
                    ) {

                        Log.d(
                            TAG,

                            "Exercise=PUSH_UP " +
                                    "FPS=${"%.1f".format(fps)} " +
                                    "Pose=$poseText " +
                                    "Reps=${pushUpResult!!.repCount} " +
                                    "Phase=${pushUpResult!!.phase} " +
                                    "Elbow=${"%.1f".format(pushUpResult!!.averageElbowAngle)} " +
                                    "Difference=${"%.1f".format(pushUpResult!!.elbowDifference)} " +
                                    "Feedback=${pushUpResult!!.feedback}"
                        )
                    }
                }
            }


            if (
                squatResult ==
                null &&
                pushUpResult ==
                null
            ) {

                Log.d(
                    TAG,

                    "Exercise=$activeExercise " +
                            "FPS=${"%.1f".format(fps)} " +
                            "Pose=$poseText " +
                            "Landmarks=$landmarkCount " +
                            "Latency=${latency}ms"
                )
            }
        }
    }


    // =========================================================
    // CLEANUP
    // =========================================================

    override fun onDestroy() {

        super.onDestroy()


        if (
            ::cameraExecutor
                .isInitialized
        ) {

            cameraExecutor.execute {

                poseLandmarker
                    ?.close()


                poseLandmarker =
                    null
            }


            cameraExecutor.shutdown()
        }
    }
}
