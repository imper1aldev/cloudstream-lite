package com.lagradost.cloudstream3

import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.ui.settings.ApkUpdater
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream4.viewmodels.DigestPair
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.DigestException

/** Explicit host-driven phases: never installs an APK during ordinary test discovery. */
class NexoUpdatePersistenceTest {
    private val id=-2026101703
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private fun version()=PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName,0))
    @Test fun seedFavorite() {
        org.junit.Assume.assumeTrue(args.getString("updatePhase")=="seed")
        assertEquals(args.getString("expectedCode","1").toLong(),version())
        ActivityScenario.launch(MainActivity::class.java).use {
            val now=System.currentTimeMillis()
            DataStoreHelper.setFavoritesData(id,DataStoreHelper.FavoritesData(now,id,now,"Nexo local update fixture",
                "https://nexo-update-fixture.invalid/title","NexoStream",TvType.Movie,null,2026))
            assertNotNull(DataStoreHelper.getFavoritesData(id))
            assertTrue(context.getSharedPrefs().edit().commit()) // Flush before instrumentation is force-stopped.
            println("Seed account=${DataStoreHelper.currentAccount}; favorite persisted before process replacement")
        }
    }
    @Test fun wrongDigestDoesNotCommitAnInstallation():Unit=runBlocking {
        org.junit.Assume.assumeTrue(args.getString("updatePhase")=="badDigest")
        val file=File(context.cacheDir,"nexo-local-update-v2.apk")
        assertTrue(file.length()>0)
        assertNotNull("Fixture absent before launching activity; account=${DataStoreHelper.currentAccount}",DataStoreHelper.getFavoritesData(id))
        val before=version()
        val sessions=context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity:MainActivity
            scenario.onActivity { activity=it }
            try {
                file.inputStream().use { ApkUpdater.packageInstallerDownloader(activity,it,file.length(),DigestPair("SHA-256",ByteArray(32))) { _,_ -> } }
                fail("Incorrect digest reached installer commit")
            } catch(expected:DigestException) { }
        }
        assertEquals(before,version())
        assertEquals(sessions,context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet())
        assertNotNull(DataStoreHelper.getFavoritesData(id))
    }
    @Test fun installThroughExistingApkUpdater():Unit=runBlocking {
        org.junit.Assume.assumeTrue(args.getString("updatePhase")=="install")
        val file=File(context.cacheDir,"nexo-local-update-v2.apk")
        assertTrue(file.length()>0)
        assertNotNull(DataStoreHelper.getFavoritesData(id))
        val installed=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        val archive=checkNotNull(context.packageManager.getPackageArchiveInfo(file.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES))
        assertEquals(context.packageName,archive.packageName)
        assertEquals(PackageInfoCompat.getLongVersionCode(installed)+1,PackageInfoCompat.getLongVersionCode(archive))
        assertArrayEquals(installed.signingInfo!!.apkContentsSigners.first().toByteArray(),archive.signingInfo!!.apkContentsSigners.first().toByteArray())
        val digest=checkNotNull(DigestPair.parse(args.getString("digest")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity:MainActivity
            scenario.onActivity { activity=it }
            file.inputStream().use { ApkUpdater.legacyDownloader(activity,it,file.length(),digest) { _,_ -> } }
            val deadline=SystemClock.uptimeMillis()+20_000
            var clicked=false
            var labels=emptyList<String>()
            while(!clicked && SystemClock.uptimeMillis()<deadline) {
                if(android.os.Build.VERSION.SDK_INT>=33)instrumentation.uiAutomation.clearCache()
                val root=instrumentation.uiAutomation.rootInActiveWindow
                val nodes=LiteUiAutomation.nodes(root).filter { it.isVisibleToUser }
                labels=nodes.mapNotNull { it.text?.toString() }
                if(root?.packageName?.toString()?.contains("packageinstaller")==true) {
                    clicked=nodes.firstOrNull { it.isClickable && it.text?.toString() in listOf("Update","Install","UPDATE","INSTALL") }
                        ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true
                }
                if(!clicked)Thread.sleep(100)
            }
            assertTrue("System installer confirmation absent: $labels",clicked)
            // Replacement terminates this old instrumentation process. Host verifies package + data afterwards.
            Thread.sleep(15_000)
        }
    }
    @Test fun verifyFavoriteAfterUpdate() {
        org.junit.Assume.assumeTrue(args.getString("updatePhase")=="verify")
        try {
            assertEquals(args.getString("expectedCode","2").toLong(),version())
            assertEquals("Nexo local update fixture",DataStoreHelper.getFavoritesData(id)?.name)
        } finally { DataStoreHelper.removeFavoritesData(id);context.getSharedPrefs().edit().commit() }
    }
    @Test fun cleanupOwnedFixture() {
        org.junit.Assume.assumeTrue(args.getString("updatePhase")=="cleanup")
        DataStoreHelper.removeFavoritesData(id)
        context.getSharedPrefs().edit().commit()
        File(context.cacheDir,"nexo-local-update-v2.apk").delete()
    }
}
