package com.skypin.flasher

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

private const val REQ_PICK_SHARE = 1010

/** Penerima mendengar pencarian di port ini (UDP). */
private const val PORT_FIND = 47821
/** Pengirim mendengar jawaban penerima di port ini (UDP). */
private const val PORT_HERE = 47822
/** Isi file dikirim lewat port ini (TCP). */
private const val PORT_DATA = 47823

private const val SHARE_MAGIC = "FMSHARE1"
private const val REPLY_OK = 1
private const val REPLY_BAD_CODE = 2
private const val REPLY_NO_SPACE = 3
private const val REPLY_REFUSED = 4

/** Ukuran potongan baca dan tulis. Potongan besar membuat kirim file besar lebih cepat. */
private const val CHUNK = 1 shl 20
private const val SOCKET_BUFFER = 4 shl 20

/**
 * Halaman SHARE FILE: kirim atau terima file besar antar dua HP yang sama-sama memasang Flashmate
 * dan tersambung ke jaringan Wi-Fi atau hotspot yang sama. File berjalan langsung antar HP, tanpa internet.
 * Setelah sampai, isi file dicek dengan CRC32 di kedua sisi.
 */
class ShareFileActivity : FlasherBase() {

    /** Satu sesi kirim atau terima. Semua soket sesi ini ditutup sekaligus saat sesi dibatalkan atau selesai. */
    private class Sess {
        @Volatile var dead = false
        private val items = Collections.synchronizedList(ArrayList<Closeable>())

        fun <T : Closeable> own(c: T): T {
            items.add(c)
            if (dead) runCatching { c.close() }
            return c
        }

        fun closeAll() {
            val copy = synchronized(items) { ArrayList(items) }
            for (c in copy) runCatching { c.close() }
        }
    }

    private class Peer(val name: String, val code: String, val host: InetAddress, var seen: Long)

    private class Target(val out: OutputStream, val where: String, val finish: (Boolean) -> Unit)

    @Volatile private var sess: Sess? = null
    @Volatile private var fileUri: Uri? = null
    @Volatile private var fileName = ""
    @Volatile private var fileSize = 0L
    private var roleSend = true
    private var pendingDialog: AlertDialog? = null
    private val peers = LinkedHashMap<String, Peer>()
    private var lock: WifiManager.MulticastLock? = null

    private lateinit var titleText: TextView
    private lateinit var subText: TextView
    private lateinit var homePanel: LinearLayout
    private lateinit var recvPanel: LinearLayout
    private lateinit var sendPanel: LinearLayout
    private lateinit var progPanel: LinearLayout
    private lateinit var resultPanel: LinearLayout
    private lateinit var resultBox: LinearLayout

    private lateinit var codeText: TextView
    private lateinit var recvState: TextView
    private lateinit var recvName: TextView
    private lateinit var recvNet: TextView
    private lateinit var recvFree: TextView
    private lateinit var recvWarn: TextView

    private lateinit var fileInfo: TextView
    private lateinit var peersBox: LinearLayout
    private lateinit var peersEmpty: TextView
    private lateinit var sendNet: TextView
    private lateinit var sendWarn: TextView

    private lateinit var progFile: TextView
    private lateinit var progPercent: TextView
    private lateinit var progBytes: TextView
    private lateinit var progBar: LinearLayout
    private lateinit var progFill: View
    private lateinit var progRest: View
    private lateinit var progSpeed: TextView
    private lateinit var progEta: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        showHome()
    }

    override fun onDestroy() {
        cancelSession()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_SHARE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val (name, size) = try {
            queryNameSize(uri)
        } catch (e: Exception) {
            Pair("file", 0L)
        }
        fileUri = uri
        fileName = name
        fileSize = size
        fileInfo.text = "$name\n${human(size)}"
        fileInfo.setTextColor(pal().text)
        log(tr("File dipilih: $name (${human(size)})", "File chosen: $name (${human(size)})"))
    }

    // ---------- Sesi ----------

    /** Membatalkan sesi yang sedang berjalan. Soket ditutup di thread lain supaya layar tidak tersendat. */
    private fun cancelSession() {
        val s = sess ?: return
        sess = null
        s.dead = true
        pendingDialog?.let { d -> runCatching { d.dismiss() } }
        pendingDialog = null
        Thread { s.closeAll() }.start()
        releaseLock()
    }

    private fun live(s: Sess): Boolean = sess === s && !s.dead

    /** Menjalankan [block] di thread layar hanya jika sesi [s] masih berlaku. */
    private fun ui(s: Sess, block: () -> Unit) {
        runOnUiThread { if (live(s)) block() }
    }

    private fun acquireLock() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val l = wm.createMulticastLock("flashmate-share")
            l.setReferenceCounted(false)
            l.acquire()
            lock = l
        } catch (e: Exception) {
            // Tanpa kunci ini pencarian tetap dicoba
        }
    }

    private fun releaseLock() {
        val l = lock ?: return
        lock = null
        runCatching { if (l.isHeld) l.release() }
    }

    // ---------- Jaringan ----------

    private fun deviceName(): String {
        val n = try {
            Settings.Global.getString(contentResolver, "device_name")
        } catch (e: Exception) {
            null
        }
        val name = if (n.isNullOrBlank()) Build.MODEL ?: "Android" else n
        return name.replace("|", " ").trim().take(40).ifBlank { "Android" }
    }

    /** Alamat broadcast semua jaringan yang aktif di HP ini, termasuk hotspot milik HP ini sendiri. */
    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            val all = NetworkInterface.getNetworkInterfaces()
            if (all != null) {
                for (nif in Collections.list(all)) {
                    if (!nif.isUp || nif.isLoopback) continue
                    for (ia in nif.interfaceAddresses) {
                        val b = ia.broadcast ?: continue
                        out.add(b)
                    }
                }
            }
        } catch (e: Exception) {
            // Daftar jaringan tidak terbaca: pakai alamat umum di bawah
        }
        if (out.isEmpty()) {
            runCatching { out.add(InetAddress.getByName("255.255.255.255")) }
        }
        return out
    }

    private fun localIps(): List<String> {
        val out = ArrayList<String>()
        try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return out
            for (nif in Collections.list(all)) {
                if (!nif.isUp || nif.isLoopback) continue
                // Jaringan data seluler tidak dipakai untuk kirim antar HP
                val n = nif.name.lowercase()
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("dummy")) continue
                for (a in Collections.list(nif.inetAddresses)) {
                    if (a is Inet4Address && !a.isLoopbackAddress) out.add(a.hostAddress ?: continue)
                }
            }
        } catch (e: Exception) {
            // Kosong berarti tidak terbaca
        }
        return out
    }

    /** Keterangan jaringan untuk ditampilkan, dan teks peringatan jika 2,4 GHz atau belum tersambung (null jika tidak perlu). */
    @Suppress("DEPRECATION")
    private fun networkInfo(): Pair<String, String?> {
        var freq = -1
        var speed = -1
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            if (info != null) {
                freq = info.frequency
                speed = info.linkSpeed
            }
        } catch (e: Exception) {
            // Tidak terbaca
        }
        if (freq > 0) {
            val band = when {
                freq >= 5925 -> "6 GHz"
                freq >= 4900 -> "5 GHz"
                else -> "2,4 GHz"
            }
            val text = if (speed > 0) "Wi-Fi $band · $speed Mbps" else "Wi-Fi $band"
            val warn = if (freq < 4900) {
                tr("jaringan ini 2,4 GHz, kirim file besar akan lambat. jika bisa, pakai hotspot 5 GHz.",
                    "this network is 2.4 GHz, large files will be slow. use a 5 GHz hotspot if you can.")
            } else {
                null
            }
            return Pair(text, warn)
        }
        if (localIps().isNotEmpty()) {
            return Pair(tr("hotspot hp ini", "this phone's hotspot"), null)
        }
        return Pair(
            tr("belum tersambung", "not connected"),
            tr("hp ini belum tersambung ke Wi-Fi atau hotspot. sambungkan dulu kedua hp ke jaringan yang sama.",
                "this phone is not connected to Wi-Fi or a hotspot. connect both phones to the same network first.")
        )
    }

    private fun refreshNet() {
        val (text, warn) = networkInfo()
        recvNet.text = text
        sendNet.text = tr("jaringan: $text", "network: $text")
        for (w in listOf(recvWarn, sendWarn)) {
            w.text = warn ?: ""
            w.visibility = if (warn != null) View.VISIBLE else View.GONE
        }
    }

    private fun sendAll(udp: DatagramSocket, text: String, port: Int) {
        val data = text.toByteArray(Charsets.UTF_8)
        for (addr in broadcastAddresses()) {
            try {
                udp.send(DatagramPacket(data, data.size, addr, port))
            } catch (e: Exception) {
                // Satu jaringan gagal tidak menghentikan jaringan lain
            }
        }
    }

    private fun freeSpace(): Long = try {
        val dir = getExternalFilesDir(null) ?: filesDir
        StatFs(dir.path).availableBytes
    } catch (e: Exception) {
        -1L
    }

    /** Pesan kesalahan yang bisa dibaca, karena sebagian kesalahan jaringan tidak membawa pesan. */
    private fun errText(e: Exception): String = when (e) {
        is EOFException -> tr("sambungan ditutup oleh hp lawan", "the other phone closed the connection")
        is SocketTimeoutException -> tr("hp lawan tidak menjawab (waktu tunggu habis)", "the other phone did not answer (timed out)")
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun duration(sec: Long): String {
        val m = sec / 60
        val s = sec % 60
        return if (m > 0) tr("$m mnt $s dtk", "$m min $s s") else tr("$s dtk", "$s s")
    }

    private fun cleanName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val safe = base.replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim().take(200)
        return if (safe.isBlank() || safe == "." || safe == "..") "file" else safe
    }

    // ---------- Kemajuan ----------

    /** Menghitung kecepatan dan sisa waktu, lalu memperbarui tampilan paling sering dua kali per detik. */
    private inner class Meter(private val s: Sess, private val total: Long) {
        private val start = SystemClock.elapsedRealtime()
        private var lastT = start
        private var lastB = 0L
        private var speed = 0.0

        fun tick(done: Long) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastT < 500L && done < total) return
            val dt = (now - lastT).coerceAtLeast(1L)
            val inst = (done - lastB) * 1000.0 / dt
            speed = if (speed <= 0.0) inst else speed * 0.7 + inst * 0.3
            lastT = now
            lastB = done
            val sp = speed.toLong()
            val eta = if (speed > 1.0) ((total - done) / speed).toLong() else -1L
            ui(s) { showProgress(done, total, sp, eta) }
        }

        fun seconds(): Long = ((SystemClock.elapsedRealtime() - start) / 1000L).coerceAtLeast(1L)
    }

    private fun showProgress(done: Long, total: Long, speed: Long, eta: Long) {
        val pct = if (total > 0L) (done * 100L / total).toInt().coerceIn(0, 100) else 0
        progPercent.text = "$pct%"
        progBytes.text = tr("${human(done)} dari ${human(total)}", "${human(done)} of ${human(total)}")
        (progFill.layoutParams as LinearLayout.LayoutParams).weight = pct.toFloat()
        (progRest.layoutParams as LinearLayout.LayoutParams).weight = (100 - pct).toFloat()
        progBar.requestLayout()
        progSpeed.text = if (speed > 0L) tr("${human(speed)}/dtk", "${human(speed)}/s") else "-"
        progEta.text = if (eta >= 0L) tr("sekitar ${duration(eta)}", "about ${duration(eta)}") else "-"
    }

    // ---------- TERIMA ----------

    private fun startReceive() {
        if (busy) {
            Toast.makeText(this, tr("Tunggu proses sebelumnya berhenti dulu.", "Wait for the previous task to stop."), Toast.LENGTH_SHORT).show()
            return
        }
        cancelSession()
        val s = Sess()
        sess = s
        roleSend = false
        val code = (1000 + (Math.random() * 9000).toInt()).coerceIn(1000, 9999).toString()
        codeText.text = code
        recvState.text = tr("menunggu pengirim...", "waiting for sender...")
        recvName.text = deviceName()
        val free = freeSpace()
        recvFree.text = if (free >= 0L) human(free) else "-"
        refreshNet()
        titleText.text = tr("TERIMA FILE", "RECEIVE FILE")
        subText.text = tr("hp ini siap menerima", "this phone is ready to receive")
        show(recvPanel)
        acquireLock()
        runTask(tr("TERIMA FILE", "RECEIVE FILE")) { receiveSession(s, code) }
    }

    private fun receiveSession(s: Sess, code: String) {
        val name = deviceName()
        try {
            val server = s.own(ServerSocket())
            server.reuseAddress = true
            server.receiveBufferSize = SOCKET_BUFFER
            server.bind(InetSocketAddress(PORT_DATA))
            val udp = s.own(DatagramSocket(null as SocketAddress?))
            udp.reuseAddress = true
            udp.broadcast = true
            udp.bind(InetSocketAddress(PORT_FIND))

            val ips = localIps()
            log(tr("siap menerima, kode $code", "ready to receive, code $code"))
            log(tr("alamat hp ini: ", "this phone's address: ") + (if (ips.isEmpty()) "-" else ips.joinToString(", ")))

            val here = "$SHARE_MAGIC|HERE|$code|$name"
            // Memberi tahu keberadaan HP ini tiap detik
            Thread {
                var n = 0
                while (!udp.isClosed && !s.dead) {
                    sendAll(udp, here, PORT_HERE)
                    n++
                    if (n % 3 == 0) ui(s) { if (recvPanel.visibility == View.VISIBLE) refreshNet() }
                    try {
                        Thread.sleep(1000L)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }.start()
            // Menjawab pencarian dari pengirim
            Thread {
                val buf = ByteArray(512)
                val reply = here.toByteArray(Charsets.UTF_8)
                while (!udp.isClosed && !s.dead) {
                    try {
                        val pk = DatagramPacket(buf, buf.size)
                        udp.receive(pk)
                        val text = String(pk.data, pk.offset, pk.length, Charsets.UTF_8)
                        if (text.startsWith("$SHARE_MAGIC|FIND")) {
                            udp.send(DatagramPacket(reply, reply.size, pk.address, pk.port))
                        }
                    } catch (e: Exception) {
                        if (udp.isClosed) break
                    }
                }
            }.start()

            while (!s.dead) {
                val sock = s.own(server.accept())
                val finished = handleIncoming(s, sock, code)
                runCatching { sock.close() }
                if (finished) break
            }
        } catch (e: Exception) {
            if (live(s)) {
                val msg = errText(e)
                log(tr("GAGAL: ", "FAILED: ") + msg)
                ui(s) { showFailure(msg) }
            } else {
                log(tr("dibatalkan", "cancelled"))
            }
        } finally {
            s.closeAll()
            runOnUiThread { if (sess === s) releaseLock() }
        }
    }

    private class Incoming(
        val inp: DataInputStream,
        val out: DataOutputStream,
        val sender: String,
        val name: String,
        val size: Long
    )

    /** Membaca pembuka sambungan masuk dan meminta persetujuan pengguna. Null jika sambungan ini tidak dilanjutkan. */
    private fun readIncoming(s: Sess, sock: Socket, code: String): Incoming? {
        try {
            sock.soTimeout = 15000
            val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 1 shl 16))
            val out = DataOutputStream(sock.getOutputStream())
            if (inp.readUTF() != SHARE_MAGIC) return null
            val theirCode = inp.readUTF()
            val sender = inp.readUTF().take(40)
            val name = cleanName(inp.readUTF())
            val size = inp.readLong()
            if (theirCode != code) {
                out.writeByte(REPLY_BAD_CODE)
                out.flush()
                log(tr("sambungan ditolak: kode tidak sama", "connection rejected: code does not match"))
                return null
            }
            if (size <= 0L) return null
            val free = freeSpace()
            if (free in 0L until size) {
                out.writeByte(REPLY_NO_SPACE)
                out.flush()
                log(tr("ruang kosong tidak cukup untuk $name (${human(size)})",
                    "not enough free space for $name (${human(size)})"))
                return null
            }
            if (!askAccept(s, sender, name, size)) {
                out.writeByte(REPLY_REFUSED)
                out.flush()
                log(tr("kiriman dari $sender ditolak", "transfer from $sender refused"))
                return null
            }
            return Incoming(inp, out, sender, name, size)
        } catch (e: Exception) {
            if (s.dead) throw e
            // Sambungan yang bukan dari Flashmate atau putus sebelum mulai: abaikan, tetap menunggu
            return null
        }
    }

    /** Menangani satu sambungan masuk. True jika file sudah diterima sampai selesai. Gagal di tengah melempar kesalahan. */
    private fun handleIncoming(s: Sess, sock: Socket, code: String): Boolean {
        val req = readIncoming(s, sock, code) ?: return false
        val inp = req.inp
        val out = req.out
        val sender = req.sender
        val name = req.name
        val size = req.size

        // Dari sini file benar-benar diterima
        log(tr("menerima $name (${human(size)}) dari $sender", "receiving $name (${human(size)}) from $sender"))
        ui(s) {
            titleText.text = tr("TERIMA FILE", "RECEIVE FILE")
            subText.text = tr("menerima dari $sender", "receiving from $sender")
            progFile.text = name
            showProgress(0L, size, 0L, -1L)
            show(progPanel)
        }
        val target = openTarget(name)
        var ok = false
        val meter = Meter(s, size)
        try {
            sock.soTimeout = 30000
            out.writeByte(REPLY_OK)
            out.flush()
            val buf = ByteArray(CHUNK)
            val crc = CRC32()
            var got = 0L
            while (got < size) {
                if (s.dead) throw IOException(tr("dibatalkan", "cancelled"))
                val want = minOf(buf.size.toLong(), size - got).toInt()
                val n = inp.read(buf, 0, want)
                if (n < 0) throw IOException(tr("sambungan terputus sebelum file selesai", "connection lost before the file finished"))
                target.out.write(buf, 0, n)
                crc.update(buf, 0, n)
                got += n
                meter.tick(got)
            }
            target.out.flush()
            val theirs = inp.readLong()
            ok = theirs == crc.value
        } finally {
            // File ditutup dan disahkan dulu (atau dihapus jika tidak utuh), baru pengirim diberi kabar
            runCatching { target.out.close() }
            runCatching { target.finish(ok) }
        }
        runCatching {
            out.writeByte(if (ok) 1 else 0)
            out.flush()
        }
        if (!ok) throw IOException(tr("isi file tidak sama dengan kiriman (CRC berbeda)", "file contents differ from what was sent (CRC mismatch)"))
        val sec = meter.seconds()
        log(tr("selesai dalam ${duration(sec)}", "finished in ${duration(sec)}"))
        log(tr("cek isi file: cocok", "file content check: match"))
        log(tr("disimpan di ${target.where}", "saved to ${target.where}"))
        ui(s) {
            showSuccess(
                tr("Terima Berhasil", "Receive Successful"),
                name, size, sec,
                tr("disimpan di", "saved to"), target.where,
                tr("TERIMA FILE LAIN", "RECEIVE ANOTHER FILE")
            )
        }
        return true
    }

    /** Menanyakan ke pengguna apakah kiriman diterima. Menunggu sampai dijawab atau sesi dibatalkan. */
    private fun askAccept(s: Sess, sender: String, name: String, size: Long): Boolean {
        val latch = CountDownLatch(1)
        val yes = AtomicBoolean(false)
        runOnUiThread {
            if (!live(s) || isFinishing) {
                latch.countDown()
                return@runOnUiThread
            }
            pendingDialog = AlertDialog.Builder(this)
                .setMessage(tr("Terima file ini dari $sender?\n\n$name\n${human(size)}",
                    "Accept this file from $sender?\n\n$name\n${human(size)}"))
                .setCancelable(false)
                .setPositiveButton(tr("Terima", "Accept")) { _, _ ->
                    yes.set(true)
                    latch.countDown()
                }
                .setNegativeButton(tr("Tolak", "Reject")) { _, _ -> latch.countDown() }
                .show()
        }
        while (!latch.await(500L, TimeUnit.MILLISECONDS)) {
            if (s.dead) return false
        }
        runOnUiThread { pendingDialog = null }
        return yes.get() && !s.dead
    }

    /** Membuka file tujuan. Android 10 ke atas: Download/Flashmate. Di bawah itu: folder aplikasi. */
    private fun openTarget(name: String): Target {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Flashmate")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException(tr("tidak bisa membuat file di folder Download", "cannot create the file in Download"))
            val os = contentResolver.openOutputStream(uri)
                ?: throw IOException(tr("tidak bisa membuka file tujuan", "cannot open the target file"))
            return Target(os, "Download/Flashmate") { ok ->
                if (ok) {
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    contentResolver.update(uri, done, null, null)
                } else {
                    // File yang tidak utuh dihapus supaya tidak terpakai untuk flash
                    contentResolver.delete(uri, null, null)
                }
            }
        }
        val dir = File(getExternalFilesDir(null) ?: filesDir, "Flashmate")
        dir.mkdirs()
        var f = File(dir, name)
        var i = 1
        while (f.exists()) {
            f = File(dir, "($i) $name")
            i++
        }
        val file = f
        return Target(FileOutputStream(file), file.parent ?: dir.path) { ok ->
            if (!ok) file.delete()
        }
    }

    // ---------- KIRIM ----------

    private fun startSend() {
        cancelSession()
        val s = Sess()
        sess = s
        roleSend = true
        peers.clear()
        renderPeers()
        refreshNet()
        titleText.text = tr("KIRIM FILE", "SEND FILE")
        subText.text = tr("pilih file, lalu pilih hp penerima", "choose a file, then choose the receiving phone")
        show(sendPanel)
        acquireLock()
        log("== " + tr("KIRIM FILE", "SEND FILE") + " ==")
        log(tr("mencari penerima...", "looking for receivers..."))
        Thread { discover(s) }.start()
    }

    /** Mencari HP penerima di jaringan yang sama sampai sesi berakhir atau pengiriman dimulai. */
    private fun discover(s: Sess) {
        val udp = try {
            val d = s.own(DatagramSocket(null as SocketAddress?))
            d.reuseAddress = true
            d.broadcast = true
            d.soTimeout = 500
            d.bind(InetSocketAddress(PORT_HERE))
            d
        } catch (e: Exception) {
            if (live(s)) log(tr("GAGAL mencari penerima: ", "FAILED to look for receivers: ") + errText(e))
            return
        }
        discoverSocket = udp
        val find = "$SHARE_MAGIC|FIND"
        val buf = ByteArray(512)
        var lastFind = 0L
        var n = 0
        while (!udp.isClosed && !s.dead) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFind >= 1000L) {
                lastFind = now
                sendAll(udp, find, PORT_FIND)
                n++
                val refresh = n % 3 == 0
                ui(s) {
                    // Penerima yang tidak terdengar lagi selama 5 detik dihapus dari daftar
                    val before = peers.size
                    val iter = peers.values.iterator()
                    while (iter.hasNext()) {
                        if (now - iter.next().seen > 5000L) iter.remove()
                    }
                    if (peers.size != before) renderPeers()
                    if (refresh && sendPanel.visibility == View.VISIBLE) refreshNet()
                }
            }
            try {
                val pk = DatagramPacket(buf, buf.size)
                udp.receive(pk)
                val parts = String(pk.data, pk.offset, pk.length, Charsets.UTF_8).split("|", limit = 4)
                if (parts.size == 4 && parts[0] == SHARE_MAGIC && parts[1] == "HERE") {
                    val host = pk.address
                    val key = host.hostAddress ?: continue
                    val code = parts[2]
                    val name = parts[3]
                    val seen = SystemClock.elapsedRealtime()
                    ui(s) {
                        val old = peers[key]
                        if (old != null && old.code == code && old.name == name) {
                            old.seen = seen
                        } else {
                            peers[key] = Peer(name, code, host, seen)
                            renderPeers()
                            log(tr("ditemukan: $name ($code)", "found: $name ($code)"))
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                // Tidak ada jawaban dalam setengah detik: lanjut mencari
            } catch (e: Exception) {
                if (udp.isClosed) break
            }
        }
    }

    @Volatile private var discoverSocket: DatagramSocket? = null

    private fun renderPeers() {
        val p = pal()
        peersBox.removeAllViews()
        peersEmpty.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
        for (peer in peers.values) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = neonBorder(10, p.card)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = lp(top = 8)
                isClickable = true
                isFocusable = true
                setOnClickListener { onPickPeer(peer) }
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(textLine(peer.name, 15f, medium = true, color = p.text))
            col.addView(textLine(tr("siap menerima", "ready to receive"), 12f, color = p.muted))
            row.addView(col)
            row.addView(textLine(peer.code, 16f, medium = true, color = p.accent, mono = true))
            peersBox.addView(row)
        }
    }

    private fun onPickPeer(peer: Peer) {
        val s = sess ?: return
        val uri = fileUri
        if (uri == null) {
            alert(tr("Pilih file yang mau dikirim dulu.", "Choose the file to send first."))
            return
        }
        if (fileSize <= 0L) {
            alert(tr("Ukuran file tidak terbaca. Pilih ulang filenya.", "File size could not be read. Choose the file again."))
            return
        }
        if (busy) {
            Toast.makeText(this, tr("Tunggu proses sebelumnya berhenti dulu.", "Wait for the previous task to stop."), Toast.LENGTH_SHORT).show()
            return
        }
        val name = fileName
        val size = fileSize
        confirm(tr("Kirim $name (${human(size)}) ke ${peer.name} (kode ${peer.code})?",
            "Send $name (${human(size)}) to ${peer.name} (code ${peer.code})?")) {
            if (!live(s) || busy) return@confirm
            // Pencarian dihentikan, lalu pengiriman dimulai
            val udp = discoverSocket
            discoverSocket = null
            Thread { runCatching { udp?.close() } }.start()
            subText.text = tr("menunggu ${peer.name} menerima...", "waiting for ${peer.name} to accept...")
            runTask(tr("KIRIM FILE", "SEND FILE")) { sendSession(s, peer, uri, name, size) }
        }
    }

    private fun sendSession(s: Sess, peer: Peer, uri: Uri, name: String, size: Long) {
        try {
            log(tr("menyambung ke ${peer.name}...", "connecting to ${peer.name}..."))
            val sock = s.own(Socket())
            sock.sendBufferSize = SOCKET_BUFFER
            sock.connect(InetSocketAddress(peer.host, PORT_DATA), 8000)
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 1 shl 16))
            val inp = DataInputStream(sock.getInputStream())
            out.writeUTF(SHARE_MAGIC)
            out.writeUTF(peer.code)
            out.writeUTF(deviceName())
            out.writeUTF(name.take(200))
            out.writeLong(size)
            out.flush()

            // Penerima perlu waktu untuk menekan Terima
            sock.soTimeout = 120000
            when (inp.readByte().toInt()) {
                REPLY_OK -> {}
                REPLY_BAD_CODE -> throw IOException(tr("kode tidak sama dengan hp penerima", "code does not match the receiving phone"))
                REPLY_NO_SPACE -> throw IOException(tr("ruang kosong di hp penerima tidak cukup", "not enough free space on the receiving phone"))
                REPLY_REFUSED -> throw IOException(tr("hp penerima menolak kiriman", "the receiving phone refused the transfer"))
                else -> throw IOException(tr("jawaban hp penerima tidak dikenal", "unknown reply from the receiving phone"))
            }

            log(tr("tersambung, mengirim ${human(size)}...", "connected, sending ${human(size)}..."))
            ui(s) {
                subText.text = tr("mengirim ke ${peer.name}", "sending to ${peer.name}")
                progFile.text = name
                showProgress(0L, size, 0L, -1L)
                show(progPanel)
            }
            val meter = Meter(s, size)
            val crc = CRC32()
            val buf = ByteArray(CHUNK)
            var sent = 0L
            val src = contentResolver.openInputStream(uri)
                ?: throw IOException(tr("tidak bisa membuka file", "cannot open the file"))
            src.use { input ->
                while (sent < size) {
                    if (s.dead) throw IOException(tr("dibatalkan", "cancelled"))
                    val want = minOf(buf.size.toLong(), size - sent).toInt()
                    val n = input.read(buf, 0, want)
                    if (n < 0) throw IOException(tr("file berakhir sebelum ukurannya tercapai", "the file ended before its stated size"))
                    out.write(buf, 0, n)
                    crc.update(buf, 0, n)
                    sent += n
                    meter.tick(sent)
                }
            }
            out.writeLong(crc.value)
            out.flush()
            // Penerima menyelesaikan tulisan ke penyimpanan, lalu membandingkan CRC
            sock.soTimeout = 180000
            val verdict = inp.readByte().toInt()
            if (verdict != 1) {
                throw IOException(tr("isi file di hp penerima tidak sama (CRC berbeda)", "file contents on the receiving phone differ (CRC mismatch)"))
            }
            val sec = meter.seconds()
            log(tr("selesai dalam ${duration(sec)}", "finished in ${duration(sec)}"))
            log(tr("cek isi file: cocok", "file content check: match"))
            ui(s) {
                showSuccess(
                    tr("Kirim Berhasil", "Send Successful"),
                    name, size, sec,
                    tr("penerima", "receiver"), peer.name,
                    tr("KIRIM FILE LAIN", "SEND ANOTHER FILE")
                )
            }
        } catch (e: Exception) {
            if (live(s)) {
                val msg = errText(e)
                log(tr("GAGAL: ", "FAILED: ") + msg)
                ui(s) { showFailure(msg) }
            } else {
                log(tr("dibatalkan", "cancelled"))
            }
        } finally {
            s.closeAll()
            runOnUiThread { if (sess === s) releaseLock() }
        }
    }

    // ---------- Tampilan ----------

    private fun show(panel: View) {
        for (v in listOf(homePanel, recvPanel, sendPanel, progPanel, resultPanel)) {
            v.visibility = if (v === panel) View.VISIBLE else View.GONE
        }
    }

    private fun showHome() {
        titleText.text = "SHARE FILE"
        subText.text = tr("kirim file besar antar hp lewat Wi-Fi", "send large files between phones over Wi-Fi")
        show(homePanel)
    }

    /** Tanda Kembali: dari halaman kirim atau terima kembali ke pilihan peran, dari pilihan peran keluar. */
    private fun onBack() {
        if (homePanel.visibility == View.VISIBLE) {
            finish()
            return
        }
        cancelSession()
        showHome()
    }

    private fun again() {
        if (roleSend) startSend() else startReceive()
    }

    private fun showSuccess(head: String, name: String, size: Long, sec: Long, extraKey: String, extraVal: String, button: String) {
        val p = pal()
        resultBox.removeAllViews()
        resultBox.addView(textLine(head, 16f, medium = true, color = p.okText))
        resultBox.addView(textLine(name, 12f, color = p.text, mono = true).apply { setPadding(0, dp(6), 0, dp(2)) })
        kvRow(resultBox, tr("ukuran", "size")).text = human(size)
        kvRow(resultBox, tr("waktu", "time")).text = duration(sec)
        kvRow(resultBox, tr("rata-rata", "average")).text = tr("${human(size / sec)}/dtk", "${human(size / sec)}/s")
        kvRow(resultBox, tr("isi file", "file contents")).text = tr("sama persis", "identical")
        kvRow(resultBox, extraKey).text = extraVal
        resultBox.addView(btn(button, BtnStyle.PRIMARY) { again() })
        show(resultPanel)
    }

    private fun showFailure(msg: String) {
        val p = pal()
        resultBox.removeAllViews()
        resultBox.addView(textLine(tr("Gagal", "Failed"), 16f, medium = true, color = p.danger))
        resultBox.addView(textLine(msg, 13f, color = p.text).apply { setPadding(0, dp(6), 0, 0) })
        resultBox.addView(btn(tr("COBA LAGI", "TRY AGAIN"), BtnStyle.PRIMARY) { again() })
        show(resultPanel)
    }

    private fun warnBox(text: String): TextView {
        val p = pal()
        return textLine(text, 12f, color = p.warnText).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(p.warnBg, 10, null)
            layoutParams = lp(bottom = 8)
        }
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }

    /** Log setinggi 250 dp. Isi halaman dinaikkan ke atas supaya log bisa setinggi ini. */
    private fun logPanel(): LinearLayout = makeLog().also { section ->
        section.getChildAt(1).layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(250))
    }

    private fun buildUi() {
        val p = pal()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }

        // Jarak atas dibuat kecil supaya isi halaman naik dan log bisa lebih tinggi
        val back = backButton { onBack() }
        back.minimumHeight = dp(40)
        back.layoutParams = lp(top = 0)
        content.addView(back)
        titleText = textLine("SHARE FILE", 22f, medium = true).apply {
            paint.isFakeBoldText = true
            applyNeonText(this)
            setPadding(0, dp(4), 0, 0)
        }
        content.addView(titleText)
        subText = caption("")
        content.addView(subText)

        // 1. Pilihan peran
        homePanel = panel()
        val homeBox = box()
        homeBox.addView(caption(tr("pilih peran hp ini", "choose this phone's role")))
        val bSend = btn(tr("KIRIM FILE", "SEND FILE"), BtnStyle.PRIMARY) { startSend() }
        bSend.paint.isFakeBoldText = true
        homeBox.addView(bSend)
        homeBox.addView(caption(tr("hp ini mengirim file ke hp lain", "this phone sends a file to another phone")))
        homeBox.addView(neonOutlineBtn(tr("TERIMA FILE", "RECEIVE FILE")) { startReceive() })
        homeBox.addView(caption(tr("hp ini menunggu kiriman dari hp lain", "this phone waits for a file from another phone")))
        homePanel.addView(homeBox)
        homePanel.addView(warnBox(
            tr("kedua hp harus tersambung ke jaringan yang sama. cara tercepat: nyalakan hotspot 5 GHz di salah satu hp, lalu hp satunya sambung ke hotspot itu.",
                "both phones must be on the same network. fastest way: turn on a 5 GHz hotspot on one phone, then connect the other phone to it.")
        ))
        content.addView(homePanel)

        // 2. Penerima menunggu
        recvPanel = panel()
        val recvBox = box()
        val codeCard = subCard()
        codeCard.layoutParams = lp(top = 0)
        codeCard.addView(caption(tr("tunjukkan kode ini ke pengirim", "show this code to the sender")))
        codeText = textLine("0000", 40f, medium = true, mono = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            paint.isFakeBoldText = true
            applyNeonText(this)
        }
        codeCard.addView(codeText)
        recvState = textLine("", 13f, color = p.text).apply { gravity = Gravity.CENTER }
        codeCard.addView(recvState)
        recvBox.addView(codeCard)
        val infoCard = subCard()
        recvName = kvRow(infoCard, tr("nama hp ini", "this phone's name"))
        recvNet = kvRow(infoCard, tr("jaringan", "network"))
        kvRow(infoCard, tr("simpan ke", "save to")).text =
            if (Build.VERSION.SDK_INT >= 29) "Download/Flashmate" else tr("folder aplikasi", "app folder")
        recvFree = kvRow(infoCard, tr("ruang kosong", "free space"))
        recvBox.addView(infoCard)
        recvBox.addView(neonOutlineBtn(tr("BATAL", "CANCEL")) { onBack() })
        recvPanel.addView(recvBox)
        recvWarn = warnBox("")
        recvWarn.visibility = View.GONE
        recvPanel.addView(recvWarn)
        content.addView(recvPanel)

        // 3. Pengirim: pilih file dan penerima
        sendPanel = panel()
        val sendBox = box()
        val fileCard = subCard()
        fileCard.layoutParams = lp(top = 0)
        fileCard.addView(caption(tr("file yang dikirim", "file to send")))
        fileCard.addView(neonOutlineBtn(tr("PILIH FILE", "CHOOSE FILE")) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            startActivityForResult(i, REQ_PICK_SHARE)
        })
        fileInfo = textLine(tr("belum ada file yang dipilih", "no file chosen yet"), 12f, color = p.muted, mono = true)
        fileInfo.setPadding(0, dp(8), 0, 0)
        fileCard.addView(fileInfo)
        sendBox.addView(fileCard)
        val peerCard = subCard()
        peerCard.addView(caption(tr("penerima ditemukan · ketuk yang kodenya sama", "receivers found · tap the one with the same code")))
        peersEmpty = textLine(tr("mencari penerima...", "looking for receivers..."), 13f, color = p.muted)
        peersEmpty.setPadding(0, dp(6), 0, 0)
        peerCard.addView(peersEmpty)
        peersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        peerCard.addView(peersBox)
        sendNet = textLine("", 12f, color = p.muted)
        sendNet.setPadding(0, dp(8), 0, 0)
        peerCard.addView(sendNet)
        sendBox.addView(peerCard)
        sendPanel.addView(sendBox)
        sendWarn = warnBox("")
        sendWarn.visibility = View.GONE
        sendPanel.addView(sendWarn)
        content.addView(sendPanel)

        // 4. Sedang mengirim atau menerima
        progPanel = panel()
        val progBox = box()
        val progCard = subCard()
        progCard.layoutParams = lp(top = 0)
        progFile = textLine("", 12f, color = p.text, mono = true)
        progCard.addView(progFile)
        val progRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(8), 0, 0)
        }
        progPercent = textLine("100%", 32f, medium = true).apply {
            paint.isFakeBoldText = true
            applyNeonText(this)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        progRow.addView(progPercent)
        progBytes = textLine("", 13f, color = p.text, mono = true)
        progRow.addView(progBytes)
        progCard.addView(progRow)
        progFill = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(NeonTheme.VIOLET, NeonTheme.MAGENTA, NeonTheme.CYAN)
            ).apply { cornerRadius = dp(4).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f)
        }
        progRest = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 100f)
        }
        progBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(p.border, 4, null)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply {
                topMargin = dp(8)
                bottomMargin = dp(6)
            }
            addView(progFill)
            addView(progRest)
        }
        progCard.addView(progBar)
        progSpeed = kvRow(progCard, tr("kecepatan", "speed"))
        progEta = kvRow(progCard, tr("sisa waktu", "time left"))
        progBox.addView(progCard)
        progBox.addView(neonOutlineBtn(tr("BATAL", "CANCEL")) {
            confirm(tr("Batalkan proses ini? File yang belum selesai akan dihapus di hp penerima.",
                "Cancel this transfer? The unfinished file will be deleted on the receiving phone.")) { onBack() }
        })
        progPanel.addView(progBox)
        progPanel.addView(warnBox(
            tr("jangan tutup aplikasi dan jangan jauhkan kedua hp sampai selesai.",
                "do not close the app or move the phones apart until it finishes.")
        ))
        content.addView(progPanel)

        // 5. Hasil
        resultPanel = panel()
        resultBox = box()
        resultPanel.addView(resultBox)
        content.addView(resultPanel)

        setContentView(page(ScrollView(this).apply { addView(content) }, logPanel()))
    }
}
