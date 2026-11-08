package com.skypin.flasher

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * Ekstrak arsip ROM (.tgz / .tar.gz / .tar) ke folder tujuan.
 * Folder paling atas di dalam arsip dibuang, jadi flash_all.sh dan images/ langsung ada di folder tujuan.
 */
object TgzExtractor {

    fun extract(input: InputStream, dest: File, log: (String) -> Unit) {
        val bis = BufferedInputStream(input, 65536)
        bis.mark(4)
        val b0 = bis.read()
        val b1 = bis.read()
        bis.reset()
        val tar: InputStream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(bis, 65536) else bis

        val root = dest.canonicalFile
        root.mkdirs()
        val header = ByteArray(512)
        var longName: String? = null
        var paxName: String? = null
        var files = 0

        while (readFull(tar, header)) {
            if (header.all { it == 0.toByte() }) break
            val size = parseSize(header)
            val type = header[156].toInt().toChar()
            when (type) {
                'L' -> {
                    longName = cstrOf(readAll(tar, size))
                    skipFully(tar, pad(size))
                }
                'x' -> {
                    paxName = paxPath(readAll(tar, size))
                    skipFully(tar, pad(size))
                }
                '0', '\u0000', '7', '5' -> {
                    val rawName = longName ?: paxName ?: ustarName(header)
                    longName = null
                    paxName = null
                    val rel = relativePath(rawName)
                    if (rel == null) {
                        skipFully(tar, size)
                    } else {
                        val out = File(root, rel)
                        if (!out.canonicalPath.startsWith(root.path + File.separator)) {
                            throw IOException("Path mencurigakan di arsip: $rawName")
                        }
                        if (type == '5') {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { copyN(tar, it, size) }
                            files++
                            log(rel)
                        }
                    }
                    skipFully(tar, pad(size))
                }
                else -> {
                    longName = null
                    paxName = null
                    skipFully(tar, size + pad(size))
                }
            }
        }
        log("Ekstrak selesai: $files file")
    }

    private fun pad(size: Long): Long = (512 - size % 512) % 512

    private fun readFull(s: InputStream, b: ByteArray): Boolean {
        var got = 0
        while (got < b.size) {
            val n = s.read(b, got, b.size - got)
            if (n < 0) return false
            got += n
        }
        return true
    }

    /** Baca data metadata kecil (nama panjang, pax) ke memori. */
    private fun readAll(s: InputStream, n: Long): ByteArray {
        if (n < 0L || n > 4_000_000L) throw IOException("Entri metadata arsip tidak valid")
        val b = ByteArray(n.toInt())
        var got = 0
        while (got < b.size) {
            val r = s.read(b, got, b.size - got)
            if (r < 0) throw IOException("Arsip terpotong")
            got += r
        }
        return b
    }

    private fun copyN(s: InputStream, out: OutputStream, n: Long) {
        val buf = ByteArray(65536)
        var rem = n
        while (rem > 0) {
            val r = s.read(buf, 0, minOf(rem, buf.size.toLong()).toInt())
            if (r < 0) throw IOException("Arsip terpotong")
            out.write(buf, 0, r)
            rem -= r
        }
    }

    private fun skipFully(s: InputStream, n: Long) {
        var rem = n
        val buf = ByteArray(8192)
        while (rem > 0) {
            val r = s.read(buf, 0, minOf(rem, buf.size.toLong()).toInt())
            if (r < 0) throw IOException("Arsip terpotong")
            rem -= r
        }
    }

    private fun cstrOf(b: ByteArray): String {
        var end = 0
        while (end < b.size && b[end] != 0.toByte()) end++
        return String(b, 0, end, Charsets.UTF_8)
    }

    private fun ustarName(h: ByteArray): String {
        val name = cstrOf(h.copyOfRange(0, 100))
        val prefix = cstrOf(h.copyOfRange(345, 500))
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun parseSize(h: ByteArray): Long {
        var v = 0L
        if ((h[124].toInt() and 0x80) != 0) {
            for (i in 125 until 136) v = (v shl 8) or (h[i].toLong() and 0xFF)
            return v
        }
        for (i in 124 until 136) {
            val c = h[i].toInt()
            if (c in 48..55) v = v * 8 + (c - 48)
        }
        return v
    }

    /** Ambil nilai 'path=' dari data pax header. */
    private fun paxPath(d: ByteArray): String? {
        var i = 0
        while (i < d.size) {
            var sp = i
            while (sp < d.size && d[sp] != ' '.code.toByte()) sp++
            if (sp >= d.size) return null
            val len = String(d, i, sp - i, Charsets.US_ASCII).toIntOrNull() ?: return null
            val end = i + len
            if (len <= 0 || end > d.size || end - sp - 2 < 0) return null
            val kv = String(d, sp + 1, end - sp - 2, Charsets.UTF_8)
            if (kv.startsWith("path=")) return kv.substring(5)
            i = end
        }
        return null
    }

    /** Buang komponen paling atas, tolak '..' dan path kosong. */
    private fun relativePath(name: String): String? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        val kept = if (parts.size >= 2) parts.drop(1) else parts
        return if (kept.isEmpty()) null else kept.joinToString("/")
    }
}
