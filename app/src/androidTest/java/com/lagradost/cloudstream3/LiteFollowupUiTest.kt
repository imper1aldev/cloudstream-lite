package com.lagradost.cloudstream3

import android.graphics.Rect
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigationrail.NavigationRailView
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import org.junit.Assert.*
import org.junit.Test

class LiteFollowupUiTest {
    private fun mobile(block: (ActivityScenario<MainActivity>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = context.getString(R.string.app_layout_key); val previous = prefs.all[key]
        prefs.edit().putInt(key, 0).commit(); context.updateTv()
        try { ActivityScenario.launch(MainActivity::class.java).use(block) }
        finally { val edit=prefs.edit(); if(previous is Int)edit.putInt(key,previous)else edit.remove(key); edit.commit();context.updateTv() }
    }
    private fun nav(activity: MainActivity) = (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
    @Test fun emptySearchHasVisibleExit() = mobile { scenario ->
        LiteUiAutomation.clickLabel("Search titles")
        LiteUiAutomation.clickLabel(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.go_back))
        scenario.onActivity { assertEquals(R.id.navigation_home, nav(it).currentDestination?.id) }
    }
    @Test fun searchCanReturnThroughAlreadySelectedHomeTab() = mobile { scenario ->
        LiteUiAutomation.clickLabel("Search titles")
        Thread.sleep(450)
        scenario.onActivity {
            val rail=it.findViewById<NavigationRailView>(R.id.nav_rail_view)
            val bottom=it.findViewById<BottomNavigationView>(R.id.nav_view)
            val button=if(rail.isShown)rail.findViewById<android.view.View>(R.id.navigation_home) else bottom.findViewById<android.view.View>(R.id.navigation_home)
            button.performClick()
            assertEquals(R.id.navigation_home,nav(it).currentDestination?.id)
        }
    }
    @Test fun searchCanLeaveThroughFavoritesAndSettings() = mobile { scenario ->
        listOf(R.id.navigation_library, R.id.navigation_settings).forEach { destination ->
            scenario.onActivity { nav(it).navigate(R.id.navigation_search) }
            Thread.sleep(450)
            scenario.onActivity {
                val rail=it.findViewById<NavigationRailView>(R.id.nav_rail_view)
                val bottom=it.findViewById<BottomNavigationView>(R.id.nav_view)
                (if(rail.isShown)rail.findViewById<android.view.View>(destination)else bottom.findViewById<android.view.View>(destination)).performClick()
                assertEquals(destination,nav(it).currentDestination?.id)
            }
        }
    }
}
