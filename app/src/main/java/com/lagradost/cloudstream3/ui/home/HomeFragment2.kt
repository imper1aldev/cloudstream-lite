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

/** Phone/tablet keep HomeScreen; TV uses its own child and the shared HomeViewModel.
 * Rollback TV only: replace NexoTvHomeFragment with HomeFragment below.
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

        // TV/emulator: own presentation over the existing catalog contracts. A stable container id lets the
        // child fragment state survive recreation and return from the back stack.
        val host = FrameLayout(inflater.context).apply {
            id = R.id.home_lite_legacy_host
        }
        // A back-stack return can recreate the view with no savedInstanceState.
        // Keep the child that FragmentManager already owns, including its focus state.
        if (childFragmentManager.findFragmentById(R.id.home_lite_legacy_host) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.home_lite_legacy_host, com.lagradost.cloudstream3.ui.tv.NexoTvHomeFragment())
                .commit()
        }
        return host
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // TV child owns its layout; retain the existing phone/tablet padding path.
        if (isLiteDevice) super.onViewCreated(view, savedInstanceState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (isLiteDevice) super.onConfigurationChanged(newConfig)
    }
}
