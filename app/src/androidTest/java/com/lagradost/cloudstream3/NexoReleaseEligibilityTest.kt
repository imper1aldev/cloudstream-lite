package com.lagradost.cloudstream3

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream3.ui.settings.GithubReleases
import com.lagradost.cloudstream3.ui.settings.ApkUpdater
import com.lagradost.cloudstream4.viewmodels.*
import org.junit.Assert.*
import org.junit.Test

class NexoReleaseEligibilityTest {
    private fun check(prerelease: Boolean, remoteCode: Int, installedVersion: String, expected: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val channel = if (prerelease) "prerelease" else "stable"
        val json = org.json.JSONObject(instrumentation.context.assets.open("nexo/$channel.json").bufferedReader().use { it.readText() })
        json.put("body", "Fixture\n<!-- nexostream-version-code: $remoteCode -->")
        val release = GithubReleases.parseRelease(json.toString(), listOf("x86_64"), prerelease)
        assertEquals("Shared Android policy used by the legacy updater", expected, GithubReleases.isNewerRelease(release))
        val sameSha = "1234567890abcdef"
        val repository = object : GithubRepository by GithubReleases {
            override suspend fun getRelease(userName: String, repository: String, prerelease: Boolean, prereleaseTag: String, contentType: String) = release
            override suspend fun getSha(userName: String, repository: String, tag: String) = sameSha
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: GithubViewModel
            scenario.onActivity {
                vm = GithubViewModel("imper1aldev", "cloudstream-lite", "pre-release", GithubReleases.APK_CONTENT_TYPE,
                    installedVersion, prerelease, false, sameSha, ViewModelProvider(it)[GithubViewModel::class.java].settings, ApkUpdater, repository)
                vm.onAction(GithubAction.SearchForUpdate)
            }
            val deadline = System.currentTimeMillis() + 8000
            while (vm.state.value.dialog?.state.let { it == null || it is GithubUpdateDialogState.Loading } && System.currentTimeMillis() < deadline) Thread.sleep(30)
            val state = vm.state.value.dialog?.state
            assertEquals("remote=$remoteCode installed=${BuildConfig.VERSION_CODE}, state=$state", expected, state is GithubUpdateDialogState.UpdateFound)
            if (!expected) assertTrue(state is GithubUpdateDialogState.NoUpdateFound)
            scenario.onActivity { vm.onAction(GithubAction.Dismiss) }
        }
    }
    @Test fun sameCommitNewerPrereleaseIsOffered() = check(true, BuildConfig.VERSION_CODE + 1, "0.1.0-PRE", true)
    @Test fun sameCommitSamePrereleaseIsNotOffered() = check(true, BuildConfig.VERSION_CODE, "0.1.0-PRE", false)
    @Test fun olderStableIsNotOffered() = check(false, BuildConfig.VERSION_CODE - 1, "0.3.0", false)
    @Test fun equalStableIsNotOffered() = check(false, BuildConfig.VERSION_CODE, "0.2.0", false)
    @Test fun newerStableIsOffered() = check(false, BuildConfig.VERSION_CODE + 1, "0.1.0", true)
}
