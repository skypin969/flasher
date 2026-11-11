package com.skypin.flasher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Simulasi jawaban bootloader untuk max-download-size. Tidak butuh HP.
 * Letakkan di app/src/test/java/com/skypin/flasher/ lalu jalankan: ./gradlew testDebugUnitTest
 */
class MaxDownloadTest {

    private val MB128 = 128L * 1024 * 1024
    private val MB256 = 256L * 1024 * 1024

    private fun ok(text: String): () -> FbResp = { FbResp("OKAY", text, 0L) }

    private fun assertRefused(query: () -> FbResp, mustContain: String) {
        try {
            val v = readMaxDownload(query)
            fail("Seharusnya berhenti, tetapi menghasilkan $v")
        } catch (e: FastbootException) {
            val msg = e.message ?: ""
            assertTrue("Pesan tidak memuat \"$mustContain\": $msg", msg.contains(mustContain))
        }
    }

    @Test
    fun hexValuesAreAccepted() {
        assertEquals(MB128, readMaxDownload(ok("0x8000000")))
        assertEquals(MB256, readMaxDownload(ok("0x10000000")))
        assertEquals(MB256, readMaxDownload(ok("  0X10000000\n")))
    }

    @Test
    fun nonHexValuesAreNeverGuessed() {
        assertNull(parseMaxDownloadHex(null))
        assertNull(parseMaxDownloadHex(""))
        assertNull(parseMaxDownloadHex("0x"))
        assertNull(parseMaxDownloadHex("0x0"))
        assertNull(parseMaxDownloadHex("0xZZ"))
        assertNull(parseMaxDownloadHex("256M"))
        // Desimal tidak boleh terbaca diam-diam sebagai hex (parseHex lama akan membacanya sebagai 0x268435456)
        assertNull(parseMaxDownloadHex("268435456"))
        assertNull(parseMaxDownloadHex("0x1FFFFFFFFFFFFFFFF"))
    }

    @Test
    fun bootloaderRejectionStopsWithMessage() {
        assertRefused({ throw FastbootRejected("getvar tidak didukung") }, "menolak")
    }

    @Test
    fun silentBootloaderStopsWithMessage() {
        assertRefused({ throw FastbootException("Tidak ada respon dari perangkat (timeout atau USB putus)") }, "tidak memberi jawaban")
    }

    @Test
    fun emptyAnswerStopsWithMessage() {
        assertRefused(ok(""), "kosong")
    }

    @Test
    fun decimalAnswerStopsWithRawText() {
        assertRefused(ok("268435456"), "\"268435456\"")
    }

    @Test
    fun unitAnswerStopsWithRawText() {
        assertRefused(ok("256M"), "\"256M\"")
    }

    @Test
    fun zeroAnswerStopsWithRawText() {
        assertRefused(ok("0x0"), "\"0x0\"")
    }
}
