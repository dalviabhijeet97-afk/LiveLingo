package com.livelingo.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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
        layout.addView(label("Japanese → English\nInternal audio path\nVersion 0.4.1 experimental", 18f, Color.LTGRAY))
        statusView = label(
            "Microphone translation is intentionally disabled in this test build. Start internal audio capture, approve Android's system prompt, then play media in a supported app.",
            15f, Color.rgb(100, 230, 130)
        )
        layout.addView(statusView)
        val info = label(
            "This build focuses only on direct playback audio capture. It currently checks whether audio buffers arrive; English captions are not available until the audio-buffer Japanese speech engine is integrated. Android 10+ is required. Some apps block capture. Android requires the RECORD_AUDIO permission as a system prerequisite, but LiveLingo does not use it as the source audio in this path.",
            15f, Color.WHITE
        )
        layout.addView(ScrollView(this).apply { addView(info) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        layout.addView(Button(this).apply {
            text = "Start Internal Audio Capture"
            setOnClickListener { requestPlaybackCapture() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            statusView.text = "Internal playback capture requires Android 10 or newer."
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
            statusView.text = "Capture approved. Play media in Chrome or another supported app. Check the ongoing notification for audio detection."
        } else {
            statusView.text = "Capture was cancelled. Nothing is being captured."
        }
    }
}
