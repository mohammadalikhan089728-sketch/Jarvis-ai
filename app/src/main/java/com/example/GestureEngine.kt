package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class SwipeDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    UP_LEFT,
    UP_RIGHT,
    DOWN_LEFT,
    DOWN_RIGHT
}

data class TouchStroke(
    val points: List<Pair<Float, Float>>,
    val startTimeMs: Long = 0L,
    val durationMs: Long = 300L
)

data class ScreenBounds(
    val widthPx: Int,
    val heightPx: Int
)

class GestureEngine(private val service: AccessibilityService) {

    fun getScreenBounds(): ScreenBounds {
        val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            ScreenBounds(
                widthPx = max(bounds.width(), 100),
                heightPx = max(bounds.height(), 100)
            )
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            ScreenBounds(
                widthPx = max(metrics.widthPixels, 100),
                heightPx = max(metrics.heightPixels, 100)
            )
        }
    }

    private fun clampX(x: Float, bounds: ScreenBounds): Float {
        return min(max(x, 2f), (bounds.widthPx - 2).toFloat())
    }

    private fun clampY(y: Float, bounds: ScreenBounds): Float {
        return min(max(y, 2f), (bounds.heightPx - 2).toFloat())
    }

    suspend fun tap(x: Float, y: Float, durationMs: Long = 80L): Boolean {
        val bounds = getScreenBounds()
        val cx = clampX(x, bounds)
        val cy = clampY(y, bounds)
        val stroke = TouchStroke(
            points = listOf(cx to cy),
            startTimeMs = 0L,
            durationMs = durationMs.coerceIn(30L, 400L)
        )
        return dispatchMultiTouch(listOf(stroke))
    }

    suspend fun longPress(x: Float, y: Float, durationMs: Long = 750L): Boolean {
        val bounds = getScreenBounds()
        val cx = clampX(x, bounds)
        val cy = clampY(y, bounds)
        val stroke = TouchStroke(
            points = listOf(cx to cy),
            startTimeMs = 0L,
            durationMs = durationMs.coerceIn(450L, 4000L)
        )
        return dispatchMultiTouch(listOf(stroke))
    }

    suspend fun dragAnyDirection(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 420L
    ): Boolean {
        val bounds = getScreenBounds()
        val sx = clampX(startX, bounds)
        val sy = clampY(startY, bounds)
        val ex = clampX(endX, bounds)
        val ey = clampY(endY, bounds)
        val stroke = TouchStroke(
            points = listOf(sx to sy, ex to ey),
            startTimeMs = 0L,
            durationMs = durationMs.coerceIn(80L, 4000L)
        )
        return dispatchMultiTouch(listOf(stroke))
    }

    suspend fun dragByAngle(
        originX: Float? = null,
        originY: Float? = null,
        angleDegrees: Float,
        distancePx: Float,
        durationMs: Long = 420L
    ): Boolean {
        val bounds = getScreenBounds()
        val startX = originX ?: (bounds.widthPx * 0.5f)
        val startY = originY ?: (bounds.heightPx * 0.5f)
        val radians = angleDegrees * (PI / 180.0)
        val endX = startX + (cos(radians) * distancePx).toFloat()
        val endY = startY + (sin(radians) * distancePx).toFloat()
        return dragAnyDirection(startX, startY, endX, endY, durationMs)
    }

    suspend fun swipeDirection(
        direction: SwipeDirection,
        distanceFraction: Float = 0.52f,
        durationMs: Long = 340L
    ): Boolean {
        val bounds = getScreenBounds()
        val cx = bounds.widthPx * 0.5f
        val cy = bounds.heightPx * 0.5f
        val dx = bounds.widthPx * distanceFraction.coerceIn(0.15f, 0.85f) * 0.5f
        val dy = bounds.heightPx * distanceFraction.coerceIn(0.15f, 0.85f) * 0.5f

        val (start, end) = when (direction) {
            SwipeDirection.UP -> (cx to (cy + dy)) to (cx to (cy - dy))
            SwipeDirection.DOWN -> (cx to (cy - dy)) to (cx to (cy + dy))
            SwipeDirection.LEFT -> ((cx + dx) to cy) to ((cx - dx) to cy)
            SwipeDirection.RIGHT -> ((cx - dx) to cy) to ((cx + dx) to cy)
            SwipeDirection.UP_LEFT -> ((cx + dx) to (cy + dy)) to ((cx - dx) to (cy - dy))
            SwipeDirection.UP_RIGHT -> ((cx - dx) to (cy + dy)) to ((cx + dx) to (cy - dy))
            SwipeDirection.DOWN_LEFT -> ((cx + dx) to (cy - dy)) to ((cx - dx) to (cy + dy))
            SwipeDirection.DOWN_RIGHT -> ((cx - dx) to (cy - dy)) to ((cx + dx) to (cy + dy))
        }
        return dragAnyDirection(start.first, start.second, end.first, end.second, durationMs)
    }

    suspend fun pinchZoomIn(
        centerX: Float? = null,
        centerY: Float? = null,
        spreadPx: Float = 260f,
        durationMs: Long = 450L
    ): Boolean {
        val bounds = getScreenBounds()
        val cx = clampX(centerX ?: (bounds.widthPx * 0.5f), bounds)
        val cy = clampY(centerY ?: (bounds.heightPx * 0.5f), bounds)
        val innerOffset = 45f
        val outerOffset = spreadPx.coerceIn(100f, min(bounds.widthPx, bounds.heightPx) * 0.42f)

        val finger1 = TouchStroke(
            points = listOf(
                clampX(cx - innerOffset, bounds) to clampY(cy - innerOffset, bounds),
                clampX(cx - outerOffset, bounds) to clampY(cy - outerOffset, bounds)
            ),
            durationMs = durationMs.coerceIn(150L, 2000L)
        )
        val finger2 = TouchStroke(
            points = listOf(
                clampX(cx + innerOffset, bounds) to clampY(cy + innerOffset, bounds),
                clampX(cx + outerOffset, bounds) to clampY(cy + outerOffset, bounds)
            ),
            durationMs = durationMs.coerceIn(150L, 2000L)
        )
        return dispatchMultiTouch(listOf(finger1, finger2))
    }

    suspend fun pinchZoomOut(
        centerX: Float? = null,
        centerY: Float? = null,
        spreadPx: Float = 260f,
        durationMs: Long = 450L
    ): Boolean {
        val bounds = getScreenBounds()
        val cx = clampX(centerX ?: (bounds.widthPx * 0.5f), bounds)
        val cy = clampY(centerY ?: (bounds.heightPx * 0.5f), bounds)
        val innerOffset = 45f
        val outerOffset = spreadPx.coerceIn(100f, min(bounds.widthPx, bounds.heightPx) * 0.42f)

        val finger1 = TouchStroke(
            points = listOf(
                clampX(cx - outerOffset, bounds) to clampY(cy - outerOffset, bounds),
                clampX(cx - innerOffset, bounds) to clampY(cy - innerOffset, bounds)
            ),
            durationMs = durationMs.coerceIn(150L, 2000L)
        )
        val finger2 = TouchStroke(
            points = listOf(
                clampX(cx + outerOffset, bounds) to clampY(cy + outerOffset, bounds),
                clampX(cx + innerOffset, bounds) to clampY(cy + innerOffset, bounds)
            ),
            durationMs = durationMs.coerceIn(150L, 2000L)
        )
        return dispatchMultiTouch(listOf(finger1, finger2))
    }

    suspend fun twoFingerRotate(
        centerX: Float? = null,
        centerY: Float? = null,
        radiusPx: Float = 180f,
        degrees: Float = 45f,
        durationMs: Long = 520L
    ): Boolean {
        val bounds = getScreenBounds()
        val cx = clampX(centerX ?: (bounds.widthPx * 0.5f), bounds)
        val cy = clampY(centerY ?: (bounds.heightPx * 0.5f), bounds)
        val r = radiusPx.coerceIn(60f, min(bounds.widthPx, bounds.heightPx) * 0.35f)
        val steps = 12
        val finger1Points = mutableListOf<Pair<Float, Float>>()
        val finger2Points = mutableListOf<Pair<Float, Float>>()

        for (i in 0..steps) {
            val fraction = i.toFloat() / steps.toFloat()
            val angle1 = (fraction * degrees) * (PI / 180.0)
            val angle2 = angle1 + PI
            finger1Points.add(
                clampX(cx + (cos(angle1) * r).toFloat(), bounds) to
                    clampY(cy + (sin(angle1) * r).toFloat(), bounds)
            )
            finger2Points.add(
                clampX(cx + (cos(angle2) * r).toFloat(), bounds) to
                    clampY(cy + (sin(angle2) * r).toFloat(), bounds)
            )
        }

        val dur = durationMs.coerceIn(200L, 2500L)
        return dispatchMultiTouch(
            listOf(
                TouchStroke(points = finger1Points, durationMs = dur),
                TouchStroke(points = finger2Points, durationMs = dur)
            )
        )
    }

    suspend fun twoFingerSwipe(
        direction: SwipeDirection,
        distanceFraction: Float = 0.45f,
        durationMs: Long = 380L
    ): Boolean {
        val bounds = getScreenBounds()
        val cx = bounds.widthPx * 0.5f
        val cy = bounds.heightPx * 0.5f
        val fingerGap = 90f
        val dx = bounds.widthPx * distanceFraction.coerceIn(0.15f, 0.75f) * 0.5f
        val dy = bounds.heightPx * distanceFraction.coerceIn(0.15f, 0.75f) * 0.5f

        val (startCenter, endCenter, horizontalGap) = when (direction) {
            SwipeDirection.UP -> Triple(cx to (cy + dy), cx to (cy - dy), true)
            SwipeDirection.DOWN -> Triple(cx to (cy - dy), cx to (cy + dy), true)
            SwipeDirection.LEFT -> Triple((cx + dx) to cy, (cx - dx) to cy, false)
            SwipeDirection.RIGHT -> Triple((cx - dx) to cy, (cx + dx) to cy, false)
            SwipeDirection.UP_LEFT -> Triple((cx + dx) to (cy + dy), (cx - dx) to (cy - dy), true)
            SwipeDirection.UP_RIGHT -> Triple((cx - dx) to (cy + dy), (cx + dx) to (cy - dy), true)
            SwipeDirection.DOWN_LEFT -> Triple((cx + dx) to (cy - dy), (cx - dx) to (cy + dy), true)
            SwipeDirection.DOWN_RIGHT -> Triple((cx - dx) to (cy - dy), (cx + dx) to (cy + dy), true)
        }

        val offsetX = if (horizontalGap) fingerGap else 0f
        val offsetY = if (horizontalGap) 0f else fingerGap
        val dur = durationMs.coerceIn(120L, 2500L)

        val finger1 = TouchStroke(
            points = listOf(
                clampX(startCenter.first - offsetX, bounds) to clampY(startCenter.second - offsetY, bounds),
                clampX(endCenter.first - offsetX, bounds) to clampY(endCenter.second - offsetY, bounds)
            ),
            durationMs = dur
        )
        val finger2 = TouchStroke(
            points = listOf(
                clampX(startCenter.first + offsetX, bounds) to clampY(startCenter.second + offsetY, bounds),
                clampX(endCenter.first + offsetX, bounds) to clampY(endCenter.second + offsetY, bounds)
            ),
            durationMs = dur
        )
        return dispatchMultiTouch(listOf(finger1, finger2))
    }

    suspend fun simultaneousMultiTap(
        points: List<Pair<Float, Float>>,
        durationMs: Long = 100L
    ): Boolean {
        if (points.isEmpty()) return false
        val bounds = getScreenBounds()
        val strokes = points.take(GestureDescription.getMaxStrokeCount()).map { (x, y) ->
            TouchStroke(
                points = listOf(clampX(x, bounds) to clampY(y, bounds)),
                startTimeMs = 0L,
                durationMs = durationMs.coerceIn(40L, 500L)
            )
        }
        return dispatchMultiTouch(strokes)
    }

    suspend fun dispatchMultiTouch(strokes: List<TouchStroke>): Boolean {
        if (strokes.isEmpty()) return false
        val bounds = getScreenBounds()
        val maxStrokes = GestureDescription.getMaxStrokeCount()
        val maxDuration = GestureDescription.getMaxGestureDuration()

        val builder = GestureDescription.Builder()
        var validStrokeCount = 0

        for (stroke in strokes.take(maxStrokes)) {
            if (stroke.points.isEmpty()) continue
            val path = Path()
            val first = stroke.points.first()
            path.moveTo(clampX(first.first, bounds), clampY(first.second, bounds))
            for (i in 1 until stroke.points.size) {
                val pt = stroke.points[i]
                path.lineTo(clampX(pt.first, bounds), clampY(pt.second, bounds))
            }
            val startTime = stroke.startTimeMs.coerceAtLeast(0L)
            val duration = stroke.durationMs.coerceIn(20L, maxDuration.toLong())
            builder.addStroke(GestureDescription.StrokeDescription(path, startTime, duration))
            validStrokeCount++
        }

        if (validStrokeCount == 0) return false
        val gesture = builder.build()

        return withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine { continuation ->
                val dispatched = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                    null
                )
                if (!dispatched && continuation.isActive) {
                    continuation.resume(false)
                }
            }
        } ?: false
    }
}
