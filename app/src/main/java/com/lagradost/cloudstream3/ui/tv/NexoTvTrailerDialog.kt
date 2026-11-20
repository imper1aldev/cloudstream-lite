package com.lagradost.cloudstream3.ui.tv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.NexoTvTrailerBinding
import com.lagradost.cloudstream3.ui.player.*
import com.lagradost.cloudstream3.ui.result.ExtractedTrailerData
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType

internal fun List<ExtractedTrailerData>.tvTrailerLinks(): List<ExtractorLink> =
    flatMap { it.mirros }.map { it.first }.filter {
        it.url.isNotBlank() && it.type in setOf(ExtractorLinkType.VIDEO, ExtractorLinkType.M3U8, ExtractorLinkType.DASH)
    }.sortedByDescending { it.quality }

/** A manual trailer session, isolated from episode actions, progress and progressive-server policy. */
@OptIn(UnstableApi::class)
class NexoTvTrailerDialog : DialogFragment() {
    private var binding: NexoTvTrailerBinding? = null
    private var engine: CS3IPlayer? = null
    private var links = emptyList<ExtractorLink>()
    private var mirror = 0
    private var lifecyclePaused = false
    private var resumePlayback = false
    // A trailer owns its focus request. A stale Activity must not pause a newer trailer.
    private var audioManager: AudioManager? = null
    private var audioRequest: AudioFocusRequest? = null
    private val audioListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change < 0) engine?.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.Player)
    }
    @Suppress("DEPRECATION")
    private fun requestAudioFocus() {
        val manager = audioManager ?: (requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager).also { audioManager = it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener(audioListener).build().also { audioRequest = it }
            manager.requestAudioFocus(request)
        } else manager.requestAudioFocus(audioListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
    }
    @Suppress("DEPRECATION")
    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) audioRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        else audioManager?.abandonAudioFocus(audioListener)
        audioRequest = null
        audioManager = null
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setStyle(STYLE_NO_TITLE, R.style.NexoTvTrailerDialogTheme)
    }
    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        return NexoTvTrailerBinding.inflate(inflater, parent, false).also { binding = it }.root
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        val ui = binding ?: return
        ui.nexoTvTrailerClose.setOnClickListener { dismiss() }
        // Inset only the controls; the video surface remains full screen.
        val density = resources.displayMetrics.density
        ui.nexoTvTrailerPlayer.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
            .setPadding((48 * density).toInt(), (27 * density).toInt(), (48 * density).toInt(), (27 * density).toInt())
        ui.nexoTvTrailerPlayer.setControllerVisibilityListener(androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
            ui.nexoTvTrailerHeader.isVisible = visibility == View.VISIBLE || ui.nexoTvTrailerError.isVisible || ui.nexoTvTrailerClose.hasFocus()
        })
        ui.nexoTvTrailerClose.setOnFocusChangeListener { _, focused ->
            if (focused) ui.nexoTvTrailerPlayer.showController()
            else ui.nexoTvTrailerHeader.isVisible = ui.nexoTvTrailerPlayer.isControllerFullyVisible || ui.nexoTvTrailerError.isVisible
        }
        val model = ViewModelProvider(requireParentFragment())[ResultViewModel2::class.java]
        links = model.trailers.value.orEmpty().tvTrailerLinks()
        if (links.isEmpty()) showFailure()
        else loadMirror((state?.getInt("mirror") ?: 0).coerceIn(links.indices), state?.getLong("position") ?: 0L, state?.getBoolean("play") ?: true)
    }
    private fun loadMirror(index: Int, position: Long = 0L, play: Boolean = true) {
        val ui = binding ?: return
        engine?.releaseCallbacks(); engine?.release()
        ui.nexoTvTrailerPlayer.player = null
        mirror = index
        val link = links.getOrNull(index) ?: return showFailure()
        ui.nexoTvTrailerError.isVisible = false
        val current = CS3IPlayer()
        engine = current
        current.initCallbacks({ event ->
            if (engine === current && binding === ui) when (event) {
                is PlayerAttachedEvent -> ui.nexoTvTrailerPlayer.player = event.player as? Player
                is RequestAudioFocusEvent -> {
                    requestAudioFocus()
                    // Start the native hide timeout after the initial loading/attachment transitions.
                    ui.root.post { if (engine === current && binding === ui) ui.nexoTvTrailerPlayer.showController() }
                }
                is ErrorEvent -> ui.root.post {
                    if (engine === current && binding === ui) {
                        if (mirror + 1 < links.size) loadMirror(mirror + 1) else showFailure()
                    }
                }
            }
        })
        // Keep the original ExtractorLink, including referer, headers and any specialized link data.
        current.loadPlayer(requireContext(), false, link, null, position, emptySet(), null, autoPlay = play, preview = false)
    }
    private fun showFailure() {
        val ui = binding ?: return
        engine?.releaseCallbacks(); engine?.release(); engine = null
        releaseAudioFocus()
        ui.nexoTvTrailerPlayer.player = null
        ui.nexoTvTrailerError.isVisible = true
        ui.nexoTvTrailerHeader.isVisible = true
        ui.nexoTvTrailerClose.post { if (binding === ui) ui.nexoTvTrailerClose.requestFocus() }
    }
    override fun onStart() {
        super.onStart()
        dialog?.setCanceledOnTouchOutside(false)
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        binding?.nexoTvTrailerPlayer?.apply { showController(); requestFocus() }
    }
    override fun onPause() {
        resumePlayback = binding?.nexoTvTrailerPlayer?.player?.playWhenReady == true
        lifecyclePaused = true
        engine?.onPause()
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        if (lifecyclePaused) {
            engine?.onResume(requireContext())
            if (resumePlayback) engine?.handleEvent(CSPlayerEvent.Play, PlayerEventSource.Player)
            lifecyclePaused = false
        }
    }
    override fun onStop() {
        releaseAudioFocus()
        engine?.onStop()
        super.onStop()
    }
    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("mirror", mirror)
        out.putLong("position", engine?.getPosition() ?: 0L)
        out.putBoolean("play", if (lifecyclePaused) resumePlayback else binding?.nexoTvTrailerPlayer?.player?.playWhenReady == true)
        super.onSaveInstanceState(out)
    }
    override fun onDestroyView() {
        binding?.nexoTvTrailerPlayer?.setControllerVisibilityListener(null as androidx.media3.ui.PlayerView.ControllerVisibilityListener?)
        binding?.nexoTvTrailerPlayer?.player = null
        binding = null
        engine?.releaseCallbacks(); engine?.release(); engine = null
        releaseAudioFocus()
        super.onDestroyView()
    }
    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        val parent = parentFragment ?: return
        if (parent.isResumed) {
            val root = parent.view ?: return
            (root.findViewById<View>(R.id.nexo_tv_result_trailer)?.takeIf { it.isShown }
                ?: root.findViewById<View>(R.id.nexo_tv_result_play)?.takeIf { it.isShown }
                ?: root.findViewById<View>(R.id.nexo_tv_result_favorite)).requestFocus()
        }
    }
}
