package com.livelingo.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color

class MainActivity : Activity() {
    companion object {
        private const val AUDIO_PERMISSION_REQUEST = 1001
        private const val PLAYBACK_CAPTURE_REQUEST = 1003
    }

    private lateinit var statusView: TextView
    private var modelsReady = false
    @Volatile private var preparedEngine: JapaneseCaptionEngine? = null
    @Volatile private var engineCloseAfterPrepare = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 28, 24, 28)
            setBackgroundColor(Color.rgb(18, 24, 38))
        }
        fun label(text: String, size: Float, color: Int) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
        }

        layout.addView(label("LiveLingo", 32f, Color.WHITE))
        layout.addView(label("Japanese → English\nInternal audio path\nVersion 0.5.0 prototype", 18f, Color.LTGRAY))
        statusView = label(
            "Prepare the models before starting. Speech model: Vosk Japanese vosk-model-ja-0.22 (download size shown during setup). Translation: Google ML Kit Japanese → English. Both must be ready before audio capture can start.",
            15f, Color.rgb(100, 230, 130)
        )
        layout.addView(statusView)
        val info = label(
            "Step 1: tap Prepare Models and wait for both downloads to finish. Progress shows the Vosk model name and downloaded/total size when the server provides it. The model is large, so keep internet enabled and the app open. Step 2: start audio capture. Android 10+ is required; some apps block playback capture. Android requires RECORD_AUDIO permission as a system prerequisite; the app captures internal playback audio, not microphone input.",
            15f, Color.WHITE
        )
        layout.addView(ScrollView(this).apply { addView(info) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val prepareButton = Button(this).apply {
            text = "1. Prepare / Download Models"
            setOnClickListener {
                isEnabled = false
                statusView.text = "Preparing Vosk Japanese speech model and ML Kit English translation model…"
                Thread({
                    val engine = JapaneseCaptionEngine(
                        this@MainActivity,
                        onCaption = {},
                        onStatus = { message ->
                            runOnUiThread {
                                statusView.text = message
                                if (message == "Japanese speech and English translation ready.") {
                                    modelsReady = true
                                    prepareButton.isEnabled = false
                                    prepareButton.text = "Models Ready"
                                    startButton.isEnabled = true
                                    preparedEngine?.close()
                                    preparedEngine = null
                                } else if (message.contains("download failed", ignoreCase = true) ||
                                    message.contains("setup failed", ignoreCase = true)) {
                                    prepareButton.isEnabled = true
                                    prepareButton.text = "Retry Model Setup"
                                }
                            }
                        }
                    )
                    preparedEngine = engine
                    try {
                        engine.initialize()
                        // ML Kit completes asynchronously; close only after its ready callback.
                    } catch (e: Exception) {
                        engine.close()
                        runOnUiThread {
                            statusView.text = "Model setup failed: ${e.localizedMessage ?: "check connection and retry"}"
                            prepareButton.isEnabled = true
                            prepareButton.text = "Retry Model Setup"
                        }
                    }
                }, "LiveLingoModelSetup").start()
            }
        }
        layout.addView(prepareButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val startButton = Button(this).apply {
            text = "2. Start Internal Audio Capture"
            isEnabled = false
            setOnClickListener { requestPlaybackCapture() }
        }
        layout.addView(startButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        layout.addView(Button(this).apply {
            text = "Stop Internal Audio Capture"
            setOnClickListener {
                stopService(Intent(this@MainActivity, PlaybackCaptureService::class.java).setAction(PlaybackCaptureService.ACTION_STOP))
                statusView.text = "Internal audio capture stopped."
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(layout)
    }

    private fun requestPlaybackCapture() {
        if (!modelsReady) {
            statusView.text = "Please prepare both models completely before starting live captions."
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            statusView.text = "Internal playback capture requires Android 10 or newer."
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            statusView.text = "Allow Display over other apps, then tap Start Internal Audio Capture again."
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        statusView.text = "Approve the Android system capture prompt. Then play media in a supported app."
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), PLAYBACK_CAPTURE_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            statusView.text = if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                "Permission granted. Tap Start Internal Audio Capture again."
            } else {
                "Android requires this permission for playback capture setup. The microphone is not used as the audio source."
            }
        }
    }

    @Deprecated("Retained for compatibility with the current project structure")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PLAYBACK_CAPTURE_REQUEST) return
        if (resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, PlaybackCaptureService::class.java).apply {
                action = PlaybackCaptureService.ACTION_START
                putExtra(PlaybackCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(PlaybackCaptureService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            statusView.text = "Capture approved. Play Japanese media in a supported app. First-run model setup may take a few minutes; English captions should appear in the floating window once speech is recognized."
        } else {
            statusView.text = "Capture was cancelled. Nothing is being captured."
        }
    }
}
