package com.example.posebenchmark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.Optional
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkeletonOverlayTest {

    @Test
    fun clearDuringDrawFinishesCurrentFrameAndClearsNextFrame() = onMainThread {
        val overlay = createOverlay()
        overlay.setLandmarks(initialLandmarks(), 100, 200)
        val currentFrame = RecordingCanvas {
            onWorkerThread { overlay.clear() }
        }

        overlay.draw(currentFrame)

        assertInitialFrame(currentFrame)
        val nextFrame = RecordingCanvas()
        overlay.draw(nextFrame)
        assertEquals(0, nextFrame.lines.size)
        assertEquals(0, nextFrame.circles.size)
    }

    @Test
    fun updateDuringDrawUsesNewLandmarksAndDimensionsOnlyOnNextFrame() = onMainThread {
        val overlay = createOverlay()
        overlay.setLandmarks(initialLandmarks(), 100, 200)
        val currentFrame = RecordingCanvas {
            onWorkerThread {
                overlay.setLandmarks(
                    listOf(landmark(0.1f, 0.2f), landmark(0.3f, 0.4f), landmark(0.6f, 0.8f)),
                    200,
                    100
                )
            }
        }

        overlay.draw(currentFrame)

        assertInitialFrame(currentFrame)
        val nextFrame = RecordingCanvas()
        overlay.draw(nextFrame)
        assertFrame(nextFrame, floatArrayOf(20f, 70f, 60f, 90f, 120f, 130f))
    }

    @Test
    fun modifyingSubmittedListDoesNotChangePendingFrame() = onMainThread {
        val overlay = createOverlay()
        val submittedLandmarks = initialLandmarks().toMutableList()
        overlay.setLandmarks(submittedLandmarks, 100, 200)

        onWorkerThread { submittedLandmarks.clear() }

        val frame = RecordingCanvas()
        overlay.draw(frame)
        assertInitialFrame(frame)
    }

    private fun createOverlay() = SkeletonOverlay(
        InstrumentationRegistry.getInstrumentation().targetContext
    ).apply {
        layout(0, 0, 200, 200)
    }

    private fun initialLandmarks() = listOf(
        landmark(0.25f, 0.25f),
        landmark(0.5f, 0.5f),
        landmark(0.75f, 0.75f)
    )

    private fun landmark(x: Float, y: Float) = NormalizedLandmark.create(
        x, y, 0f, Optional.of(1f), Optional.of(1f)
    )

    private fun assertInitialFrame(canvas: RecordingCanvas) {
        assertFrame(canvas, floatArrayOf(75f, 50f, 100f, 100f, 125f, 150f))
    }

    private fun assertFrame(canvas: RecordingCanvas, points: FloatArray) {
        assertEquals(2, canvas.lines.size)
        assertEquals(3, canvas.circles.size)
        canvas.lines.forEachIndexed { index, line ->
            assertArrayEquals(points.copyOfRange(index * 2, index * 2 + 4), line, 0.001f)
        }
        canvas.circles.forEachIndexed { index, circle ->
            assertArrayEquals(
                floatArrayOf(points[index * 2], points[index * 2 + 1], 8f),
                circle,
                0.001f
            )
        }
    }

    private fun onMainThread(action: () -> Unit) {
        val task = FutureTask<Unit> { action() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
        task.get(5, TimeUnit.SECONDS)
    }

    private fun onWorkerThread(action: () -> Unit) {
        val task = FutureTask<Unit> { action() }
        Thread(task, "skeleton-overlay-test-update").apply {
            isDaemon = true
            start()
        }
        task.get(5, TimeUnit.SECONDS)
    }

    private class RecordingCanvas(
        private val duringFirstLine: (() -> Unit)? = null
    ) : Canvas(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)) {
        val lines = mutableListOf<FloatArray>()
        val circles = mutableListOf<FloatArray>()

        override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
            lines.add(floatArrayOf(startX, startY, stopX, stopY))
            if (lines.size == 1) {
                duringFirstLine?.invoke()
            }
        }

        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
            circles.add(floatArrayOf(cx, cy, radius))
        }
    }
}
