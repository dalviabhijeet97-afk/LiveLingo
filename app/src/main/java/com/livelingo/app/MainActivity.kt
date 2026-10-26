package com.livelingo.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
    }

    private lateinit var statusView: TextView
    private lateinit var startButton: Button
    private var waitingForOverlayPermission = false

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
        layout.addView(label("Japanese → English\nLive floating subtitles\nVersion 0.3.0", 18f, Color.LTGRAY))
        statusView = label(
            "Translate Japanese speech while using other apps. Allow microphone and display-over-apps access.",
            15f, Color.rgb(100, 230, 130)
        )
        layout.addView(statusView)
        val info = label(
            "Important: LiveLingo listens through your microphone. Play Chrome or browser audio through the tablet speakers. Direct internal-audio capture is not supported in this version.",
            15f, Color.WHITE
        )
        layout.addView(ScrollView(this).apply { addView(info) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        startButton = Button(this).apply {
            text = "Start Floating Translation"
            setOnClickListener { requestPermissionsAndStart() }
        }
        layout.addView(startButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val stopButton = Button(this).apply {
            text = "Stop Translation"
            setOnClickListener {
                stopService(Intent(this@MainActivity, TranslationService::class.java).setAction(TranslationService.ACTION_STOP))
                statusView.text = "Translation stopped."
            }
        }
        layout.addView(stopButton)
        setContentView(layout)
    }

    private fun requestPermissionsAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = true
            statusView.text = "Enable 'Display over other apps' for LiveLingo, then return here and tap Start again."
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            return
        }
        startTranslationService()
    }

    private fun startTranslationService() {
        waitingForOverlayPermission = false
        val intent = Intent(this, TranslationService::class.java).setAction(TranslationService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        statusView.text = "Translation started. A floating subtitle bar should appear over other apps."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestPermissionsAndStart()
            } else {
                statusView.text = "Microphone permission is required."
            }
        }
    }

    @Deprecated("Deprecated by Android; retained for compatibility with older devices")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                statusView.text = "Overlay permission granted. Tap Start Floating Translation."
            } else {
                statusView.text = "Overlay permission was not granted. Floating subtitles need this permission."
            }
            waitingForOverlayPermission = false
        }
    }
}
