package com.skypin.flasher

import java.io.InputStream
import java.util.zip.CRC32

/** Hasil pemeriksaan satu file: cocok, korup, tidak bisa dipastikan, atau hilang. */
enum class FileVerdict { OK, CORRUPT, UNKNOWN, MISSING }

class FileCheck(val file: String, val verdict: FileVerdict, val note: String)

/** Hasil pemeriksaan satu paket ROM. [hasLists] = paket membawa daftar pembanding. */
class RomCheckResult(val items: List<FileCheck>, val hasLists: Boolean) {
    fun of(v: FileVerdict): List<FileCheck> = items.filter { it.verdict == v }

    /** Ada file korup atau hilang: flash tidak boleh dijalankan. */
    val blocked: Boolean get() = items.any { it.verdict == FileVerdict.CORRUPT || it.verdict == FileVerdict.MISSING }
}

class CheckProgress(
    val index: Int,
    val total: Int,
    val file: String,
    val fileDone: Long,
    val fileSize: Long,
    val allDone: Long,
    val allTotal: Long
)

/** Dilempar saat pemeriksaan dihentikan karena halaman ditutup. */
class CheckCancelled : RuntimeException()

private class SparseError(msg: String) : Exception(msg)

/** Angka hasil hitungan dan angka dari daftar untuk satu file. */
private class Measure(val file: String) {
    var missing = false
    var structError: String? = null
    var crcExp: Long? = null
    var crcGot: Long? = null
    var partsExp: List<Long>? = null
    var partsGot: List<Long>? = null
    var sumExp: Int? = null
    var sumGot: Int? = null
}

private const val CHUNK_RAW = 0xCAC1
private const val CHUNK_FILL = 0xCAC2
private const val CHUNK_SKIP = 0xCAC3
private const val CHUNK_CRC = 0xCAC4
private const val DEFAULT_MAX_DOWNLOAD = 256L * 1024L * 1024L

private fun hexOf(s: String): Long? =
    s.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16)

private fun u16(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

private fun u32(b: ByteArray, o: Int): Long =
    (b[o].toLong() and 0xFF) or
        ((b[o + 1].toLong() and 0xFF) shl 8) or
        ((b[o + 2].toLong() and 0xFF) shl 16) or
        ((b[o + 3].toLong() and 0xFF) shl 24)

/** crclist.txt: tiap baris "nama_partisi 0xCRC32". */
private fun readCrcList(text: String?): Map<String, Long> {
    val out = HashMap<String, Long>()
    if (text == null) return out
    for (line in text.lines()) {
        val tok = line.trim().split(Regex("\\s+"))
        if (tok.size != 2) continue
        val v = hexOf(tok[1]) ?: continue
        out[tok[0]] = v
    }
    return out
}

/** sparsecrclist.txt: tiap baris "nama_partisi jumlah_bagian 0xCRC 0xCRC ...". */
private fun readSparseList(text: String?): Map<String, List<Long>> {
    val out = HashMap<String, List<Long>>()
    if (text == null) return out
    for (line in text.lines()) {
        val tok = line.trim().split(Regex("\\s+"))
        if (tok.size < 3) continue
        val count = tok[1].toIntOrNull() ?: continue
        val crcs = tok.drop(2).map { hexOf(it) }
        if (crcs.size != count || crcs.any { it == null }) continue
        out[tok[0]] = crcs.map { it!! }
    }
    return out
}

/** Checksum.ini (paket MediaTek): bagian [CheckSum], tiap baris "nama_partisi=0xHHHH". */
private fun readChecksumIni(text: String?): Map<String, Int> {
    val out = HashMap<String, Int>()
    if (text == null) return out
    var inSection = false
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.startsWith("[")) {
            inSection = line.equals("[CheckSum]", ignoreCase = true)
            continue
        }
        if (!inSection) continue
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val v = hexOf(line.substring(eq + 1)) ?: continue
        out[line.substring(0, eq).trim()] = (v and 0xFFFF).toInt()
    }
    return out
}

/** Batas ukuran tiap bagian sparse, dibaca dari flash_gen_crc_list.py jika ada di paket. */
private fun readMaxDownload(text: String?): Long {
    if (text == null) return DEFAULT_MAX_DOWNLOAD
    val m = Regex("MAX_DOWNLOAD_SIZE\\s*=\\s*([0-9][0-9*\\s]*)").find(text) ?: return DEFAULT_MAX_DOWNLOAD
    var v = 1L
    for (part in m.groupValues[1].split('*')) {
        val n = part.trim().toLongOrNull() ?: return DEFAULT_MAX_DOWNLOAD
        v *= n
    }
    return if (v >= 1024L * 1024L) v else DEFAULT_MAX_DOWNLOAD
}

/** Nama yang dicoba di daftar untuk satu partisi: nama asli, tanpa akhiran slot, lalu dengan _a dan _b. */
private fun keysFor(parts: List<String>): List<String> {
    val keys = LinkedHashSet<String>()
    for (p in parts) {
        keys.add(p)
        val base = if (p.endsWith("_a") || p.endsWith("_b")) p.dropLast(2) else p
        keys.add(base)
        keys.add(base + "_a")
        keys.add(base + "_b")
    }
    return keys.toList()
}

/** Pembaca file yang sekaligus menghitung CRC32 seluruh file dan jumlah semua byte. */
private class Meter(
    private val ins: InputStream,
    private val wantCrc: Boolean,
    private val cancelled: () -> Boolean,
    private val onBytes: (Long) -> Unit
) {
    val crc = CRC32()
    var sum = 0L
    private var pending = 0L

    private fun account(buf: ByteArray, n: Int) {
        if (wantCrc) crc.update(buf, 0, n)
        var s = 0L
        for (i in 0 until n) s += (buf[i].toInt() and 0xFF)
        sum += s
        pending += n
        if (pending >= 8L * 1024L * 1024L) flush()
    }

    fun flush() {
        if (cancelled()) throw CheckCancelled()
        if (pending > 0L) onBytes(pending)
        pending = 0L
    }

    /** Baca sampai [n] byte ke awal [buf]. Mengembalikan jumlah yang terbaca (kurang dari n jika file habis). */
    fun read(buf: ByteArray, n: Int): Int {
        var got = 0
        while (got < n) {
            val r = ins.read(buf, got, n - got)
            if (r < 0) break
            got += r
        }
        if (got > 0) account(buf, got)
        return got
    }

    /** Baca tepat [n] byte, tiap potongan diberikan ke [each]. False jika file habis lebih dulu. */
    fun stream(n: Long, buf: ByteArray, each: (ByteArray, Int) -> Unit): Boolean {
        var rem = n
        while (rem > 0L) {
            val want = minOf(rem, buf.size.toLong()).toInt()
            val r = ins.read(buf, 0, want)
            if (r < 0) return false
            if (r == 0) continue
            account(buf, r)
            each(buf, r)
            rem -= r
        }
        return true
    }

    /** Baca sisa file sampai habis. */
    fun drain(buf: ByteArray) {
        while (true) {
            val r = ins.read(buf, 0, buf.size)
            if (r < 0) break
            if (r > 0) account(buf, r)
        }
        flush()
    }
}

/**
 * Menghitung CRC tiap bagian file sparse, dengan aturan pecah yang sama seperti flash_gen_crc_list.py dari Xiaomi.
 * [head] adalah 28 byte pertama file yang sudah dibaca.
 */
private fun sparsePartCrcs(
    meter: Meter,
    head: ByteArray,
    maxDl: Long,
    buf: ByteArray,
    cancelled: () -> Boolean
): List<Long> {
    val major = u16(head, 4)
    val fileHdr = u16(head, 8)
    val chunkHdr = u16(head, 10)
    val blk = u32(head, 12)
    val totalChunks = u32(head, 20)
    if (major != 1 || fileHdr != 28 || chunkHdr != 12) throw SparseError(tr("kepala file sparse tidak dikenali", "sparse header not recognised"))
    if (blk <= 0L || blk % 4L != 0L || blk > 1024L * 1024L) throw SparseError(tr("ukuran blok sparse tidak wajar", "sparse block size is not valid"))
    val truncated = tr("file terpotong", "file is truncated")

    val mx = maxDl - 56L
    var backed = 28L
    val parts = ArrayList<CRC32>()
    parts.add(CRC32())
    var p = 0
    val hdr = ByteArray(12)
    val four = ByteArray(4)

    var i = 0L
    while (i < totalChunks) {
        i++
        if (meter.read(hdr, 12) < 12) throw SparseError(truncated)
        val type = u16(hdr, 0)
        val blocks = u32(hdr, 4)
        var rem = u32(hdr, 8) - 12L
        var fill: ByteArray? = null
        when (type) {
            CHUNK_RAW -> if (rem != blocks * blk) throw SparseError(tr("isi chunk tidak sesuai ukurannya", "chunk size does not match its data"))
            CHUNK_FILL, CHUNK_CRC -> {
                if (rem != 4L) throw SparseError(tr("chunk sparse tidak valid", "invalid sparse chunk"))
                if (meter.read(four, 4) < 4) throw SparseError(truncated)
                if (type == CHUNK_FILL) {
                    val f = ByteArray(blk.toInt())
                    for (k in f.indices) f[k] = four[k % 4]
                    fill = f
                }
            }
            CHUNK_SKIP -> if (rem != 0L) throw SparseError(tr("chunk sparse tidak valid", "invalid sparse chunk"))
            else -> throw SparseError(tr("jenis chunk sparse tidak dikenal", "unknown sparse chunk type"))
        }

        while (true) {
            var real = backed - 16L
            var piece = rem
            var more = false
            var newPart = 0
            if (real + rem <= mx) {
                backed += rem + 12L
            } else {
                var over = -1
                if (real >= mx * 7L / 8L) {
                    if (rem + 12L <= mx) {
                        backed = rem + 52L
                        newPart = 1
                    } else {
                        over = 1
                        real = 0L
                    }
                } else {
                    over = 0
                }
                if (over >= 0) {
                    piece = blk * ((mx - real) / blk)
                    if (piece <= 0L || piece >= rem) throw SparseError(tr("pecahan sparse tidak bisa dihitung", "sparse split could not be computed"))
                    more = true
                    if (over == 1) {
                        backed = piece + 12L
                        newPart = 1
                    } else {
                        backed = 40L
                        newPart = 2
                    }
                }
            }
            if (newPart == 1) {
                parts.add(CRC32())
                p++
            }
            val cur = parts[p]
            if (type == CHUNK_RAW) {
                if (!meter.stream(piece, buf) { b, n -> cur.update(b, 0, n) }) throw SparseError(truncated)
            } else if (type == CHUNK_FILL && fill != null) {
                var j = 0L
                while (j < blocks) {
                    cur.update(fill, 0, fill.size)
                    j++
                    if (j % 2048L == 0L && cancelled()) throw CheckCancelled()
                }
            }
            if (newPart == 2) {
                parts.add(CRC32())
                p++
            }
            if (parts.size > 4096) throw SparseError(tr("jumlah bagian sparse tidak wajar", "too many sparse parts"))
            if (!more) break
            rem -= piece
        }
    }
    return parts.map { it.value }
}

/** Membaca satu file sampai habis dan mengisi angka hasil hitungan di [m]. */
private fun measureFile(
    src: RomSource,
    path: String,
    m: Measure,
    maxDl: Long,
    buf: ByteArray,
    cancelled: () -> Boolean,
    onBytes: (Long) -> Unit
) {
    if (src.size(path) <= 0L) {
        m.structError = tr("ukuran file 0", "file size is 0")
        return
    }
    src.open(path).use { ins ->
        val meter = Meter(ins, m.crcExp != null, cancelled, onBytes)
        val head = ByteArray(28)
        val got = meter.read(head, 28)
        val sparse = got == 28 &&
            head[0] == 0x3A.toByte() && head[1] == 0xFF.toByte() &&
            head[2] == 0x26.toByte() && head[3] == 0xED.toByte()
        if (sparse) {
            try {
                m.partsGot = sparsePartCrcs(meter, head, maxDl, buf, cancelled)
            } catch (e: SparseError) {
                m.structError = e.message
            }
        }
        meter.drain(buf)
        if (m.crcExp != null) m.crcGot = meter.crc.value
        m.sumGot = (meter.sum and 0xFFFF).toInt()
    }
}

/**
 * Memeriksa semua file yang dipakai [script] di paket [src].
 * Pembanding dibaca dari paket itu sendiri: images/crclist.txt, images/sparsecrclist.txt, dan images/Checksum.ini.
 * File hanya disebut korup jika hitungannya memang bisa dibandingkan. Selain itu disebut tidak bisa dipastikan.
 */
fun verifyRom(
    src: RomSource,
    script: String,
    cancelled: () -> Boolean,
    onProgress: (CheckProgress) -> Unit,
    log: (String) -> Unit
): RomCheckResult {
    val text = src.readText(script) ?: return RomCheckResult(emptyList(), false)
    val files = LinkedHashMap<String, MutableList<String>>()
    for (s in parseScript(text).steps) {
        if (s is Step.Flash && !s.onlyIfCrc) files.getOrPut(s.file) { ArrayList() }.add(s.part)
    }

    val crcList = readCrcList(src.readText("images/crclist.txt"))
    val sparseList = readSparseList(src.readText("images/sparsecrclist.txt"))
    val sumList = readChecksumIni(src.readText("images/Checksum.ini"))
    val maxDl = readMaxDownload(src.readText("flash_gen_crc_list.py"))
    val hasLists = crcList.isNotEmpty() || sparseList.isNotEmpty() || sumList.isNotEmpty()

    val names = files.keys.toList()
    val sizes = names.map { if (src.exists(it)) src.size(it) else 0L }
    val allTotal = sizes.sum()
    var allDone = 0L
    val buf = ByteArray(1 shl 20)
    val measures = ArrayList<Measure>()

    for ((idx, path) in names.withIndex()) {
        if (cancelled()) throw CheckCancelled()
        val short = path.substringAfterLast('/')
        val m = Measure(short)
        measures.add(m)
        if (!src.exists(path)) {
            m.missing = true
            continue
        }
        val keys = keysFor(files[path] ?: emptyList())
        m.crcExp = keys.firstNotNullOfOrNull { crcList[it] }
        m.partsExp = keys.firstNotNullOfOrNull { sparseList[it] }
        m.sumExp = keys.firstNotNullOfOrNull { sumList[it] }

        val size = sizes[idx]
        var fileDone = 0L
        onProgress(CheckProgress(idx + 1, names.size, short, 0L, size, allDone, allTotal))
        try {
            measureFile(src, path, m, maxDl, buf, cancelled) { n ->
                fileDone += n
                allDone += n
                onProgress(CheckProgress(idx + 1, names.size, short, fileDone, size, allDone, allTotal))
            }
        } catch (e: CheckCancelled) {
            throw e
        } catch (e: Exception) {
            m.structError = tr("tidak bisa dibaca: ", "cannot be read: ") + (e.message ?: e.javaClass.simpleName)
        }
        if (m.structError == null && isMatch(m)) log(tr("$short cocok", "$short matches"))
    }

    // Satu cara hitung baru dipercaya jika cocok minimal sekali di paket ini.
    val crcTrusted = measures.any { it.crcExp != null && it.crcGot == it.crcExp }
    val sumTrusted = measures.any { it.sumExp != null && it.sumGot == it.sumExp }
    val sparseTrusted = measures.any { m ->
        val e = m.partsExp
        val g = m.partsGot
        e != null && g != null && e.size == g.size && e.indices.any { e[it] == g[it] }
    }

    val items = measures.map { judge(it, crcTrusted, sparseTrusted, sumTrusted, hasLists) }
    for (c in items) {
        when (c.verdict) {
            FileVerdict.OK -> Unit
            FileVerdict.CORRUPT -> log(tr("${c.file} KORUP: ${c.note}", "${c.file} CORRUPT: ${c.note}"))
            FileVerdict.MISSING -> log(tr("${c.file} HILANG", "${c.file} MISSING"))
            FileVerdict.UNKNOWN -> log("${c.file}: ${c.note}")
        }
    }
    val result = RomCheckResult(items, hasLists)
    log(
        tr(
            "Selesai: ${result.of(FileVerdict.OK).size} cocok, " +
                "${result.of(FileVerdict.CORRUPT).size} korup, " +
                "${result.of(FileVerdict.MISSING).size} hilang, " +
                "${result.of(FileVerdict.UNKNOWN).size} tidak bisa dipastikan",
            "Done: ${result.of(FileVerdict.OK).size} match, " +
                "${result.of(FileVerdict.CORRUPT).size} corrupt, " +
                "${result.of(FileVerdict.MISSING).size} missing, " +
                "${result.of(FileVerdict.UNKNOWN).size} could not be confirmed"
        )
    )
    return result
}

/** Cocok dengan salah satu daftar. Hasil cocok tidak perlu menunggu cara hitungnya dipercaya. */
private fun isMatch(m: Measure): Boolean {
    if (m.crcExp != null && m.crcGot == m.crcExp) return true
    val e = m.partsExp
    val g = m.partsGot
    if (e != null && g != null && e == g) return true
    return m.sumExp != null && m.sumGot == m.sumExp
}

private fun judge(
    m: Measure,
    crcTrusted: Boolean,
    sparseTrusted: Boolean,
    sumTrusted: Boolean,
    hasLists: Boolean
): FileCheck {
    if (m.missing) return FileCheck(m.file, FileVerdict.MISSING, tr("file tidak ada di paket", "file is not in the package"))
    m.structError?.let { return FileCheck(m.file, FileVerdict.CORRUPT, it) }

    if (m.crcExp != null && m.crcGot == m.crcExp) return FileCheck(m.file, FileVerdict.OK, tr("cocok", "matches"))
    val e = m.partsExp
    val g = m.partsGot
    val comparable = e != null && g != null && e.size == g.size
    if (comparable && e == g) return FileCheck(m.file, FileVerdict.OK, tr("cocok", "matches"))

    if (m.crcExp != null && crcTrusted) {
        return FileCheck(m.file, FileVerdict.CORRUPT, tr("isi tidak sama dengan crclist.txt", "content differs from crclist.txt"))
    }
    if (comparable && e != null && g != null && sparseTrusted) {
        val bad = e.indices.filter { e[it] != g[it] }
        if (e.size == 1 || bad.size * 2 <= e.size) {
            val shown = bad.take(3).joinToString(", ") { (it + 1).toString() }
            return FileCheck(
                m.file, FileVerdict.CORRUPT,
                tr("bagian $shown dari ${e.size} tidak sama dengan sparsecrclist.txt",
                    "part $shown of ${e.size} differs from sparsecrclist.txt")
            )
        }
    }
    if (m.sumExp != null && m.sumGot == m.sumExp) {
        return FileCheck(m.file, FileVerdict.OK, tr("cocok (Checksum.ini)", "matches (Checksum.ini)"))
    }
    if (m.sumExp != null && sumTrusted) {
        return FileCheck(m.file, FileVerdict.CORRUPT, tr("isi tidak sama dengan Checksum.ini", "content differs from Checksum.ini"))
    }
    val listed = m.crcExp != null || m.partsExp != null || m.sumExp != null
    val note = when {
        !hasLists -> tr("paket ini tidak punya daftar pembanding", "this package has no reference list")
        listed -> tr("tidak bisa dibandingkan dengan daftar paket ini", "cannot be compared with this package's list")
        else -> tr("tidak ada di daftar pembanding", "not in the reference list")
    }
    return FileCheck(m.file, FileVerdict.UNKNOWN, note)
}
