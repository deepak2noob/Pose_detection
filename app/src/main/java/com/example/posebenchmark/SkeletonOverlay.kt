package com.example.posebenchmark

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.min

class SkeletonOverlay(
    context: Context
) : View(context) {

    companion object {
        private const val MIN_VISIBILITY = 0.4f
    }

    private var landmarks: List<NormalizedLandmark> = emptyList()

    private var imageWidth = 1
    private var imageHeight = 1

    private val pointPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val connections = listOf(

        // Face
        0 to 1,
        1 to 2,
        2 to 3,
        3 to 7,

        0 to 4,
        4 to 5,
        5 to 6,
        6 to 8,

        9 to 10,

        // Shoulders
        11 to 12,

        // Left arm
        11 to 13,
        13 to 15,

        // Right arm
        12 to 14,
        14 to 16,

        // Left hand
        15 to 17,
        15 to 19,
        15 to 21,
        17 to 19,

        // Right hand
        16 to 18,
        16 to 20,
        16 to 22,
        18 to 20,

        // Torso
        11 to 23,
        12 to 24,
        23 to 24,

        // Left leg
        23 to 25,
        25 to 27,
        27 to 29,
        29 to 31,
        27 to 31,

        // Right leg
        24 to 26,
        26 to 28,
        28 to 30,
        30 to 32,
        28 to 32
    )

    fun setLandmarks(
        newLandmarks: List<NormalizedLandmark>,
        frameWidth: Int,
        frameHeight: Int
    ) {
        landmarks = newLandmarks

        imageWidth = frameWidth
        imageHeight = frameHeight

        postInvalidate()
    }

    fun clear() {
        landmarks = emptyList()
        postInvalidate()
    }

    /*
     * Draw only landmarks that are likely visible
     * and actually lie inside the camera frame.
     */
    private fun isVisible(
        landmark: NormalizedLandmark
    ): Boolean {

        val visibility =
            landmark.visibility().orElse(0f)

        return visibility >= MIN_VISIBILITY &&
                landmark.x() in 0f..1f &&
                landmark.y() in 0f..1f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (
            landmarks.isEmpty() ||
            imageWidth <= 0 ||
            imageHeight <= 0
        ) {
            return
        }

        /*
         * PreviewView uses FIT_CENTER.
         */

        val scaleFactor = min(
            width.toFloat() / imageWidth,
            height.toFloat() / imageHeight
        )

        val displayedWidth =
            imageWidth * scaleFactor

        val displayedHeight =
            imageHeight * scaleFactor

        val offsetX =
            (width - displayedWidth) / 2f

        val offsetY =
            (height - displayedHeight) / 2f


        fun getX(
            landmark: NormalizedLandmark
        ): Float {

            return (
                    landmark.x() *
                            imageWidth *
                            scaleFactor
                    ) + offsetX
        }


        fun getY(
            landmark: NormalizedLandmark
        ): Float {

            return (
                    landmark.y() *
                            imageHeight *
                            scaleFactor
                    ) + offsetY
        }


        // -------------------------
        // Draw visible lines only
        // -------------------------

        for ((startIndex, endIndex) in connections) {

            if (
                startIndex >= landmarks.size ||
                endIndex >= landmarks.size
            ) {
                continue
            }

            val start =
                landmarks[startIndex]

            val end =
                landmarks[endIndex]


            /*
             * Do not draw a bone if either endpoint
             * is considered hidden / unreliable.
             */
            if (
                !isVisible(start) ||
                !isVisible(end)
            ) {
                continue
            }


            canvas.drawLine(
                getX(start),
                getY(start),
                getX(end),
                getY(end),
                linePaint
            )
        }


        // -------------------------
        // Draw visible points only
        // -------------------------

        for (landmark in landmarks) {

            if (!isVisible(landmark)) {
                continue
            }


            canvas.drawCircle(
                getX(landmark),
                getY(landmark),
                8f,
                pointPaint
            )
        }
    }
}