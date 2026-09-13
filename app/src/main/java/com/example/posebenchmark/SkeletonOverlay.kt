package com.example.posebenchmark

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.min

/** Visualization only. Call setters on the UI thread, as MainActivity already does. */
class SkeletonOverlay(context: Context) : View(context) {
    companion object {
        // Drawing sizes are dp; outline widths are the border on each side.
        const val JOINT_RADIUS = 10f
        const val JOINT_OUTLINE_WIDTH = 3f
        const val BONE_WIDTH = 7f
        const val BONE_OUTLINE_WIDTH = 3f
        const val ARROW_WIDTH = 8f
        const val ARROW_SIZE = 64f
        const val ARROW_OUTLINE_WIDTH = 3f
        const val ARROW_HEAD_SIZE = 20f
        const val ARROW_GAP = 20f
        const val ARROW_TARGET_SIDE_GAP = 40f
        const val TARGET_MARKER_RADIUS = 14f
        const val TARGET_MARKER_WIDTH = 3f
        const val TARGET_FILL_ALPHA = 65

        private val JOINTS = intArrayOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
        private val BONES = arrayOf(
            11 to 12, 11 to 13, 13 to 15, 12 to 14, 14 to 16,
            11 to 23, 12 to 24, 23 to 24,
            23 to 25, 25 to 27, 24 to 26, 26 to 28
        )

        private fun colorFor(state: PostureVisualState): Int = when (state) {
            PostureVisualState.NORMAL -> Color.WHITE
            PostureVisualState.GOOD -> Color.GREEN
            PostureVisualState.WARNING -> Color.YELLOW
            PostureVisualState.WRONG -> Color.RED
        }
    }

    private val density = resources.displayMetrics.density
    private var landmarks: List<NormalizedLandmark> = emptyList()
    private var imageWidth = 1
    private var imageHeight = 1
    private val jointStates = Array(33) { PostureVisualState.NORMAL }
    private val directions = arrayOfNulls<CorrectionDirection>(33)
    private val boneStates = Array(BONES.size) { PostureVisualState.NORMAL }
    private val filters = Array(33) { LandmarkDisplayFilter() }
    private val targets = arrayOfNulls<TargetJointPosition>(33)
    private val renderable = BooleanArray(33)
    private var displayedWidth = 0f
    private var displayedHeight = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private val screenX = FloatArray(33)
    private val screenY = FloatArray(33)

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val jointOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private fun strokePaint(widthDp: Float, paintColor: Int = Color.WHITE) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = widthDp * density
            color = paintColor
        }
    private val bonePaint = strokePaint(BONE_WIDTH)
    private val boneOutlinePaint = strokePaint(BONE_WIDTH + 2 * BONE_OUTLINE_WIDTH, Color.BLACK)
    private val arrowPaint = strokePaint(ARROW_WIDTH)
    private val arrowOutlinePaint = strokePaint(ARROW_WIDTH + 2 * ARROW_OUTLINE_WIDTH, Color.BLACK)

    private val targetPaint = strokePaint(TARGET_MARKER_WIDTH)
    private val targetOutlinePaint = strokePaint(TARGET_MARKER_WIDTH + 2 * JOINT_OUTLINE_WIDTH, Color.BLACK)
    private val targetFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setLandmarks(newLandmarks: List<NormalizedLandmark>, frameWidth: Int, frameHeight: Int) {
        if (frameWidth != imageWidth || frameHeight != imageHeight) clear()
        if (newLandmarks.isEmpty() || frameWidth <= 0 || frameHeight <= 0) {
            clear()
            return
        }
        for (index in JOINTS) {
            val point = newLandmarks.getOrNull(index)
            if (point == null) filters[index].reset()
            else filters[index].update(point.x(), point.y(), point.visibility().orElse(0f))
        }
        landmarks = newLandmarks
        imageWidth = frameWidth
        imageHeight = frameHeight
        invalidate()
    }

    /** Replaces all guidance. Unspecified joints/bones return to NORMAL; last entry wins.
     * Connections are explicit so an analyzer can highlight only the segments it intends.
     * Guidance persists across frames until replaced or clear() is called.
     */
    fun setGuidance(guidance: PostureGuidance) {
        jointStates.fill(PostureVisualState.NORMAL)
        boneStates.fill(PostureVisualState.NORMAL)
        directions.fill(null)
        targets.fill(null)
        for (joint in guidance.joints) {
            if (joint.landmarkIndex !in JOINTS) continue
            jointStates[joint.landmarkIndex] = joint.state
            directions[joint.landmarkIndex] = joint.direction
            val target = joint.target
            targets[joint.landmarkIndex] = target?.takeIf {
                it.x in 0f..1f && it.y in 0f..1f
            }
        }
        for (connection in guidance.connections) {
            for (i in BONES.indices) {
                val (start, end) = BONES[i]
                if ((start == connection.startIndex && end == connection.endIndex) ||
                    (start == connection.endIndex && end == connection.startIndex)) {
                    boneStates[i] = connection.state
                }
            }
        }
        invalidate()
    }

    /** Also call before switching cameras, even if the new frame dimensions match. */
    fun clear() {
        landmarks = emptyList()
        for (filter in filters) filter.reset()
        renderable.fill(false)
        setGuidance(PostureGuidance())
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
