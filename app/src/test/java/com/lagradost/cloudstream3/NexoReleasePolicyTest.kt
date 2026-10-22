package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.lite.*
import org.junit.Assert.*
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class NexoReleasePolicyTest {
    private val mime="application/vnd.android.package-archive"
    private fun asset(name:String,repo:String="imper1aldev/cloudstream-lite",digest:String?=null)=NexoApkAsset(name,"https://github.com/$repo/releases/download/v0.2.0/$name",mime,digest)
    @Test fun selectsFirstSupportedAbiIndependentlyOfAssetOrder() {
        val universal=asset("NexoStream-stable-universal.apk")
        val arm=asset("NexoStream-stable-arm64-v8a.apk")
        val x86=asset("NexoStream-stable-x86_64.apk")
        val assets=listOf(asset("cloudstream.apk"),universal,x86,arm,asset("NexoStream-prerelease-arm64-v8a.apk"))
        listOf(assets,assets.reversed(),assets.drop(2)+assets.take(2)).forEach {
            assertEquals(arm,chooseNexoApk(it,listOf("arm64-v8a","x86_64"),"stable"))
            assertEquals(x86,chooseNexoApk(it,listOf("x86_64","arm64-v8a"),"stable"))
        }
        assertEquals(universal,chooseNexoApk(assets,listOf("armeabi-v7a"),"stable"))
    }
    @Test fun rejectsForeignChannelArchitectureAndNonApkAssets() {
        val assets=listOf(asset("CloudStream-stable-universal.apk"),asset("NexoStream-stable-arm64-v8a.apk"),asset("NexoStream-prerelease-universal.apk"),asset("NexoStream-stable-universal.jar"))
        assertNull(chooseNexoApk(assets,listOf("x86_64"),"stable"))
        assertNull(chooseNexoApk(emptyList(),listOf("arm64-v8a"),"stable"))
        assertNull(chooseNexoApk(listOf(asset("NexoStream-stable-universal.apk")),listOf("x86"),"beta"))
    }
    @Test fun acceptsOnlyDownloadsFromTheConfiguredRepository() {
        val valid=asset("NexoStream-stable-universal.apk")
        listOf(valid.copy(url=valid.url.replace("github.com","github.com.evil.invalid")),
            asset(valid.name,"recloudstream/cloudstream"),valid.copy(url=valid.url.replace("https:","http:")),
            valid.copy(url=valid.url+"?redirect=foreign"),valid.copy(url=valid.url.replace("/v0.2.0/","/../")),
            valid.copy(url=valid.url.replace("/v0.2.0/","/%2e%2e/")),valid.copy(url=valid.url.replace("github.com/","user@github.com/")),
            valid.copy(url=valid.url.replace(valid.name,"unrelated.apk")),valid.copy(contentType="application/java-archive")).forEach {
            assertNull(it.toString(),chooseNexoApk(listOf(it),listOf("x86_64"),"stable"))
        }
        assertEquals(valid,chooseNexoApk(listOf(valid),listOf("x86_64"),"stable"))
    }
    @Test fun validatesProvidedDigestButAllowsAbsentDigest() {
        val valid=asset("NexoStream-stable-universal.apk",digest="sha256:"+"ab".repeat(32))
        assertEquals(valid,chooseNexoApk(listOf(valid),emptyList(),"stable"))
        listOf("sha256:abcd","md5:"+"aa".repeat(16),"not-a-digest").forEach {
            assertNull(chooseNexoApk(listOf(valid.copy(digest=it)),emptyList(),"stable"))
        }
    }
    @Test fun endpointsSeparateStableAndPrereleaseWithoutUpstreamFallback() {
        assertEquals("https://api.github.com/repos/imper1aldev/cloudstream-lite/releases/latest",nexoReleaseEndpoint("imper1aldev","cloudstream-lite","stable","pre-release"))
        assertEquals("https://api.github.com/repos/imper1aldev/cloudstream-lite/releases/tags/pre-release",nexoReleaseEndpoint("imper1aldev","cloudstream-lite","prerelease","pre-release"))
    }
    @Test fun absentReleaseIsDistinctFromFailedQuery() {
        checkNexoReleaseStatus(200)
        try { checkNexoReleaseStatus(404);fail("404 must represent absent release") } catch(expected:FileNotFoundException) { }
        listOf(403,429,500,503).forEach { status ->
            try { checkNexoReleaseStatus(status);fail("HTTP$status must be a query failure") } catch(error:IOException) { assertFalse(error is FileNotFoundException) }
        }
    }
}
