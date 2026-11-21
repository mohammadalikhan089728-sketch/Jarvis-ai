package com.example

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

class FloatingOrbService : Service() {

    private var windowManager: WindowManager? = null
    private var orbView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (orbView == null) {
            attachFloatingOrb()
        }
        return START_STICKY
    }

    private fun attachFloatingOrb() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val density = resources.displayMetrics.density
        val sizePx = (62 * density).toInt()
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (24 * density).toInt()
            y = (280 * density).toInt()
        }

        val view = ArcOrbOverlayView(this)
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var downTimeMs = 0L

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    downTimeMs = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    runCatching { wm.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    val duration = System.currentTimeMillis() - downTimeMs
                    if (dx < 16f && dy < 16f) {
                        if (duration >= 900L) {
                            stopSelf()
                        } else {
                            val launchIntent = Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                putExtra(EXTRA_START_LISTENING, true)
                            }
                            startActivity(launchIntent)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(view, params)
            orbView = view
            _isRunning.value = true
        }.onFailure {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        orbView?.let { v -> runCatching { windowManager?.removeView(v) } }
        orbView = null
        _isRunning.value = false
    }

    private class ArcOrbOverlayView(context: Context) : View(context) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#00E5FF")
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = (minOf(width, height) / 2f) - 6f

            corePaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    Color.parseColor("#E6FAFF"),
                    Color.parseColor("#00E5FF"),
                    Color.parseColor("#CC040812")
                ),
                floatArrayOf(0.18f, 0.55f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, corePaint)

            ringPaint.strokeWidth = 4f
            ringPaint.alpha = 230
            canvas.drawCircle(cx, cy, radius * 0.86f, ringPaint)

            ringPaint.strokeWidth = 2.5f
            ringPaint.alpha = 180
            canvas.drawCircle(cx, cy, radius * 0.52f, ringPaint)
        }
    }

    companion object {
        const val EXTRA_START_LISTENING = "com.example.jarvis.EXTRA_START_LISTENING"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            runCatching {
                context.startService(Intent(context, FloatingOrbService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, FloatingOrbService::class.java))
            }
        }
    }
}
