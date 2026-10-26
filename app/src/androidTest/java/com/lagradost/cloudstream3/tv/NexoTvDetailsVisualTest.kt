package com.lagradost.cloudstream3.tv

import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.plugins.ensureAllowedProvider
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File

/** Opt-in visual evidence from the real provider; never presses Play or changes user favorites. */
class NexoTvDetailsVisualTest {
    @Test fun longTitleAndLargeTextWithoutArtworkKeepActionsReachable() {
        val title="Un viaje a traves de las estrellas: la extraordinaria historia de una familia que busca un nuevo hogar mas alla de los limites del universo conocido"
        val provider=object:MainAPI() {
            override var name="NexoStream"
            override var mainUrl="https://nexo-details-stress-${System.nanoTime()}.invalid"
            override val hasMainPage=true
            override val mainPage=mainPageOf("movie" to "Visual fixture")
            override suspend fun getMainPage(page:Int,request:MainPageRequest)=newHomePageResponse(request.name,listOf(newMovieSearchResponse(title,"$mainUrl/movie",TvType.Movie)),false)
            override suspend fun load(url:String)=newMovieLoadResponse(title,url,TvType.Movie,"fixture") {
                plot="Una sinopsis extensa para comprobar la lectura a distancia y el acceso a las acciones sin imagen de fondo. ".repeat(15)
                tags=listOf("Ciencia ficcion","Aventura","Drama")
            }
        }
        NexoTvTestEnvironment(provider).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            env.await(scenario,"Fixture Home missing") { it.findViewById<androidx.recyclerview.widget.RecyclerView>(env.id("nexo_tv_home_list"))?.adapter?.itemCount?.let { n -> n>0 }==true }
            scenario.onActivity { it.loadResult("${provider.mainUrl}/movie",provider.name,title) }
            env.await(scenario,"Fixture Details missing") { it.findViewById<View>(env.id("nexo_tv_result_play"))?.isShown==true }
            scenario.onActivity { a ->
                a.findViewById<android.widget.TextView>(env.id("nexo_tv_result_title")).textSize=46f
                a.findViewById<android.widget.TextView>(env.id("nexo_tv_result_plot")).textSize=22f
            }
            env.instrumentation.waitForIdleSync()
            scenario.onActivity { a ->
                a.findViewById<View>(env.id("nexo_tv_result_favorite")).requestFocus()
                a.findViewById<View>(env.id("nexo_tv_result_play")).requestFocus()
            }
            env.await(scenario,"Large text made Play unreachable") { a ->
                val view=a.findViewById<View>(env.id("nexo_tv_result_play"));val rect=android.graphics.Rect()
                view.hasFocus() && view.getGlobalVisibleRect(rect) && rect.height()==view.height
            }
            env.instrumentation.waitForIdleSync();SystemClock.sleep(600)
            env.instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(env.context.getExternalFilesDir(null),"details-phase1-stress.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
        } }
    }
    @Test fun captureMovieAndSeriesDetails() {
        val label=InstrumentationRegistry.getArguments().getString("nexo_details_capture") ?: "preview"
        NexoTvTestEnvironment().use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var activity:MainActivity?=null
            scenario.onActivity { activity=it }
            check(runBlocking(Dispatchers.IO) { withTimeout(90_000) { ensureAllowedProvider(checkNotNull(activity)) } })
            val api=checkNotNull(APIHolder.getApiFromNameNull("NexoStream"))
            fun model(a:MainActivity):ResultViewModel2? {
                val fragment=(a.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).childFragmentManager.primaryNavigationFragment
                return fragment?.takeIf { it is com.lagradost.cloudstream3.ui.tv.NexoTvResultFragment }?.let { ViewModelProvider(it)[ResultViewModel2::class.java] }
            }
            fun await(condition:(MainActivity)->Boolean) {
                val end=SystemClock.elapsedRealtime()+60_000
                while(SystemClock.elapsedRealtime()<end) {
                    var done=false;scenario.onActivity { done=condition(it) }
                    if(done) return
                    SystemClock.sleep(100)
                }
                error("Real Details visual evidence timed out")
            }
            for((kind,query,year) in listOf(Triple("movie","Inception",2010),Triple("series","True Beauty",2020))) {
                val matches=runBlocking(Dispatchers.IO) { withTimeout(60_000) { checkNotNull(api.search(query,1)).items } }
                val match=matches.first { when(it) { is MovieSearchResponse -> it.year==year; is TvSeriesSearchResponse -> it.year==year; is AnimeSearchResponse -> it.year==year; else -> false } }
                scenario.onActivity { it.loadResult(match.url,match.apiName,match.name) }
                await { a -> (model(a)?.page?.value as? Resource.Success)?.value?.url==match.url && a.findViewById<View>(env.id("nexo_tv_result_play"))?.isShown==true }
                await { it.findViewById<ImageView>(env.id("nexo_tv_result_backdrop"))?.drawable!=null }
                scenario.onActivity { it.findViewById<View>(env.id("nexo_tv_result_play")).requestFocus() }
                env.instrumentation.waitForIdleSync();SystemClock.sleep(1500)
                scenario.onActivity { a ->
                    val result=org.json.JSONObject()
                    for(name in listOf("nexo_tv_result_scroll","nexo_tv_result_title","nexo_tv_result_plot","nexo_tv_result_more","nexo_tv_result_play")) {
                        val v=a.findViewById<View>(env.id(name));val loc=IntArray(2);v.getLocationOnScreen(loc)
                        result.put(name,org.json.JSONObject().put("screenY",loc[1]).put("height",v.height).put("scrollY",v.scrollY))
                    }
                    File(env.context.getExternalFilesDir(null),"details-$label-$kind.json").writeText(result.toString(2))
                }
                env.instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File(env.context.getExternalFilesDir(null),"details-$label-$kind.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                }
            }
        } }
    }
}
