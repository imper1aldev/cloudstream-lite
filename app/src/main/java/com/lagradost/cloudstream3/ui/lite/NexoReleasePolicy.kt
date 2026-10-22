package com.lagradost.cloudstream3.ui.lite

import com.lagradost.cloudstream3.BuildConfig
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

data class NexoApkAsset(val name:String,val url:String,val contentType:String,val digest:String?)
class NexoNoReleaseException : FileNotFoundException("No published NexoStream release")
class NexoNoCompatibleApkException : IOException("No compatible NexoStream APK")
class NexoReleaseQueryException(val status:Int) : IOException("Release query failed: HTTP $status")

fun checkNexoReleaseStatus(status:Int) {
    if(status==404)throw NexoNoReleaseException()
    if(status !in 200..299)throw NexoReleaseQueryException(status)
}

fun nexoReleaseEndpoint(owner:String,repo:String,channel:String,prereleaseTag:String):String {
    require(owner.matches(Regex("[A-Za-z0-9_.-]+")) && repo.matches(Regex("[A-Za-z0-9_.-]+")))
    val base="https://api.github.com/repos/$owner/$repo/releases"
    return when(channel) {
        "stable" -> "$base/latest"
        "prerelease" -> "$base/tags/${URLEncoder.encode(prereleaseTag,"UTF-8").replace("+","%20")}"
        else -> throw IllegalArgumentException("Unknown release channel")
    }
}

fun chooseNexoApk(assets:List<NexoApkAsset>,supportedAbis:List<String>,channel:String):NexoApkAsset? {
    if(channel !in listOf("stable","prerelease"))return null
    val architectures=(supportedAbis.filter { it in listOf("arm64-v8a","armeabi-v7a","x86_64","x86") }+"universal").distinct()
    val eligible=assets.filter { asset ->
        val uri=runCatching { URI(asset.url) }.getOrNull()
        val path=uri?.path?.removePrefix("/")?.split('/')
        uri?.scheme=="https" && uri.host=="github.com" && uri.port == -1 && uri.userInfo==null &&
            uri.query==null && uri.fragment==null && path?.size==6 &&
            path.take(4)==listOf(BuildConfig.NEXO_UPDATE_OWNER,BuildConfig.NEXO_UPDATE_REPO,"releases","download") &&
            path[4].isNotBlank() && path.none { it=="." || it==".." } && path[5]==asset.name &&
            asset.contentType in listOf("application/vnd.android.package-archive","application/octet-stream") &&
            (asset.digest==null || asset.digest.matches(Regex("sha256:[a-fA-F0-9]{64}")))
    }
    return architectures.firstNotNullOfOrNull { abi ->
        eligible.filter { it.name=="NexoStream-$channel-$abi.apk" }
            .sortedWith(compareBy({it.url},{it.digest.orEmpty()},{it.contentType})).firstOrNull()
    }
}

/** Published by our release workflow; Android version codes order builds even at the same SHA. */
fun nexoReleaseVersionCode(body: String): Long {
    val matches = Regex("<!-- nexostream-version-code: ([0-9]+) -->").findAll(body).toList()
    val code = matches.singleOrNull()?.groupValues?.get(1)?.toLongOrNull()
    if (code == null || code !in 1L..2_100_000_000L) {
        throw java.io.IOException("Missing or invalid NexoStream build version")
    }
    return code
}
