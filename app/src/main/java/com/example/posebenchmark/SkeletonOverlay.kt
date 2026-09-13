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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (landmarks.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return
        updateScreenCoordinates()
        // Separate rendering layers leave room for future target/ghost guidance.
        drawBones(canvas)
        drawJoints(canvas)
        drawCorrections(canvas)
    }

    private fun updateScreenCoordinates() {
        // Preserve the camera's FIT_CENTER scale and letterbox offsets.
        val scale = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        displayedWidth = imageWidth * scale
        displayedHeight = imageHeight * scale
        offsetX = (width - displayedWidth) / 2f
        offsetY = (height - displayedHeight) / 2f
        for (index in JOINTS) {
            val filter = filters[index]
            renderable[index] = filter.visible
            if (filter.visible) {
                screenX[index] = filter.x * displayedWidth + offsetX
                screenY[index] = filter.y * displayedHeight + offsetY
            }
        }
    }

    private fun drawBones(canvas: Canvas) {
        for (i in BONES.indices) {
            val (start, end) = BONES[i]
            if (!renderable[start] || !renderable[end]) continue
            bonePaint.color = colorFor(boneStates[i])
            canvas.drawLine(screenX[start], screenY[start], screenX[end], screenY[end], boneOutlinePaint)
            canvas.drawLine(screenX[start], screenY[start], screenX[end], screenY[end], bonePaint)
        }
    }

    private fun drawJoints(canvas: Canvas) {
        for (index in JOINTS) {
            if (!renderable[index]) continue
            jointPaint.color = colorFor(jointStates[index])
            canvas.drawCircle(screenX[index], screenY[index],
                (JOINT_RADIUS + JOINT_OUTLINE_WIDTH) * density, jointOutlinePaint)
            canvas.drawCircle(screenX[index], screenY[index], JOINT_RADIUS * density, jointPaint)
        }
    }

    private fun drawCorrections(canvas: Canvas) {
        for (index in JOINTS) {
            if (!renderable[index]) continue
            val target = targets[index]
            if (target != null) drawTarget(canvas, index, target)
            val direction = directions[index] ?: continue
            if (direction == CorrectionDirection.NONE) continue
            val dx = when (direction) {
                CorrectionDirection.LEFT -> -1f
                CorrectionDirection.RIGHT -> 1f
                else -> 0f
            }
            val dy = when (direction) {
                CorrectionDirection.UP -> -1f
                CorrectionDirection.DOWN -> 1f
                else -> 0f
            }
            if (target != null) {
                val targetX = target.x * displayedWidth + offsetX
                val targetY = target.y * displayedHeight + offsetY
                val deltaX = targetX - screenX[index]
                val deltaY = targetY - screenY[index]
                val distance = kotlin.math.hypot(deltaX, deltaY)
                if (distance >= (ARROW_SIZE + ARROW_GAP + TARGET_MARKER_RADIUS) * density) {
                    val unitX = deltaX / distance
                    val unitY = deltaY / distance
                    arrowPaint.color = colorFor(jointStates[index])
                    val fromX = screenX[index] + unitX * ARROW_GAP * density
                    val fromY = screenY[index] + unitY * ARROW_GAP * density
                    val toX = targetX - unitX * TARGET_MARKER_RADIUS * density
                    val toY = targetY - unitY * TARGET_MARKER_RADIUS * density
                    drawArrow(canvas, fromX, fromY, toX, toY, unitX, unitY, arrowOutlinePaint)
                    drawArrow(canvas, fromX, fromY, toX, toY, unitX, unitY, arrowPaint)
                    continue
                }
            }
            // Small corrections still need a large arrow. Place it beside the target
            // instead of running through (and beyond) the desired joint marker.
            val sideGap = if (target != null) ARROW_TARGET_SIDE_GAP * density else 0f
            val startX = screenX[index] + dx * ARROW_GAP * density - dy * sideGap
            val startY = screenY[index] + dy * ARROW_GAP * density + dx * sideGap
            val endX = startX + dx * ARROW_SIZE * density
            val endY = startY + dy * ARROW_SIZE * density
            arrowPaint.color = colorFor(jointStates[index])
            drawArrow(canvas, startX, startY, endX, endY, dx, dy, arrowOutlinePaint)
            drawArrow(canvas, startX, startY, endX, endY, dx, dy, arrowPaint)
        }
    }

    private fun drawTarget(canvas: Canvas, index: Int, target: TargetJointPosition) {
        val x = target.x * displayedWidth + offsetX
        val y = target.y * displayedHeight + offsetY
        val radius = TARGET_MARKER_RADIUS * density
        targetPaint.color = colorFor(jointStates[index])
        targetFillPaint.color = targetPaint.color
        targetFillPaint.alpha = TARGET_FILL_ALPHA
        canvas.drawCircle(x, y, radius, targetFillPaint)
        canvas.drawCircle(x, y, radius, targetOutlinePaint)
        canvas.drawCircle(x, y, radius, targetPaint)
    }

    private fun drawArrow(canvas: Canvas, startX: Float, startY: Float,
                          endX: Float, endY: Float, dx: Float, dy: Float, paint: Paint) {
        val head = ARROW_HEAD_SIZE * density
        canvas.drawLine(startX, startY, endX, endY, paint)
        canvas.drawLine(endX, endY, endX - dx * head - dy * head,
            endY - dy * head + dx * head, paint)
        canvas.drawLine(endX, endY, endX - dx * head + dy * head,
            endY - dy * head - dx * head, paint)
    }
}
