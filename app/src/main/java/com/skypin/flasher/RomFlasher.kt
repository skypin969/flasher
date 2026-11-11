package com.skypin.flasher

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream

val ROM_SCRIPTS = listOf("flash_all.sh", "flash_all_except_data_storage.sh", "flash_all_except_storage.sh")

private const val CHECK_FAIL = "if [ \$? -ne 0 ]"

/** Sumber paket ROM: folder biasa atau folder dari SAF. Path relatif, contoh "images/boot.img". */
interface RomSource {
    fun exists(path: String): Boolean
    fun size(path: String): Long
    fun open(path: String): InputStream
    fun readText(path: String): String?
}

class FileRomSource(private val root: File) : RomSource {
    private fun f(p: String) = File(root, p)
    override fun exists(path: String): Boolean = f(path).isFile
    override fun size(path: String): Long = f(path).length()
    override fun open(path: String): InputStream = f(path).inputStream()
    override fun readText(path: String): String? = if (f(path).isFile) f(path).readText() else null
}

class DocEntry(val id: String, val name: String, val isDir: Boolean, val size: Long)

fun listChildren(cr: ContentResolver, tree: Uri, parentId: String): List<DocEntry> {
    val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
    val proj = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE
    )
    val out = ArrayList<DocEntry>()
    cr.query(uri, proj, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val id = c.getString(0) ?: continue
            val name = c.getString(1) ?: continue
            val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
            val size = if (c.isNull(3)) 0L else c.getLong(3)
            out.add(DocEntry(id, name, isDir, size))
        }
    }
    return out
}

/** Folder hasil pilihan SAF (ACTION_OPEN_DOCUMENT_TREE). Indeks dibuat sekali saat dibuka. */
class SafRomSource(private val cr: ContentResolver, private val tree: Uri) : RomSource {
    private val index = HashMap<String, Pair<String, Long>>()

    init {
        walk(DocumentsContract.getTreeDocumentId(tree), "", 0)
    }

    private fun walk(docId: String, prefix: String, depth: Int) {
        if (depth > 3) return
        for (e in listChildren(cr, tree, docId)) {
            val p = prefix + e.name
            if (e.isDir) {
                walk(e.id, "$p/", depth + 1)
            } else {
                index[p] = Pair(e.id, e.size)
            }
        }
    }

    override fun exists(path: String): Boolean = index.containsKey(path)

    override fun size(path: String): Long = index[path]?.second ?: 0L

    override fun open(path: String): InputStream {
        val id = index[path]?.first ?: throw FastbootException("Berkas tidak ada di paket: $path")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return cr.openInputStream(uri) ?: throw FastbootException("Tidak bisa membuka $path")
    }

    override fun readText(path: String): String? =
        if (exists(path)) open(path).bufferedReader().use { it.readText() } else null
}

/** Satu langkah dari script flash asli. Urutan dan pengecekan mengikuti script. */
sealed class Step {
    abstract fun describe(): String

    /** erase biasa. checked = script menghentikan proses jika gagal. */
    class Erase(val part: String, val checked: Boolean) : Step() {
        override fun describe(): String = "Hapus partisi $part"
    }

    /** Blok script: erase hanya jika tipe partisi termasuk [types], yaitu tipe yang diperiksa script (raw, ext4). */
    class EraseIfRawOrExt4(val part: String, val types: MutableSet<String>) : Step() {
        override fun describe(): String =
            "Hapus partisi $part (hanya jika tipenya ${types.sorted().joinToString(" atau ")})"
    }

    class Flash(val part: String, val file: String, val checked: Boolean, val onlyIfCrc: Boolean) : Step() {
        override fun describe(): String =
            "Tulis $part dari $file" + if (onlyIfCrc) " (hanya jika CRC aktif)" else ""
    }

    class SetActive(val slot: String) : Step() {
        override fun describe(): String = "Pilih slot aktif $slot"
    }

    class Oem(val cmd: String) : Step() {
        override fun describe(): String = "Perintah oem $cmd"
    }

    class Reboot(val checked: Boolean) : Step() {
        override fun describe(): String = "Reboot perangkat"
    }
}

class DeviceInfo(
    val product: String,
    val serial: String,
    val maxDownload: Long,
    val anti: Long,
    val crc: Boolean
)

fun readDevice(fb: Fastboot): DeviceInfo {
    val product = fb.getvar("product")?.trim().orEmpty()
    val serial = fb.getvar("serialno")?.trim().orEmpty()
    val maxDl = readMaxDownload { fb.cmd("getvar:max-download-size") }
    val anti = fb.getvar("anti")?.trim()?.toLongOrNull() ?: 0L
    val crc = fb.getvar("crc")?.trim() == "1"
    return DeviceInfo(product, serial, maxDl, anti, crc)
}

class ParsedScript(val productRegex: String?, val steps: List<Step>, val errors: List<String>)

/** Membaca script flash_all.sh dan mengubahnya menjadi daftar langkah. */
fun parseScript(text: String): ParsedScript {
    val lines = text.lines().map { it.trim() }
    var productRegex: String? = null
    val steps = ArrayList<Step>()
    val errors = ArrayList<String>()
    val partTypeSteps = HashMap<String, Step.EraseIfRawOrExt4>()
    var skipBlock = false
    var crcBlock = false

    for ((idx, line) in lines.withIndex()) {
        if (line.isEmpty() || line.startsWith("#")) continue
        if (line == "fi") {
            skipBlock = false
            crcBlock = false
            continue
        }
        // Pengecekan tipe partisi dibaca juga di dalam blok yang dilewati, karena script bisa
        // menaruh pengecekan ext4 di dalam cabang else dari pengecekan raw.
        if (line.startsWith("fastboot") && line.contains("getvar partition-type:")) {
            val part = Regex("partition-type:([A-Za-z0-9_.-]+)").find(line)?.groupValues?.get(1)
            val type = Regex("grep[^\"]*\"\\^?partition-type:[A-Za-z0-9_.-]+: *([A-Za-z0-9]+)\"").find(line)?.groupValues?.get(1)
            if (part != null) {
                var step = partTypeSteps[part]
                if (step == null) {
                    step = Step.EraseIfRawOrExt4(part, HashSet())
                    partTypeSteps[part] = step
                    steps.add(step)
                }
                // Tipe tidak terbaca dari script: pakai raw dan ext4 seperti script Xiaomi pada umumnya
                if (type != null) step.types.add(type) else step.types.addAll(listOf("raw", "ext4"))
            }
            skipBlock = true
            continue
        }
        if (skipBlock || !line.startsWith("fastboot")) continue

        when {
            line.contains("getvar product") -> {
                // Script bisa memakai grep -E atau grep biasa
                productRegex = Regex("grep(?: -E)? \"([^\"]*)\"").find(line)?.groupValues?.get(1)
            }
            line.contains("getvar crc") -> {
                crcBlock = true
            }
            line.contains("getvar") -> Unit
            else -> {
                // Baris pengecekan berikutnya (kecuali komentar dan baris kosong).
                val next = lines.drop(idx + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                val checked = next?.startsWith(CHECK_FAIL) == true
                // Titik koma di ujung baris adalah pemisah perintah, bukan bagian dari nama
                val body = line.removePrefix("fastboot").replace("\$*", "").substringBefore("||").trim()
                    .trimEnd(';').trim()
                val tok = body.split(Regex("\\s+"))
                when (tok[0]) {
                    "flash" -> {
                        val name = Regex("images/([^\\s`\"';]+)").find(body)?.groupValues?.get(1)
                        if (tok.size >= 2 && name != null) {
                            steps.add(Step.Flash(tok[1], "images/$name", checked, crcBlock))
                        } else {
                            errors.add(line)
                        }
                    }
                    "erase" -> {
                        if (tok.size >= 2) steps.add(Step.Erase(tok[1], checked)) else errors.add(line)
                    }
                    "set_active" -> {
                        if (tok.size >= 2) steps.add(Step.SetActive(tok[1])) else errors.add(line)
                    }
                    "oem" -> steps.add(Step.Oem(body.removePrefix("oem").trim()))
                    "reboot" -> steps.add(Step.Reboot(checked))
                    else -> errors.add(line)
                }
            }
        }
    }
    return ParsedScript(productRegex, steps, errors)
}

class RomPlan(
    val script: String,
    val rom: String,
    val steps: List<Step>,
    val errors: List<String>,
    val device: DeviceInfo,
    /** Catatan yang perlu dibaca sebelum flash, misalnya daftar pemeriksa CRC tidak dikirim. */
    val notes: List<String> = emptyList()
)

fun listScripts(src: RomSource): List<String> = ROM_SCRIPTS.filter { src.exists(it) }

/** Membuat rencana. Jika errors tidak kosong, rencana tidak boleh dijalankan. */
fun buildPlan(src: RomSource, script: String, dev: DeviceInfo, rom: String): RomPlan {
    val text = src.readText(script)
        ?: return RomPlan(script, rom, emptyList(), listOf("Script $script tidak ditemukan"), dev)
    val parsed = parseScript(text)
    val errors = ArrayList<String>()
    for (line in parsed.errors) errors.add("Baris script tidak dikenali: $line")

    val pattern = parsed.productRegex
    if (pattern == null) {
        errors.add("Script tidak punya pengecekan codename, flash diblokir")
    } else if (dev.product.isBlank()) {
        errors.add("Codename perangkat tidak terbaca, flash diblokir")
    } else if (!Regex(pattern).matches("product: ${dev.product}")) {
        errors.add("Codename tidak cocok: paket untuk '$pattern', perangkat '${dev.product}'")
    }

    val curAnti = src.readText("images/anti_version.txt")?.trim()?.toLongOrNull() ?: 0L
    if (dev.anti > curAnti) {
        errors.add("Antirollback perangkat (${dev.anti}) lebih tinggi dari paket ($curAnti), flash diblokir")
    }

    val steps = ArrayList<Step>()
    for (s in parsed.steps) {
        if (s is Step.Flash) {
            if (s.onlyIfCrc && !dev.crc) continue
            if (!src.exists(s.file)) errors.add("File ${s.file} untuk ${s.part} tidak ada di paket")
        }
        steps.add(s)
    }

    // Daftar pemeriksa CRC dari paket hanya dikirim jika pemotongan file di perangkat ini cocok dengan daftar itu.
    // Jika tidak cocok, bootloader akan menolak partisi besar (contoh: super) di tengah proses flash.
    val notes = ArrayList<String>()
    var finalSteps: List<Step> = steps
    if (dev.crc && errors.isEmpty() && steps.any { it is Step.Flash && it.onlyIfCrc }) {
        val bad = crcMismatches(src, steps, dev)
        if (bad.isEmpty()) {
            notes.add("Pemotongan file cocok dengan daftar pemeriksa CRC paket (batas perangkat ${human(dev.maxDownload)}). Daftar dikirim, bootloader ikut memeriksa.")
        } else {
            finalSteps = steps.filterNot { it is Step.Flash && it.onlyIfCrc }
            notes.add("PERINGATAN: daftar pemeriksa CRC paket TIDAK dikirim ke perangkat, karena pemotongan file dengan batas perangkat ini (${human(dev.maxDownload)}) tidak cocok dengan daftar itu:")
            for (b in bad) notes.add("  $b")
            notes.add("Akibatnya bootloader tidak memeriksa setiap bagian saat flash. Flash tetap berjalan dengan batas perangkat. Isi file sudah diperiksa aplikasi sebelum flash, tetapi tidak diperiksa lagi saat dikirim.")
        }
    }
    return RomPlan(script, rom, finalSteps, errors, dev, notes)
}

/** Nama yang mungkin dipakai daftar CRC untuk partisi [part]: daftar bisa memakai nama tanpa akhiran slot atau dengan akhiran lain. */
private fun crcKeys(part: String): List<String> {
    val base = when {
        part.endsWith("_ab") -> part.dropLast(3)
        part.endsWith("_a") || part.endsWith("_b") -> part.dropLast(2)
        else -> part
    }
    return listOf(part, base, base + "_a", base + "_b", base + "_ab").distinct()
}

/** Membaca images/sparsecrclist.txt: nama partisi dan jumlah bagian sparse yang diharapkan bootloader. */
private fun readSparseCounts(src: RomSource): Map<String, Int> {
    val out = HashMap<String, Int>()
    val text = try {
        src.readText("images/sparsecrclist.txt")
    } catch (e: Exception) {
        null
    }
    if (text == null) return out
    for (line in text.lines()) {
        val tok = line.trim().split(Regex("\\s+"))
        if (tok.size < 2) continue
        val n = tok[1].toIntOrNull() ?: continue
        out[tok[0]] = n
    }
    return out
}

/** Membaca images/crclist.txt: nama partisi yang dicek bootloader sebagai satu file utuh. */
private fun readCrcNames(src: RomSource): Set<String> {
    val out = HashSet<String>()
    val text = try {
        src.readText("images/crclist.txt")
    } catch (e: Exception) {
        null
    }
    if (text == null) return out
    for (line in text.lines()) {
        val tok = line.trim().split(Regex("\\s+"))
        if (tok.size >= 2 && tok[1].startsWith("0x", ignoreCase = true)) out.add(tok[0])
    }
    return out
}

/**
 * Membandingkan jumlah bagian yang akan dikirim aplikasi (dengan batas max-download-size perangkat)
 * dengan jumlah bagian di daftar CRC paket. Hasil kosong berarti semuanya cocok.
 */
private fun crcMismatches(src: RomSource, steps: List<Step>, dev: DeviceInfo): List<String> {
    val sparse = readSparseCounts(src)
    val plain = readCrcNames(src)
    val out = ArrayList<String>()
    val planned = HashMap<String, Int>()
    val reported = HashSet<String>()
    for (s in steps) {
        if (s !is Step.Flash || s.onlyIfCrc) continue
        if (!src.exists(s.file)) continue
        var want: Int? = null
        var listed = false
        for (k in crcKeys(s.part)) {
            if (want == null) want = sparse[k]
            if (k in plain) listed = true
        }
        if (want == null && !listed) continue
        val size = src.size(s.file)
        // Partisi di daftar file utuh yang muat dalam satu kiriman tidak perlu dihitung
        if (want == null && size <= dev.maxDownload) continue
        val expected = want ?: 1
        val count = planned.getOrPut(s.file) {
            try {
                val map = SparseImage.scan(ImageSrc(size) { src.open(s.file) })
                if (!map.isSparse && size <= dev.maxDownload) 1 else SparseImage.planParts(map, dev.maxDownload).size
            } catch (e: Exception) {
                -1
            }
        }
        if (count != expected && reported.add(s.file)) {
            val mine = if (count < 0) "tidak bisa dihitung" else "$count bagian"
            out.add("${s.file}: daftar paket $expected bagian, perangkat ini $mine")
        }
    }
    return out
}

fun describePlan(p: RomPlan, src: RomSource): String {
    val sb = StringBuilder()
    sb.append("Paket: ").append(p.rom).append('\n')
    sb.append("Script: ").append(p.script).append('\n')
    sb.append("Perangkat: ").append(p.device.product).append(" (serial ").append(p.device.serial).append(")\n")
    if (p.errors.isNotEmpty()) {
        sb.append("\nTIDAK BISA DIJALANKAN:\n")
        for (e in p.errors) sb.append("- ").append(e).append('\n')
    }
    if (p.notes.isNotEmpty()) {
        sb.append("\nCATATAN:\n")
        for (n in p.notes) sb.append(n).append('\n')
    }
    sb.append("\nLangkah (").append(p.steps.size).append("):\n")
    var total = 0L
    p.steps.forEachIndexed { i, s ->
        sb.append(i + 1).append(". ").append(s.describe())
        if (s is Step.Flash) {
            val sz = src.size(s.file)
            total += sz
            sb.append(" (").append(human(sz)).append(")")
        }
        sb.append('\n')
    }
    sb.append("\nTotal file yang dikirim: ").append(human(total)).append('\n')
    return sb.toString()
}

/**
 * Menjalankan rencana. dry = true hanya mencatat langkah (getvar tetap dibaca, tidak ada tulis).
 * Langkah yang dicek script (checked) akan menghentikan proses jika gagal.
 */
fun runPlan(fb: Fastboot, src: RomSource, plan: RomPlan, dry: Boolean, log: (String) -> Unit) {
    val dev = plan.device
    val total = plan.steps.size
    plan.steps.forEachIndexed { idx, step ->
        val tag = "[${idx + 1}/$total]"
        when (step) {
            is Step.EraseIfRawOrExt4 -> {
                val t = fb.getvar("partition-type:${step.part}") ?: ""
                if (t !in step.types) {
                    log("$tag Partisi ${step.part} bertipe '$t', tidak dihapus")
                } else if (dry) {
                    log("$tag [UJI] ${step.describe()} (tipe $t)")
                } else {
                    guarded(true, "$tag ${step.describe()} (tipe $t)", log) { fb.erase(step.part) }
                }
            }
            is Step.Erase -> {
                if (dry) {
                    log("$tag [UJI] ${step.describe()}")
                } else {
                    guarded(step.checked, "$tag ${step.describe()}", log) { fb.erase(step.part) }
                }
            }
            is Step.Flash -> {
                val size = src.size(step.file)
                if (dry) {
                    log("$tag [UJI] ${step.describe()} (${human(size)})")
                    flashImage(fb, ImageSrc(size) { src.open(step.file) }, step.part, dev.maxDownload, true) { m ->
                        log(m)
                    }
                } else {
                    val label = "$tag ${step.describe()} (${human(size)})"
                    guarded(step.checked, label, log) {
                        flashImage(fb, ImageSrc(size) { src.open(step.file) }, step.part, dev.maxDownload, false) { m ->
                            log(m)
                        }
                    }
                }
            }
            is Step.SetActive -> {
                if (dry) {
                    log("$tag [UJI] ${step.describe()}")
                } else {
                    // Gagal = berhenti sebelum oem cdms dan reboot. current-slot belum berubah sebelum reboot, jadi tidak dicek di sini.
                    guarded(true, "$tag ${step.describe()}", log) { fb.setActive(step.slot) }
                }
            }
            is Step.Oem -> {
                if (dry) {
                    log("$tag [UJI] ${step.describe()}")
                } else {
                    guarded(false, "$tag ${step.describe()}", log) { fb.oem(step.cmd) }
                }
            }
            is Step.Reboot -> {
                if (dry) {
                    log("$tag [UJI] ${step.describe()}")
                } else {
                    guarded(step.checked, "$tag ${step.describe()}", log) { fb.reboot() }
                }
            }
        }
    }
    log(if (dry) "Mode uji selesai. Tidak ada data yang dikirim ke perangkat." else "Semua langkah selesai.")
}

private fun guarded(checked: Boolean, label: String, log: (String) -> Unit, block: () -> Unit) {
    log(label)
    try {
        block()
        log("   OK")
    } catch (e: FastbootException) {
        if (checked) throw FastbootException("$label GAGAL: ${e.message}")
        log("   Gagal, dilewati (script asli juga tidak mengecek langkah ini): ${e.message}")
    }
}

/**
 * Kirim satu gambar ke partisi [part].
 * File biasa (bukan sparse) yang muat dalam max-download-size dikirim apa adanya, seperti MiTool.
 * Selain itu, gambar dipecah menjadi bagian sparse.
 */
fun flashImage(
    fb: Fastboot,
    img: ImageSrc,
    part: String,
    maxDl: Long,
    dry: Boolean,
    log: (String) -> Unit
) {
    if (img.size <= 0L) throw FastbootException("Ukuran file untuk $part tidak terbaca")
    val map = SparseImage.scan(img)

    if (!map.isSparse && img.size <= maxDl) {
        log("   1 bagian, dikirim apa adanya (${human(img.size)})")
        if (dry) return
        img.open().use { ins -> fb.download(img.size, ins) }
        fb.flash(part)
        return
    }

    val parts = SparseImage.planParts(map, maxDl)
    log("   ${parts.size} bagian, batas tiap bagian ${human(maxDl)}")
    if (dry) return

    val src = SourceReader(img.open())
    try {
        parts.forEachIndexed { i, p ->
            log("   Kirim bagian ${i + 1}/${parts.size} (${human(p.size)})")
            fb.download(p.size, PartStream(p, src))
            fb.flash(part)
        }
    } finally {
        src.close()
    }
}
