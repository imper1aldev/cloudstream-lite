package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.settings.ApkUpdater
import com.lagradost.cloudstream4.viewmodels.DigestPair
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.DigestException
import java.security.MessageDigest

class NexoUpdateTransferTest {
    private val bytes="NexoStream local transfer fixture".toByteArray()
    @Test fun absentDigestRetainsOriginalTransferBehavior():Unit=runBlocking {
        val output=ByteArrayOutputStream()
        var progress=0L
        ApkUpdater.transfer(output,bytes.inputStream(),bytes.size.toLong(),{ done,_ -> progress=done },null)
        assertArrayEquals(bytes,output.toByteArray());assertEquals(bytes.size.toLong(),progress)
    }
    @Test fun matchingDigestAllowsTransfer():Unit=runBlocking {
        val output=ByteArrayOutputStream()
        ApkUpdater.transfer(output,bytes.inputStream(),bytes.size.toLong(),{ _,_ -> },DigestPair("SHA-256",MessageDigest.getInstance("SHA-256").digest(bytes)))
        assertArrayEquals(bytes,output.toByteArray())
    }
    @Test fun incorrectDigestRejectsTransferBeforeInstallation():Unit=runBlocking {
        try {
            ApkUpdater.transfer(ByteArrayOutputStream(),bytes.inputStream(),bytes.size.toLong(),{ _,_ -> },DigestPair("SHA-256",ByteArray(32)))
            fail("Corrupt download accepted")
        } catch(expected:DigestException) { }
    }
}
