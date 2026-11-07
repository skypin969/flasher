package com.skypin.flasher

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this)
        text.text = "Flasher v0.1\nHello!"
        text.textSize = 24f
        text.setPadding(48, 48, 48, 48)
        setContentView(text)
    }
}
