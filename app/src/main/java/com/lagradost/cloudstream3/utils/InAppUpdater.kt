package com.lagradost.cloudstream3.utils

import android.app.Activity
import android.content.pm.PackageManager.NameNotFoundException
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ui.settings.GithubReleases
import com.lagradost.cloudstream3.ui.settings.ApkUpdater
import com.lagradost.cloudstream3.ui.lite.NexoNoReleaseException
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream4.viewmodels.DigestPair
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

object InAppUpdater {
    private val PRERELEASE_PACKAGE_NAME = BuildConfig.NEXO_APPLICATION_ID + ".prerelease"

    @Serializable
    private data class Update(
        @JsonProperty("shouldUpdate") @SerialName("shouldUpdate") val shouldUpdate: Boolean,
        @JsonProperty("updateURL") @SerialName("updateURL") val updateURL: String?,
        @JsonProperty("updateVersion") @SerialName("updateVersion") val updateVersion: String?,
        @JsonProperty("changelog") @SerialName("changelog") val changelog: String?,
        @JsonProperty("updateNodeId") @SerialName("updateNodeId") val updateNodeId: String?,
        val digest: String? = null,
    )

    private suspend fun Activity.getAppUpdate(installPrerelease: Boolean): Update {
        if(BuildConfig.DEBUG)return Update(false,null,null,null,null)
        val prerelease=BuildConfig.FLAVOR=="prerelease" || installPrerelease
        val release=GithubReleases.getRelease(GithubReleases.APK_USERNAME,GithubReleases.APK_REPOSITORY,
            prerelease,GithubReleases.APK_PRERELEASE,GithubReleases.APK_CONTENT_TYPE)
        val shouldUpdate = installPrerelease || GithubReleases.isNewerRelease(release)
        return Update(shouldUpdate,release.downloadUrl,release.displayName,release.changeLog,release.nodeId,release.digest)
    }

    fun Activity.installPreReleaseIfNeeded() = ioSafe {
        val isInstalled = try {
            packageManager.getPackageInfo(PRERELEASE_PACKAGE_NAME, 0)
            true
        } catch (_: NameNotFoundException) {
            false
        }

        if (isInstalled) {
            showToast(R.string.prerelease_already_installed)
        } else if (runAutoUpdate(checkAutoUpdate = false, installPrerelease = true) == false) {
            showToast(R.string.prerelease_install_failed)
        }
    }


    /**
     * @return true when an update is offered, false when none is needed, null when the query fails.
     * @param checkAutoUpdate if the update check was launched automatically
     * @param installPrerelease if we want to install the pre-release version
     */
    suspend fun Activity.runAutoUpdate(
        checkAutoUpdate: Boolean = true, installPrerelease: Boolean = false
    ): Boolean? {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val autoUpdateEnabled =
            settingsManager.getBoolean(getString(R.string.auto_update_key), true)
        if (checkAutoUpdate && !autoUpdateEnabled) {
            return false
        }

        val update = try { getAppUpdate(installPrerelease) } catch(error:Exception) {
            logError(error)
            if(!checkAutoUpdate)showToast(if(error is NexoNoReleaseException) R.string.nexo_no_release else R.string.nexo_update_query_failed)
            return null // Failed query; callers must not report "up to date".
        }
        if (!update.shouldUpdate || update.updateURL == null) {
            return false
        }

        // Check if update should be skipped
        val updateNodeId = settingsManager.getString(
            getString(R.string.skip_update_key), ""
        )

        // Skips the update if its an automatic update and the update is skipped
        // This allows updating manually
        if (update.updateNodeId.equals(updateNodeId) && checkAutoUpdate) {
            return false
        }

        runOnUiThread {
            safe {
                val currentVersion = packageName?.let {
                    packageManager.getPackageInfo(it, 0)
                }

                val builder = AlertDialog.Builder(this, R.style.AlertDialogCustom)
                builder.setTitle(
                    getString(R.string.new_update_format).format(
                        currentVersion?.versionName, update.updateVersion
                    )
                )

                val logRegex = Regex("\\[(.*?)]\\((.*?)\\)")
                val sanitizedChangelog = update.changelog?.replace(logRegex) { matchResult ->
                    matchResult.groupValues[1]
                } // Sanitized because it looks cluttered

                builder.setMessage(sanitizedChangelog)
                builder.apply {
                    setPositiveButton(R.string.update) { _, _ ->
                        // Forcefully start any delayed installations
                        if (ApkInstaller.delayedInstaller?.startInstallation() == true) return@setPositiveButton

                        showToast(R.string.download_started, Toast.LENGTH_LONG)

                        // Check if the setting hasn't been changed
                        if (settingsManager.getInt(
                                getString(R.string.apk_installer_key), -1
                            ) == -1
                        ) {
                            // Set to legacy installer if using MIUI
                            if (isMiUi()) {
                                settingsManager.edit {
                                    putInt(getString(R.string.apk_installer_key), 1)
                                }
                            }
                        }

                        ioSafe {
                            try {
                                ApkUpdater.update(AppSettings(this@runAutoUpdate),update.updateURL,
                                    DigestPair.parse(update.digest)) { _, _ -> }
                            } catch(error:Exception) {
                                logError(error)
                                runOnUiThread { showToast(R.string.download_failed,Toast.LENGTH_LONG) }
                            }
                        }
                    }

                    setNegativeButton(R.string.cancel) { _, _ -> }

                    if (checkAutoUpdate) {
                        setNeutralButton(R.string.skip_update) { _, _ ->
                            settingsManager.edit {
                                putString(
                                    getString(R.string.skip_update_key), update.updateNodeId ?: ""
                                )
                            }
                        }
                    }
                }
                builder.show().setDefaultFocus()
            }
        }
        return true
    }

    private fun isMiUi(): Boolean = !getSystemProperty("ro.miui.ui.version.name").isNullOrEmpty()

    private fun getSystemProperty(propName: String): String? = try {
        val p = Runtime.getRuntime().exec("getprop $propName")
        BufferedReader(InputStreamReader(p.inputStream), 1024).use {
            it.readLine()
        }
    } catch (_: IOException) {
        null
    }
}
