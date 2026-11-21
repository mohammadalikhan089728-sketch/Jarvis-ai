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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

class FloatingOrbService : Service() {

    private var windowManager: WindowManager? = null
    private var orbView: View? = null
    private var stopHudView: View? = null
    private var stopHudLabel: TextView? = null
    private var persistentOrbRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_SHOW_STOP_HUD -> {
                val status = intent.getStringExtra(EXTRA_STEP_STATUS).orEmpty()
                showOrUpdateStopHud(status)
            }
            ACTION_HIDE_STOP_HUD -> {
                removeStopHud()
                if (!persistentOrbRequested && orbView == null) {
                    stopSelf()
                }
            }
            ACTION_STOP_ORB_ONLY -> {
                persistentOrbRequested = false
                removeOrb()
                if (stopHudView == null) {
                    stopSelf()
                }
            }
            else -> {
                persistentOrbRequested = true
                if (orbView == null) {
                    attachFloatingOrb()
                }
            }
        }
        return START_STICKY
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun ensureWindowManager(): WindowManager {
        return windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
            windowManager = it
        }
    }

    private fun showOrUpdateStopHud(stepStatus: String) {
        val label = stepStatus.ifBlank { "JARVIS Autonomous Agent Active" }
        stopHudLabel?.let { tv ->
            tv.text = "■ STOP  •  $label"
            return
        }

        val wm = ensureWindowManager()
        val density = resources.displayMetrics.density
        val padH = (16 * density).toInt()
        val padV = (10 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * density
                setColor(Color.parseColor("#EB160812"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#FF2E54"))
            }
            elevation = 10f * density
        }

        val textView = TextView(this).apply {
            text = "■ STOP  •  $label"
            setTextColor(Color.parseColor("#FF5C7A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            maxLines = 1
        }
        container.addView(textView)

        container.setOnClickListener {
            JarvisAccessibilityService.requestStopMultiStep()
            removeStopHud()
            if (!persistentOrbRequested && orbView == null) {
                stopSelf()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (42 * density).toInt()
        }

        runCatching {
            wm.addView(container, params)
            stopHudView = container
            stopHudLabel = textView
        }
    }

    private fun removeStopHud() {
        stopHudView?.let { v -> runCatching { windowManager?.removeView(v) } }
        stopHudView = null
        stopHudLabel = null
    }

    private fun attachFloatingOrb() {
        val wm = ensureWindowManager()
        val density = resources.displayMetrics.density
        val sizePx = (62 * density).toInt()

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayWindowType(),
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
                            persistentOrbRequested = false
                            removeOrb()
                            if (stopHudView == null) stopSelf()
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
            if (stopHudView == null) stopSelf()
        }
    }

    private fun removeOrb() {
        orbView?.let { v -> runCatching { windowManager?.removeView(v) } }
        orbView = null
        _isRunning.value = false
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOrb()
        removeStopHud()
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
        private const val ACTION_SHOW_STOP_HUD = "com.example.jarvis.ACTION_SHOW_STOP_HUD"
        private const val ACTION_HIDE_STOP_HUD = "com.example.jarvis.ACTION_HIDE_STOP_HUD"
        private const val ACTION_STOP_ORB_ONLY = "com.example.jarvis.ACTION_STOP_ORB_ONLY"
        private const val EXTRA_STEP_STATUS = "com.example.jarvis.EXTRA_STEP_STATUS"

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
                context.startService(
                    Intent(context, FloatingOrbService::class.java).apply {
                        action = ACTION_STOP_ORB_ONLY
                    }
                )
            }
        }

        fun showStopHud(context: Context, stepStatus: String) {
            if (!Settings.canDrawOverlays(context)) return
            runCatching {
                context.startService(
                    Intent(context, FloatingOrbService::class.java).apply {
                        action = ACTION_SHOW_STOP_HUD
                        putExtra(EXTRA_STEP_STATUS, stepStatus)
                    }
                )
            }
        }

        fun hideStopHud(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, FloatingOrbService::class.java).apply {
                        action = ACTION_HIDE_STOP_HUD
                    }
                )
            }
        }
    }
}
