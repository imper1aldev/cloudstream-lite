package com.lagradost.cloudstream3.ui.lite

import android.app.Activity
import android.os.Bundle
import androidx.media3.ui.PlayerView

/** Visible rendering surface for local instrumentation; excluded from release. */
class LitePlaybackProbeActivity : Activity() {
    lateinit var video: PlayerView
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        video = PlayerView(this).apply { useController = false; keepScreenOn = true }
        setContentView(video)
    }
}
