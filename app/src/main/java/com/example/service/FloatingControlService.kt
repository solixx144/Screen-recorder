package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

class FloatingControlService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var isViewAdded = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // UI Elements in the overlay
    private lateinit var tvTimer: TextView
    private lateinit var ivPlayPause: ImageView
    private lateinit var ivRecordStop: ImageView
    private lateinit var ivSettings: ImageView
    private lateinit var ivClose: ImageView
    private lateinit var ivDragHandle: ImageView
    private lateinit var pulsingDot: View

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        createFloatingWidget()
        observeRecordingState()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // We stay alive so the overlay is persistent
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "floating_control_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Floating Controls Active",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Floating Recorder Controls")
            .setContentText("Aether Capture overlay widget is visible.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(9988, notification)
    }

    private fun createFloatingWidget() {
        if (floatingView != null) return

        // 1. Create the root layout (horizontal pill shape)
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val density = resources.displayMetrics.density
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            
            // Translucent beautiful cosmic dark background with indigo border
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 40 * density
                setColor(Color.parseColor("#E60F0C1B")) // Deep cosmic translucent background
                setStroke((1.5f * density).toInt(), Color.parseColor("#FF6366F1")) // Neon Indigo border
            }
        }

        val density = resources.displayMetrics.density

        // 2. Add Drag Handle (visual cue)
        ivDragHandle = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_sort_by_size)
            setColorFilter(Color.parseColor("#FF818CF8")) // Sleek light indigo
            val layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply {
                rightMargin = (8 * density).toInt()
            }
            this.layoutParams = layoutParams
        }
        rootLayout.addView(ivDragHandle)

        // 3. Pulsing Red Dot (only visible when recording)
        pulsingDot = View(this).apply {
            val dotBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
            }
            background = dotBg
            val layoutParams = LinearLayout.LayoutParams((8 * density).toInt(), (8 * density).toInt()).apply {
                rightMargin = (6 * density).toInt()
            }
            this.layoutParams = layoutParams
            visibility = View.GONE
        }
        rootLayout.addView(pulsingDot)

        // 4. Timer/State Text
        tvTimer = TextView(this).apply {
            text = "IDLE"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (16 * density).toInt()
            }
            this.layoutParams = layoutParams
        }
        rootLayout.addView(tvTimer)

        // Helper to create circular button containers programmatically
        fun createCircularButton(iconResId: Int, tintColor: Int, onClick: () -> Unit): ImageView {
            val img = ImageView(this).apply {
                setImageResource(iconResId)
                setColorFilter(tintColor)
                val pad = (6 * density).toInt()
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#1AFFFFFF")) // Translucent glass effect
                }
                setOnClickListener { onClick() }
            }
            val lp = LinearLayout.LayoutParams((32 * density).toInt(), (32 * density).toInt()).apply {
                rightMargin = (8 * density).toInt()
            }
            img.layoutParams = lp
            return img
        }

        // 5. Play / Pause Button (toggles active recording pause state)
        ivPlayPause = createCircularButton(
            android.R.drawable.ic_media_pause,
            Color.parseColor("#FFF59E0B") // Amber
        ) {
            val isRecording = ScreenCaptureService.isRecording.value
            if (isRecording) {
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = if (ScreenCaptureService.isPaused.value) {
                        ScreenCaptureService.ACTION_RESUME
                    } else {
                        ScreenCaptureService.ACTION_PAUSE
                    }
                }
                startService(intent)
            } else {
                Toast.makeText(this, "Start recording first", Toast.LENGTH_SHORT).show()
            }
        }
        rootLayout.addView(ivPlayPause)

        // 6. Record / Stop Button
        ivRecordStop = createCircularButton(
            android.R.drawable.presence_video_online,
            Color.parseColor("#FFEF4444") // Coral Red
        ) {
            val isRecording = ScreenCaptureService.isRecording.value
            if (isRecording) {
                // Stop capturing
                val intent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP
                }
                startService(intent)
                Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
            } else {
                // Open MainActivity to request permission and start
                val activityIntent = Intent(this, MainActivity::class.java).apply {
                    action = "com.example.ACTION_SHORTCUT_START"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(activityIntent)
                Toast.makeText(this, "Launching screen capture flow...", Toast.LENGTH_SHORT).show()
            }
        }
        rootLayout.addView(ivRecordStop)

        // 7. Settings Button (launches MainActivity)
        ivSettings = createCircularButton(
            android.R.drawable.ic_menu_preferences,
            Color.parseColor("#FF6366F1") // Indigo
        ) {
            val activityIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(activityIntent)
        }
        rootLayout.addView(ivSettings)

        // 8. Close Overlay Button
        ivClose = createCircularButton(
            android.R.drawable.ic_menu_close_clear_cancel,
            Color.parseColor("#FF9CA3AF") // Gray
        ) {
            stopSelf()
        }
        rootLayout.addView(ivClose)

        // Setup WindowManager parameters for overlay layout
        val typeParam = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            typeParam,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        // Draggable touch listener
        rootLayout.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        params.x = initialX + dx
                        params.y = initialY + dy
                        
                        // Restrict bounds within screen size
                        try {
                            windowManager.updateViewLayout(rootLayout, params)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = abs(event.rawX - initialTouchX)
                        val diffY = abs(event.rawY - initialTouchY)
                        if (diffX < 10 && diffY < 10) {
                            // Touch is a click, register action (or standard clicks)
                            v.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })

        floatingView = rootLayout
        try {
            windowManager.addView(floatingView, params)
            isViewAdded = true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Please grant overlay permissions.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeRecordingState() {
        // Collect state from ScreenCaptureService and update the Floating View controls and colors
        serviceScope.launch {
            ScreenCaptureService.isRecording.collectLatest { isRecording ->
                withContext(Dispatchers.Main) {
                    if (isRecording) {
                        pulsingDot.visibility = View.VISIBLE
                        // Show STOP icon (represented by presence_video_busy during recording)
                        ivRecordStop.setImageResource(android.R.drawable.presence_video_busy)
                        ivRecordStop.setColorFilter(Color.parseColor("#FFEF4444")) // Red Stop
                        ivPlayPause.alpha = 1.0f
                        ivPlayPause.isEnabled = true
                    } else {
                        pulsingDot.visibility = View.GONE
                        tvTimer.text = "IDLE"
                        // Show RECORD icon (red dot conceptually, but standard video record)
                        ivRecordStop.setImageResource(android.R.drawable.presence_video_online)
                        ivRecordStop.setColorFilter(Color.parseColor("#FF10B981")) // Emerald Green launch
                        ivPlayPause.alpha = 0.4f
                        ivPlayPause.isEnabled = false
                    }
                }
            }
        }

        // Collect Pause State
        serviceScope.launch {
            ScreenCaptureService.isPaused.collectLatest { isPaused ->
                withContext(Dispatchers.Main) {
                    if (isPaused) {
                        ivPlayPause.setImageResource(android.R.drawable.ic_media_play)
                        ivPlayPause.setColorFilter(Color.parseColor("#FF10B981")) // Green Play when paused
                        tvTimer.text = "PAUSED"
                    } else {
                        ivPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                        ivPlayPause.setColorFilter(Color.parseColor("#FFF59E0B")) // Amber Pause when recording
                    }
                }
            }
        }

        // Collect Duration State
        serviceScope.launch {
            ScreenCaptureService.durationSeconds.collectLatest { seconds ->
                withContext(Dispatchers.Main) {
                    if (ScreenCaptureService.isRecording.value && !ScreenCaptureService.isPaused.value) {
                        val m = seconds / 60
                        val s = seconds % 60
                        tvTimer.text = String.format("%02d:%02d", m, s)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        removeFloatingWidget()
    }

    private fun removeFloatingWidget() {
        if (isViewAdded && floatingView != null) {
            try {
                windowManager.removeView(floatingView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            floatingView = null
            isViewAdded = false
        }
    }
}
