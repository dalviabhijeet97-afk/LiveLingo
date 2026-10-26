package com.livelingo.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
        private const val OVERLAY_PERMISSION_REQUEST = 1002
        private const val PLAYBACK_CAPTURE_REQUEST = 1003
    }

    private lateinit var statusView: TextView

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
        layout.addView(label("Japanese → English\nInternal audio capture prototype\nVersion 0.4.0 experimental", 18f, Color.LTGRAY))
        statusView = label(
            "New: test internal audio capture from supported apps. Android will ask for screen/audio capture consent.",
            15f, Color.rgb(100, 230, 130)
        )
        layout.addView(statusView)
        val info = label(
            "This experimental build checks whether Android can deliver app playback audio to LiveLingo without listening through the microphone. It does not produce captions yet; speech recognition is the next integration step. Some apps block audio capture. Android also requires the RECORD_AUDIO permission as a playback-capture prerequisite.",
            15f, Color.WHITE
        )
        layout.addView(ScrollView(this).apply { addView(info) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        layout.addView(Button(this).apply {
            text = "Test Internal Audio Capture"
            setOnClickListener { requestPlaybackCapture() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        layout.addView(Button(this).apply {
            text = "Start Microphone Translation (old)"
            setOnClickListener { requestMicrophoneTranslation() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        layout.addView(Button(this).apply {
            text = "Stop All"
            setOnClickListener {
                stopService(Intent(this@MainActivity, PlaybackCaptureService::class.java).setAction(PlaybackCaptureService.ACTION_STOP))
                stopService(Intent(this@MainActivity, TranslationService::class.java).setAction(TranslationService.ACTION_STOP))
                statusView.text = "Stopped."
            }
        })
        setContentView(layout)
    }

    private fun requestPlaybackCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            statusView.text = "Internal playback capture requires Android 10 or newer."
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        statusView.text = "Approve Android's system capture prompt, then play media in a supported app."
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), PLAYBACK_CAPTURE_REQUEST)
    }

    private fun requestMicrophoneTranslation() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            statusView.text = "Enable Display over other apps, return here, then tap the microphone button again."
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            @Suppress("DEPRECATION")
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            return
        }
        startMicrophoneTranslationService()
    }

    private fun startMicrophoneTranslationService() {
        val intent = Intent(this, TranslationService::class.java).setAction(TranslationService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        statusView.text = "Microphone translation started."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                statusView.text = "Permission granted. Tap the desired start button again."
            } else {
                statusView.text = "Android's audio permission is required for playback capture."
            }
        }
    }

    @Deprecated("Android uses Activity Result APIs in newer apps; retained for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PLAYBACK_CAPTURE_REQUEST -> {
                if (resultCode == RESULT_OK && data != null) {
                    val intent = Intent(this, PlaybackCaptureService::class.java).apply {
                        action = PlaybackCaptureService.ACTION_START
                        putExtra(PlaybackCaptureService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(PlaybackCaptureService.EXTRA_RESULT_DATA, data)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                    statusView.text = "Capture approved. Play media in Chrome or another app, then check the notification status."
                } else {
                    statusView.text = "Capture was cancelled. Nothing is being recorded."
                }
            }
            OVERLAY_PERMISSION_REQUEST -> {
                statusView.text = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                    "Overlay permission granted. Tap microphone translation again."
                } else {
                    "Overlay permission was not granted."
                }
            }
        }
    }
}
