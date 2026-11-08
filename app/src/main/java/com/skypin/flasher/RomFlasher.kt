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

    /** Blok script: erase hanya jika tipe partisi raw atau ext4. */
    class EraseIfRawOrExt4(val part: String) : Step() {
        override fun describe(): String = "Hapus partisi $part (hanya jika tipenya raw atau ext4)"
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
    val maxDl = parseHex(fb.getvar("max-download-size")) ?: (64L shl 20)
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
    val seenPartType = HashSet<String>()
    var skipBlock = false
    var crcBlock = false

    for ((idx, line) in lines.withIndex()) {
        if (line.isEmpty() || line.startsWith("#")) continue
        if (line == "fi") {
            skipBlock = false
            crcBlock = false
            continue
        }
        if (skipBlock || !line.startsWith("fastboot")) continue

        when {
            line.contains("getvar product") -> {
                productRegex = Regex("grep -E \"([^\"]*)\"").find(line)?.groupValues?.get(1)
            }
            line.contains("getvar crc") -> {
                crcBlock = true
            }
            line.contains("getvar partition-type:") -> {
                val part = Regex("partition-type:([A-Za-z0-9_.-]+)").find(line)?.groupValues?.get(1)
                if (part != null && seenPartType.add(part)) steps.add(Step.EraseIfRawOrExt4(part))
                skipBlock = true
            }
            line.contains("getvar") -> Unit
            else -> {
                // Baris pengecekan berikutnya (kecuali komentar dan baris kosong).
                val next = lines.drop(idx + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                val checked = next?.startsWith(CHECK_FAIL) == true
                val body = line.removePrefix("fastboot").replace("\$*", "").substringBefore("||").trim()
                val tok = body.split(Regex("\\s+"))
                when (tok[0]) {
                    "flash" -> {
                        val name = Regex("images/([^\\s`\"']+)").find(body)?.groupValues?.get(1)
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
    val device: DeviceInfo
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
    return RomPlan(script, rom, steps, errors, dev)
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
                if (t != "raw" && t != "ext4") {
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
