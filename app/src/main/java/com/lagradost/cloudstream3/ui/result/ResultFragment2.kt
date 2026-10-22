package com.lagradost.cloudstream3.ui.result

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.SettingsGeneral.Companion.pickDownloadPath
import com.lagradost.cloudstream3.ui.settings.utils.getChooseFolderLauncher
import com.lagradost.cloudstream3.utils.downloader.DownloadFileManagement.getBasePath
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream4.compose.Screen

/**
 * Details host (T4.1). Phone/tablet get the Lite Compose Details
 * ([ResultScreen]); TV/emulator keep the untouched legacy details fragment
 * ([ResultTrailerPlayer], the current `navigation_results_phone` destination),
 * hosted as a child so its behaviour (trailer player, panels, focus) is
 * byte-for-byte the same code path.
 *
 * Routing lives entirely at the device-selection point ([isLayout]) inside this
 * file plus the single `navigation_results_phone` line in `mobile_navigation.xml`.
 * Rollback: point `navigation_results_phone` back to `ResultTrailerPlayer` (one
 * line) and optionally delete this file + [ResultScreen].
 *
 * The player contract is untouched: the Lite path only invokes
 * [ResultViewModel2.handleAction] and the TV path proxies the legacy fragment
 * that owns the handoff.
 */
class ResultFragment2 : BaseComposeFragment(), Screen by ResultScreen {

    private var pendingDownload: (() -> Unit)? = null
    private val downloadPathPicker = getChooseFolderLauncher { uri, path ->
        if (uri == null) {
            pendingDownload = null
            return@getChooseFolderLauncher
        }
        pickDownloadPath(uri, path)
        pendingDownload?.invoke()
        pendingDownload = null
    }

    fun requestDownload(onDownload: () -> Unit) {
        val (uri, path) = context?.getBasePath() ?: return
        if (uri != null || path != null) {
            onDownload()
            return
        }

        pendingDownload = onDownload
        try {
            context?.let {
                Toast.makeText(it, R.string.download_path_pref, Toast.LENGTH_SHORT).show()
            }
            downloadPathPicker.launch(Uri.EMPTY)
        } catch (e: Exception) {
            pendingDownload = null
            android.util.Log.e("T4.4Details", "Download path picker failed", e)
            context?.let { Toast.makeText(it, R.string.error, Toast.LENGTH_LONG).show() }
        }
    }

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

        // TV/emulator: proxy the legacy details fragment with the same
        // stable-container pattern as T3.1 (state survives configuration changes).
        val host = FrameLayout(inflater.context).apply {
            id = R.id.result_lite_legacy_host
        }
        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.result_lite_legacy_host, ResultTrailerPlayer())
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
