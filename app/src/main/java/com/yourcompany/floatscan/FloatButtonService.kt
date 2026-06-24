package com.yourcompany.floatscan

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

class FloatButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatView: View? = null
    private var ballImageView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var hiddenForInject = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, createNotification())
        showFloatButton()
        ScanResultBus.onInjectResult = { success -> handleInjectResult(success) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (floatView == null && Settings.canDrawOverlays(this)) {
            showFloatButton()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ScanResultBus.onInjectResult = null
        removeFloatButton()
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatButton() {
        if (floatView != null) return

        val size = dpToPx(FLOAT_SIZE_DP)
        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
        }

        val ball = ImageView(this).apply {
            setImageResource(R.drawable.ic_scan)
            scaleType = ImageView.ScaleType.CENTER
            setBackgroundResource(R.drawable.float_ball_bg)
            contentDescription = getString(R.string.app_name)
        }
        container.addView(ball)
        ballImageView = ball

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            size,
            size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = displayMetrics.widthPixels - size - dpToPx(8)
            y = displayMetrics.heightPixels / 2 - size / 2
        }

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(container, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        snapToEdge(params, container)
                    } else if (!hiddenForInject) {
                        launchScan()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(container, params)
        floatView = container
        layoutParams = params
    }

    private fun snapToEdge(params: WindowManager.LayoutParams, view: View) {
        val screenWidth = resources.displayMetrics.widthPixels
        val targetX = if (params.x + view.width / 2 < screenWidth / 2) {
            dpToPx(8)
        } else {
            screenWidth - view.width - dpToPx(8)
        }

        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 200
            addUpdateListener { animator ->
                params.x = animator.animatedValue as Int
                windowManager.updateViewLayout(view, params)
            }
            start()
        }
    }

    private fun launchScan() {
        InjectService.instance?.captureTargetApp()
        val intent = Intent(this, ScanActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        startActivity(intent)
    }

    private fun handleInjectResult(success: Boolean) {
        handler.post {
            val ball = ballImageView ?: return@post
            if (success) {
                vibrateShort()
                flashBallColor(ball, R.color.float_ball_green) {
                    hideBallTemporarily()
                }
            } else {
                flashBallColor(ball, R.color.float_ball_red) {
                    restoreBallColor(ball)
                }
                Toast.makeText(this, R.string.toast_inject_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun flashBallColor(ball: ImageView, colorRes: Int, onEnd: () -> Unit) {
        val drawable = ball.background
        ball.setBackgroundColor(ContextCompat.getColor(this, colorRes))
        handler.postDelayed({
            ball.background = drawable
            onEnd()
        }, 1000)
    }

    private fun restoreBallColor(ball: ImageView) {
        ball.setBackgroundResource(R.drawable.float_ball_bg)
    }

    private fun hideBallTemporarily() {
        hiddenForInject = true
        floatView?.visibility = View.GONE
        handler.postDelayed({
            hiddenForInject = false
            floatView?.visibility = View.VISIBLE
            ballImageView?.setBackgroundResource(R.drawable.float_ball_bg)
        }, 1500)
    }

    private fun vibrateShort() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(VibratorManager::class.java)
            vibratorManager?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        }
    }

    private fun removeFloatButton() {
        floatView?.let {
            windowManager.removeView(it)
            floatView = null
            ballImageView = null
            layoutParams = null
        }
    }

    private fun createNotification(): Notification {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_scan)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val CHANNEL_ID = "float_scan_service"
        private const val NOTIFICATION_ID = 1001
        private const val FLOAT_SIZE_DP = 48

        fun start(context: Context) {
            val intent = Intent(context, FloatButtonService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatButtonService::class.java))
        }

        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            return manager.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == FloatButtonService::class.java.name }
        }
    }
}
