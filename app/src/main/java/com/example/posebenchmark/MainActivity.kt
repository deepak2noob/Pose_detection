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
import android.widget.FrameLayout
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

    private lateinit var previewView: PreviewView
    private lateinit var skeletonOverlay: SkeletonOverlay
    private lateinit var statusText: TextView

    //edited started
    private lateinit var exerciseText: TextView

    private val squatExercise =
        SquatExercise()

    private var lastExerciseUiUpdate =
        0L
    //edited ended

    private lateinit var cameraExecutor: ExecutorService

    private var poseLandmarker: PoseLandmarker? = null



    // -------------------------
    // FPS counter
    // -------------------------

    private var poseImageWidth = 0
    private var poseImageHeight = 0
    private var poseFrameCount = 0
    private var fpsWindowStart = 0L


    companion object {
        private const val TAG = "PoseBenchmark"

        private const val MODEL_NAME =
            "pose_landmarker_full.task"
    }


    // -------------------------
    // Camera permission
    // -------------------------

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


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // -------------------------
        // UI
        // -------------------------

        val root = FrameLayout(this)

        previewView = PreviewView(this)

        previewView.scaleType =
            PreviewView.ScaleType.FIT_CENTER

        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        skeletonOverlay =
            SkeletonOverlay(this)

        root.addView(
            skeletonOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )


        // FPS / pose information overlay

        statusText = TextView(this).apply {

            text = "MediaPipe Full\nLoading model..."

            setTextColor(Color.WHITE)

            textSize = 16f

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
                    Gravity.TOP or Gravity.START

                leftMargin = 20
                topMargin = 20
            }


        root.addView(
            statusText,
            statusParams
        )


// =====================================================
// SQUAT INFORMATION UI
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


        setContentView(root)


        // -------------------------
        // Background thread
        // -------------------------

        cameraExecutor =
            Executors.newSingleThreadExecutor()


        // Load MediaPipe in background

        cameraExecutor.execute {

            setupPoseLandmarker()
        }


        // -------------------------
        // Permission
        // -------------------------

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            startCamera()

        } else {

            cameraPermissionLauncher.launch(
                Manifest.permission.CAMERA
            )
        }
    }


    // =========================================================
    // MEDIAPIPE SETUP
    // =========================================================

    private fun setupPoseLandmarker() {

        try {

            val baseOptions =
                BaseOptions.builder()

                    // Start with CPU.
                    // We will benchmark GPU later.

                    .setDelegate(
                        Delegate.GPU
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

                    // ONE PERSON ONLY

                    .setNumPoses(1)

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


                // -------------------------
                // Preview
                // -------------------------

                val preview =
                    Preview.Builder()
                        .build()
                        .also {

                            it.setSurfaceProvider(
                                previewView.surfaceProvider
                            )
                        }


                // -------------------------
                // Image Analysis
                // -------------------------

                val imageAnalysis =
                    ImageAnalysis.Builder()

                        .setBackpressureStrategy(
                            ImageAnalysis
                                .STRATEGY_KEEP_ONLY_LATEST
                        )

                        /*
                         * MediaPipe sample works with
                         * RGBA frames.
                         */

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


                // -------------------------
                // Bind camera
                // -------------------------

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

        Log.d(
            TAG,
            "Camera analysis = ${imageProxy.width}x${imageProxy.height}"
        )


        // Model still loading

        if (landmarker == null) {

            imageProxy.close()

            return
        }


        val frameTime =
            SystemClock.uptimeMillis()


        val rotationDegrees =
            imageProxy
                .imageInfo
                .rotationDegrees


        // Create bitmap from RGBA camera frame

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

            /*
             * ALWAYS close ImageProxy.
             */

            imageProxy.close()
        }


        // Rotate frame correctly

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

        poseImageWidth = rotatedBitmap.width
        poseImageHeight = rotatedBitmap.height


        // Bitmap -> MediaPipe image

        val mpImage =
            BitmapImageBuilder(
                rotatedBitmap
            ).build()


        // Async inference

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


        var squatResult: SquatExerciseResult? =
            null


        // =====================================================
        // POSE + SQUAT
        // =====================================================

        if (poseDetected) {

            val landmarks =
                result.landmarks()[0]


            // Draw visible skeleton.
            skeletonOverlay.setLandmarks(
                landmarks,
                poseImageWidth,
                poseImageHeight
            )


            // Analyze squat using the same landmarks.
            squatResult =
                squatExercise.analyze(
                    landmarks
                )


        } else {

            skeletonOverlay.clear()

            squatExercise.onPoseLost()
        }


        // =====================================================
        // SQUAT UI
        // =====================================================

        if (
            currentTime -
            lastExerciseUiUpdate >= 100 
        ) {

            lastExerciseUiUpdate =
                currentTime


            val exerciseDisplay =

                if (
                    squatResult == null
                ) {

                    "SQUAT\nNo pose detected"

                } else if (
                    !squatResult.bodyVisible
                ) {

                    "SQUAT\n" +
                            squatResult.feedback

                } else {

                    String.format(
                        Locale.US,

                        "SQUAT  |  Reps: %d\n" +
                                "Phase: %s\n" +
                                "Knee: %.0f°\n" +
                                "Torso: %.0f°\n" +
                                "%s",

                        squatResult.repCount,

                        squatResult.phase.name,

                        squatResult.averageKneeAngle,

                        squatResult.torsoLeanAngle,

                        squatResult.feedback
                    )
                }


            val exerciseColor =

                if (
                    squatResult != null &&
                    squatResult.bodyVisible &&
                    squatResult.postureGood
                ) {

                    Color.rgb(
                        120,
                        255,
                        120
                    )

                } else {

                    Color.WHITE
                }


            runOnUiThread {

                exerciseText.text =
                    exerciseDisplay

                exerciseText.setTextColor(
                    exerciseColor
                )
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


        if (elapsed >= 1000) {

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


            if (squatResult != null) {

                Log.d(
                    TAG,

                    "FPS=${"%.1f".format(fps)} " +
                            "Pose=$poseText " +
                            "Reps=${squatResult.repCount} " +
                            "Phase=${squatResult.phase} " +
                            "Knee=${"%.1f".format(squatResult.averageKneeAngle)} " +
                            "Torso=${"%.1f".format(squatResult.torsoLeanAngle)} " +
                            "Feedback=${squatResult.feedback}"
                )

            } else {

                Log.d(
                    TAG,

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


        if (::cameraExecutor.isInitialized) {

            cameraExecutor.execute {

                poseLandmarker?.close()

                poseLandmarker = null
            }


            cameraExecutor.shutdown()
        }
    }
}