package com.skypin.flasher

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.NetworkInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

private const val REQ_PICK_SHARE = 1010
private const val REQ_P2P_PERM = 1011

/** Layanan yang diiklankan penerima lewat Wi-Fi Direct. Pengirim mencari layanan dengan jenis ini. */
private const val SHARE_SERVICE_TYPE = "_flashmate._tcp"
/** Isi file dikirim lewat port ini (TCP), di dalam sambungan Wi-Fi Direct. */
private const val PORT_DATA = 47823

private const val SHARE_MAGIC = "FMSHARE1"
private const val REPLY_OK = 1
private const val REPLY_BAD_CODE = 2
private const val REPLY_NO_SPACE = 3
private const val REPLY_REFUSED = 4

/** Ukuran potongan baca dan tulis. Potongan besar membuat kirim file besar lebih cepat. */
private const val SHARE_CHUNK = 1 shl 20
private const val SOCKET_BUFFER = 4 shl 20

/** Batas jumlah file dalam satu kiriman. */
private const val MAX_FILES = 500

/** Berapa lama pengirim menunggu penerima menekan Izinkan di dialog sistem. */
private const val CONNECT_WAIT_SEC = 60L
/** Batas waktu satu perintah Wi-Fi Direct ke sistem. */
private const val P2P_CALL_SEC = 15L
/** Penerima yang tidak terlihat selama ini dihapus dari daftar. */
private const val PEER_TIMEOUT_MS = 30000L
/** Pencarian Wi-Fi Direct dimulai ulang selang waktu ini supaya penerima baru ikut terdaftar. */
private const val PEER_REFRESH_MS = 10000L
/** Selang waktu pengecekan daftar penerima di layar kirim. */
private const val TICK_MS = 4000L

/**
 * Halaman SHARE FILE: kirim atau terima file besar antar dua HP yang memasang Flashmate.
 * Kedua HP tidak perlu satu jaringan Wi-Fi atau hotspot: HP penerima membuat grup Wi-Fi Direct,
 * lalu HP pengirim masuk ke grup itu dan file dikirim langsung antar HP.
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

    /** Penerima yang ditemukan lewat Wi-Fi Direct. [address] adalah alamat perangkat Wi-Fi Direct-nya. */
    private class Peer(val name: String, val code: String, val address: String, var seen: Long)

    private class Target(val out: OutputStream, val where: String, val finish: (Boolean) -> Unit)

    /** Satu file yang dipilih pengirim. */
    private class SendItem(val uri: Uri, val name: String, val size: Long)

    @Volatile private var sess: Sess? = null
    /** File yang dipilih untuk dikirim, sesuai urutan pilihan. */
    private val files = ArrayList<SendItem>()
    /** Penerima yang dipilih di daftar. Null jika belum ada yang dipilih. */
    @Volatile private var selectedPeer: Peer? = null
    private var roleSend = true
    private var pendingDialog: AlertDialog? = null
    private val peers = LinkedHashMap<String, Peer>()
    private var lock: WifiManager.MulticastLock? = null
    /** Tindakan yang ditunda sampai izin lokasi atau NEARBY_WIFI_DEVICES diberikan. */
    private var pendingAfterPermission: (() -> Unit)? = null

    // Wi-Fi Direct
    private var p2p: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastRestart = 0L
    private var lastWarn = ""
    /** Dibuka saat pengirim menunggu penerima menyetujui koneksi, dan dilepas saat koneksi terbentuk. */
    @Volatile private var connectLatch: CountDownLatch? = null
    @Volatile private var connectedInfo: WifiP2pInfo? = null

    private val p2pTick = object : Runnable {
        override fun run() {
            val s = sess ?: return
            if (s.dead || !roleSend) return
            if (!busy) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastRestart >= PEER_REFRESH_MS) {
                    lastRestart = now
                    restartServiceDiscovery()
                }
                val before = peers.size
                peers.values.removeAll { now - it.seen > PEER_TIMEOUT_MS }
                if (peers.size != before) renderPeers()
            }
            mainHandler.postDelayed(this, TICK_MS)
        }
    }

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

    private lateinit var fileSummary: TextView
    private lateinit var fileList: LinearLayout
    private lateinit var sendGo: Button
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

    // ---------- Siklus halaman ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        p2p = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        ensureChannel()
        registerP2pReceiver()
        buildUi()
        showHome()
    }

    override fun onDestroy() {
        cancelSession()
        unregisterP2pReceiver()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_SHARE || resultCode != RESULT_OK) return
        // Bisa banyak file: lewat ClipData, atau satu file lewat data biasa
        val uris = ArrayList<Uri>()
        data?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        }
        if (uris.isEmpty()) data?.data?.let { uris.add(it) }
        for (uri in uris) {
            val (name, size) = try {
                queryNameSize(uri)
            } catch (e: Exception) {
                Pair("file", 0L)
            }
            files.add(SendItem(uri, name, size))
            log(tr("File dipilih: $name (${human(size)})", "File chosen: $name (${human(size)})"))
        }
        renderFiles()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_P2P_PERM) return
        val action = pendingAfterPermission
        pendingAfterPermission = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            action?.invoke()
        } else {
            alert(tr("Izin untuk mencari hp lain ditolak. Tanpa izin ini, Wi-Fi Direct tidak bisa dipakai.",
                "Permission to find other phones was denied. Wi-Fi Direct cannot be used without it."))
        }
    }

    // ---------- Sesi ----------

    /** Membatalkan sesi yang sedang berjalan. Soket ditutup di thread lain supaya layar tidak tersendat. */
    private fun cancelSession() {
        val s = sess ?: return
        sess = null
        s.dead = true
        pendingDialog?.let { d -> runCatching { d.dismiss() } }
        pendingDialog = null
        mainHandler.removeCallbacks(p2pTick)
        connectLatch?.countDown()
        Thread { s.closeAll() }.start()
        teardownP2p()
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
            // Tanpa kunci ini sambungan tetap dicoba
        }
    }

    private fun releaseLock() {
        val l = lock ?: return
        lock = null
        runCatching { if (l.isHeld) l.release() }
    }

    // ---------- Wi-Fi Direct ----------

    /** Menyiapkan saluran Wi-Fi Direct dan pendengar layanan. Mengembalikan null jika perangkat tidak mendukung. */
    private fun ensureChannel(): WifiP2pManager.Channel? {
        p2pChannel?.let { return it }
        val m = p2p ?: return null
        val ch = m.initialize(this, mainLooper, WifiP2pManager.ChannelListener { p2pChannel = null }) ?: return null
        p2pChannel = ch
        m.setDnsSdResponseListeners(
            ch,
            WifiP2pManager.DnsSdServiceResponseListener { _, _, _ -> },
            WifiP2pManager.DnsSdTxtRecordListener { _, record, device -> onTxtRecord(record, device) }
        )
        return ch
    }

    /** Mendengarkan perubahan koneksi Wi-Fi Direct. Dipasang sekali saat halaman dibuka. */
    @Suppress("DEPRECATION")
    private fun registerP2pReceiver() {
        if (p2pReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                if (intent.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                val ni = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                if (ni?.isConnected == true) {
                    val ch = p2pChannel ?: return
                    val m = p2p ?: return
                    m.requestConnectionInfo(ch, WifiP2pManager.ConnectionInfoListener { info -> onConnectionInfo(info) })
                }
            }
        }
        registerReceiver(r, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
        p2pReceiver = r
    }

    private fun unregisterP2pReceiver() {
        val r = p2pReceiver ?: return
        p2pReceiver = null
        runCatching { unregisterReceiver(r) }
    }

    private fun onConnectionInfo(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) return
        connectedInfo = info
        connectLatch?.countDown()
    }

    private fun actionListener(onOk: () -> Unit = {}, onFail: (Int) -> Unit = {}): WifiP2pManager.ActionListener =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onOk()
            override fun onFailure(reason: Int) = onFail(reason)
        }

    private fun p2pReason(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> tr("hp ini tidak mendukung Wi-Fi Direct", "this phone does not support Wi-Fi Direct")
        WifiP2pManager.BUSY -> tr("Wi-Fi Direct sedang sibuk. coba lagi sebentar.", "Wi-Fi Direct is busy. try again shortly.")
        WifiP2pManager.NO_SERVICE_REQUESTS -> tr("pencarian belum siap. coba lagi.", "search is not ready. try again.")
        else -> tr("Wi-Fi Direct gagal (kode $reason). pastikan Wi-Fi menyala.",
            "Wi-Fi Direct failed (code $reason). make sure Wi-Fi is on.")
    }

    private fun warnP2p(msg: String) {
        if (msg == lastWarn) return
        lastWarn = msg
        log(tr("GAGAL: ", "FAILED: ") + msg)
    }

    /**
     * Menjalankan satu perintah Wi-Fi Direct di thread layar lalu menunggu jawabannya.
     * Mengembalikan null jika berhasil, atau pesan gagal. Hanya dipanggil dari thread kerja, bukan dari thread layar.
     */
    private fun p2pCall(call: (WifiP2pManager, WifiP2pManager.Channel, WifiP2pManager.ActionListener) -> Unit): String? {
        val m = p2p ?: return tr("Wi-Fi Direct tidak tersedia di hp ini", "Wi-Fi Direct is not available on this phone")
        val ch = ensureChannel() ?: return tr("Wi-Fi Direct belum siap. coba lagi.", "Wi-Fi Direct is not ready. try again.")
        val latch = CountDownLatch(1)
        val failure = arrayOfNulls<String>(1)
        runOnUiThread {
            call(m, ch, actionListener(
                onOk = { latch.countDown() },
                onFail = { r ->
                    failure[0] = p2pReason(r)
                    latch.countDown()
                }
            ))
        }
        if (!latch.await(P2P_CALL_SEC, TimeUnit.SECONDS)) {
            return tr("Wi-Fi Direct tidak menjawab. coba lagi.", "Wi-Fi Direct did not answer. try again.")
        }
        return failure[0]
    }

    /** Mencari layanan Flashmate dari hp lain. Dipanggil ulang berkala karena pencarian Wi-Fi Direct bisa berhenti sendiri. */
    private fun restartServiceDiscovery() {
        val m = p2p ?: return
        val ch = ensureChannel() ?: return
        // Setiap langkah menunggu langkah sebelumnya selesai, supaya tidak saling mendahului
        val addAndDiscover: () -> Unit = {
            m.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(), actionListener(
                onOk = {
                    m.discoverServices(ch, actionListener(onFail = { r -> warnP2p(p2pReason(r)) }))
                },
                onFail = { r -> warnP2p(p2pReason(r)) }
            ))
        }
        m.clearServiceRequests(ch, actionListener(onOk = addAndDiscover, onFail = { addAndDiscover() }))
    }

    /** Dipanggil saat hp lain menjawab pencarian. Hanya hp yang memasang Flashmate dan sedang menerima yang masuk daftar. */
    private fun onTxtRecord(record: Map<String, String>?, device: WifiP2pDevice?) {
        val s = sess ?: return
        if (!roleSend || !live(s) || device == null || record == null) return
        if (record["fm"] != "1") return
        val code = record["code"] ?: return
        val key = device.deviceAddress ?: return
        val name = (record["name"] ?: device.deviceName ?: "Android").take(40)
        val now = SystemClock.elapsedRealtime()
        val old = peers[key]
        if (old != null && old.code == code && old.name == name) {
            old.seen = now
        } else {
            peers[key] = Peer(name, code, key, now)
            renderPeers()
            log(tr("ditemukan: $name ($code)", "found: $name ($code)"))
        }
    }

    /**
     * Penerima membuat grup Wi-Fi Direct dan mengiklankan kodenya, supaya pengirim bisa menemukannya.
     * Melempar IOException jika salah satu langkah gagal.
     */
    private fun setupReceiverGroup(code: String) {
        p2pCall { m, ch, l -> m.removeGroup(ch, l) }
        val groupErr = p2pCall { m, ch, l -> m.createGroup(ch, l) }
        if (groupErr != null) throw IOException(groupErr)
        val txt = hashMapOf("fm" to "1", "code" to code, "name" to deviceName())
        val service = WifiP2pDnsSdServiceInfo.newInstance("flashmate$code", SHARE_SERVICE_TYPE, txt)
        p2pCall { m, ch, l -> m.clearLocalServices(ch, l) }
        val svcErr = p2pCall { m, ch, l -> m.addLocalService(ch, service, l) }
        if (svcErr != null) throw IOException(svcErr)
        p2pCall { m, ch, l -> m.discoverPeers(ch, l) }
    }

    /** Membuat sambungan Wi-Fi Direct ke hp penerima dan mengembalikan alamat hp penerima di dalam grup itu. Melempar IOException jika gagal. */
    private fun connectWifiDirect(s: Sess, peer: Peer): InetAddress {
        p2pCall { m, ch, l -> m.removeGroup(ch, l) }
        connectedInfo = null
        val armed = CountDownLatch(1)
        connectLatch = armed
        val config = WifiP2pConfig().apply {
            deviceAddress = peer.address
            // Pengirim minta jadi anggota grup, supaya hp penerima jadi pemilik grup dan alamatnya tetap
            groupOwnerIntent = 0
        }
        val err = p2pCall { m, ch, l -> m.connect(ch, config, l) }
        if (err != null) {
            connectLatch = null
            throw IOException(err)
        }
        log(tr("menunggu ${peer.name} menyetujui koneksi...", "waiting for ${peer.name} to accept the connection..."))
        val got = armed.await(CONNECT_WAIT_SEC, TimeUnit.SECONDS)
        connectLatch = null
        if (s.dead) throw IOException(tr("dibatalkan", "cancelled"))
        if (!got) {
            throw IOException(tr("${peer.name} tidak menyetujui koneksi dalam $CONNECT_WAIT_SEC detik",
                "${peer.name} did not accept the connection within $CONNECT_WAIT_SEC seconds"))
        }
        val info = connectedInfo ?: throw IOException(tr("koneksi Wi-Fi Direct tidak terbentuk",
            "the Wi-Fi Direct connection was not formed"))
        if (info.isGroupOwner) {
            throw IOException(tr("hp ini jadi pemilik grup, bukan anggota. coba kirim lagi.",
                "this phone became the group owner instead of a member. try sending again."))
        }
        return info.groupOwnerAddress
            ?: throw IOException(tr("alamat hp penerima tidak terbaca", "the receiver's address could not be read"))
    }

    /** Melepas grup, layanan, dan pencarian Wi-Fi Direct supaya Wi-Fi kembali normal setelah sesi selesai. */
    private fun teardownP2p() {
        val m = p2p ?: return
        val ch = p2pChannel ?: return
        runOnUiThread {
            runCatching { m.clearServiceRequests(ch, null) }
            runCatching { m.clearLocalServices(ch, null) }
            runCatching { m.stopPeerDiscovery(ch, null) }
            runCatching { m.removeGroup(ch, null) }
        }
    }

    private fun p2pPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) "android.permission.NEARBY_WIFI_DEVICES" else "android.permission.ACCESS_FINE_LOCATION"

    /** Meminta izin yang dibutuhkan Wi-Fi Direct, lalu menjalankan [action] jika sudah diberikan. */
    private fun withP2pPermission(action: () -> Unit) {
        val perm = p2pPermission()
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            action()
            return
        }
        pendingAfterPermission = action
        requestPermissions(arrayOf(perm), REQ_P2P_PERM)
    }

    @Suppress("DEPRECATION")
    private fun wifiOn(): Boolean = try {
        (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
    } catch (e: Exception) {
        false
    }

    private fun refreshNet() {
        val on = wifiOn()
        val text = if (on) tr("Wi-Fi Direct siap", "Wi-Fi Direct ready") else tr("Wi-Fi belum menyala", "Wi-Fi is off")
        recvNet.text = text
        sendNet.text = text
        val warn = if (on) {
            null
        } else {
            tr("nyalakan Wi-Fi dulu. Wi-Fi Direct tidak bisa jalan saat Wi-Fi mati.",
                "turn on Wi-Fi first. Wi-Fi Direct does not work while Wi-Fi is off.")
        }
        for (w in listOf(recvWarn, sendWarn)) {
            w.text = warn ?: ""
            w.visibility = if (warn != null) View.VISIBLE else View.GONE
        }
    }

    private fun deviceName(): String {
        val n = try {
            Settings.Global.getString(contentResolver, "device_name")
        } catch (e: Exception) {
            null
        }
        val name = if (n.isNullOrBlank()) Build.MODEL ?: "Android" else n
        return name.replace("|", " ").trim().take(40).ifBlank { "Android" }
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
        withP2pPermission { startReceiveNow() }
    }

    private fun startReceiveNow() {
        cancelSession()
        val s = Sess()
        sess = s
        roleSend = false
        val code = (1000 + (Math.random() * 9000).toInt()).coerceIn(1000, 9999).toString()
        codeText.text = code
        recvState.text = tr("menyiapkan Wi-Fi Direct...", "setting up Wi-Fi Direct...")
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
        try {
            val server = s.own(ServerSocket())
            server.reuseAddress = true
            server.receiveBufferSize = SOCKET_BUFFER
            server.bind(InetSocketAddress(PORT_DATA))

            log(tr("menyiapkan grup Wi-Fi Direct...", "setting up a Wi-Fi Direct group..."))
            setupReceiverGroup(code)
            log(tr("siap menerima, kode $code", "ready to receive, code $code"))
            ui(s) { recvState.text = tr("menunggu pengirim...", "waiting for sender...") }

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
            teardownP2p()
            runOnUiThread { if (sess === s) releaseLock() }
        }
    }

    private class Incoming(
        val inp: DataInputStream,
        val out: DataOutputStream,
        val sender: String,
        val names: List<String>,
        val sizes: List<Long>
    )

    /** Membaca pembuka sambungan masuk (nama dan ukuran semua file), lalu meminta persetujuan sekali untuk semua. Null jika sambungan ini tidak dilanjutkan. */
    private fun readIncoming(s: Sess, sock: Socket, code: String): Incoming? {
        try {
            sock.soTimeout = 15000
            val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 1 shl 16))
            val out = DataOutputStream(sock.getOutputStream())
            if (inp.readUTF() != SHARE_MAGIC) return null
            val theirCode = inp.readUTF()
            val sender = inp.readUTF().take(40)
            val count = inp.readInt()
            if (count !in 1..MAX_FILES) return null
            val names = ArrayList<String>()
            val sizes = ArrayList<Long>()
            for (i in 0 until count) {
                names.add(cleanName(inp.readUTF()))
                sizes.add(inp.readLong())
            }
            if (theirCode != code) {
                out.writeByte(REPLY_BAD_CODE)
                out.flush()
                log(tr("sambungan ditolak: kode tidak sama", "connection rejected: code does not match"))
                return null
            }
            if (sizes.any { it <= 0L }) return null
            val total = sizes.sum()
            val free = freeSpace()
            if (free in 0L until total) {
                out.writeByte(REPLY_NO_SPACE)
                out.flush()
                log(tr("ruang kosong tidak cukup untuk ${names.size} file (${human(total)})",
                    "not enough free space for ${names.size} files (${human(total)})"))
                return null
            }
            if (!askAccept(s, sender, names, sizes)) {
                out.writeByte(REPLY_REFUSED)
                out.flush()
                log(tr("kiriman dari $sender ditolak", "transfer from $sender refused"))
                return null
            }
            return Incoming(inp, out, sender, names, sizes)
        } catch (e: Exception) {
            if (s.dead) throw e
            // Sambungan yang bukan dari Flashmate atau putus sebelum mulai: abaikan, tetap menunggu
            return null
        }
    }

    /** Menerima semua file dalam satu sambungan. True jika sambungan ini sudah selesai. Gagal sebelum file pertama masuk melempar kesalahan. */
    private fun handleIncoming(s: Sess, sock: Socket, code: String): Boolean {
        val req = readIncoming(s, sock, code) ?: return false
        val inp = req.inp
        val out = req.out
        val sender = req.sender
        val count = req.names.size
        val total = req.sizes.sum()

        // Dari sini file benar-benar diterima
        log(tr("menerima $count file (${human(total)}) dari $sender", "receiving $count files (${human(total)}) from $sender"))
        ui(s) {
            titleText.text = tr("TERIMA FILE", "RECEIVE FILE")
            subText.text = tr("menerima dari $sender", "receiving from $sender")
            progFile.text = if (count == 1) req.names[0] else tr("$count file", "$count files")
            showProgress(0L, total, 0L, -1L)
            show(progPanel)
        }
        val okFlags = BooleanArray(count)
        val meter = Meter(s, total)
        val buf = ByteArray(SHARE_CHUNK)
        var base = 0L
        var whereSaved = ""
        var lost: Exception? = null
        try {
            sock.soTimeout = 30000
            out.writeByte(REPLY_OK)
            out.flush()
            for (i in 0 until count) {
                if (s.dead) throw IOException(tr("dibatalkan", "cancelled"))
                val name = req.names[i]
                val size = req.sizes[i]
                log(tr("menerima (${i + 1}/$count) $name", "receiving (${i + 1}/$count) $name"))
                ui(s) { progFile.text = "(${i + 1}/$count) $name" }
                var dst: Target? = null
                var werr: Exception? = null
                try {
                    val opened = openTarget(name)
                    dst = opened
                    whereSaved = opened.where
                } catch (e: Exception) {
                    werr = e
                }
                val crc = CRC32()
                var got = 0L
                try {
                    while (got < size) {
                        if (s.dead) throw IOException(tr("dibatalkan", "cancelled"))
                        val want = minOf(buf.size.toLong(), size - got).toInt()
                        val n = inp.read(buf, 0, want)
                        if (n < 0) throw IOException(tr("sambungan terputus sebelum file selesai", "connection lost before the file finished"))
                        val target = dst
                        if (werr == null && target != null) {
                            try {
                                target.out.write(buf, 0, n)
                            } catch (e: Exception) {
                                werr = e
                            }
                        }
                        // Kalau tulisan gagal, data tetap dibaca sampai habis supaya urutan kiriman tidak rusak
                        crc.update(buf, 0, n)
                        got += n
                        meter.tick(base + got)
                    }
                } catch (e: Exception) {
                    // File yang belum utuh harus dibuang, supaya tidak tertinggal dan terpakai untuk flash
                    dst?.let { t ->
                        runCatching { t.out.close() }
                        runCatching { t.finish(false) }
                    }
                    throw e
                }
                val theirs = inp.readLong()
                val fin = dst
                if (fin != null) {
                    if (werr == null) {
                        try {
                            fin.out.flush()
                        } catch (e: Exception) {
                            werr = e
                        }
                    }
                    runCatching { fin.out.close() }
                }
                val ok = werr == null && theirs == crc.value
                if (fin != null) runCatching { fin.finish(ok) }
                okFlags[i] = ok
                base += size
                if (ok) {
                    log(tr("cek isi file: cocok ($name)", "file content check: match ($name)"))
                } else {
                    val why = werr?.let { errText(it) }
                        ?: tr("isi tidak sama dengan kiriman (CRC berbeda)", "contents differ from what was sent (CRC mismatch)")
                    log(tr("GAGAL: $name, $why", "FAILED: $name, $why"))
                }
                out.writeByte(if (ok) 1 else 0)
                out.flush()
            }
        } catch (e: Exception) {
            if (s.dead) throw e
            lost = e
        }

        val sec = meter.seconds()
        val okCount = okFlags.count { it }
        val err = lost
        if (err != null) {
            log(tr("GAGAL: ", "FAILED: ") + errText(err))
            if (okCount == 0) throw err
        }
        log(tr("selesai dalam ${duration(sec)}: $okCount dari $count file diterima",
            "finished in ${duration(sec)}: $okCount of $count files received"))
        if (whereSaved.isNotEmpty()) log(tr("disimpan di $whereSaved", "saved to $whereSaved"))
        ui(s) {
            if (okCount == count) {
                showSuccess(
                    tr("Terima Berhasil", "Receive Successful"),
                    if (count == 1) req.names[0] else tr("$count file", "$count files"),
                    total, sec,
                    tr("disimpan di", "saved to"), whereSaved,
                    tr("TERIMA FILE LAIN", "RECEIVE ANOTHER FILE")
                )
            } else {
                showPartial(
                    tr("Sebagian gagal", "Partly failed"),
                    okCount, count,
                    req.names.filterIndexed { i, _ -> !okFlags[i] },
                    sender,
                    tr("TERIMA FILE LAIN", "RECEIVE ANOTHER FILE")
                )
            }
        }
        return true
    }

    /** Menanyakan ke pengguna apakah kiriman diterima. Satu jawaban berlaku untuk semua file. Menunggu sampai dijawab atau sesi dibatalkan. */
    private fun askAccept(s: Sess, sender: String, names: List<String>, sizes: List<Long>): Boolean {
        val total = sizes.sum()
        val list = names.take(6).mapIndexed { i, n -> "$n (${human(sizes[i])})" }.joinToString("\n")
        val more = if (names.size > 6) {
            tr("\ndan ${names.size - 6} file lainnya", "\nand ${names.size - 6} more files")
        } else {
            ""
        }
        val head = if (names.size == 1) {
            tr("Terima file ini dari $sender?", "Accept this file from $sender?")
        } else {
            tr("Terima ${names.size} file (${human(total)}) dari $sender?",
                "Accept ${names.size} files (${human(total)}) from $sender?")
        }
        val latch = CountDownLatch(1)
        val yes = AtomicBoolean(false)
        runOnUiThread {
            if (!live(s) || isFinishing) {
                latch.countDown()
                return@runOnUiThread
            }
            pendingDialog = AlertDialog.Builder(this)
                .setMessage("$head\n\n$list$more")
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
        withP2pPermission { startSendNow() }
    }

    private fun startSendNow() {
        cancelSession()
        val s = Sess()
        sess = s
        roleSend = true
        peers.clear()
        selectedPeer = null
        renderPeers()
        refreshNet()
        titleText.text = tr("KIRIM FILE", "SEND FILE")
        subText.text = tr("pilih file, lalu pilih hp penerima", "choose a file, then choose the receiving phone")
        show(sendPanel)
        acquireLock()
        log("== " + tr("KIRIM FILE", "SEND FILE") + " ==")
        log(tr("mencari penerima lewat Wi-Fi Direct...", "looking for receivers over Wi-Fi Direct..."))
        lastRestart = 0L
        mainHandler.post(p2pTick)
    }

    private fun renderPeers() {
        val p = pal()
        // Penerima yang sudah tidak terlihat dilepas dari pilihan
        selectedPeer?.let { sp ->
            if (peers.values.none { it.address == sp.address && it.code == sp.code }) selectedPeer = null
        }
        peersBox.removeAllViews()
        peersEmpty.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
        for (peer in peers.values) {
            val picked = selectedPeer?.let { it.address == peer.address && it.code == peer.code } == true
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
            col.addView(textLine(
                if (picked) tr("dipilih · tekan KIRIM", "selected · press SEND") else tr("Wi-Fi Direct · siap", "Wi-Fi Direct · ready"),
                12f,
                color = if (picked) p.accent else p.muted
            ))
            row.addView(col)
            row.addView(textLine(peer.code, 16f, medium = true, color = p.accent, mono = true))
            peersBox.addView(row)
        }
        updateSendButton()
    }

    /** Mengingat penerima yang dipilih. Pengiriman baru dimulai saat tombol KIRIM ditekan. */
    private fun onPickPeer(peer: Peer) {
        if (sess == null) return
        selectedPeer = peer
        renderPeers()
    }

    /** Daftar file yang dipilih: nama, ukuran, tombol HAPUS, serta ringkasan jumlah dan total ukuran. */
    private fun renderFiles() {
        val p = pal()
        fileList.removeAllViews()
        for ((i, f) in files.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(textLine(f.name, 12f, color = p.text, mono = true))
            col.addView(textLine(human(f.size), 11f, color = p.muted))
            row.addView(col)
            row.addView(textLine(tr("HAPUS", "REMOVE"), 11f, medium = true, color = p.danger).apply {
                setPadding(dp(8), dp(4), 0, dp(4))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (i < files.size) files.removeAt(i)
                    renderFiles()
                }
            })
            fileList.addView(row)
        }
        val total = files.sumOf { it.size }
        fileSummary.text = if (files.isEmpty()) {
            tr("belum ada file dipilih", "no file chosen yet")
        } else {
            tr("${files.size} file · ${human(total)}", "${files.size} files · ${human(total)}")
        }
        updateSendButton()
    }

    private fun updateSendButton() {
        val n = files.size
        val on = n > 0 && selectedPeer != null
        sendGo.text = tr("KIRIM $n FILE", "SEND $n FILES")
        sendGo.isEnabled = on
        sendGo.alpha = if (on) 1f else 0.4f
    }

    /** Tombol KIRIM: cek file dan penerima, lalu konfirmasi dan mulai mengirim semua file yang dipilih. */
    private fun sendSelected() {
        val s = sess ?: return
        val peer = selectedPeer ?: return
        if (!wifiOn()) {
            alert(tr("Nyalakan Wi-Fi dulu. Wi-Fi Direct tidak bisa jalan saat Wi-Fi mati.",
                "Turn on Wi-Fi first. Wi-Fi Direct does not work while Wi-Fi is off."))
            return
        }
        if (files.isEmpty()) {
            alert(tr("Pilih file yang mau dikirim dulu.", "Choose the files to send first."))
            return
        }
        for (f in files) {
            if (f.size <= 0L) {
                alert(tr("Ukuran ${f.name} tidak terbaca. Hapus file itu, lalu pilih ulang.",
                    "The size of ${f.name} could not be read. Remove it, then choose it again."))
                return
            }
            // Semua file dicek bisa dibaca dulu, supaya tidak ada file yang putus di tengah sambungan
            val readable = try {
                contentResolver.openInputStream(f.uri)?.use { true } ?: false
            } catch (e: Exception) {
                false
            }
            if (!readable) {
                alert(tr("Tidak bisa membaca ${f.name}. Hapus file itu, lalu pilih ulang.",
                    "Cannot read ${f.name}. Remove it, then choose it again."))
                return
            }
        }
        if (busy) {
            Toast.makeText(this, tr("Tunggu proses sebelumnya berhenti dulu.", "Wait for the previous task to stop."), Toast.LENGTH_SHORT).show()
            return
        }
        val batch = ArrayList(files)
        val n = batch.size
        val total = batch.sumOf { it.size }
        confirm(tr("Kirim $n file (${human(total)}) ke ${peer.name} (kode ${peer.code})?",
            "Send $n files (${human(total)}) to ${peer.name} (code ${peer.code})?")) {
            if (!live(s) || busy) return@confirm
            subText.text = tr("menunggu ${peer.name} menerima...", "waiting for ${peer.name} to accept...")
            runTask(tr("KIRIM FILE", "SEND FILE")) { connectAndSend(s, peer, batch) }
        }
    }

    /** Menyambung ke hp penerima lewat Wi-Fi Direct, lalu mengirim semua file. Jika sambungan gagal, pesannya ditampilkan. */
    private fun connectAndSend(s: Sess, peer: Peer, batch: List<SendItem>) {
        val host = try {
            connectWifiDirect(s, peer)
        } catch (e: Exception) {
            val msg = errText(e)
            log(tr("GAGAL: ", "FAILED: ") + msg)
            ui(s) { showFailure(msg) }
            s.closeAll()
            teardownP2p()
            runOnUiThread { if (sess === s) releaseLock() }
            return
        }
        sendSession(s, peer, host, batch)
    }

    /** Membuka sambungan data ke hp penerima. Beberapa kali dicoba, karena grup Wi-Fi Direct baru kadang belum siap seketika. */
    private fun openDataSocket(s: Sess, host: InetAddress): Socket {
        var last: IOException? = null
        for (attempt in 1..4) {
            if (s.dead) throw IOException(tr("dibatalkan", "cancelled"))
            val sock = s.own(Socket())
            try {
                sock.sendBufferSize = SOCKET_BUFFER
                sock.connect(InetSocketAddress(host, PORT_DATA), 5000)
                return sock
            } catch (e: IOException) {
                last = e
                runCatching { sock.close() }
                Thread.sleep(1000L)
            }
        }
        throw last ?: IOException(tr("tidak bisa tersambung ke hp penerima", "cannot connect to the receiving phone"))
    }

    /** Mengirim semua file dalam [batch] ke [peer] lewat satu sambungan. Satu file yang gagal tidak menghentikan file berikutnya. */
    private fun sendSession(s: Sess, peer: Peer, host: InetAddress, batch: List<SendItem>) {
        val total = batch.sumOf { it.size }
        val okFlags = BooleanArray(batch.size)
        try {
            log(tr("menyambung ke ${peer.name}...", "connecting to ${peer.name}..."))
            val sock = openDataSocket(s, host)
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 1 shl 16))
            val inp = DataInputStream(sock.getInputStream())
            out.writeUTF(SHARE_MAGIC)
            out.writeUTF(peer.code)
            out.writeUTF(deviceName())
            out.writeInt(batch.size)
            for (f in batch) {
                out.writeUTF(f.name.take(200))
                out.writeLong(f.size)
            }
            out.flush()

            // Penerima perlu waktu untuk menekan Terima (sekali untuk semua file)
            sock.soTimeout = 120000
            when (inp.readByte().toInt()) {
                REPLY_OK -> {}
                REPLY_BAD_CODE -> throw IOException(tr("kode tidak sama dengan hp penerima", "code does not match the receiving phone"))
                REPLY_NO_SPACE -> throw IOException(tr("ruang kosong di hp penerima tidak cukup", "not enough free space on the receiving phone"))
                REPLY_REFUSED -> throw IOException(tr("hp penerima menolak kiriman", "the receiving phone refused the transfer"))
                else -> throw IOException(tr("jawaban hp penerima tidak dikenal", "unknown reply from the receiving phone"))
            }

            log(tr("tersambung, mengirim ${batch.size} file (${human(total)})...",
                "connected, sending ${batch.size} files (${human(total)})..."))
            ui(s) {
                subText.text = tr("mengirim ke ${peer.name}", "sending to ${peer.name}")
                progFile.text = if (batch.size == 1) batch[0].name else tr("${batch.size} file", "${batch.size} files")
                showProgress(0L, total, 0L, -1L)
                show(progPanel)
            }
            val meter = Meter(s, total)
            val buf = ByteArray(SHARE_CHUNK)
            var base = 0L
            for ((i, f) in batch.withIndex()) {
                if (s.dead) throw IOException(tr("dibatalkan", "cancelled"))
                log(tr("mengirim (${i + 1}/${batch.size}) ${f.name}", "sending (${i + 1}/${batch.size}) ${f.name}"))
                ui(s) { progFile.text = "(${i + 1}/${batch.size}) ${f.name}" }
                val crc = CRC32()
                var sent = 0L
                val src = contentResolver.openInputStream(f.uri)
                    ?: throw IOException(tr("tidak bisa membuka ${f.name}", "cannot open ${f.name}"))
                src.use { input ->
                    while (sent < f.size) {
                        if (s.dead) throw IOException(tr("dibatalkan", "cancelled"))
                        val want = minOf(buf.size.toLong(), f.size - sent).toInt()
                        val n = input.read(buf, 0, want)
                        if (n < 0) throw IOException(tr("${f.name} berakhir sebelum ukurannya tercapai",
                            "${f.name} ended before its stated size"))
                        out.write(buf, 0, n)
                        crc.update(buf, 0, n)
                        sent += n
                        meter.tick(base + sent)
                    }
                }
                out.writeLong(crc.value)
                out.flush()
                // Penerima menyelesaikan tulisan file ini, lalu membandingkan CRC dan mengirim hasilnya
                sock.soTimeout = 180000
                val verdict = inp.readByte().toInt()
                base += f.size
                okFlags[i] = verdict == 1
                log(
                    if (verdict == 1) tr("cocok: ${f.name}", "match: ${f.name}")
                    else tr("GAGAL: isi ${f.name} di hp penerima tidak sama", "FAILED: ${f.name} differs on the receiving phone")
                )
            }
            val sec = meter.seconds()
            val okCount = okFlags.count { it }
            log(tr("selesai dalam ${duration(sec)}: $okCount dari ${batch.size} file berhasil",
                "finished in ${duration(sec)}: $okCount of ${batch.size} files succeeded"))
            dropSent(batch, okFlags)
            ui(s) {
                if (okCount == batch.size) {
                    showSuccess(
                        tr("Kirim Berhasil", "Send Successful"),
                        if (batch.size == 1) batch[0].name else tr("${batch.size} file", "${batch.size} files"),
                        total, sec,
                        tr("penerima", "receiver"), peer.name,
                        tr("KIRIM FILE LAIN", "SEND ANOTHER FILE")
                    )
                } else {
                    showPartial(
                        tr("Sebagian gagal", "Partly failed"),
                        okCount, batch.size,
                        batch.filterIndexed { i, _ -> !okFlags[i] }.map { it.name },
                        peer.name,
                        tr("KIRIM ULANG YANG GAGAL", "RESEND FAILED")
                    )
                }
            }
        } catch (e: Exception) {
            if (live(s)) {
                val msg = errText(e)
                val okCount = okFlags.count { it }
                dropSent(batch, okFlags)
                log(tr("GAGAL: ", "FAILED: ") + msg)
                val note = if (okCount > 0) {
                    tr(" $okCount file sudah terkirim dan dihapus dari daftar.", " $okCount files were sent and removed from the list.")
                } else {
                    ""
                }
                ui(s) { showFailure(msg + note) }
            } else {
                log(tr("dibatalkan", "cancelled"))
            }
        } finally {
            s.closeAll()
            teardownP2p()
            runOnUiThread { if (sess === s) releaseLock() }
        }
    }

    /** Menghapus dari daftar file yang sudah berhasil, supaya kirim ulang hanya mengirim sisanya. */
    private fun dropSent(batch: List<SendItem>, okFlags: BooleanArray) {
        val done = batch.filterIndexed { i, _ -> okFlags[i] }.toSet()
        runOnUiThread {
            files.removeAll(done)
            renderFiles()
        }
    }

    // ---------- Tampilan ----------

    /** Tombol KIRIM FILE dan TERIMA FILE di halaman utama. Gaya dan ketebalannya sama dengan tombol utama lain. */
    private fun homeButton(label: String, onClick: () -> Unit): Button = btn(label, BtnStyle.PRIMARY, onClick)

    private fun show(panel: View) {
        for (v in listOf(homePanel, recvPanel, sendPanel, progPanel, resultPanel)) {
            v.visibility = if (v === panel) View.VISIBLE else View.GONE
        }
    }

    private fun showHome() {
        titleText.text = "SHARE FILE"
        subText.text = tr("kirim file besar antar hp lewat Wi-Fi Direct", "send large files between phones over Wi-Fi Direct")
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

    /** Hasil saat sebagian file berhasil. Daftar file yang gagal ditampilkan supaya bisa dicoba lagi. */
    private fun showPartial(head: String, okCount: Int, total: Int, failedNames: List<String>, who: String, button: String) {
        val p = pal()
        resultBox.removeAllViews()
        resultBox.addView(textLine(head, 16f, medium = true, color = p.danger))
        resultBox.addView(textLine(
            tr("$okCount dari $total file berhasil. hp lawan: $who", "$okCount of $total files succeeded. other phone: $who"),
            13f,
            color = p.text
        ).apply { setPadding(0, dp(6), 0, dp(4)) })
        resultBox.addView(textLine(tr("yang gagal:", "failed:"), 12f, medium = true, color = p.muted))
        for (n in failedNames.take(10)) {
            resultBox.addView(textLine(n, 12f, color = p.danger, mono = true))
        }
        if (failedNames.size > 10) {
            resultBox.addView(textLine(
                tr("dan ${failedNames.size - 10} file lainnya", "and ${failedNames.size - 10} more files"),
                12f,
                color = p.muted
            ))
        }
        resultBox.addView(btn(button, BtnStyle.PRIMARY) { again() })
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

    /** Catatan Wi-Fi di halaman pilihan peran. Di Android 12 ke bawah, lokasi juga harus menyala. */
    private fun wifiNote(): String {
        val base = tr("wi-fi harus menyala. internet wi-fi terputus selama kirim. tidak perlu satu jaringan yang sama.",
            "Wi-Fi must be on. Internet over Wi-Fi drops during a transfer. both phones do not need the same network.")
        if (Build.VERSION.SDK_INT >= 33) return base
        return base + " " + tr("di Android 12 ke bawah, lokasi (GPS) juga harus menyala.",
            "on Android 12 and older, location (GPS) must be on too.")
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }

    private fun buildUi() {
        val p = pal()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }

        // Jarak atas dibuat kecil supaya isi halaman naik dan log bisa lebih tinggi
        val back = backButton { onBack() }
        back.minimumHeight = dp(32)
        back.setPadding(dp(4), 0, dp(12), 0)
        back.layoutParams = lp(top = 0)
        content.addView(back)
        titleText = textLine("SHARE FILE", 22f, medium = true).apply {
            applyNeonText(this)
        }
        content.addView(titleText)
        subText = caption("")
        content.addView(subText)

        // 1. Pilihan peran
        homePanel = panel()
        val homeBox = box()
        homeBox.addView(caption(tr("pilih peran hp ini", "choose this phone's role")))
        homeBox.addView(homeButton(tr("KIRIM FILE", "SEND FILE")) { startSend() })
        homeBox.addView(caption(tr("hp ini mengirim file ke hp lain", "this phone sends a file to another phone")))
        homeBox.addView(homeButton(tr("TERIMA FILE", "RECEIVE FILE")) { startReceive() })
        homeBox.addView(caption(tr("hp ini menunggu kiriman dari hp lain", "this phone waits for a file from another phone")))
        homePanel.addView(homeBox)
        homePanel.addView(warnBox(wifiNote()))
        content.addView(homePanel)

        // 2. Penerima menunggu
        recvPanel = panel()
        val recvBox = box()
        val codeCard = subCard()
        codeCard.layoutParams = lp(top = 0)
        codeCard.addView(caption(tr("tunjukkan kode ini ke pengirim", "show this code to the sender")))
        codeText = textLine("0000", 40f, medium = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            applyNeonText(this)
        }
        codeCard.addView(codeText)
        recvState = textLine("", 13f, color = p.text).apply { gravity = Gravity.CENTER }
        codeCard.addView(recvState)
        recvBox.addView(codeCard)
        val infoCard = subCard()
        recvName = kvRow(infoCard, tr("nama hp ini", "this phone's name"))
        recvNet = kvRow(infoCard, tr("wi-fi direct", "wi-fi direct"))
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

        // 3. Pengirim: pilih file, pilih penerima, lalu kirim
        sendPanel = panel()
        val sendBox = box()
        val fileCard = subCard()
        fileCard.layoutParams = lp(top = 0)
        fileCard.addView(caption(tr("file yang dikirim", "files to send")))
        fileSummary = textLine("", 13f, medium = true, color = p.text)
        fileSummary.setPadding(0, dp(4), 0, 0)
        fileCard.addView(fileSummary)
        fileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fileCard.addView(fileList)
        fileCard.addView(neonOutlineBtn(tr("TAMBAH FILE", "ADD FILES")) {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            startActivityForResult(i, REQ_PICK_SHARE)
        })
        fileCard.addView(neonOutlineBtn(tr("KOSONGKAN", "CLEAR")) {
            files.clear()
            renderFiles()
        })
        sendBox.addView(fileCard)
        val peerCard = subCard()
        peerCard.addView(caption(tr("penerima di sekitar (Wi-Fi Direct) · ketuk yang kodenya sama",
            "nearby receivers (Wi-Fi Direct) · tap the one with the same code")))
        peersEmpty = textLine(tr("mencari penerima...", "looking for receivers..."), 13f, color = p.muted)
        peersEmpty.setPadding(0, dp(6), 0, 0)
        peerCard.addView(peersEmpty)
        peersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        peerCard.addView(peersBox)
        sendNet = textLine("", 12f, color = p.muted)
        sendNet.setPadding(0, dp(8), 0, 0)
        peerCard.addView(sendNet)
        sendBox.addView(peerCard)
        sendGo = btn(tr("KIRIM 0 FILE", "SEND 0 FILES"), BtnStyle.PRIMARY) { sendSelected() }
        sendGo.layoutParams = lp(top = 10)
        sendBox.addView(sendGo)
        sendPanel.addView(sendBox)
        sendWarn = warnBox("")
        sendWarn.visibility = View.GONE
        sendPanel.addView(sendWarn)
        content.addView(sendPanel)
        renderFiles()

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

        setContentView(logPage(ScrollView(this).apply { addView(content) }))
    }
}
