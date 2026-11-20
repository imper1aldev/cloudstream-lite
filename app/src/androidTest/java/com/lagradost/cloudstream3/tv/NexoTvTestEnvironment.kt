package com.lagradost.cloudstream3.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.ui.settings.Globals.updateTv
import com.lagradost.cloudstream3.utils.AtomicList
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.DataStoreHelper
import org.junit.Assert.fail

/** Temporary profile and provider only; never clear data or touch stable app packages. */
internal class NexoTvTestEnvironment(private val provider: MainAPI? = null, layout: Int = 1) : AutoCloseable {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val layoutKey = context.getString(R.string.app_layout_key)
    private val oldLayout = prefs.all[layoutKey]
    private val store=context.getSharedPrefs()
    private val installed=store.getString("PLUGINS_KEY",null)
    private val locals=store.getString("PLUGINS_KEY_LOCAL",null)
    private val updateKey=context.getString(R.string.auto_update_plugins_key)
    private val oldUpdate=prefs.all[updateKey]
    private val oldProviders = APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }
    private val oldApis = APIHolder.apis
    private val oldAccounts = DataStoreHelper.accounts
    private val oldSelected = DataStoreHelper.selectedKeyIndex
    private val profile = generateSequence(-7754000) { it-1 }.first { id -> oldAccounts.none { it.keyIndex == id } && CloudStreamApp.getKeys(id.toString()).isNullOrEmpty() }
    init {
        // ADB profile-management taps can leave the TV in touch mode, where requestFocus is rejected.
        instrumentation.setInTouchMode(false)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 224")).use { it.readBytes() }
        if(provider != null) {
            store.edit().remove("PLUGINS_KEY").remove("PLUGINS_KEY_LOCAL").commit()
            prefs.edit().putBoolean(updateKey,false).commit()
        }
        if (android.os.Build.VERSION.SDK_INT <= 32) listOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE").forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,it)
        }
        DataStoreHelper.accounts = oldAccounts + DataStoreHelper.Account(profile,"TV test",defaultImageIndex=0)
        DataStoreHelper.selectedKeyIndex = profile
        prefs.edit().putInt(layoutKey,layout).commit(); context.updateTv()
        register()
    }
    fun register() {
        provider?.let { api ->
            APIHolder.allProviders.withLock { APIHolder.allProviders.clear(); APIHolder.allProviders.add(api) }
            APIHolder.apis = AtomicList<MainAPI>(listOf(api)); APIHolder.apiMap = null
        }
    }
    fun id(name: String) = context.resources.getIdentifier(name,"id",context.packageName)
    fun key(code: Int) = instrumentation.sendKeyDownUpSync(code)
    fun await(scenario: ActivityScenario<MainActivity>, description: String, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime()+15_000
        while (SystemClock.elapsedRealtime()<deadline) {
            var done = false
            scenario.onActivity { done = condition(it) }
            if(done) return
            SystemClock.sleep(60)
        }
        scenario.onActivity { android.util.Log.e("NexoTvTest", "$description focus=${it.currentFocus} tag=${it.currentFocus?.tag}") }
        fail(description)
    }
    override fun close() {
        CloudStreamApp.removeKeys(profile.toString())
        DataStoreHelper.accounts=oldAccounts; DataStoreHelper.selectedKeyIndex=oldSelected
        if(provider != null) {
            val restore=store.edit()
            if(installed==null) restore.remove("PLUGINS_KEY") else restore.putString("PLUGINS_KEY",installed)
            if(locals==null) restore.remove("PLUGINS_KEY_LOCAL") else restore.putString("PLUGINS_KEY_LOCAL",locals)
            restore.commit()
            val update=prefs.edit(); if(oldUpdate is Boolean) update.putBoolean(updateKey,oldUpdate) else update.remove(updateKey);update.commit()
        }
        context.getSharedPrefs().edit().commit()
        APIHolder.allProviders.withLock { APIHolder.allProviders.clear(); APIHolder.allProviders.addAll(oldProviders) }
        APIHolder.apis=oldApis; APIHolder.apiMap=null
        val edit=prefs.edit(); if(oldLayout is Int) edit.putInt(layoutKey,oldLayout) else edit.remove(layoutKey)
        edit.commit(); context.updateTv()
    }
}
