package com.lagradost.cloudstream3

import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.ui.settings.GithubReleases
import com.lagradost.cloudstream3.ui.lite.NexoNoReleaseException
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import com.lagradost.cloudstream4.viewmodels.GithubViewModel
import com.lagradost.cloudstream4.viewmodels.GithubUpdateDialogState
import com.lagradost.cloudstream4.viewmodels.GithubAction
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NexoReleaseAdapterTest {
    @Test fun realJsonAdapterSelectsCompatibleAssetsAndVersionDisplay() {
        val context=InstrumentationRegistry.getInstrumentation().context
        listOf("stable" to false,"prerelease" to true).forEach { (channel,prerelease) ->
            val json=context.assets.open("nexo/$channel.json").bufferedReader().use { it.readText() }
            val release=GithubReleases.parseRelease(json,listOf("x86_64"),prerelease)
            assertTrue(release.downloadUrl.endsWith("NexoStream-$channel-universal.apk"))
            assertEquals(if(prerelease) "pre-release" else "0.2.0",release.displayName)
            assertNull(release.digest)
            assertEquals("fixture-$channel:103",release.nodeId)
        }
    }
    @Test fun incompatibleArchitectureIsReportedWithoutOfferingUpdate() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val fixture=org.json.JSONObject(instrumentation.context.assets.open("nexo/stable.json").bufferedReader().use { it.readText() })
        val assets=fixture.getJSONArray("assets")
        val incompatible=org.json.JSONArray()
        for(i in 0 until assets.length())if(assets.getJSONObject(i).getString("name").endsWith("arm64-v8a.apk"))incompatible.put(assets.getJSONObject(i))
        fixture.put("assets",incompatible)
        val error=try {
            GithubReleases.parseRelease(fixture.toString(),listOf("x86_64"),false)
            throw AssertionError("Incompatible APK offered")
        } catch(expected:com.lagradost.cloudstream3.ui.lite.NexoNoCompatibleApkException) { expected }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                ViewModelProvider(it)[GithubViewModel::class.java].updateState {
                    copy(dialog=com.lagradost.cloudstream4.viewmodels.GithubDialog(false,true,GithubUpdateDialogState.Error(error)))
                }
            }
            LiteUiAutomation.waitFor {
                if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
                LiteUiAutomation.nodes(instrumentation.uiAutomation.rootInActiveWindow).any { it.text?.toString()==context.getString(R.string.nexo_no_compatible_apk) }
            }
            scenario.onActivity { ViewModelProvider(it)[GithubViewModel::class.java].onAction(GithubAction.Dismiss) }
        }
    }
    /** Opt-in live assertion; run only after confirming the owned public repo has no release. */
    @Test fun liveManualCheckShowsNoPublishedRelease() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveNoRelease")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val prefs=PreferenceManager.getDefaultSharedPreferences(context)
        val key=context.getString(R.string.app_layout_key);val previous=prefs.all[key]
        prefs.edit().putInt(key,0).commit();context.updateTv()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    (it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController.navigate(R.id.navigation_settings_updates)
                }
                LiteUiAutomation.clickLabel(context.getString(R.string.check_for_update))
                val deadline=System.currentTimeMillis()+20_000
                var expected=false
                while(!expected && System.currentTimeMillis()<deadline) {
                    scenario.onActivity {
                        val dialog=ViewModelProvider(it)[GithubViewModel::class.java].state.value.dialog
                        expected=(dialog?.state as? GithubUpdateDialogState.Error)?.error is NexoNoReleaseException
                    }
                    Thread.sleep(100)
                }
                assertTrue("Manual check did not distinguish absent release",expected)
                if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
                assertTrue(LiteUiAutomation.nodes(instrumentation.uiAutomation.rootInActiveWindow).any { it.text?.toString()==context.getString(R.string.nexo_no_release) })
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    java.io.File(context.cacheDir,"nexo-update-no-release.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                }
                scenario.onActivity { ViewModelProvider(it)[GithubViewModel::class.java].onAction(GithubAction.Dismiss) }
            }
        } finally { val edit=prefs.edit();if(previous is Int)edit.putInt(key,previous)else edit.remove(key);edit.commit();context.updateTv() }
    }
}
