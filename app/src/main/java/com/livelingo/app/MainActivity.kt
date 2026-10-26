package com.livelingo.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import com.google.mlkit.nl.translate.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateLanguage

class MainActivity : Activity() {

    companion object {
        private const val AUDIO_PERMISSION_REQUEST = 1001
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var translator: Translator? = null
    private var translationEnabled = false
    private var modelReady = false
    private var latestTranslationRequest = 0

    private lateinit var statusView: TextView
    private lateinit var sourceView: TextView
    private lateinit var translationView: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 28, 24, 28)
            setBackgroundColor(Color.rgb(18, 24, 38))
        }

        fun label(text: String, size: Float, color: Int): TextView =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                setPadding(8, 8, 8, 8)
            }

        val title = label("LiveLingo", 32f, Color.WHITE)
        val description = label(
            "Japanese → English\nLive speech subtitles\nVersion 0.2.0",
            18f,
            Color.LTGRAY
        )
        statusView = label(
            "Ready. The English translation model will be downloaded the first time you start.",
            15f,
            Color.rgb(100, 230, 130)
        )
        sourceView = label("Japanese speech will appear here.", 18f, Color.WHITE)
        translationView = label("English translation will appear here.", 22f, Color.WHITE)

        val subtitles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(8, 16, 8, 16)
            addView(label("JAPANESE", 12f, Color.LTGRAY))
            addView(sourceView)
            addView(label("ENGLISH", 12f, Color.LTGRAY))
            addView(translationView)
        }

        startButton = Button(this).apply {
            text = "Start Translation"
            setOnClickListener {
                if (translationEnabled) {
                    stopTranslation()
                } else {
                    requestAudioPermissionAndStart()
                }
            }
        }

        layout.addView(title)
        layout.addView(description)
        layout.addView(statusView)
        layout.addView(
            ScrollView(this).apply {
                addView(subtitles)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        layout.addView(
            startButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(layout)
    }

    private fun requestAudioPermissionAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                AUDIO_PERMISSION_REQUEST
            )
            return
        }
        prepareTranslation()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                prepareTranslation()
            } else {
                statusView.text = "Microphone permission is required to translate speech."
            }
        }
    }

    private fun prepareTranslation() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusView.text = "Speech recognition is unavailable on this device. Install or enable a speech recognition service."
            return
        }

        translationEnabled = true
        startButton.text = "Stop Translation"
        statusView.text = "Preparing Japanese → English translation model. Internet is needed for the first download."
        sourceView.text = ""
        translationView.text = ""

        if (translator == null) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            translator = Translation.getClient(options)
        }

        if (modelReady) {
            startSpeechRecognition()
            return
        }

        translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
            ?.addOnSuccessListener {
                modelReady = true
                if (translationEnabled) {
                    statusView.text = "Translation model ready. Listening for Japanese speech…"
                    startSpeechRecognition()
                }
            }
            ?.addOnFailureListener { error ->
                translationEnabled = false
                startButton.text = "Start Translation"
                statusView.text = "Could not download the translation model. Connect to the internet and try again. ${error.localizedMessage ?: ""}"
            }
    }

    private fun startSpeechRecognition() {
        if (!translationEnabled) return

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        if (translationEnabled) statusView.text = "Listening… Speak Japanese near your device."
                    }

                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        if (translationEnabled) statusView.text = "Processing speech…"
                    }

                    override fun onError(error: Int) {
                        if (!translationEnabled) return
                        statusView.text = when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition reported a network issue. Checking whether offline recognition is available…"
                            else -> "Listening will resume…"
                        }
                        scheduleRecognitionRestart()
                    }

                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!text.isNullOrBlank()) translateSpeech(text)
                        scheduleRecognitionRestart()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            sourceView.text = text
                            translateSpeech(text)
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
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (error: Exception) {
            statusView.text = "Could not start speech recognition: ${error.localizedMessage ?: "unknown error"}"
            scheduleRecognitionRestart()
        }
    }

    private fun translateSpeech(text: String) {
        val currentTranslator = translator ?: return
        val requestId = ++latestTranslationRequest
        currentTranslator.translate(text)
            .addOnSuccessListener { translated ->
                if (requestId == latestTranslationRequest) {
                    sourceView.text = text
                    translationView.text = translated
                }
            }
            .addOnFailureListener { error ->
                if (requestId == latestTranslationRequest) {
                    statusView.text = "Translation failed: ${error.localizedMessage ?: "please try again"}"
                }
            }
    }

    private fun scheduleRecognitionRestart() {
        if (!translationEnabled) return
        mainHandler.postDelayed({
            if (translationEnabled) startSpeechRecognition()
        }, 500)
    }

    private fun stopTranslation() {
        translationEnabled = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.cancel()
        startButton.text = "Start Translation"
        statusView.text = if (modelReady) {
            "Stopped. The translation model is saved on this device."
        } else {
            "Stopped."
        }
    }

    override fun onDestroy() {
        translationEnabled = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        translator?.close()
        translator = null
        super.onDestroy()
    }
}
