package com.skypin.flasher

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Gagal di protokol ADB atau saat transfer sideload. */
class AdbException(msg: String) : RuntimeException(msg)

// Kode perintah pesan ADB (teks ASCII dibaca sebagai little-endian)
private const val ADB_CNXN = 0x4e584e43
private const val ADB_OPEN = 0x4e45504f
private const val ADB_OKAY = 0x59414b4f
private const val ADB_CLSE = 0x45534c43
private const val ADB_WRTE = 0x45545257
private const val ADB_AUTH = 0x48545541
private const val ADB_VERSION = 0x01000000
private const val ADB_HOST_MAX = 256 * 1024
private const val ADB_MAX_IN = 256 * 1024
private const val ADB_MAX_WRITE = 64 * 1024
private const val ADB_LOCAL_ID = 1

/** Ukuran blok sideload, sama dengan adb resmi (64 KB). */
private const val SIDE_BLOCK = 64 * 1024

/** Batas tunggu pesan dari perangkat selama sideload. Setelah blok terakhir, Poco memverifikasi zip dulu dan bisa diam beberapa menit. */
private const val SIDE_WAIT_TIMEOUT = 30 * 60 * 1000
private val ADB_HOST_BANNER = "host::\u0000".toByteArray(Charsets.US_ASCII)
private val ADB_NO_DATA = ByteArray(0)

/**
 * Protokol ADB sideload di atas USB bulk. Satu objek = satu sesi koneksi.
 * Pesan ADB: header 24 byte (perintah, arg0, arg1, panjang, checksum, magic) lalu data.
 * Recovery meminta blok dengan nomor 8 digit, Flashmate mengirim isi blok itu,
 * dan "DONEDONE" menandai transfer selesai.
 */
class AdbSideload(private val conn: UsbDeviceConnection, private val iface: UsbInterface) {

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint
    private val scratch = ByteArray(CHUNK)
    private val queue = java.util.ArrayDeque<Msg>()
    private var peerMaxData = 4096
    private var connected = false

    /** Codename perangkat dari pesan koneksi. Kosong jika pesan tidak memuatnya. */
    var codename = ""
        private set

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
        if (i == null || o == null) {
            throw AdbException(tr("Interface ADB tidak punya endpoint bulk", "ADB interface has no bulk endpoints"))
        }
        epIn = i
        epOut = o
        if (!conn.claimInterface(iface, true)) {
            throw AdbException(tr("Gagal mengklaim interface USB", "Failed to claim USB interface"))
        }
    }

    fun close() {
        runCatching { conn.releaseInterface(iface) }
        conn.close()
    }

    /** Jabat tangan awal: kirim CNXN lalu tunggu balasan. Tidak mengirim data apa pun. Codename dibaca dari balasan. */
    fun connect() {
        queue.clear()
        writeMsg(ADB_CNXN, ADB_VERSION, ADB_HOST_MAX, ADB_HOST_BANNER, 0, ADB_HOST_BANNER.size)
        while (true) {
            val m = readMsg(READ_TIMEOUT)
            if (m.cmd == ADB_CNXN) {
                peerMaxData = if (m.arg1 > 0) m.arg1 else 4096
                codename = codenameFrom(String(m.data, Charsets.US_ASCII))
                connected = true
                return
            }
            if (m.cmd == ADB_AUTH) {
                throw AdbException(
                    tr("Perangkat meminta otorisasi ADB. Sideload tidak bisa dilanjutkan.",
                        "The device asked for ADB authorization. Sideload cannot continue.")
                )
            }
        }
    }

    /**
     * Mengirim file berukuran [size] ke recovery.
     * [readAt] membaca [len] byte mulai dari [offset] file ke [dst], lalu mengembalikan jumlah byte yang terbaca.
     * [onProgress] dipanggil dengan jumlah byte yang sudah terkirim. Jika [connect] belum dipanggil, fungsi ini memanggilnya sendiri.
     */
    fun sideload(size: Long, readAt: (Long, ByteArray, Int) -> Int, onProgress: (Long) -> Unit) {
        if (size <= 0L || size > Int.MAX_VALUE) {
            throw AdbException(
                tr("Ukuran zip tidak didukung (harus 1 byte sampai 2 GB).",
                    "Zip size is not supported (1 byte to 2 GB).")
            )
        }
        if (!connected) connect()
        val remoteId = openService(size)
        val block = ByteArray(SIDE_BLOCK)
        while (true) {
            val m = readMsg(SIDE_WAIT_TIMEOUT)
            when (m.cmd) {
                ADB_WRTE -> {
                    // Setiap pesan dari perangkat harus dibalas OKAY
                    writeMsg(ADB_OKAY, ADB_LOCAL_ID, remoteId)
                    val text = String(m.data, Charsets.US_ASCII)
                        .trim { it == '\u0000' || it == ' ' || it == '\n' || it == '\r' }
                    if (text == "DONEDONE") {
                        writeMsg(ADB_CLSE, ADB_LOCAL_ID, remoteId)
                        return
                    }
                    if (text == "FAILFAIL") {
                        throw AdbException(tr("Recovery membatalkan sideload.", "Recovery cancelled the sideload."))
                    }
                    val index = text.toIntOrNull()
                        ?: throw AdbException(tr("Permintaan blok tidak dikenal: $text", "Unknown block request: $text"))
                    val offset = index.toLong() * SIDE_BLOCK
                    if (index < 0 || offset >= size) {
                        throw AdbException(
                            tr("Perangkat meminta blok di luar ukuran file.",
                                "The device asked for a block past the end of the file.")
                        )
                    }
                    val len = minOf(SIDE_BLOCK.toLong(), size - offset).toInt()
                    if (readAt(offset, block, len) != len) {
                        throw AdbException(tr("Gagal membaca zip pada blok $index.", "Failed to read the zip at block $index."))
                    }
                    sendBlock(remoteId, block, len)
                    onProgress(offset + len)
                }
                ADB_CLSE -> throw AdbException(
                    tr("Perangkat menutup koneksi sebelum transfer selesai.",
                        "The device closed the connection before the transfer finished.")
                )
                ADB_AUTH -> throw AdbException(
                    tr("Perangkat meminta otorisasi ADB. Sideload tidak bisa dilanjutkan.",
                        "The device asked for ADB authorization. Sideload cannot continue.")
                )
                else -> Unit
            }
        }
    }

    /** Mengambil codename dari teks pesan koneksi, misalnya "device::ro.product.device=malachite;...". */
    private fun codenameFrom(banner: String): String {
        val props = banner.substringAfter("::", banner).split(';')
        for (key in listOf("ro.product.device", "ro.product.name")) {
            val raw = props.firstOrNull { it.startsWith("$key=") }?.substringAfter('=') ?: ""
            val value = raw.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(32)
            if (value.isNotBlank()) return value
        }
        return ""
    }

    /** Membuka layanan sideload-host. Mengembalikan id perangkat untuk pesan berikutnya. */
    private fun openService(size: Long): Int {
        val svc = "sideload-host:$size:$SIDE_BLOCK\u0000".toByteArray(Charsets.US_ASCII)
        writeMsg(ADB_OPEN, ADB_LOCAL_ID, 0, svc, 0, svc.size)
        while (true) {
            val m = readWire(READ_TIMEOUT)
            if (m.cmd == ADB_OKAY && m.arg1 == ADB_LOCAL_ID) return m.arg0
            if (m.cmd == ADB_CLSE) {
                throw AdbException(
                    tr("Recovery menolak sideload. Pastikan HP target di recovery dan sudah memilih Apply from ADB.",
                        "Recovery refused sideload. Make sure the target is in recovery and Apply from ADB is selected.")
                )
            }
        }
    }

    /** Mengirim satu blok. Jika batas perangkat lebih kecil dari blok, blok dipecah dan tiap potongan menunggu OKAY. */
    private fun sendBlock(remoteId: Int, data: ByteArray, len: Int) {
        val piece = minOf(peerMaxData, ADB_MAX_WRITE)
        var off = 0
        while (off < len) {
            val n = minOf(piece, len - off)
            writeMsg(ADB_WRTE, ADB_LOCAL_ID, remoteId, data, off, n)
            waitOkay()
            off += n
        }
    }

    /** Menunggu OKAY untuk pesan yang kita kirim. Permintaan blok yang datang lebih dulu disimpan di antrean. */
    private fun waitOkay() {
        while (true) {
            val m = readWire(DATA_TIMEOUT)
            when (m.cmd) {
                ADB_OKAY -> return
                ADB_WRTE -> queue.addLast(m)
                ADB_CLSE -> throw AdbException(
                    tr("Perangkat menutup koneksi saat transfer.", "The device closed the connection during transfer.")
                )
                else -> Unit
            }
        }
    }

    private fun writeMsg(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ADB_NO_DATA, off: Int = 0, len: Int = 0) {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(checksum(data, off, len)).putInt(cmd xor -1)
        bulkOut(h.array(), 0, 24)
        if (len > 0) bulkOut(data, off, len)
    }

    private fun readMsg(timeout: Int): Msg = queue.pollFirst() ?: readWire(timeout)

    private fun readWire(timeout: Int): Msg {
        val h = ByteArray(24)
        readExact(h, 24, timeout)
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.getInt(0)
        val arg0 = bb.getInt(4)
        val arg1 = bb.getInt(8)
        val len = bb.getInt(12)
        val magic = bb.getInt(20)
        if (magic != (cmd xor -1)) {
            throw AdbException(tr("Pesan ADB rusak dari perangkat.", "Corrupt ADB message from the device."))
        }
        if (len < 0 || len > ADB_MAX_IN) {
            throw AdbException(tr("Panjang pesan ADB tidak valid.", "Invalid ADB message length."))
        }
        val data = ByteArray(len)
        if (len > 0) readExact(data, len, timeout)
        return Msg(cmd, arg0, arg1, data)
    }

    /** Kirim [len] byte dari [buf] mulai [off]. USB hanya menerima potongan kecil, jadi dibagi per [CHUNK]. */
    private fun bulkOut(buf: ByteArray, off: Int, len: Int) {
        var done = 0
        while (done < len) {
            val n = minOf(CHUNK, len - done)
            System.arraycopy(buf, off + done, scratch, 0, n)
            val r = conn.bulkTransfer(epOut, scratch, n, DATA_TIMEOUT)
            if (r != n) {
                throw AdbException(
                    tr("Gagal kirim ke perangkat ($r dari $n byte). USB putus?",
                        "Failed to send to the device ($r of $n bytes). Is USB disconnected?")
                )
            }
            done += n
        }
    }

    private fun readExact(dst: ByteArray, len: Int, timeout: Int) {
        var got = 0
        while (got < len) {
            val n = conn.bulkTransfer(epIn, scratch, minOf(CHUNK, len - got), timeout)
            if (n < 0) {
                throw AdbException(
                    tr("Tidak ada respon dari perangkat (timeout atau USB putus).",
                        "No response from the device (timeout or USB disconnected).")
                )
            }
            System.arraycopy(scratch, 0, dst, got, n)
            got += n
        }
    }

    /** Checksum pesan ADB: jumlah semua byte data. */
    private fun checksum(data: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        for (i in off until off + len) sum += data[i].toInt() and 0xFF
        return sum
    }
}
