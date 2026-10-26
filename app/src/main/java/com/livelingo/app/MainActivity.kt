package com.livelingo.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(18, 24, 38))
        }

        val title = TextView(this).apply {
            text = "LiveLingo"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val description = TextView(this).apply {
            text = "\nJapanese → English\n\nOffline live subtitles\n\nVersion 0.1.0"
            textSize = 18f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }

        val status = TextView(this).apply {
            text = "\nReady to get started"
            textSize = 16f
            setTextColor(Color.GREEN)
            gravity = Gravity.CENTER
        }

        val button = Button(this).apply {
            text = "Start Translation"
            setOnClickListener {
                status.text =
                    "Translation engine is not installed yet.\n\nNext: audio capture and Whisper."
            }
        }

        layout.addView(title)
        layout.addView(description)
        layout.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        layout.addView(button)

        setContentView(layout)
    }
}
