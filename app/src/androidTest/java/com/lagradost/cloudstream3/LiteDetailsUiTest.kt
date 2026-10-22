package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.LiteUiAutomation.nodes
import com.lagradost.cloudstream3.LiteUiAutomation.clickLabel
import com.lagradost.cloudstream3.LiteUiAutomation.waitFor
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.ui.result.getId
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.VIDEO_POS_DUR
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exercises the real Details route/ViewModel with deterministic test-only metadata. */
class LiteDetailsUiTest {
    private class FixtureProvider : MainAPI() {
        override var name = "NexoStream UI fixture"
        override var mainUrl = "https://nexo-ui-fixture.invalid"
        var scenario = "movie"
        override val hasDownloadSupport get() = scenario != "nodownload"
        override val hasChromecastSupport = false
        override suspend fun load(url: String): LoadResponse = if(scenario=="series") {
            newTvSeriesLoadResponse("Fixture series",url,TvType.TvSeries,
                (1..3).map { number -> newEpisode("fixture-$number",initializer={ episode=number;season=1;name="Episode $number" },fix=false) }) {
                plot="A short test synopsis."; year=2026
            }
        } else newMovieLoadResponse("Fixture $scenario",url,TvType.Movie,"fixture") {
            plot="A short test synopsis."; year=2026
        }
    }
    @Test fun detailsPresentation(): Unit = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val prefs=PreferenceManager.getDefaultSharedPreferences(context)
        val key=context.getString(R.string.app_layout_key)
        val previous=prefs.all[key]
        val provider=FixtureProvider()
        val ownedIds=mutableListOf<Int>()
        prefs.edit().putInt(key,0).commit()
        context.updateTv()
        val args=InstrumentationRegistry.getArguments()
        val prefix=args.getString("prefix","details-baseline")
        val verify=args.getString("verify","false")=="true"
        val results=JSONArray()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activityScenario ->
                APIHolder.allProviders.add(provider)
                APIHolder.addPluginMapping(provider)
                for(case in args.getString("cases","movie,series,resume,nodownload").split(',')) {
                    provider.scenario=case
                    val url=provider.mainUrl+"/__evidence_20261017_$case"
                    val response=provider.load(url)
                    val id=response.getId()
                    ownedIds.add(id)
                    DataStoreHelper.removeFavoritesData(id)
                    if(case=="resume") {
                        DataStoreHelper.setViewPos(id,120_000,600_000)
                        DataStoreHelper.setLastWatched(id,id,0,null)
                    }
                    activityScenario.onActivity { it.loadResult(url,provider.name,response.name) }
                    val deadline=System.currentTimeMillis()+35_000
                    var tree=emptyList<AccessibilityNodeInfo>()
                    while(System.currentTimeMillis()<deadline) {
                        if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
                        tree=nodes(instrumentation.uiAutomation.rootInActiveWindow)
                        if(tree.any { it.text?.toString()?.contains("Fixture $case")==true })break
                        Thread.sleep(200)
                    }
                    assertTrue("Details fixture did not load: $case; tree=${tree.mapNotNull { it.text ?: it.contentDescription }}; api=${APIHolder.getApiFromNameNull(provider.name)}", tree.any { it.text?.toString()?.contains("Fixture $case")==true })
                    Thread.sleep(700)
                    if(args.getString("scroll","false")=="true") {
                        val rect=Rect();instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(rect)
                        val now=android.os.SystemClock.uptimeMillis()
                        val x=rect.exactCenterX();val from=rect.height()*0.85f;val to=rect.height()*0.5f
                        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,from,0))
                        for(step in 1..6) {
                            Thread.sleep(35)
                            instrumentation.sendPointerSync(android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_MOVE,x,from+(to-from)*step/6,0))
                        }
                        instrumentation.sendPointerSync(android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,x,to,0))
                        Thread.sleep(500)
                    }
                    tree=nodes(instrumentation.uiAutomation.rootInActiveWindow)
                    val actions=tree.filter { it.isClickable || !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() }.map { node ->
                        val bounds=Rect();node.getBoundsInScreen(bounds)
                        var target: AccessibilityNodeInfo?=node
                        while(target!=null && !target.isClickable)target=target.parent
                        val targetBounds=Rect();target?.getBoundsInScreen(targetBounds)
                        mapOf("target_bounds" to target?.let { targetBounds.flattenToString() }, "text" to node.text?.toString(),"description" to node.contentDescription?.toString(),
                            "clickable" to node.isClickable,"bounds" to bounds.flattenToString())
                    }
                    results.put(JSONObject(mapOf("case" to case,"nodes" to actions)))
                    instrumentation.uiAutomation.takeScreenshot().useBitmap { bitmap ->
                        File(context.cacheDir,"$prefix-$case.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    }
                    if(verify && case=="movie") {
                        assertTrue("Principal download lacks visible label",tree.any { it.text?.toString()==context.getString(R.string.download) })
                    }
                    if(args.getString("interact","false")=="true") {
                        clickLabel(context.getString(R.string.lite_favorite))
                        waitFor { DataStoreHelper.getFavoritesData(id)!=null }
                        if(case=="movie") {
                            val events=CopyOnWriteArrayList<VideoDownloadManager.DownloadActionType>()
                            val observer:(Pair<Int,VideoDownloadManager.DownloadActionType>)->Unit={ if(it.first==id)events.add(it.second) }
                            VideoDownloadManager.downloadEvent+=observer
                            try {
                                fun status(value: VideoDownloadManager.DownloadType) {
                                    activityScenario.onActivity {
                                        VideoDownloadManager.downloadStatus[id]=value
                                        VideoDownloadManager.downloadStatusEvent.invoke(id to value)
                                        VideoDownloadManager.downloadProgressEvent.invoke(Triple(id,25,100))
                                    }
                                    instrumentation.waitForIdleSync();Thread.sleep(250)
                                }
                                status(VideoDownloadManager.DownloadType.IsDownloading)
                                assertFalse(nodes(instrumentation.uiAutomation.rootInActiveWindow).any { it.text?.toString()==context.getString(R.string.downloading) })
                                clickLabel(context.getString(R.string.downloading))
                                clickLabel(context.getString(R.string.popup_pause_download))
                                assertEquals(VideoDownloadManager.DownloadActionType.Pause,events.last())
                                status(VideoDownloadManager.DownloadType.IsPaused)
                                clickLabel(context.getString(R.string.download_paused))
                                clickLabel(context.getString(R.string.popup_resume_download))
                                assertEquals(VideoDownloadManager.DownloadActionType.Resume,events.last())
                                status(VideoDownloadManager.DownloadType.IsDone)
                                clickLabel(context.getString(R.string.downloaded))
                                clickLabel(context.getString(R.string.popup_delete_file))
                                assertTrue(nodes(instrumentation.uiAutomation.rootInActiveWindow).any { it.text?.toString()?.equals(context.getString(R.string.delete_file),true)==true })
                                clickLabel(context.getString(R.string.cancel))
                                assertNotNull(DataStoreHelper.getFavoritesData(id))
                                status(VideoDownloadManager.DownloadType.IsFailed)
                                instrumentation.uiAutomation.takeScreenshot().useBitmap { bitmap ->
                                    File(context.cacheDir,"$prefix-error.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                                }
                            } finally {
                                VideoDownloadManager.downloadEvent-=observer
                                VideoDownloadManager.downloadStatus.remove(id)
                                VideoDownloadManager.downloadDeleteEvent.invoke(id)
                            }
                            clickLabel(context.getString(R.string.lite_more_actions))
                            clickLabel(context.getString(R.string.lite_options))
                            waitFor { nodes(instrumentation.uiAutomation.rootInActiveWindow).any { it.text?.toString()==context.getString(R.string.episode_action_play_in_app) } }
                            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                        }
                        if(case=="series") {
                            val work=androidx.work.WorkManager.getInstance(context)
                            val workName=com.lagradost.cloudstream3.services.SUBSCRIPTION_WORK_NAME
                            val hadWorker=work.getWorkInfosForUniqueWork(workName).get().any { !it.state.isFinished }
                            try {
                                clickLabel(context.getString(R.string.lite_more_actions))
                                clickLabel(context.getString(R.string.lite_follow))
                                waitFor { DataStoreHelper.getSubscribedData(id)!=null }
                                clickLabel(context.getString(R.string.lite_more_actions))
                                clickLabel(context.getString(R.string.lite_following))
                                waitFor { DataStoreHelper.getSubscribedData(id)==null }
                            } finally {
                                DataStoreHelper.removeSubscribedData(id)
                                if(!hadWorker)work.cancelUniqueWork(workName).result.get()
                            }
                        }
                        clickLabel(context.getString(R.string.lite_favorite))
                        waitFor { DataStoreHelper.getFavoritesData(id)==null }
                        results.put(JSONObject(mapOf("case" to case,"interactions" to "favorite/menu/download-state events verified")))
                    }
                    activityScenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                    instrumentation.waitForIdleSync()
                }
            }
        } finally {
            File(context.cacheDir,"$prefix.json").writeText(results.toString())
            ownedIds.forEach { id ->
                DataStoreHelper.removeLastWatched(id)
                DataStoreHelper.removeFavoritesData(id)
                DataStoreHelper.removeSubscribedData(id)
                removeKey("${DataStoreHelper.currentAccount}/$VIDEO_POS_DUR",id.toString())
            }
            APIHolder.removePluginMapping(provider)
            APIHolder.allProviders.remove(provider)
            val edit=prefs.edit()
            if(previous is Int)edit.putInt(key,previous)else edit.remove(key)
            edit.commit();context.updateTv()
        }
    }
    private inline fun Bitmap.useBitmap(block:(Bitmap)->Unit) { try { block(this) } finally { recycle() } }
}
