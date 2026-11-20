package com.lagradost.cloudstream3.tv

import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Test

class NexoTvHomeStartupTest {
    private class Provider : MainAPI() {
        override var name = "NexoStream"
        override var mainUrl = "https://nexo-startup-fixture-${System.nanoTime()}.invalid"
        override val hasMainPage = true
        override val mainPage = mainPageOf("trending" to "Startup catalog")
        val rejectCatalog = AtomicBoolean()
        val startedMetadata = AtomicBoolean()
        val releaseMetadata = CompletableDeferred<Unit>()

        override suspend fun getMainPage(page: Int, request: MainPageRequest): com.lagradost.cloudstream3.HomePageResponse {
            if (rejectCatalog.get()) throw java.io.IOException("Fixture offline")
            return newHomePageResponse(request.name, listOf(newMovieSearchResponse(
                "Startup title", "$mainUrl/title", TvType.Movie
            )), false)
        }

        override suspend fun load(url: String) = newMovieLoadResponse(
            "Startup title", url, TvType.Movie, "fixture"
        ) {
            year = 2024
        }.also {
            startedMetadata.set(true)
            releaseMetadata.await()
        }
    }

    @Test fun catalogIsUsableWhileFeaturedMetadataIsStillLoading() {
        val provider = Provider()
        NexoTvTestEnvironment(provider).use { env ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    env.await(scenario, "Featured metadata was never requested") { provider.startedMetadata.get() }
                    env.await(scenario, "The catalog waited for featured metadata") { activity ->
                        val model = ViewModelProvider(activity)[HomeViewModel::class.java]
                        (model.page.value as? Resource.Success)?.value?.containsKey("Startup catalog") == true &&
                            activity.findViewById<View>(R.id.nexo_tv_home_list)?.isShown == true &&
                            activity.findViewById<View>(R.id.nexo_tv_hero_action)?.isShown == true
                    }
                    env.key(KeyEvent.KEYCODE_DPAD_DOWN)
                    env.await(scenario, "Remote focus did not reach the first poster") {
                        it.currentFocus?.tag?.toString()?.endsWith("/title") == true
                    }
                    provider.releaseMetadata.complete(Unit)
                    env.await(scenario, "Featured metadata did not arrive after the catalog") { activity ->
                        activity.findViewById<TextView>(R.id.nexo_tv_hero_metadata)?.text?.contains("2024") == true &&
                            activity.currentFocus?.tag?.toString()?.endsWith("/title") == true
                    }
                } finally {
                    provider.releaseMetadata.complete(Unit)
                }
            }
        }
    }

    @Test fun catalogCanRecoverFromAnOfflineLaunch() {
        val provider = Provider()
        provider.rejectCatalog.set(true)
        provider.releaseMetadata.complete(Unit)
        NexoTvTestEnvironment(provider).use { env ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                env.await(scenario, "Offline startup did not show Retry") {
                    it.findViewById<View>(R.id.nexo_tv_home_retry)?.isShown == true
                }
                provider.rejectCatalog.set(false)
                scenario.onActivity {
                    it.findViewById<View>(R.id.nexo_tv_home_retry).performClick()
                }
                env.await(scenario, "Home did not recover when the provider became reachable") {
                    it.findViewById<View>(R.id.nexo_tv_home_list)?.isShown == true &&
                        it.findViewById<View>(R.id.nexo_tv_home_retry)?.isShown == false
                }
            }
        }
    }
}
