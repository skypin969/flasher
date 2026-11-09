package com.skypin.flasher

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

/**
 * Satu sesi shell. Mode tanpa root memakai "sh", mode root memakai "su"
 * (KernelSU meminta izin sekali). Keluaran dibaca di thread terpisah dan
 * dikirim baris demi baris lewat [onLine].
 */
class ShellSession(
    private val root: Boolean,
    private val dir: File,
    private val onLine: (String) -> Unit,
    private val onEnd: () -> Unit
) {
    private var process: Process? = null
    private var writer: BufferedWriter? = null

    @Volatile
    private var closing = false

    /** Mengembalikan false jika tidak ada shell yang bisa dibuka. */
    fun start(): Boolean {
        val candidates = if (root) {
            listOf("su", "/system/bin/su", "/system/xbin/su", "/sbin/su")
        } else {
            listOf("sh")
        }
        for (bin in candidates) {
            val proc: Process = try {
                ProcessBuilder(bin).directory(dir).redirectErrorStream(true).start()
            } catch (e: Exception) {
                continue
            }
            process = proc
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
            val reader = Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        if (!closing) onLine(line)
                    }
                } catch (e: Exception) {
                    // aliran putus: dianggap sesi berakhir
                }
                if (!closing) onEnd()
            }
            reader.isDaemon = true
            reader.start()
            return true
        }
        return false
    }

    /** Mengirim satu baris perintah. Mengembalikan false jika gagal. */
    fun send(cmd: String): Boolean {
        val w = writer ?: return false
        return try {
            w.write(cmd)
            w.write("\n")
            w.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        closing = true
        try {
            writer?.close()
        } catch (e: Exception) {
            // sudah tertutup
        }
        process?.destroy()
    }
}

/**
 * TERMINAL: perintah shell seperti Termux. Mode TANPA ROOT memakai sh,
 * mode ROOT memakai su. Tidak ada konfirmasi untuk perintah berisiko.
 * Riwayat perintah disimpan permanen (maks. 50) dan bisa diketuk untuk
 * mengisi kolom perintah.
 */
class TerminalActivity : FlasherBase() {

    private var rootMode = false
    private var shell: ShellSession? = null

    private lateinit var termSegNormal: TextView
    private lateinit var termSegRoot: TextView
    private lateinit var termHistoryBtn: TextView
    private lateinit var termPrompt: TextView
    private lateinit var termScroll: ScrollView
    private lateinit var termLog: TextView
    private lateinit var termHistoryPanel: LinearLayout
    private lateinit var termHistoryList: LinearLayout
    private lateinit var termInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildUi()
        switchMode(false)
    }

    override fun onDestroy() {
        closeShell()
        super.onDestroy()
    }

    // ---------- mode dan sesi shell ----------

    /** Menutup sesi lama lalu membuka shell baru untuk mode yang dipilih. */
    private fun switchMode(asRoot: Boolean) {
        closeShell()
        closeHistory()
        rootMode = asRoot
        styleSeg(termSegNormal, !asRoot)
        styleSeg(termSegRoot, asRoot)
        termPrompt.text = if (asRoot) "#" else "\$"
        termPrompt.setTextColor(if (asRoot) NeonTheme.MAGENTA else NeonTheme.CYAN)

        val info = if (asRoot) {
            tr("== mode ROOT ==", "== ROOT mode ==")
        } else {
            tr("== mode TANPA ROOT ==", "== NO ROOT mode ==")
        }
        appendLog(info, pal().muted)

        val s = ShellSession(
            root = asRoot,
            dir = filesDir,
            onLine = { line -> runOnUiThread { appendLog(line, pal().text) } },
            onEnd = { runOnUiThread { shellEnded() } }
        )
        if (s.start()) {
            shell = s
        } else {
            appendLog(
                tr("Shell tidak bisa dibuka. Untuk mode ROOT, pastikan su tersedia dan izinkan Flashmate di manager root.",
                    "Shell could not be opened. For ROOT mode, make sure su is available and allow Flashmate in your root manager."),
                NeonTheme.DANGER
            )
        }
    }

    /** Dipanggil saat proses shell berhenti sendiri (misalnya izin root ditolak). */
    private fun shellEnded() {
        shell = null
        val msg = if (rootMode) {
            tr("== sesi berakhir. Untuk ROOT, izinkan Flashmate di manager root, lalu pilih ROOT lagi ==",
                "== session ended. For ROOT, allow Flashmate in your root manager, then choose ROOT again ==")
        } else {
            tr("== sesi berakhir. Pilih mode lagi untuk membuka shell baru ==",
                "== session ended. Choose a mode again to open a new shell ==")
        }
        appendLog(msg, pal().muted)
    }

    private fun closeShell() {
        shell?.close()
        shell = null
    }

    // ---------- kirim perintah ----------

    private fun onSend() {
        val cmd = termInput.text.toString().trim()
        if (cmd.isEmpty()) return
        val s = shell
        if (s == null) {
            appendLog(tr("Shell belum aktif. Pilih mode lagi.", "Shell is not active. Choose a mode again."), NeonTheme.DANGER)
            return
        }
        appendCommand(cmd)
        addHistory(cmd, rootMode)
        termInput.setText("")
        if (!s.send(cmd)) {
            appendLog(tr("Perintah tidak terkirim. Pilih mode lagi.", "Command was not sent. Choose a mode again."), NeonTheme.DANGER)
        }
    }

    // ---------- keluaran ----------

    /** Menambah satu baris keluaran berwarna ke layar terminal. */
    private fun appendLog(text: String, color: Int) {
        val s = SpannableString(text + "\n")
        s.setSpan(ForegroundColorSpan(color), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        termLog.append(s)
        termScroll.post { termScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Menambah perintah yang diketik: prompt berwarna, perintah putih. */
    private fun appendCommand(cmd: String) {
        val mark = if (rootMode) "# " else "\$ "
        val s = SpannableString(mark + cmd + "\n")
        val markColor = if (rootMode) NeonTheme.MAGENTA else NeonTheme.CYAN
        s.setSpan(ForegroundColorSpan(markColor), 0, mark.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(ForegroundColorSpan(pal().text), mark.length, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        termLog.append(s)
        termScroll.post { termScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- riwayat ----------

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun loadHistory(): JSONArray {
        val raw = prefs().getString(KEY_HISTORY, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    /** Perintah terbaru di atas. Perintah yang sama (mode sama) dihapus dulu lalu ditaruh di atas lagi. */
    private fun addHistory(cmd: String, asRoot: Boolean) {
        val old = loadHistory()
        val items = mutableListOf<JSONObject>()
        items.add(JSONObject().put("cmd", cmd).put("root", asRoot))
        for (i in 0 until old.length()) {
            if (items.size >= HISTORY_MAX) break
            val o = old.optJSONObject(i) ?: continue
            if (o.optString("cmd") == cmd && o.optBoolean("root") == asRoot) continue
            items.add(o)
        }
        val arr = JSONArray()
        for (o in items) arr.put(o)
        prefs().edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun toggleHistory() {
        if (termHistoryPanel.visibility == View.VISIBLE) closeHistory() else openHistory()
    }

    private fun openHistory() {
        renderHistory()
        termScroll.visibility = View.GONE
        termHistoryPanel.visibility = View.VISIBLE
        styleHistoryBtn(true)
    }

    private fun closeHistory() {
        termHistoryPanel.visibility = View.GONE
        termScroll.visibility = View.VISIBLE
        styleHistoryBtn(false)
    }

    private fun renderHistory() {
        termHistoryList.removeAllViews()
        val items = loadHistory()
        if (items.length() == 0) {
            termHistoryList.addView(caption(tr("belum ada riwayat", "no history yet")))
            return
        }
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            termHistoryList.addView(historyRow(o.optString("cmd"), o.optBoolean("root"), i == 0))
        }
    }

    /** Satu baris riwayat. Diketuk: perintah masuk ke kolom input, mode tidak berubah. */
    private fun historyRow(cmd: String, asRoot: Boolean, first: Boolean): LinearLayout {
        val p = pal()
        val tag = if (asRoot) "ROOT" else tr("TANPA ROOT", "NO ROOT")
        val badge = if (first) "$tag · " + tr("terbaru", "latest") else tag
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = neonBorder(10, p.card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = lp(top = 8)
            isClickable = true
            setOnClickListener {
                termInput.setText(cmd)
                termInput.setSelection(cmd.length)
                termInput.requestFocus()
                closeHistory()
            }
            addView(textLine(badge, 11f, medium = true, color = if (asRoot) NeonTheme.MAGENTA else p.muted))
            addView(textLine(cmd, 14f, color = p.text, mono = true).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun styleHistoryBtn(on: Boolean) {
        val p = pal()
        termHistoryBtn.setTextColor(if (on) p.onAccent else p.muted)
        termHistoryBtn.background = if (on) neonButtonBackground(this, 999) else rounded(p.card, 999, p.border)
    }

    // ---------- tampilan ----------

    /** Tombol kecil berbentuk pil untuk RIWAYAT dan BERSIHKAN. */
    private fun pillButton(label: String, action: () -> Unit): TextView {
        val p = pal()
        return textLine(label, 12f, medium = true, color = p.muted, mono = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(p.card, 999, p.border)
            setOnClickListener { action() }
        }
    }

    /** Segmen pilihan mode: TANPA ROOT atau ROOT. */
    private fun modeSeg(label: String, asRoot: Boolean): TextView {
        return textLine(label, 12f, medium = true, color = pal().muted, mono = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                if (rootMode != asRoot || shell == null) switchMode(asRoot)
            }
        }
    }

    private fun styleSeg(v: TextView, on: Boolean) {
        val p = pal()
        v.setTextColor(if (on) p.onAccent else p.muted)
        v.background = if (on) neonButtonBackground(this, 999) else null
    }

    private fun buildUi() {
        val p = pal()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.background = neonBackground()

        // Header: tombol kembali dan judul bergradasi neon
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(textLine("< " + tr("Kembali", "Back"), 14f, medium = true, color = p.muted).apply {
            setPadding(0, dp(6), dp(14), dp(6))
            setOnClickListener { finish() }
        })
        val title = textLine("TERMINAL", 22f, medium = true, color = p.text).apply {
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        applyNeonText(title)
        header.addView(title)
        root.addView(header)
        root.addView(caption(tr("jalankan perintah di sini", "run commands here")))

        // Kartu mode: TANPA ROOT atau ROOT
        val modeCard = neonCard()
        val segs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(p.card, 999, p.border)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        termSegNormal = modeSeg(tr("TANPA ROOT", "NO ROOT"), false)
        termSegRoot = modeSeg(tr("ROOT", "ROOT"), true)
        segs.addView(termSegNormal)
        segs.addView(termSegRoot)
        modeCard.addView(segs)
        modeCard.addView(textLine(
            tr("mode root memakai su, izinkan dari manager root di perangkat anda.",
                "root mode uses su, allow it from your root manager on this device."),
            12f,
            color = p.muted
        ).apply { setPadding(0, dp(8), 0, 0) })
        root.addView(modeCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        // Kartu layar: alat di atas, lalu keluaran atau panel riwayat
        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 12, p.border)
            setPadding(dp(10), dp(8), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(12) }
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        termHistoryBtn = pillButton("↑ RIWAYAT") { toggleHistory() }
        toolbar.addView(termHistoryBtn)
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        toolbar.addView(pillButton(tr("BERSIHKAN", "CLEAR")) { termLog.text = "" })
        logCard.addView(toolbar)

        termLog = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(p.text)
            setTextIsSelectable(true)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        termScroll = ScrollView(this).apply {
            addView(termLog)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        termHistoryPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val panelHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        panelHead.addView(textLine(tr("RIWAYAT PERINTAH", "COMMAND HISTORY"), 12f, medium = true, color = p.text, mono = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        panelHead.addView(textLine("× " + tr("TUTUP", "CLOSE"), 12f, medium = true, color = p.muted, mono = true).apply {
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener { closeHistory() }
        })
        termHistoryPanel.addView(panelHead)
        val historyScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        termHistoryList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        historyScroll.addView(termHistoryList)
        termHistoryPanel.addView(historyScroll)
        termHistoryPanel.addView(caption(tr("ketuk perintah untuk mengisi kolom di bawah",
            "tap a command to fill the box below")))

        logCard.addView(termScroll)
        logCard.addView(termHistoryPanel)
        root.addView(logCard)

        // Baris input: prompt, kolom perintah, dan KIRIM
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        termPrompt = textLine("\$", 16f, medium = true, color = NeonTheme.CYAN, mono = true).apply {
            setPadding(0, 0, dp(8), 0)
        }
        inputRow.addView(termPrompt)
        termInput = EditText(this).apply {
            hint = tr("ketik perintah", "type a command")
            setHintTextColor(p.muted)
            setTextColor(p.text)
            typeface = Typeface.MONOSPACE
            textSize = 14f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = rounded(p.card, 10, p.border)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    onSend()
                    true
                } else {
                    false
                }
            }
        }
        inputRow.addView(termInput)
        inputRow.addView(textLine(tr("KIRIM", "SEND"), 13f, medium = true, color = p.onAccent).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = neonButtonBackground(this@TerminalActivity, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
            setOnClickListener { onSend() }
        })
        root.addView(inputRow)

        setContentView(root)
        styleHistoryBtn(false)
    }

    companion object {
        private const val PREFS_NAME = "flasher"
        private const val KEY_HISTORY = "term_history"
        private const val HISTORY_MAX = 50
    }
}
