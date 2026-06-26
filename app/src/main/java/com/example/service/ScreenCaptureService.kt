package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.engine.ScreenRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCaptureService : Service() {

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var recorder: ScreenRecorder? = null
    
    private var durationJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_record_channel"
        private const val NOTIFICATION_ID = 1001

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_USE_HEVC = "extra_use_hevc"

        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"

        private val _isRecording = MutableStateFlow(false)
        val isRecording = _isRecording.asStateFlow()

        private val _durationSeconds = MutableStateFlow(0L)
        val durationSeconds = _durationSeconds.asStateFlow()

        private val _currentFile = MutableStateFlow<File?>(null)
        val currentFile = _currentFile.asStateFlow()

        private val _error = MutableStateFlow<String?>(null)
        val error = _error.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val width = intent.getIntExtra(EXTRA_WIDTH, 1920)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 1080)
                val fps = intent.getIntExtra(EXTRA_FPS, 30)
                val bitrate = intent.getIntExtra(EXTRA_BITRATE, 0)
                val useHevc = intent.getBooleanExtra(EXTRA_USE_HEVC, true)

                if (resultCode != -1 && resultData != null) {
                    startRecording(resultCode, resultData, width, height, fps, bitrate, useHevc)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startRecording(
        resultCode: Int,
        resultData: Intent,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        useHevc: Boolean
    ) {
        if (_isRecording.value) return

        try {
            // Must launch in foreground first to satisfy Android 14 projection requirements
            val notification = buildNotification(0L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            // Obtain MediaProjection
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            val projection = mediaProjection
            if (projection == null) {
                _error.value = "Failed to acquire MediaProjection token"
                stopSelf()
                return
            }

            // Create output file
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val movieDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            if (movieDir != null && !movieDir.exists()) {
                movieDir.mkdirs()
            }
            val ext = if (useHevc) "mp4" else "mp4"
            val file = File(movieDir, "SCR_$timestamp.$ext")
            _currentFile.value = file

            // Get DPI
            val dpi = resources.displayMetrics.densityDpi

            // Setup and start recorder
            recorder = ScreenRecorder(
                width = width,
                height = height,
                fps = fps,
                bitrate = bitrate,
                useHevc = useHevc,
                outputFile = file,
                mediaProjection = projection,
                dpi = dpi
            ).apply {
                start()
            }

            _isRecording.value = true
            _error.value = null
            _durationSeconds.value = 0L

            // Start duration ticker
            startDurationCounter()

        } catch (e: Exception) {
            Log.e(TAG, "Error starting ScreenCaptureService recording pipeline", e)
            _error.value = "Recording failed to start: ${e.localizedMessage}"
            stopRecording()
            stopSelf()
        }
    }

    private fun stopRecording() {
        if (!_isRecording.value) return
        Log.d(TAG, "Stopping foreground service recording")

        durationJob?.cancel()
        durationJob = null

        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder engine cleanly", e)
        } finally {
            recorder = null
        }

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media projection cleanly", e)
        } finally {
            mediaProjection = null
        }

        _isRecording.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startDurationCounter() {
        durationJob = serviceScope.launch {
            while (_isRecording.value) {
                delay(1000)
                _durationSeconds.value += 1
                updateNotification(_durationSeconds.value)
            }
        }
    }

    private fun updateNotification(duration: Long) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(duration))
    }

    private fun buildNotification(duration: Long): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        
        // Handle pending intents according to Android 12+ API 31 flags
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val stopPendingIntent = PendingIntent.getService(this, 1, stopIntent, flags)

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(this, 0, mainIntent, flags)

        val durationStr = String.format(
            Locale.getDefault(),
            "%02d:%02d",
            duration / 60,
            duration % 60
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Recording Active")
            .setContentText("Duration: $durationStr")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(mainPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Recording",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Screen Recorder Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows screen recording notifications and control overlays"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
