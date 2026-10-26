package com.livelingo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.view.MotionEvent
import android.widget.TextView

/**
 * Experimental internal playback capture.
 *
 * This service captures audio buffers from other apps that allow playback capture.
 * It intentionally does not use the microphone. Speech recognition is the next
 * integration step; for now this service verifies that playback PCM is available.
 */
class PlaybackCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.livelingo.app.START_PLAYBACK_CAPTURE"
        const val ACTION_STOP = "com.livelingo.app.STOP_PLAYBACK_CAPTURE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        private const val CHANNEL_ID = "livelingo_playback_capture"
        private const val NOTIFICATION_ID = 2102
        private const val TAG = "LiveLingoCapture"
        private const val SAMPLE_RATE = 16000
    }

    @Volatile private var running = false
    private var captureThread: Thread? = null
    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayText: TextView? = null
    @Volatile private var captionEngine: JapaneseCaptionEngine? = null
    private val transcriptLines = ArrayDeque<String>()
    @Volatile private var hasCaptions = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Preparing internal audio capture"))
        showCaptureOverlay("LiveLingo internal audio: starting…")
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || resultData == null) {
            updateNotification("Capture consent missing. Return to LiveLingo and try again.")
            stopCapture()
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)
            startAudioReader()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start playback capture", e)
            updateNotification("Internal audio capture could not start: ${e.localizedMessage ?: "unsupported"}")
            stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startAudioReader() {
        val mediaProjection = projection ?: return
        val config = android.media.AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) throw IllegalStateException("Invalid AudioRecord buffer size: $minBuffer")
        val recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes((minBuffer * 2).coerceAtLeast(4096))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("Playback AudioRecord failed to initialize")
        }
        audioRecord = recorder
        running = true
        recorder.startRecording()
        updateNotification("Internal audio capture active. Play media in a supported app.")
        captureThread = Thread({
            val buffer = ShortArray(2048)
            var lastStatusAt = 0L
            val engine = JapaneseCaptionEngine(
                this,
                onCaption = { japanese, english -> showCaption(japanese, english) },
                onStatus = { status ->
                    updateNotification(status)
                    Log.i(TAG, status)
                }
            )
            try {
                try {
                    engine.initialize()
                    captionEngine = engine
                } catch (e: Exception) {
                    Log.e(TAG, "Japanese speech engine initialization failed", e)
                    updateNotification("Speech engine setup failed: " + (e.localizedMessage ?: "check internet and restart"))
                }
                while (running) {
                    val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count > 0) {
                        captionEngine?.process(buffer, count)
                        var energy = 0.0
                        for (i in 0 until count) {
                            val value = buffer[i].toDouble()
                            energy += value * value
                        }
                        val rms = kotlin.math.sqrt(energy / count)
                        val now = System.currentTimeMillis()
                        if (now - lastStatusAt > 5000) {
                            lastStatusAt = now
                            if (captionEngine == null) {
                                val status = if (rms > 120.0) "Audio captured; Japanese speech engine is unavailable."
                                    else "Capturing audio; no audible media detected yet."
                                updateNotification(status)
                            }
                            Log.d(TAG, "PCM capture count=$count rms=$rms")
                        }
                    } else if (count < 0) {
                        Log.w(TAG, "AudioRecord read failed: $count")
                        updateNotification("Audio capture read error: $count")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback read loop failed", e)
                updateNotification("Playback capture stopped: ${e.localizedMessage ?: "read error"}")
            } finally {
                captionEngine?.close()
                captionEngine = null
                engine.close()
                stopCapture()
            }
        }, "LiveLingoPlaybackCapture").also { it.start() }
    }

    private fun stopCapture() {
        if (!running && audioRecord == null && projection == null) {
            removeCaptureOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        running = false
        try { captionEngine?.close() } catch (_: Exception) {}
        captionEngine = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        captureThread = null
        removeCaptureOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "LiveLingo internal audio capture",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun notification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("LiveLingo internal audio")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun showCaptureOverlay(message: String) {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        if (overlayView != null) { updateOverlay(message); return }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.90f).toInt().coerceAtLeast(260),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 90 }
        val label = TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(16, 14, 16, 14)
            minHeight = (64 * resources.displayMetrics.density).toInt()
            setBackgroundColor(Color.rgb(25, 31, 46))
            maxLines = 5
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            contentDescription = "LiveLingo audio status. Drag to move."
            setOnTouchListener(object : View.OnTouchListener {
                private var downRawX = 0f
                private var downRawY = 0f
                private var startX = 0
                private var startY = 0

                override fun onTouch(view: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = event.rawX
                            downRawY = event.rawY
                            startX = params.x
                            startY = params.y
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.gravity = Gravity.TOP or Gravity.START
                            params.x = startX + (event.rawX - downRawX).toInt()
                            params.y = startY + (event.rawY - downRawY).toInt()
                            try { windowManager?.updateViewLayout(view, params) } catch (e: Exception) {
                                Log.w(TAG, "Could not move capture overlay", e)
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                    }
                    return false
                }
            })
        }
        try {
            windowManager?.addView(label, params)
            overlayView = label
            overlayText = label
        } catch (e: Exception) { Log.w(TAG, "Could not show capture overlay", e) }
    }

    private fun showCaption(japanese: String, english: String) {
        val ja = japanese.trim()
        val en = english.trim()
        if (en.isBlank()) return
        val caption = if (ja.isNotBlank()) "$ja\n$en" else en
        android.os.Handler(mainLooper).post {
            if (transcriptLines.lastOrNull() != caption) {
                transcriptLines.addLast(caption)
                while (transcriptLines.size > 5) transcriptLines.removeFirst()
            }
            hasCaptions = true
            overlayText?.text = transcriptLines.joinToString("\n\n")
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification("English: " + en.take(120)))
    }

    private fun updateOverlay(message: String) {
        android.os.Handler(mainLooper).post { overlayText?.text = message }
    }

    private fun removeCaptureOverlay() {
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        overlayView = null
        overlayText = null
        windowManager = null
    }

    private fun updateNotification(message: String) {
        if (!hasCaptions) updateOverlay(message)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(message.take(100)))
    }

    override fun onDestroy() {
        running = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        removeCaptureOverlay()
        super.onDestroy()
    }
}
