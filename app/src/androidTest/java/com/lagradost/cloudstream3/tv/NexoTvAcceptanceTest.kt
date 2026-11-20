package com.lagradost.cloudstream3.tv

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import com.lagradost.cloudstream3.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NexoTvAcceptanceTest {
    private class Provider:MainAPI() {
        override var name="NexoStream"
        override var mainUrl="https://nexo-tv-accept-${System.nanoTime()}.invalid"
        override val hasMainPage=true
        override val mainPage=mainPageOf("home" to "Soak row")
        override val hasDownloadSupport=false
        override val hasChromecastSupport=false
        override suspend fun getMainPage(page:Int,request:MainPageRequest)=newHomePageResponse(request.name,(0..35).map { newMovieSearchResponse("Soak $it","$mainUrl/$it",TvType.Movie) },false)
        override suspend fun load(url:String)=newMovieLoadResponse("Soak details",url,TvType.Movie,"data")
    }
    @Test fun productionHomeSurvivesRepeatedNavigationBackgroundAndRecreation() {
        NexoTvTestEnvironment(Provider()).use { env -> ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun card(a:MainActivity)=a.findViewById<View>(env.id("nexo_tv_home_list"))?.findViewWithTag<View>("section:Soak row")?.findViewById<RecyclerView>(env.id("nexo_tv_row_list"))?.findViewHolderForAdapterPosition(0)?.itemView
            val folder=checkNotNull(env.context.getExternalFilesDir(null))
            fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(env.instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
            fun awaitCard()=env.await(scenario,"Production Home lost selected identity") { card(it)?.hasFocus()==true && it.currentFocus?.contentDescription=="Soak 0" }
            fun settledRect():Rect {
                var last=Rect();var equal=0
                repeat(20) {
                    var current=Rect();scenario.onActivity { a -> card(a)?.getGlobalVisibleRect(current) }
                    if(current==last && !current.isEmpty) equal++ else equal=0
                    if(equal>=3)return current
                    last=current;SystemClock.sleep(50)
                }
                throw AssertionError("Production card never settled: $last")
            }
            env.await(scenario,"Production Home missing") { card(it)!=null }
            scenario.onActivity { it.findViewById<View>(R.id.navigation_home).requestFocus() }
            shell("dumpsys gfxinfo ${env.context.packageName} reset")
            repeat(10) {
                repeat(3) { env.key(KeyEvent.KEYCODE_DPAD_DOWN) }
                env.await(scenario,"Menu bottom lost") { it.currentFocus?.id==R.id.navigation_settings }
                repeat(3) { env.key(KeyEvent.KEYCODE_DPAD_UP) }
                env.await(scenario,"Menu top lost") { it.currentFocus?.id==R.id.navigation_home }
            }
            File(folder,"production-menu-memory.txt").writeText(shell("dumpsys meminfo ${env.context.packageName}"))
            File(folder,"production-menu-frames.txt").writeText(shell("dumpsys gfxinfo ${env.context.packageName}"))
            scenario.onActivity { card(it)!!.requestFocus() };awaitCard()
            repeat(50) { cycle ->
                env.key(KeyEvent.KEYCODE_DPAD_LEFT)
                env.await(scenario,"Menu entry lost cycle $cycle") { it.currentFocus?.id==R.id.navigation_home }
                env.key(KeyEvent.KEYCODE_DPAD_RIGHT);awaitCard()
            }
            repeat(20) { cycle ->
                val before=settledRect()
                env.key(if(cycle%2==0)KeyEvent.KEYCODE_ENTER else KeyEvent.KEYCODE_DPAD_CENTER)
                env.await(scenario,"Own Details did not open cycle $cycle") { it.findViewById<TextView>(env.id("nexo_tv_result_title"))?.text=="Soak details" }
                env.key(KeyEvent.KEYCODE_BACK);awaitCard()
                assertEquals("Home viewport moved after Details $cycle",before,settledRect())
            }
            val before=settledRect()
            scenario.moveToState(Lifecycle.State.CREATED);scenario.moveToState(Lifecycle.State.RESUMED)
            awaitCard();assertEquals(before,settledRect())
            scenario.recreate();awaitCard();assertEquals(before,settledRect())
            env.instrumentation.uiAutomation.takeScreenshot().let { bitmap -> File(folder,"nexo-tv-production-home.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
            File(folder,"nexo-tv-production-acceptance.json").writeText(JSONObject().put("detailsCycles",20).put("menuContentCycles",50).put("stableGeometry",true).put("background",true).put("recreation",true).toString(2))
        } }
    }
}
