package com.lagradost.cloudstream3

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.AppContextUtils.isCastApiAvailable
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Inspect real Details state, popup nodes and action callbacks without changing production UI. */
class LiteMoreAuditTest {
    private class Provider : MainAPI() {
        override var name="NexoStream More audit"
        override var mainUrl="https://more-audit.invalid"
        override val hasChromecastSupport=true
        override val hasDownloadSupport=true
        override suspend fun load(url:String):LoadResponse = if(url.endsWith("series")) {
            newTvSeriesLoadResponse("Audit series",url,TvType.TvSeries,
                (1..3).map { newEpisode("fixture-$it",initializer={episode=it;season=1},fix=false) }) { plot="Audit synopsis" }
        } else newMovieLoadResponse("Audit movie",url,TvType.Movie,"fixture") { plot="Audit synopsis" }
    }
    private fun tree():List<AccessibilityNodeInfo> {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        if(android.os.Build.VERSION.SDK_INT>=33)automation.clearCache()
        return LiteUiAutomation.nodes(automation.rootInActiveWindow).filter { it.isVisibleToUser }
    }
    private fun inspect(scenario:ActivityScenario<MainActivity>,provider:MainAPI,url:String,title:String,key:String):JSONObject {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        scenario.onActivity { it.loadResult(url,provider.name,title) }
        val deadline=System.currentTimeMillis()+30_000
        while(tree().none { it.text?.toString()==title } && System.currentTimeMillis()<deadline)Thread.sleep(100)
        assertTrue("Details failed for $title; visible="+tree().mapNotNull { it.text ?: it.contentDescription },tree().any { it.text?.toString()==title })
        Thread.sleep(1000)
        val record=JSONObject().put("case",key).put("title",title).put("provider",provider.name)
        scenario.onActivity { activity ->
            val nav=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
            val fragment=nav.childFragmentManager.primaryNavigationFragment!!
            val owner=fragment.requireView().findViewTreeViewModelStoreOwner() ?: fragment
            val vm=ViewModelProvider(owner)[ResultViewModel2::class.java]
            record.put("owner",owner.javaClass.simpleName)
            record.put("movie_episode_ready",vm.movie.value is Resource.Success)
            record.put("resume_ready",vm.resumeWatching.value!=null)
            record.put("subscribe_status",vm.subscribeStatus.value ?: JSONObject.NULL)
            record.put("provider_cast_support",vm.currentRepo?.api?.hasChromecastSupport==true)
            record.put("cast_api_available",activity.isCastApiAvailable())
        }
        LiteUiAutomation.clickLabel(context.getString(R.string.lite_more_actions))
        fun visibleLabels()=tree().mapNotNull { it.text?.toString()?.takeIf { text -> text.isNotBlank() } ?: it.contentDescription?.toString()?.takeIf { text -> text.isNotBlank() } }
        record.put("menu_labels",JSONArray(visibleLabels()))
        val entries=JSONArray()
        listOf(R.string.lite_options,R.string.lite_follow,R.string.lite_following,R.string.lite_action_sync,R.string.lite_cast_action).forEach { res ->
            val label=context.getString(res);val node=tree().firstOrNull { it.text?.toString()==label || it.contentDescription?.toString()==label }
            var target=node;while(target!=null && !target.isClickable)target=target.parent
            val bounds=Rect();node?.getBoundsInScreen(bounds)
            entries.put(JSONObject().put("label",label).put("visible",node!=null).put("enabled",target?.isEnabled==true).put("clickable",target?.isClickable==true).put("bounds",bounds.flattenToString()))
        }
        record.put("entries",entries)
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(context.cacheDir,"more-$key.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        if(tree().any { it.text?.toString()==context.getString(R.string.lite_options) }) {
            LiteUiAutomation.clickLabel(context.getString(R.string.lite_options));Thread.sleep(300)
            record.put("options_dialog_labels",JSONArray(visibleLabels()))
            LiteUiAutomation.clickLabel(context.getString(R.string.cancel))
        } else {
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
        }
        scenario.onActivity {
            (it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController.popBackStack()
        }
        instrumentation.waitForIdleSync()
        return record
    }
    private fun audit(live:Boolean) = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val prefs=PreferenceManager.getDefaultSharedPreferences(context);val key=context.getString(R.string.app_layout_key);val previous=prefs.all[key]
        prefs.edit().putInt(key,0).commit();context.updateTv()
        val records=JSONArray();val provider=Provider()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                if(!live) {
                    APIHolder.allProviders.add(provider);APIHolder.addPluginMapping(provider)
                    for(case in listOf("movie","series")) {
                        val url=provider.mainUrl+"/__audit_20261021_"+case
                        records.put(inspect(scenario,provider,url,"Audit $case",case))
                    }
                } else {
                    val deadline=System.currentTimeMillis()+15_000
                    while(APIHolder.getApiFromNameNull("NexoStream")==null && System.currentTimeMillis()<deadline)delay(100)
                    val actual=checkNotNull(APIHolder.getApiFromNameNull("NexoStream"))
                    val page=withTimeout(45_000) { withContext(Dispatchers.IO) { actual.mainPage.first().let { actual.getMainPage(1,MainPageRequest(it.name,it.data,it.horizontalImages)) } } }
                    val items=checkNotNull(page).items.flatMap { it.list }
                    val samples=listOfNotNull(items.firstOrNull { it.type==TvType.Movie },items.firstOrNull { it.type==TvType.TvSeries })
                    assertTrue("No usable Home samples",samples.isNotEmpty())
                    for((index,item)in samples.withIndex())records.put(inspect(scenario,actual,item.url,item.name,"live-$index"))
                }
            }
        } finally {
            File(context.cacheDir,if(live)"more-live.json" else "more-controlled.json").writeText(records.toString(2))
            APIHolder.removePluginMapping(provider);APIHolder.allProviders.remove(provider)
            val edit=prefs.edit();if(previous is Int)edit.putInt(key,previous)else edit.remove(key);edit.commit();context.updateTv()
        }
    }
    @Test fun controlledMovieAndSeriesMenus() = audit(false)
    @Test fun liveNexoMenus() { assumeTrue(InstrumentationRegistry.getArguments().getString("auditLive")=="true");audit(true) }
}
