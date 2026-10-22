package com.lagradost.cloudstream3.ui.settings

import com.fasterxml.jackson.annotation.JsonProperty
import android.os.Build
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.ui.lite.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream4.viewmodels.GithubFile
import com.lagradost.cloudstream4.viewmodels.GithubRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object GithubReleases : GithubRepository {
    @Serializable
    private data class GithubAsset(
        @JsonProperty("id") @SerialName("id") val id: Long,
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("size") @SerialName("size") val size: Int, // Size in bytes
        @JsonProperty("browser_download_url") @SerialName("browser_download_url") val browserDownloadUrl: String,
        @JsonProperty("content_type") @SerialName("content_type") val contentType: String, // application/vnd.android.package-archive
        @JsonProperty("digest") @SerialName("digest") val digest: String? = null, // sha256:..., may be null
    )

    @Serializable
    private data class GithubRelease(
        @JsonProperty("tag_name") @SerialName("tag_name") val tagName: String, // Version code
        @JsonProperty("body") @SerialName("body") val body: String, // Description
        @JsonProperty("assets") @SerialName("assets") val assets: List<GithubAsset>,
        @JsonProperty("target_commitish") @SerialName("target_commitish") val targetCommitish: String, // Branch
        @JsonProperty("prerelease") @SerialName("prerelease") val prerelease: Boolean,
        @JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
        @JsonProperty("created_at") @SerialName("created_at") val createdAt: String, // YYYY-MM-DDTHH:MM:SSZ
    )

    @Serializable
    private data class GithubObject(
        @JsonProperty("sha") @SerialName("sha") val sha: String, // SHA-256 hash
        //@JsonProperty("type") @SerialName("type") val type: String,
        ///@JsonProperty("url") @SerialName("url") val url: String,
    )

    @Serializable
    private data class GithubTag(
        //@JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
        @JsonProperty("object") @SerialName("object") val githubObject: GithubObject,
    )

    const val APK_USERNAME = BuildConfig.NEXO_UPDATE_OWNER
    const val APK_REPOSITORY = BuildConfig.NEXO_UPDATE_REPO
    const val APK_PRERELEASE = BuildConfig.NEXO_PRERELEASE_TAG
    const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"

    private val defaultHeaders = mapOf("Accept" to "application/vnd.github.v3+json")

    override fun isNewerRelease(release: GithubFile): Boolean =
        nexoReleaseVersionCode(release.changeLog) > BuildConfig.VERSION_CODE.toLong()

    @Throws
    override suspend fun getSha(
        userName: String,
        repository: String,
        tag: String,
    ): String {
        val encodedTag=java.net.URLEncoder.encode(tag,"UTF-8").replace("+","%20")
        val response=app.get(
            url = "https://api.github.com/repos/$userName/$repository/git/ref/tags/$encodedTag",
            headers = defaultHeaders
        )
        checkNexoReleaseStatus(response.code)
        return response.parsed<GithubTag>().githubObject.sha
    }

    @Throws
    override suspend fun getRelease(
        userName: String,
        repository: String,
        prerelease: Boolean,
        prereleaseTag: String,
        contentType: String,
    ): GithubFile {
        val url=nexoReleaseEndpoint(userName,repository,if(prerelease) "prerelease" else "stable",prereleaseTag)
        val response=app.get(url=url,headers=defaultHeaders)
        checkNexoReleaseStatus(response.code)
        return parseRelease(response.text,Build.SUPPORTED_ABIS.toList(),prerelease)
    }

    internal fun parseRelease(json:String,supportedAbis:List<String>,prerelease:Boolean):GithubFile {
        val latestRelease=parseJson<GithubRelease>(json)
        if(latestRelease.prerelease!=prerelease)throw java.io.IOException("Unexpected release channel")
        val foundAsset=chooseNexoApk(latestRelease.assets.map {
            NexoApkAsset(it.name,it.browserDownloadUrl,it.contentType,it.digest)
        },supportedAbis,if(prerelease) "prerelease" else "stable") ?: throw NexoNoCompatibleApkException()

        val assetId=latestRelease.assets.first { it.name==foundAsset.name && it.browserDownloadUrl==foundAsset.url }.id
        require(assetId>0) { "Invalid release asset identity" }
        return GithubFile(
            digest = foundAsset.digest,
            downloadUrl = foundAsset.url,
            displayName = latestRelease.tagName.removePrefix("v"),
            changeLog = latestRelease.body,
            tagName = latestRelease.tagName,
            nodeId = "${latestRelease.nodeId}:$assetId"
        )
    }
}