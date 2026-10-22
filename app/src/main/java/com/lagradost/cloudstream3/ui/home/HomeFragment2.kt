package com.lagradost.cloudstream3.ui.home

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream4.compose.Screen

/**
 * Home host (T3.1). Phone/tablet get the Lite Compose Home ([HomeScreen]);
 * TV/emulator keep the untouched legacy [HomeFragment], hosted as a child so
 * its behaviour (focus rail, TV channels, back handling) is byte-for-byte the
 * same code path.
 *
 * Routing lives entirely at the device-selection point ([isLayout]) inside this
 * file plus the single `navigation_home` line in `mobile_navigation.xml`.
 * Rollback: point `navigation_home` back to `HomeFragment` (one line) and
 * optionally delete this file + [HomeScreen].
 */
class HomeFragment2 : BaseComposeFragment(), Screen by HomeScreen {

    private val isLiteDevice: Boolean
        get() = !isLayout(TV or EMULATOR)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        if (isLiteDevice) {
            return super.onCreateView(inflater, container, savedInstanceState)
        }

        // TV/emulator: proxy the legacy fragment. A stable container id lets the
        // child fragment state survive configuration changes (won't re-add then).
        val host = FrameLayout(inflater.context).apply {
            id = R.id.home_lite_legacy_host
        }
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.home_lite_legacy_host, HomeFragment())
                .commit()
        }
        return host
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // TV path lets the legacy child own its own system-bar padding.
        if (isLiteDevice) super.onViewCreated(view, savedInstanceState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (isLiteDevice) super.onConfigurationChanged(newConfig)
    }
}
