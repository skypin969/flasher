package com.skypin.flasher

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors

private const val READ_TIMEOUT = 10000
private const val DATA_TIMEOUT = 60000
private const val FLASH_TIMEOUT = 300000
private const val CHUNK = 16384

class FastbootException(msg: String) : RuntimeException(msg)

class Fastboot(private val conn: UsbDeviceConnection, private val iface: UsbInterface) {

    private class Resp(val code: String, val text: String)

    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint

    init {
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (k in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(k)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
            }
        }
        epIn = inEp ?: throw FastbootException("Endpoint IN tidak ditemukan")
        epOut = outEp ?: throw FastbootException("Endpoint OUT tidak ditemukan")
        if (!conn.claimInterface(iface, true)) throw FastbootException("Gagal mengklaim interface USB")
    }

    fun close() {
        try {
            conn.releaseInterface(iface)
        } catch (e: Exception) {
            // abaikan
        }
        conn.close()
    }

    private fun writeAll(buf: ByteArray, n: Int, timeout: Int) {
        val part = if (n == buf.size) buf else buf.copyOf(n)
        val w = conn.bulkTransfer(epOut, part, n, timeout)
        if (w != n) throw FastbootException("Gagal mengirim ke HP (terkirim $w dari $n byte)")
    }

    private fun readResp(timeout: Int): Resp {
        val buf = ByteArray(64)
        val info = StringBuilder()
        while (true) {
            val n = conn.bulkTransfer(epIn, buf, buf.size, timeout)
            if (n < 0) throw FastbootException("Tidak ada balasan dari HP (timeout atau kabel OTG terlepas)")
            if (n == 0) continue
            val s = String(buf, 0, n, Charsets.US_ASCII)
            if (s.startsWith("INFO")) {
                info.append(s.drop(4)).append('\n')
            } else {
                return Resp(s.take(4), (info.toString() + s.drop(4)).trim())
            }
        }
    }

    private fun expectOkay(r: Resp, what: String): String = when (r.code) {
        "OKAY" -> r.text
        "FAIL" -> throw FastbootException(if (r.text.isEmpty()) "HP menolak: $what" else "HP menolak: ${r.text}")
        else -> throw FastbootException("Balasan tidak terduga (${r.code}) untuk: $what")
    }

    fun command(cmd: String, timeout: Int = READ_TIMEOUT): String {
        val bytes = cmd.toByteArray(Charsets.US_ASCII)
        writeAll(bytes, bytes.size, timeout)
        return expectOkay(readResp(timeout), cmd)
    }

    fun getvar(name: String): String = command("getvar:$name").lines().last().trim()

    fun download(stream: InputStream, size: Long, onProgress: (Long) -> Unit) {
        val header = String.format("download:%08x", size).toByteArray(Charsets.US_ASCII)
        writeAll(header, header.size, READ_TIMEOUT)
        val r = readResp(READ_TIMEOUT)
        if (r.code == "FAIL") throw FastbootException(r.text)
        if (r.code != "DATA") throw FastbootException("Balasan download tidak terduga: ${r.code}")

        val buf = ByteArray(CHUNK)
        var sent = 0L
        while (sent < size) {
            val want = minOf(CHUNK.toLong(), size - sent).toInt()
            var got = 0
            while (got < want) {
                val k = stream.read(buf, got, want - got)
                if (k < 0) throw IOException("File berakhir sebelum selesai dibaca")
                got += k
            }
            writeAll(buf, want, DATA_TIMEOUT)
            sent += want
            onProgress(sent)
        }
        expectOkay(readResp(DATA_TIMEOUT), "data")
    }
}

private fun parseHex(s: String?): Long? =
    s?.trim()?.lowercase()?.removePrefix("0x")?.toLongOrNull(16)

private fun human(b: Long): String = when {
    b >= (1L shl 30) -> "%.2f GB".format(b / 1073741824.0)
    b >= (1L shl 20) -> "%.2f MB".format(b / 1048576.0)
    b >= (1L shl 10) -> "%.2f KB".format(b / 1024.0)
    else -> "$b B"
}

private fun slotLabel(slot: String): String = if (slot.isEmpty()) "tidak ada (bukan A/B)" else slot

class MainActivity : Activity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.skypin.flasher.USB_PERMISSION"
        private const val REQ_PICK_IMG = 1001
    }

    // Data HP target dari hasil "Cek HP target". Semua flash dicocokkan dengan data ini.
    private class TargetInfo(val product: String, val serial: String, val unlocked: String, val slot: String)

    private class Plan(
        val product: String,
        val serial: String,
        val target: String,
        val partSize: Long,
        val fileSize: Long,
        val slot: String
    )

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var usb: UsbManager
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var readButton: Button
    private lateinit var targetView: TextView
    private lateinit var partitionInput: EditText
    private lateinit var pickButton: Button
    private lateinit var fileInfo: TextView
    private lateinit var flashButton: Button
    private lateinit var afterPanel: LinearLayout

    @Volatile private var checkedTarget: TargetInfo? = null
    private var busy = false
    private var selUri: Uri? = null
    private var selName = ""
    private var selSize = 0L
    private var selMagic = ""

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            log(if (granted) "Izin USB diberikan. Tekan tombol lagi." else "Izin USB ditolak.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, filter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        readButton = Button(this).apply {
            text = "Cek HP target (mode fastboot)"
            setOnClickListener { onReadPressed() }
        }
        root.addView(readButton)

        targetView = TextView(this).apply {
            text = "Target belum dicek. Tekan \"Cek HP target\" dulu."
        }
        root.addView(targetView)

        root.addView(TextView(this).apply {
            text = "\nNama partisi yang di-flash (contoh: boot, vendor_boot):"
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply { text = "fastboot flash " })
        partitionInput = EditText(this).apply {
            hint = "nama partisi"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(partitionInput)
        root.addView(row)

        pickButton = Button(this).apply {
            text = "Pilih file .img"
            setOnClickListener { onPickPressed() }
        }
        root.addView(pickButton)

        fileInfo = TextView(this).apply { text = "Belum ada file dipilih." }
        root.addView(fileInfo)

        flashButton = Button(this).apply {
            text = "Flash file ini"
            setOnClickListener { onFlashPressed() }
        }
        root.addView(flashButton)

        afterPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        afterPanel.addView(TextView(this).apply {
            text = "Flash berhasil. Pilih langkah berikutnya:"
        })
        afterPanel.addView(Button(this).apply {
            text = "Flash file lain"
            setOnClickListener { resetSelection() }
        })
        afterPanel.addView(Button(this).apply {
            text = "Reboot sistem (fastboot reboot)"
            setOnClickListener { onRebootPressed() }
        })
        root.addView(afterPanel)

        logView = TextView(this).apply { textSize = 13f }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append(msg + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setBusy(b: Boolean) {
        runOnUiThread {
            busy = b
            readButton.isEnabled = !b
            pickButton.isEnabled = !b
            flashButton.isEnabled = !b
        }
    }

    private fun isFastboot(i: UsbInterface): Boolean =
        i.interfaceClass == 0xFF && i.interfaceSubclass == 0x42 && i.interfaceProtocol == 0x03

    private fun findFastboot(): UsbDevice? =
        usb.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { i -> isFastboot(d.getInterface(i)) }
        }

    private fun askPermission(dev: UsbDevice) {
        val pi = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE
        )
        usb.requestPermission(dev, pi)
        log("Minta izin USB. Setujui di layar, lalu tekan tombol lagi.")
    }

    private fun openFastboot(dev: UsbDevice): Fastboot {
        val iface = (0 until dev.interfaceCount).map { dev.getInterface(it) }.first { isFastboot(it) }
        val conn = usb.openDevice(dev) ?: throw FastbootException("Gagal membuka perangkat USB")
        return Fastboot(conn, iface)
    }

    private inline fun <T> withFastboot(dev: UsbDevice, block: (Fastboot) -> T): T {
        val fb = openFastboot(dev)
        try {
            return block(fb)
        } finally {
            fb.close()
        }
    }

    private fun tryGet(fb: Fastboot, name: String): String? =
        try {
            fb.getvar(name)
        } catch (e: FastbootException) {
            null
        }

    private fun targetLabel(t: TargetInfo): String =
        "Target: ${t.product} | serial ${t.serial} | bootloader unlocked: ${t.unlocked} | slot: ${slotLabel(t.slot)}"

    // Membaca data HP yang sedang terhubung. Gagal jika codename atau serial tidak terbaca.
    private fun readTarget(fb: Fastboot): TargetInfo {
        val product = tryGet(fb, "product")?.takeIf { it.isNotEmpty() }
            ?: throw FastbootException("Codename HP tidak terbaca. Flash diblokir.")
        val serial = tryGet(fb, "serialno")?.takeIf { it.isNotEmpty() }
            ?: throw FastbootException("Serial HP tidak terbaca. Flash diblokir.")
        val unlocked = tryGet(fb, "unlocked") ?: "tidak tersedia"
        val slot = tryGet(fb, "current-slot")?.lowercase()?.takeIf { it == "a" || it == "b" } ?: ""
        return TargetInfo(product, serial, unlocked, slot)
    }

    // Memastikan HP yang terhubung sekarang sama dengan HP yang sudah dicek.
    private fun ensureSameDevice(now: TargetInfo, expectedProduct: String, expectedSerial: String) {
        if (now.product != expectedProduct || now.serial != expectedSerial) {
            throw FastbootException(
                "HP yang terhubung (${now.product}, ${now.serial}) berbeda dengan hasil Cek HP target " +
                    "($expectedProduct, $expectedSerial). Tekan Cek HP target lagi."
            )
        }
    }

    // ---------- Cek HP target ----------

    private fun onReadPressed() {
        if (busy) return
        val dev = findFastboot() ?: run {
            log("HP tidak ditemukan. Pastikan HP target dalam mode fastboot dan kabel OTG tersambung.")
            return
        }
        if (!usb.hasPermission(dev)) {
            askPermission(dev)
            return
        }
        setBusy(true)
        worker.execute {
            try {
                withFastboot(dev) { fb ->
                    val info = readTarget(fb)
                    checkedTarget = info
                    runOnUiThread { targetView.text = targetLabel(info) }
                    log(
                        "HASIL BACA (tidak ada perubahan di HP):\n" +
                            "Codename: ${info.product}\n" +
                            "Serial: ${info.serial}\n" +
                            "Bootloader unlocked: ${info.unlocked}\n" +
                            "Slot aktif: ${slotLabel(info.slot)}"
                    )
                }
            } catch (e: Exception) {
                checkedTarget = null
                runOnUiThread { targetView.text = "Target belum siap: ${e.message}" }
                log("GAGAL: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    // ---------- Pilih file ----------

    private fun onPickPressed() {
        if (busy) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType("*/*")
        }
        startActivityForResult(intent, REQ_PICK_IMG)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_IMG || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        loadFile(uri)
    }

    private fun loadFile(uri: Uri) {
        var name = "?"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (ni >= 0) name = c.getString(ni) ?: "?"
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        val magic = try {
            contentResolver.openInputStream(uri)?.use { s ->
                val b = ByteArray(8)
                val n = s.read(b)
                if (n > 0) String(b, 0, n, Charsets.ISO_8859_1) else ""
            } ?: ""
        } catch (e: Exception) {
            ""
        }

        selUri = uri
        selName = name
        selSize = size
        selMagic = magic

        val magicLabel = when {
            magic.startsWith("ANDROID!") -> "ANDROID! (format boot image)"
            magic.startsWith("VNDRBOOT") -> "VNDRBOOT (format vendor_boot)"
            else -> "tidak dikenali"
        }
        fileInfo.text = "File: $name\nUkuran: ${human(size)}\nIsi awal file: $magicLabel"
        log("File dipilih: $name")
    }

    private fun resetSelection() {
        selUri = null
        selName = ""
        selSize = 0L
        selMagic = ""
        fileInfo.text = "Belum ada file dipilih."
        partitionInput.setText("")
        afterPanel.visibility = View.GONE
        log("Siap untuk file berikutnya.")
    }

    // ---------- Flash file img ----------

    private fun checkPlan(fb: Fastboot, part: String, magic: String, size: Long, product: String, serial: String): Plan {
        val maxDownload = parseHex(tryGet(fb, "max-download-size"))
            ?: throw FastbootException("Batas ukuran download HP tidak terbaca")
        if (size > maxDownload) throw FastbootException("File lebih besar dari batas download HP")

        val slot = tryGet(fb, "current-slot")?.lowercase()?.takeIf { it == "a" || it == "b" } ?: ""
        val withSlot = if (slot.isNotEmpty()) "${part}_$slot" else part
        val slotSize = if (slot.isNotEmpty()) parseHex(tryGet(fb, "partition-size:$withSlot")) else null

        val partName: String
        val partSize: Long
        if (slotSize != null) {
            partName = withSlot
            partSize = slotSize
        } else {
            partSize = parseHex(tryGet(fb, "partition-size:$part"))
                ?: throw FastbootException("Partisi '$part' tidak ditemukan di HP $product")
            partName = part
        }

        val base = part.removeSuffix("_a").removeSuffix("_b")
        val expected = when (base) {
            "boot", "init_boot" -> "ANDROID!"
            "vendor_boot" -> "VNDRBOOT"
            else -> null
        }
        if (expected != null && !magic.startsWith(expected)) {
            throw FastbootException(
                "Isi file tidak cocok untuk partisi $base (harus diawali $expected). Pilih file yang benar."
            )
        }
        if (size > partSize) {
            throw FastbootException("File (${human(size)}) lebih besar dari partisi $partName (${human(partSize)})")
        }
        return Plan(product, serial, partName, partSize, size, slot)
    }

    private fun onFlashPressed() {
        if (busy) return
        val expected = checkedTarget ?: run {
            log("Tekan \"Cek HP target\" dulu. Flash diblokir sampai HP target dicek.")
            return
        }
        val part = partitionInput.text.toString().trim().lowercase()
        val uri = selUri
        if (!Regex("[a-z0-9_]+").matches(part)) {
            log("Nama partisi harus huruf kecil, angka, atau garis bawah. Contoh: boot")
            return
        }
        if (uri == null) {
            log("Pilih file .img dulu.")
            return
        }
        if (selSize <= 0L) {
            log("Ukuran file tidak terbaca. Pilih ulang file-nya.")
            return
        }
        val dev = findFastboot() ?: run {
            log("HP tidak ditemukan. Pastikan HP target dalam mode fastboot dan kabel OTG tersambung.")
            return
        }
        if (!usb.hasPermission(dev)) {
            askPermission(dev)
            return
        }

        setBusy(true)
        val magic = selMagic
        val size = selSize
        worker.execute {
            try {
                val plan = withFastboot(dev) { fb ->
                    val now = readTarget(fb)
                    ensureSameDevice(now, expected.product, expected.serial)
                    checkPlan(fb, part, magic, size, now.product, now.serial)
                }
                runOnUiThread { confirmFlash(dev, plan, uri) }
            } catch (e: Exception) {
                log("GAGAL sebelum flash (tidak ada yang diubah): ${e.message}")
                setBusy(false)
            }
        }
    }

    private fun confirmFlash(dev: UsbDevice, plan: Plan, uri: Uri) {
        val msg = "HP target: ${plan.product} (serial ${plan.serial})\n" +
            "Partisi tujuan: ${plan.target}\n" +
            "Slot aktif: ${slotLabel(plan.slot)}\n" +
            "File: $selName (${human(plan.fileSize)})\n" +
            "Ukuran partisi: ${human(plan.partSize)}\n\n" +
            "Pastikan file ini berasal dari ROM yang terpasang di HP target (${plan.product}), bukan dari HP lain.\n\n" +
            "Lanjut flash?"
        AlertDialog.Builder(this)
            .setTitle("Konfirmasi flash")
            .setMessage(msg)
            .setPositiveButton("Flash") { _, _ -> startFlash(dev, plan, uri) }
            .setNegativeButton("Batal") { _, _ ->
                setBusy(false)
                log("Dibatalkan. Tidak ada yang diubah.")
            }
            .setOnCancelListener {
                setBusy(false)
                log("Dibatalkan. Tidak ada yang diubah.")
            }
            .show()
    }

    private fun doFlash(fb: Fastboot, plan: Plan, uri: Uri) {
        val stream = contentResolver.openInputStream(uri) ?: throw IOException("Tidak bisa membuka file")
        stream.use { s ->
            log("Mengirim file ke HP (${human(plan.fileSize)})...")
            fb.download(s, plan.fileSize) { sent ->
                if (sent % (64L * 1024 * 1024) == 0L || sent == plan.fileSize) {
                    log("  terkirim ${human(sent)} / ${human(plan.fileSize)}")
                }
            }
        }
        log("Mem-flash ${plan.target}, mohon tunggu...")
        val info = fb.command("flash:${plan.target}", FLASH_TIMEOUT)
        if (info.isNotEmpty()) log(info)
    }

    private fun startFlash(dev: UsbDevice, plan: Plan, uri: Uri) {
        worker.execute {
            try {
                withFastboot(dev) { fb ->
                    // Cek ulang sebelum menulis: HP yang terhubung harus masih sama.
                    val now = readTarget(fb)
                    ensureSameDevice(now, plan.product, plan.serial)
                    doFlash(fb, plan, uri)
                }
                log("BERHASIL: ${plan.target} sudah di-flash.")
                runOnUiThread { afterPanel.visibility = View.VISIBLE }
            } catch (e: Exception) {
                log("GAGAL saat flash: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    // ---------- Reboot ----------

    private fun onRebootPressed() {
        if (busy) return
        val dev = findFastboot()
        if (dev == null) {
            log("HP tidak ditemukan.")
            return
        }
        if (!usb.hasPermission(dev)) {
            askPermission(dev)
            return
        }
        setBusy(true)
        worker.execute {
            try {
                withFastboot(dev) { fb -> fb.command("reboot") }
                log("Perintah reboot sistem dikirim. HP akan restart.")
            } catch (e: Exception) {
                log("Catatan reboot: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }
}
