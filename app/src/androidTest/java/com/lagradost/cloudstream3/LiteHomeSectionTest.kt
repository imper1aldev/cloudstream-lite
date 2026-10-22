package com.lagradost.cloudstream3

import android.graphics.Rect
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import org.junit.Assert.*
import org.junit.Test

class LiteHomeSectionTest {
    private class Provider : MainAPI() {
        override var name = "NexoStream"
        override var mainUrl = "https://wide-section.invalid"
        override val hasMainPage = true
        override val mainPage = mainPageOf("fixture" to "Wide section")
        val pages = java.util.concurrent.CopyOnWriteArrayList<Int>()
        override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            pages.add(page)
            val cards=(1..36).map { newMovieSearchResponse("Card $page-$it", "$mainUrl/$page/$it", TvType.Movie) }
            return newHomePageResponse(request.name, cards, hasNext = page < 3)
        }
        override suspend fun load(url: String) = newMovieLoadResponse("Section fixture",url,TvType.Movie,"fixture")
    }
    @Test fun expandedSectionUsesWindowWidthAndKeepsPagination() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val prefs=PreferenceManager.getDefaultSharedPreferences(context)
        val key=context.getString(R.string.app_layout_key);val previous=prefs.all[key]
        val oldProviders=APIHolder.allProviders.withLock { APIHolder.allProviders.toList() };val oldApis=APIHolder.apis
        val provider=Provider()
        APIHolder.allProviders.withLock { APIHolder.allProviders.clear();APIHolder.allProviders.add(provider) }
        APIHolder.apis=com.lagradost.cloudstream3.utils.AtomicList<MainAPI>(listOf(provider));APIHolder.apiMap=null
        prefs.edit().putInt(key,0).commit();context.updateTv()
        fun tree(): List<android.view.accessibility.AccessibilityNodeInfo> {
            if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
            return LiteUiAutomation.nodes(instrumentation.uiAutomation.rootInActiveWindow).filter { it.isVisibleToUser }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val deadline=System.currentTimeMillis()+15_000
                while(tree().none { it.text?.toString()=="Wide section" } && System.currentTimeMillis()<deadline) {
                    tree().firstOrNull { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    Thread.sleep(150)
                }
                LiteUiAutomation.clickLabel("Wide section")
                LiteUiAutomation.waitFor { tree().any { it.paneTitle != null } }
                val pane=tree().filter { it.paneTitle != null }.maxBy { Rect().also(it::getBoundsInScreen).width() }
                val bounds=Rect().also(pane::getBoundsInScreen)
                var width=0
                scenario.onActivity { width=it.windowManager.currentWindowMetrics.bounds.width() }
                val prefix=InstrumentationRegistry.getArguments().getString("prefix") ?: "followup"
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    java.io.File(context.cacheDir,"$prefix-section.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                }
                assertEquals("Sheet is still capped: $bounds versus window $width",width,bounds.width())
                repeat(12) { tree().firstOrNull { it.isScrollable }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(120) }
                LiteUiAutomation.waitFor { provider.pages.any { it>=2 } }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            }
        } finally {
            APIHolder.allProviders.withLock { APIHolder.allProviders.clear();APIHolder.allProviders.addAll(oldProviders) }
            APIHolder.apis=oldApis;APIHolder.apiMap=null
            val edit=prefs.edit();if(previous is Int)edit.putInt(key,previous)else edit.remove(key);edit.commit();context.updateTv()
        }
    }
}
