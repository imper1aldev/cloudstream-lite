package com.lagradost.cloudstream3.ui.lite

import androidx.fragment.app.Fragment
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.IPlayer

/** Debug-only host for the real player fragment, including framework recreation. */
class LitePlayerProbeActivity : androidx.appcompat.app.AppCompatActivity() {
    companion object { var instrumentedPlayer: IPlayer? = null }
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onAttachFragment(fragment: Fragment) {
        super.onAttachFragment(fragment)
        if (fragment is GeneratorPlayer) instrumentedPlayer?.let { fragment.player = it }
    }
}
