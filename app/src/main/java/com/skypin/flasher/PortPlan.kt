package com.skypin.flasher

import android.content.Context

/** Kode perangkat yang didukung paket ROM port. Sama dengan pengecekan di DragonX_Installer.bat. */
const val PORT_PRODUCT = "malachite"

/** Partisi dan file gambarnya. Urutannya sama dengan 27 baris flash di DragonX_Installer.bat. */
val PORT_PARTS: List<Pair<String, String>> = listOf(
    "preloader" to "images/preloader_raw.img",
    "lk" to "images/lk.img",
    "tee" to "images/tee.img",
    "scp" to "images/scp.img",
    "spmfw" to "images/spmfw.img",
    "mcupm" to "images/mcupm.img",
    "sspm" to "images/sspm.img",
    "dpm" to "images/dpm.img",
    "gz" to "images/gz.img",
    "pi_img" to "images/pi_img.img",
    "modem" to "images/modem.img",
    "logo" to "images/logo.img",
    "ccu" to "images/ccu.img",
    "apusys" to "images/apusys.img",
    "connsys_bt" to "images/connsys_bt.img",
    "connsys_gnss" to "images/connsys_gnss.img",
    "connsys_wifi" to "images/connsys_wifi.img",
    "gpueb" to "images/gpueb.img",
    "vcp" to "images/vcp.img",
    "countrycode" to "images/countrycode.img",
    "mcf_ota" to "images/mcf_ota.img",
    "boot" to "images/boot.img",
    "vendor_boot" to "images/vendor_boot.img",
    "dtbo" to "images/dtbo.img",
    "vbmeta" to "images/vbmeta.img",
    "vbmeta_system" to "images/vbmeta_system.img",
    "vbmeta_vendor" to "images/vbmeta_vendor.img"
)

/** Gambar super. Dikirim setelah 27 partisi di atas, sama seperti di .bat. */
const val PORT_SUPER_FILE = "images/super.img"

/** Pilihan root. Angka 1 sampai 4 sama dengan menu di .bat. File yang dipilih ditulis ke partisi init_boot. */
enum class PortRoot(val number: Int, val file: String, private val nameId: String, private val nameEn: String) {
    KSU(1, "images/ksu.img", "KernelSu Next", "KernelSu Next"),
    KOW(2, "images/kow.img", "kowSu", "kowSu"),
    MAGISK(3, "images/magisk.img", "Magisk Alpha", "Magisk Alpha"),
    NONE(4, "images/init_boot.img", "Tanpa root (default)", "No root (default)");

    fun label(): String = "$number  " + tr(nameId, nameEn)
}

/**
 * Mode uji khusus FLASH ROM PORT, terpisah dari FLASH FASTBOOT ROM.
 * Default aktif, jadi flash sungguhan harus dinyalakan sendiri.
 */
object PortSettings {
    private const val PREFS = "flasher"
    private const val KEY_DRY_RUN = "port_dry_run"

    fun dryRun(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DRY_RUN, true)

    fun setDryRun(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DRY_RUN, on).apply()
    }
}

/** Nama partisi yang harus ada di perangkat: 27 partisi, super, dan init_boot. */
fun portPartNames(): List<String> = PORT_PARTS.map { it.first } + listOf("super", "init_boot")

/** File yang harus ada di folder paket untuk pilihan root ini. */
fun requiredPortFiles(root: PortRoot): List<String> = PORT_PARTS.map { it.second } + PORT_SUPER_FILE + root.file

/** Ukuran setiap partisi dari perangkat. Nilai null berarti ukurannya tidak terbaca. */
fun readPortSizes(fb: Fastboot): Map<String, Long?> = portPartNames().associateWith { portPartitionSize(fb, it) }

/** Ukuran satu partisi dari getvar partition-size (hex). Mencoba nama biasa, lalu nama dengan akhiran _a untuk partisi A/B. */
fun portPartitionSize(fb: Fastboot, name: String): Long? {
    for (key in listOf(name, "${name}_a")) {
        val v = parseHex(fb.getvar("partition-size:$key"))
        if (v != null && v > 0L) return v
    }
    return null
}

/** Magic header gambar sparse Android. Dibaca little-endian dari bytes 3A FF 26 ED. */
private val PORT_SPARSE_MAGIC = 0xED26FF3A.toInt()

/**
 * Ukuran gambar setelah dibentangkan di perangkat. Untuk gambar sparse, dihitung dari header 28 byte pertama
 * (ukuran blok dikali jumlah blok). Untuk gambar biasa, sama dengan ukuran file.
 */
fun expandedSize(src: RomSource, file: String): Long {
    val head = ByteArray(28)
    var got = 0
    src.open(file).use { ins ->
        while (got < head.size) {
            val n = ins.read(head, got, head.size - got)
            if (n < 0) break
            got += n
        }
    }
    if (got < head.size) return src.size(file)
    val bb = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    if (bb.getInt(0) != PORT_SPARSE_MAGIC) return src.size(file)
    val blockSize = bb.getInt(12).toLong() and 0xFFFFFFFFL
    val totalBlocks = bb.getInt(16).toLong() and 0xFFFFFFFFL
    return blockSize * totalBlocks
}

/**
 * Rencana flash ROM port. Langkahnya tetap dan mengikuti urutan DragonX_Installer.bat.
 * Ditambah pemeriksaan yang tidak ada di .bat: file harus ada, dan ukuran hasil bentangan harus muat di partisi.
 * Jika errors tidak kosong, rencana tidak boleh dijalankan.
 */
fun buildPortPlan(
    src: RomSource,
    dev: DeviceInfo,
    wipe: Boolean,
    root: PortRoot,
    romName: String,
    partSizes: Map<String, Long?>
): RomPlan {
    val errors = ArrayList<String>()
    val notes = ArrayList<String>()

    if (dev.product != PORT_PRODUCT) {
        errors.add(
            tr(
                "Perangkat terbaca \"${dev.product}\". Paket ini hanya untuk ${marketName(PORT_PRODUCT)} ($PORT_PRODUCT).",
                "Device reads \"${dev.product}\". This package is only for ${marketName(PORT_PRODUCT)} ($PORT_PRODUCT)."
            )
        )
    }

    val steps = ArrayList<Step>()
    steps.add(Step.SetActive("a"))
    for ((part, file) in PORT_PARTS) steps.add(Step.Flash(part, file, true, false))
    steps.add(Step.Flash("super", PORT_SUPER_FILE, true, false))
    steps.add(Step.Flash("init_boot", root.file, true, false))
    if (wipe) {
        // Seperti .bat, erase tidak menghentikan proses. Jika gagal, hanya dicatat di log.
        steps.add(Step.Erase("metadata", false))
        steps.add(Step.Erase("userdata", false))
        steps.add(Step.Erase("frp", false))
    }

    for (s in steps) {
        if (s !is Step.Flash) continue
        if (!src.exists(s.file)) {
            errors.add(tr("File ${s.file} tidak ada di folder paket.", "File ${s.file} is not in the package folder."))
            continue
        }
        val limit = partSizes[s.part]
        if (limit == null || limit <= 0L) {
            errors.add(
                tr(
                    "Ukuran partisi ${s.part} tidak terbaca dari perangkat. Flash diblokir supaya tidak ada data yang tertulis setengah jalan.",
                    "Size of partition ${s.part} could not be read from the device. Flashing is blocked so nothing is written halfway."
                )
            )
            continue
        }
        val need = expandedSize(src, s.file)
        if (need > limit) {
            errors.add(
                tr(
                    "${s.file} (${human(need)}) lebih besar dari partisi ${s.part} (${human(limit)}).",
                    "${s.file} (${human(need)}) is larger than partition ${s.part} (${human(limit)})."
                )
            )
        } else if (s.file == PORT_SUPER_FILE) {
            notes.add(
                tr(
                    "super: hasil bentangan ${human(need)}, partisi ${human(limit)}.",
                    "super: expanded size ${human(need)}, partition ${human(limit)}."
                )
            )
        }
    }

    notes.add(
        tr(
            "Antirollback perangkat: ${dev.anti}. Paket ini tidak punya images/anti_version.txt, jadi versinya tidak bisa dibandingkan. Pastikan paket ini tidak lebih lama dari yang sudah ada di HP.",
            "Device antirollback: ${dev.anti}. This package has no images/anti_version.txt, so its version cannot be compared. Make sure this package is not older than what the phone already has."
        )
    )
    val wipeNote = if (wipe) {
        tr("ya (metadata, userdata, frp dihapus)", "yes (metadata, userdata, frp erased)")
    } else {
        tr("tidak", "no")
    }
    notes.add(tr("Root: ${root.label()}. Wipe: $wipeNote.", "Root: ${root.label()}. Wipe: $wipeNote."))

    return RomPlan(
        script = "DragonX_Installer.bat (langkah tetap)",
        rom = romName,
        steps = steps,
        errors = errors,
        device = dev,
        notes = notes
    )
}
