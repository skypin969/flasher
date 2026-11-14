package com.skypin.flasher

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.InputStream

const val READ_TIMEOUT = 10000
const val DATA_TIMEOUT = 60000
const val ERASE_TIMEOUT = 120000
const val FLASH_TIMEOUT = 600000
const val CHUNK = 16384

open class FastbootException(msg: String) : RuntimeException(msg)

/** Penolakan dari HP (FAIL). Turunan FastbootException, jadi kode yang menangkap FastbootException tetap bekerja. */
class FastbootRejected(msg: String) : FastbootException(msg)

class FbResp(val code: String, val text: String, val dataSize: Long)

/** Protokol fastboot di atas USB bulk. Satu objek = satu sesi koneksi. */
class Fastboot(private val conn: UsbDeviceConnection, private val iface: UsbInterface) {

    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint
    private val rbuf = ByteArray(64)

    init {
        var i: UsbEndpoint? = null
        var o: UsbEndpoint? = null
        for (k in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(k)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) {
                i = ep
            } else {
                o = ep
            }
        }
        if (i == null || o == null) throw FastbootException("Interface fastboot tidak punya endpoint bulk")
        epIn = i
        epOut = o
        if (!conn.claimInterface(iface, true)) throw FastbootException("Gagal mengklaim interface USB")
    }

    fun close() {
        runCatching { conn.releaseInterface(iface) }
        conn.close()
    }

    private fun write(s: String) {
        val b = s.toByteArray(Charsets.US_ASCII)
        if (b.size > 64) throw FastbootException("Perintah terlalu panjang")
        val n = conn.bulkTransfer(epOut, b, b.size, READ_TIMEOUT)
        if (n < 0) throw FastbootException("Gagal kirim perintah ke perangkat (USB putus?)")
    }

    private fun readResp(timeout: Int): FbResp {
        while (true) {
            val n = conn.bulkTransfer(epIn, rbuf, rbuf.size, timeout)
            if (n < 0) throw FastbootException("Tidak ada respon dari perangkat (timeout atau USB putus)")
            val s = String(rbuf, 0, n, Charsets.US_ASCII)
            when {
                s.startsWith("INFO") -> continue
                s.startsWith("OKAY") -> return FbResp("OKAY", s.substring(4), 0L)
                s.startsWith("FAIL") -> throw FastbootRejected(s.substring(4).ifBlank { "FAIL" })
                s.startsWith("DATA") -> {
                    val sz = s.substring(4, minOf(12, s.length)).toLongOrNull(16)
                        ?: throw FastbootException("Respon DATA tidak valid")
                    return FbResp("DATA", "", sz)
                }
                else -> throw FastbootException("Respon tidak dikenal: $s")
            }
        }
    }

    fun cmd(c: String, timeout: Int = READ_TIMEOUT): FbResp {
        write(c)
        return readResp(timeout)
    }

    /** Nilai getvar, atau null jika gagal / tidak didukung. */
    fun getvar(name: String): String? =
        try {
            val r = cmd("getvar:$name")
            if (r.code == "OKAY") r.text.trim() else null
        } catch (e: FastbootException) {
            null
        }

    fun erase(part: String) {
        cmd("erase:$part", ERASE_TIMEOUT)
    }

    fun flash(part: String) {
        cmd("flash:$part", FLASH_TIMEOUT)
    }

    fun setActive(slot: String) {
        cmd("set_active:$slot")
    }

    fun oem(c: String) {
        cmd("oem $c")
    }

    /** Reboot: bootloader sering memutus USB sebelum sempat membalas, jadi hanya FAIL yang dianggap error. */
    fun reboot() {
        write("reboot")
        val n = conn.bulkTransfer(epIn, rbuf, rbuf.size, READ_TIMEOUT)
        if (n > 0) {
            val s = String(rbuf, 0, n, Charsets.US_ASCII)
            if (s.startsWith("FAIL")) throw FastbootException(s.substring(4).ifBlank { "FAIL" })
        }
    }

    /** Reboot ke recovery. Sama seperti reboot: bootloader bisa memutus USB, jadi hanya FAIL yang dianggap error. */
    fun rebootRecovery() {
        write("reboot-recovery")
        val n = conn.bulkTransfer(epIn, rbuf, rbuf.size, READ_TIMEOUT)
        if (n > 0) {
            val s = String(rbuf, 0, n, Charsets.US_ASCII)
            if (s.startsWith("FAIL")) throw FastbootException(s.substring(4).ifBlank { "FAIL" })
        }
    }

    /** Kirim [size] byte dari [src] dengan perintah download. */
    fun download(size: Long, src: InputStream, onProgress: (Long) -> Unit = {}) {
        val r = cmd("download:%08x".format(size), DATA_TIMEOUT)
        if (r.code != "DATA") throw FastbootException("Bootloader tidak siap menerima data")
        if (r.dataSize != size) {
            throw FastbootException("Ukuran data tidak cocok (bootloader ${r.dataSize}, kirim $size)")
        }
        val buf = ByteArray(CHUNK)
        var sent = 0L
        while (sent < size) {
            val want = minOf(CHUNK.toLong(), size - sent).toInt()
            readExact(src, buf, want)
            val n = conn.bulkTransfer(epOut, buf, want, DATA_TIMEOUT)
            if (n != want) throw FastbootException("Gagal kirim data ke perangkat ($n dari $want byte)")
            sent += want
            onProgress(sent)
        }
        readResp(DATA_TIMEOUT)
    }

    private fun readExact(src: InputStream, buf: ByteArray, len: Int) {
        var got = 0
        while (got < len) {
            val n = src.read(buf, got, len - got)
            if (n < 0) throw FastbootException("Data sumber habis lebih cepat dari ukurannya")
            got += n
        }
    }
}

fun parseHex(s: String?): Long? = s?.trim()?.removePrefix("0x")?.toLongOrNull(16)

private val MAX_DL_HEX = Regex("0[xX][0-9a-fA-F]{1,16}")

/** Mengubah jawaban max-download-size menjadi byte. Hanya format hex 0x... yang diterima, selain itu null. */
fun parseMaxDownloadHex(raw: String?): Long? {
    val s = raw?.trim() ?: return null
    if (!MAX_DL_HEX.matches(s)) return null
    val v = s.substring(2).toLongOrNull(16) ?: return null
    return if (v > 0) v else null
}

/**
 * Membaca batas kiriman bootloader (max-download-size). Tidak ada nilai cadangan: jika tidak terbaca,
 * flash dihentikan dengan pesan, karena tebakan bisa membuat pemotongan file tidak sesuai tanpa diketahui.
 * [query] menjalankan getvar; dipisah agar bisa diuji dengan jawaban bootloader palsu.
 */
fun readMaxDownload(query: () -> FbResp): Long {
    val raw: String = try {
        val r = query()
        if (r.code == "OKAY") r.text else ""
    } catch (e: FastbootRejected) {
        throw FastbootException(
            "Bootloader menolak max-download-size (${e.message}). Flash dibatalkan karena batas kiriman tidak diketahui. " +
                "Untuk HP ini, coba cara manual lewat fastboot di Termux."
        )
    } catch (e: FastbootException) {
        throw FastbootException(
            "Perangkat tidak memberi jawaban untuk max-download-size (${e.message}). Bisa juga karena USB terputus: " +
                "hubungkan ulang lalu coba lagi. Flash dibatalkan."
        )
    }
    return parseMaxDownloadHex(raw)
        ?: throw FastbootException(
            "max-download-size tidak bisa dibaca sebagai 0x... (jawaban: " +
                (if (raw.isBlank()) "kosong" else "\"${raw.trim()}\"") +
                "). Flash dibatalkan karena batas kiriman tidak diketahui. " +
                "Untuk HP ini, coba cara manual lewat fastboot di Termux."
        )
}

fun human(b: Long): String = when {
    b >= (1L shl 30) -> "%.2f GB".format(b / 1073741824.0)
    b >= (1L shl 20) -> "%.1f MB".format(b / 1048576.0)
    b >= (1L shl 10) -> "%.1f KB".format(b / 1024.0)
    else -> "$b B"
}
