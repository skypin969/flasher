package com.skypin.flasher

import java.io.IOException
import java.io.InputStream
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min

/** Sumber gambar: ukuran file dan fungsi untuk membukanya (boleh dibuka berulang). */
class ImageSrc(val size: Long, val open: () -> InputStream)

private const val SPARSE_MAGIC = 0xED26FF3AL
private const val CHUNK_RAW = 0xCAC1
private const val CHUNK_FILL = 0xCAC2
private const val CHUNK_DONT_CARE = 0xCAC3
private const val CHUNK_CRC32 = 0xCAC4
private const val FILE_HDR = 28
private const val CHUNK_HDR = 12

class SrcChunk(val type: Int, val outBlk: Long, val blks: Long, val srcOff: Long, val fill: ByteArray?)

class SparseMap(
    val blk: Long,
    val totalBlks: Long,
    val chunks: List<SrcChunk>,
    val isSparse: Boolean,
    val rawSize: Long
)

/** Potongan keluaran: bytes tetap, atau data dari file sumber. */
sealed class Seg(val size: Long) {
    class Bytes(val b: ByteArray) : Seg(b.size.toLong())
    class Raw(val off: Long, val avail: Long, n: Long) : Seg(n)
}

class SparsePart(val segs: List<Seg>, val size: Long)

private class PItem(val type: Int, val blks: Long, val off: Long, val avail: Long, val fill: ByteArray?)

/** Pembaca maju saja (tanpa mundur) untuk satu aliran file. */
class SourceReader(private val input: InputStream) {
    private var pos = 0L

    fun position(): Long = pos

    fun seek(target: Long) {
        if (target < pos) throw IOException("Pembacaan file mundur (internal)")
        var rem = target - pos
        while (rem > 0) {
            val s = input.skip(rem)
            if (s > 0) {
                rem -= s
                pos += s
            } else {
                if (input.read() < 0) throw IOException("File habis saat melewati data")
                rem -= 1
                pos += 1
            }
        }
    }

    fun skipBytes(n: Long) {
        seek(pos + n)
    }

    fun readBytes(n: Int): ByteArray {
        val b = ByteArray(n)
        readInto(b, 0, n)
        return b
    }

    fun readInto(b: ByteArray, off: Int, len: Int) {
        var got = 0
        while (got < len) {
            val n = input.read(b, off + got, len - got)
            if (n < 0) throw IOException("File habis saat dibaca")
            got += n
            pos += n
        }
    }

    fun close() {
        input.close()
    }
}

object SparseImage {

    /** Membaca struktur gambar. Gambar biasa (raw) dianggap satu chunk RAW. */
    fun scan(img: ImageSrc): SparseMap {
        if (img.size < FILE_HDR) return rawMap(img.size)
        val r = SourceReader(img.open())
        try {
            val head = r.readBytes(FILE_HDR)
            if (le32(head, 0) != SPARSE_MAGIC) return rawMap(img.size)
            val fileHdr = le16(head, 8)
            val chdr = le16(head, 10)
            val blk = le32(head, 12)
            val total = le32(head, 16)
            val nChunks = le32(head, 20)
            if (blk <= 0L || chdr < CHUNK_HDR || fileHdr < FILE_HDR) {
                throw IOException("Header sparse image rusak")
            }
            if (fileHdr > FILE_HDR) r.skipBytes((fileHdr - FILE_HDR).toLong())

            val chunks = ArrayList<SrcChunk>()
            var out = 0L
            for (k in 0L until nChunks) {
                val ch = r.readBytes(chdr)
                val type = le16(ch, 0)
                val chunkSz = le32(ch, 4)
                val dataLen = le32(ch, 8) - chdr
                when (type) {
                    CHUNK_RAW -> {
                        chunks.add(SrcChunk(CHUNK_RAW, out, chunkSz, r.position(), null))
                        r.skipBytes(dataLen)
                        out += chunkSz
                    }
                    CHUNK_FILL -> {
                        val f = r.readBytes(4)
                        r.skipBytes(dataLen - 4)
                        chunks.add(SrcChunk(CHUNK_FILL, out, chunkSz, 0L, f))
                        out += chunkSz
                    }
                    CHUNK_DONT_CARE -> {
                        r.skipBytes(dataLen)
                        out += chunkSz
                    }
                    CHUNK_CRC32 -> r.skipBytes(dataLen)
                    else -> throw IOException("Tipe chunk tidak dikenal: $type")
                }
            }
            return SparseMap(blk, max(total, out), chunks, true, img.size)
        } finally {
            r.close()
        }
    }

    private fun rawMap(size: Long): SparseMap {
        val blk = 4096L
        val blks = (size + blk - 1) / blk
        val chunks = if (blks > 0L) listOf(SrcChunk(CHUNK_RAW, 0L, blks, 0L, null)) else emptyList()
        return SparseMap(blk, blks, chunks, false, size)
    }

    /**
     * Memecah gambar menjadi beberapa bagian sparse, masing-masing <= maxDl byte.
     * Setiap bagian memakai DONT_CARE untuk melewati area yang tidak ada di bagian itu.
     */
    fun planParts(m: SparseMap, maxDl: Long): List<SparsePart> {
        val blk = m.blk
        val parts = ArrayList<SparsePart>()
        var ci = 0
        var consumed = 0L
        while (ci < m.chunks.size) {
            val items = ArrayList<PItem>()
            var budget = maxDl - FILE_HDR - CHUNK_HDR // sisakan 12 byte untuk DONT_CARE penutup
            var out = 0L
            while (ci < m.chunks.size) {
                val c = m.chunks[ci]
                val start = c.outBlk + consumed
                if (start > out) {
                    if (budget < CHUNK_HDR) break
                    items.add(PItem(CHUNK_DONT_CARE, start - out, 0L, 0L, null))
                    budget -= CHUNK_HDR
                    out = start
                }
                val remain = c.blks - consumed
                if (c.type == CHUNK_FILL) {
                    if (budget < CHUNK_HDR + 4) break
                    items.add(PItem(CHUNK_FILL, remain, 0L, 0L, c.fill))
                    budget -= CHUNK_HDR + 4
                    out += remain
                    ci++
                    consumed = 0L
                } else {
                    val maxN = (budget - CHUNK_HDR) / blk
                    if (maxN <= 0L) break
                    val n = min(remain, maxN)
                    val off = c.srcOff + consumed * blk
                    val avail = if (m.isSparse) n * blk else max(0L, min(n * blk, m.rawSize - off))
                    items.add(PItem(CHUNK_RAW, n, off, avail, null))
                    budget -= CHUNK_HDR + n * blk
                    out += n
                    consumed += n
                    if (consumed == c.blks) {
                        ci++
                        consumed = 0L
                    } else {
                        break
                    }
                }
            }
            if (items.none { it.type != CHUNK_DONT_CARE }) {
                throw IOException("max-download-size perangkat terlalu kecil untuk gambar ini")
            }
            if (out < m.totalBlks) items.add(PItem(CHUNK_DONT_CARE, m.totalBlks - out, 0L, 0L, null))
            parts.add(buildPart(items, m.totalBlks, blk))
        }
        return parts
    }

    private fun buildPart(items: List<PItem>, total: Long, blk: Long): SparsePart {
        val segs = ArrayList<Seg>()
        var size = FILE_HDR.toLong()
        segs.add(Seg.Bytes(fileHeader(total, items.size, blk)))
        for (item in items) {
            val payload = when (item.type) {
                CHUNK_RAW -> item.blks * blk
                CHUNK_FILL -> 4L
                else -> 0L
            }
            segs.add(Seg.Bytes(chunkHeader(item.type, item.blks, CHUNK_HDR + payload)))
            size += CHUNK_HDR + payload
            when (item.type) {
                CHUNK_RAW -> segs.add(Seg.Raw(item.off, item.avail, item.blks * blk))
                CHUNK_FILL -> segs.add(Seg.Bytes(item.fill!!))
            }
        }
        return SparsePart(segs, size)
    }

    private fun fileHeader(total: Long, chunks: Int, blk: Long): ByteArray {
        val b = ByteArray(FILE_HDR)
        put32(b, 0, SPARSE_MAGIC)
        put16(b, 4, 1)
        put16(b, 6, 0)
        put16(b, 8, FILE_HDR)
        put16(b, 10, CHUNK_HDR)
        put32(b, 12, blk)
        put32(b, 16, total)
        put32(b, 20, chunks.toLong())
        put32(b, 24, 0L)
        return b
    }

    private fun chunkHeader(type: Int, blks: Long, total: Long): ByteArray {
        val b = ByteArray(CHUNK_HDR)
        put16(b, 0, type)
        put16(b, 2, 0)
        put32(b, 4, blks)
        put32(b, 8, total)
        return b
    }

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte()
        b[o + 1] = (v shr 8).toByte()
    }

    private fun put32(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte()
    }

    private fun le16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}

/** Aliran keluaran satu bagian. Sumber dibaca maju saja, jadi bagian harus dibaca berurutan. */
class PartStream(private val part: SparsePart, private val src: SourceReader) : InputStream() {
    private var si = 0
    private var sp = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (si < part.segs.size) {
            val seg = part.segs[si]
            if (sp >= seg.size) {
                si++
                sp = 0L
                continue
            }
            val want = min(len.toLong(), seg.size - sp).toInt()
            when (seg) {
                is Seg.Bytes -> System.arraycopy(seg.b, sp.toInt(), b, off, want)
                is Seg.Raw -> {
                    val fromFile = max(0L, min(want.toLong(), seg.avail - sp)).toInt()
                    if (fromFile > 0) {
                        src.seek(seg.off + sp)
                        src.readInto(b, off, fromFile)
                    }
                    if (fromFile < want) Arrays.fill(b, off + fromFile, off + want, 0.toByte())
                }
            }
            sp += want
            return want
        }
        return -1
    }
}
