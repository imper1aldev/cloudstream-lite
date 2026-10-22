package com.lagradost.cloudstream3

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale

class NexoIdentityTest {
    @Test fun newIdentityLoadsTheAllowedPlugin(): Unit = kotlinx.coroutines.runBlocking {
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity:MainActivity
            scenario.onActivity { activity=it }
            val ready=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeout(90_000) { com.lagradost.cloudstream3.plugins.ensureAllowedProvider(activity) }
            }
            assertTrue("Fixed repository/plugin failed to load in new package",ready)
            assertNotNull(APIHolder.getApiFromNameNull(com.lagradost.cloudstream3.plugins.ALLOWED_PLUGIN_API_NAME))
        }
    }

    @Test fun packageLabelsAndFileSharingUseNexoIdentity() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.imper1aldev.nexostream.prerelease.debug",context.packageName)
        listOf("en","es","ar","ja").forEach { language ->
            val localized=context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
            assertEquals("NexoStream",localized.getString(R.string.app_name))
            assertTrue(localized.getString(R.string.play_with_app_name).contains("NexoStream"))
        }
        val authority=context.packageName+".provider"
        assertNotNull(context.packageManager.resolveContentProvider(authority,0))
        val file=File(context.cacheDir,"nexo-identity-fixture.mp4")
        try {
            file.writeBytes(byteArrayOf(0))
            val uri=FileProvider.getUriForFile(context,authority,file)
            assertEquals(authority,uri.authority)
            context.contentResolver.openInputStream(uri)!!.use { assertEquals(0,it.read()) }
        } finally { file.delete() }
        val video=Intent(Intent.ACTION_VIEW).setPackage(context.packageName)
            .setDataAndType(Uri.parse("content://$authority/nexo-identity-fixture.mp4"),"video/mp4")
        val resolved=context.packageManager.resolveActivity(video,0)
        assertNotNull(resolved)
        assertEquals(context.packageName+".downloadedplayer",resolved!!.activityInfo.taskAffinity)
        val icon=context.applicationInfo.loadIcon(context.packageManager)
        val bitmap=android.graphics.Bitmap.createBitmap(256,256,android.graphics.Bitmap.Config.ARGB_8888)
        icon.setBounds(0,0,256,256);icon.draw(android.graphics.Canvas(bitmap))
        File(context.cacheDir,"nexo-launcher.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        val launch=context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull(launch)
        assertEquals("NexoStream",context.applicationInfo.loadLabel(context.packageManager).toString())
    }
}
