package com.livelingo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateLanguage

class TranslationService : Service() {
    companion object {
        const val ACTION_START = "com.livelingo.app.START_TRANSLATION"
        const val ACTION_STOP = "com.livelingo.app.STOP_TRANSLATION"
        private const val CHANNEL_ID = "livelingo_translation"
        private const val NOTIFICATION_ID = 2101
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private var enabled = false
    private var modelReady = false
    private var recognitionStarting = false
    private var overlay: View? = null
    private var windowManager: WindowManager? = null
    private var japaneseText: TextView? = null
    private var englishText: TextView? = null
    private var statusText: TextView? = null
    private var latestTranslationRequest = 0
    private var restartAttempts = 0

    private val restartRunnable = Runnable {
        if (enabled) startListening()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTranslation()
                return START_NOT_STICKY
            }
            ACTION_START -> startTranslation()
            else -> if (!enabled) startTranslation()
        }
        return START_STICKY
    }

    private fun startTranslation() {
        if (enabled) return
        enabled = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting Japanese → English subtitles"))
        showOverlay()
        setStatus("Preparing translation model…")
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Speech recognition service unavailable. Enable a speech recognition provider.")
            stopTranslation()
            return
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        translator = Translation.getClient(options)
        translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
            ?.addOnSuccessListener {
                if (!enabled) return@addOnSuccessListener
                modelReady = true
                setStatus("Listening to nearby/device-speaker audio. Keep media volume audible.")
                startListening()
            }
            ?.addOnFailureListener { error ->
                setStatus("Translation model download failed: ${error.localizedMessage ?: "check internet"}")
                stopTranslation()
            }
    }

    private fun showOverlay() {
        if (overlay != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.rgb(25, 31, 46))
            elevation = 12f
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "LiveLingo  •  JA → EN"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            typeface = Typeface.DEFAULT_BOLD
        }
        val close = Button(this).apply {
            text = "×"
            textSize = 18f
            setPadding(8, 0, 8, 0)
            setOnClickListener { stopTranslation() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(close, LinearLayout.LayoutParams(-2, -2))
        japaneseText = TextView(this).apply {
            text = "日本語: Waiting for speech…"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 6, 0, 4)
            maxLines = 3
        }
        englishText = TextView(this).apply {
            text = "English translation will appear here."
            textSize = 17f
            setTextColor(Color.rgb(140, 235, 160))
            setPadding(0, 4, 0, 2)
            maxLines = 4
        }
        statusText = TextView(this).apply {
            text = "Starting…"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, 4, 0, 0)
            maxLines = 2
        }
        root.addView(header)
        root.addView(japaneseText)
        root.addView(englishText)
        root.addView(statusText)

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels - 32).coerceAtLeast(260),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 80
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try { windowManager?.updateViewLayout(root, params) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(root, params)
            overlay = root
        } catch (_: Exception) {
            setStatus("Overlay permission missing. Stop and enable Display over other apps.")
        }
    }

    private fun setStatus(text: String) {
        statusText?.text = text
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text.take(80)))
    }

    private fun startListening() {
        if (!enabled || !modelReady || recognitionStarting) return
        handler.removeCallbacks(restartRunnable)
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            recognitionStarting = false
                            restartAttempts = 0
                            setStatus("Listening… play media aloud or use speaker audio.")
                        }
                        override fun onBeginningOfSpeech() { recognitionStarting = false }
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() {
                            recognitionStarting = false
                            setStatus("Processing speech…")
                        }
                        override fun onError(error: Int) {
                            recognitionStarting = false
                            if (!enabled) return

                            // ERROR_NO_MATCH (7) and ERROR_SPEECH_TIMEOUT are normal during
                            // pauses between movie dialogue. Do not destroy/recreate the
                            // recognizer for these recoverable conditions: doing so can cause
                            // repeated audio-session churn and make playback unstable.
                            val recoverableSilence =
                                error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

                            if (!recoverableSilence) {
                                // Recreate only when the recognition service reports a more
                                // serious failure or a busy client.
                                recreateRecognizer()
                            }

                            restartAttempts = (restartAttempts + 1).coerceAtMost(6)
                            val baseDelay = when {
                                error == SpeechRecognizer.ERROR_NO_MATCH -> 1200L
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 1500L
                                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 2000L
                                else -> 1500L
                            }
                            val delay = (baseDelay * (1L shl (restartAttempts - 1))).coerceAtMost(15000L)
                            val status = if (recoverableSilence) {
                                "No clear speech detected. Listening again in ${(delay / 1000.0).toInt().coerceAtLeast(1)}s…"
                            } else {
                                "Speech recognition error $error. Retrying in ${(delay / 1000.0).toInt().coerceAtLeast(1)}s…"
                            }
                            setStatus(status)
                            scheduleRestart(delay)
                        }
                        override fun onResults(results: Bundle?) {
                            recognitionStarting = false
                            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            if (!text.isNullOrBlank()) processSpeech(text)
                            restartAttempts = 0
                            scheduleRestart(500)
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            // Show the changing source hypothesis, but translate only final results.
                            // Translating every partial result creates stale queued captions and flicker.
                            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            if (enabled && !text.isNullOrBlank()) {
                                japaneseText?.text = "日本語: $text"
                            }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })
                }
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ja-JP")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognitionStarting = true
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            recognitionStarting = false
            recreateRecognizer()
            scheduleRestart(1200)
        }
    }

    private fun processSpeech(text: String) {
        japaneseText?.text = "日本語: $text"
        val currentTranslator = translator ?: return
        val requestId = ++latestTranslationRequest
        currentTranslator.translate(text)
            .addOnSuccessListener { translated ->
                if (enabled && requestId == latestTranslationRequest) {
                    englishText?.text = translated
                }
            }
            .addOnFailureListener { error ->
                if (enabled && requestId == latestTranslationRequest) {
                    setStatus("Translation error: ${error.localizedMessage ?: "try again"}")
                }
            }
    }

    private fun scheduleRestart(delay: Long) {
        if (!enabled) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delay)
    }

    private fun recreateRecognizer() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun stopTranslation() {
        enabled = false
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        translator?.close()
        translator = null
        modelReady = false
        recognitionStarting = false
        try {
            overlay?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        overlay = null
        japaneseText = null
        englishText = null
        statusText = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "LiveLingo translation", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("LiveLingo is running")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        enabled = false
        handler.removeCallbacksAndMessages(null)
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        translator?.close()
        translator = null
        try { overlay?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        overlay = null
        super.onDestroy()
    }
}
