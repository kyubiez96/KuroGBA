package com.kyubiez96.kurogba

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Game screen: emulation thread + audio thread + render polling thread,
 * touch controls overlay, in-game menu (states, fast-forward, cheats,
 * opacity), and SRAM battery autosave.
 */
class GameActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "rom_uri"
        const val EXTRA_NAME = "rom_name"
    }

    private lateinit var screenView: ScreenView
    private lateinit var batteryIndicator: TextView
    private lateinit var ffBadge: TextView
    private lateinit var controlsOverlay: FrameLayout

    private var romName = "game.gba"
    private var romKey = "game"

    // Input state, one mask per source, OR-ed into the core.
    private var dpadBits = 0
    private var buttonBits = 0

    @Volatile private var emuRunning = false
    @Volatile private var paused = true
    @Volatile private var destroyed = false
    private var coreReady = false
    private var ffSpeed = 1
    private var cheatText = ""

    private var emuThread: Thread? = null
    private var audioThread: Thread? = null
    private var renderThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    private val io = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastSramHash = 0
    private var framesSinceSramCheck = 0
    private val hideBatteryRunnable = Runnable { batteryIndicator.visibility = View.GONE }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        romName = intent.getStringExtra(EXTRA_NAME) ?: "game.gba"
        romKey = sanitizeKey(romName)
        title = romName

        screenView = findViewById(R.id.screenView)
        batteryIndicator = findViewById(R.id.batteryIndicator)
        batteryIndicator.text = "● ${getString(R.string.battery_saved)}"
        ffBadge = findViewById(R.id.ffBadge)
        controlsOverlay = findViewById(R.id.controlsOverlay)

        ControlsStyle.opacity = getSharedPreferences("settings", MODE_PRIVATE)
            .getInt(ControlsStyle.PREF_KEY, 150)

        buildControls()
        findViewById<Button>(R.id.menuButton).setOnClickListener { showMenu() }

        val uriString = intent.getStringExtra(EXTRA_URI)
        if (uriString == null) {
            finish()
            return
        }
        loadRom(Uri.parse(uriString))
    }

    /* ---------------- ROM loading ---------------- */

    private fun loadRom(uri: Uri) {
        io.execute {
            val bytes = try {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                when {
                    bytes == null -> fail(getString(R.string.err_read_rom))
                    !looksLikeGba(bytes) -> fail(getString(R.string.err_not_gba))
                    else -> startEmulation(bytes)
                }
            }
        }
    }

    private fun looksLikeGba(bytes: ByteArray): Boolean {
        if (bytes.size < 0xC0 || bytes.size > 64 * 1024 * 1024) return false
        // GBA header: entry branch top byte 0xEA at [3], fixed 0x96 at [0xB2].
        return bytes[3] == 0xEA.toByte() && bytes[0xB2] == 0x96.toByte()
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun startEmulation(romBytes: ByteArray) {
        val corePath = File(applicationInfo.nativeLibraryDir, "libmgba_libretro.so").absolutePath
        val systemDir = File(filesDir, "system").apply { mkdirs() }
        val saveDir = File(filesDir, "core_saves").apply { mkdirs() }
        if (!NativeBridge.nativeInit(corePath, systemDir.absolutePath, saveDir.absolutePath)) {
            fail(getString(R.string.err_load_failed))
            return
        }
        val fallback = File(cacheDir, "rom_fallback.gba").absolutePath
        if (!NativeBridge.nativeLoadRom(romBytes, romName, fallback)) {
            fail(getString(R.string.err_load_failed))
            return
        }
        coreReady = true

        emuRunning = true
        paused = true
        emuThread = Thread(::emuLoop, "kurogba-emu").apply { start() }
        audioThread = Thread(::audioLoop, "kurogba-audio").apply { start() }
        renderThread = Thread(::renderLoop, "kurogba-render").apply { start() }

        // Restore battery save (goes through the native command queue, which
        // the paused emu loop services), snapshot the baseline hash, then go.
        io.execute {
            val sav = sramFile()
            if (sav.exists()) {
                try {
                    NativeBridge.nativeSetSaveRam(sav.readBytes())
                } catch (_: Exception) {
                }
            }
            lastSramHash = NativeBridge.nativeGetSaveRam()?.contentHashCode() ?: 0
            runOnUiThread { if (!destroyed) paused = false }
        }
    }

    /* ---------------- Threads ---------------- */

    private fun emuLoop() {
        while (emuRunning) {
            if (paused) {
                NativeBridge.nativeStep(0) // still services the command queue
                sleepQuiet(30)
                continue
            }
            val t0 = System.nanoTime()
            NativeBridge.nativeStep(ffSpeed)
            framesSinceSramCheck += ffSpeed
            if (framesSinceSramCheck >= 360) {
                framesSinceSramCheck = 0
                checkSramAutosave()
            }
            val fps = NativeBridge.nativeGetFps()
            val targetNs = (1_000_000_000.0 / fps).toLong()
            val remain = targetNs - (System.nanoTime() - t0)
            if (remain > 1_000_000L) {
                sleepQuiet(remain / 1_000_000L, (remain % 1_000_000L).toInt())
            }
        }
    }

    private fun audioLoop() {
        val rate = NativeBridge.nativeGetSampleRate()
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()
        val buf = ShortArray(2048)
        while (emuRunning) {
            if (paused || ffSpeed > 1) {
                sleepQuiet(10)
                continue
            }
            val frames = NativeBridge.nativeReadAudio(buf)
            if (frames > 0) {
                track.write(buf, 0, frames * 2)
            } else {
                sleepQuiet(3)
            }
        }
        track.stop()
        track.release()
    }

    private fun renderLoop() {
        while (emuRunning) {
            if (!screenView.pollFrame()) sleepQuiet(2)
        }
    }

    private fun sleepQuiet(ms: Long, ns: Int = 0) {
        try {
            if (ns == 0) Thread.sleep(ms) else Thread.sleep(ms, ns)
        } catch (_: InterruptedException) {
        }
    }

    /* ---------------- SRAM (battery) ---------------- */

    private fun sramFile(): File =
        File(File(filesDir, "saves").apply { mkdirs() }, "$romKey.sav")

    /** Called on the emu thread; the native call then runs inline in the core. */
    private fun checkSramAutosave() {
        val data = NativeBridge.nativeGetSaveRam() ?: return
        val hash = data.contentHashCode()
        if (hash != lastSramHash) {
            lastSramHash = hash
            try {
                sramFile().writeBytes(data)
            } catch (_: Exception) {
            }
            mainHandler.post {
                batteryIndicator.visibility = View.VISIBLE
                mainHandler.removeCallbacks(hideBatteryRunnable)
                mainHandler.postDelayed(hideBatteryRunnable, 2500)
            }
        }
    }

    private fun saveSramNow() {
        if (!coreReady) return
        val data = NativeBridge.nativeGetSaveRam() ?: return
        lastSramHash = data.contentHashCode()
        try {
            sramFile().writeBytes(data)
        } catch (_: Exception) {
        }
    }

    /* ---------------- Controls ---------------- */

    private fun pushButtons() {
        NativeBridge.nativeSetButtons(dpadBits or buttonBits)
    }

    private fun onButton(bit: Int, pressed: Boolean) {
        buttonBits = if (pressed) buttonBits or (1 shl bit) else buttonBits and (1 shl bit).inv()
        pushButtons()
    }

    private fun buildControls() {
        val dpad = DPadView(this) { mask ->
            dpadBits = mask
            pushButtons()
        }
        controlsOverlay.addView(
            dpad,
            FrameLayout.LayoutParams(dp(176), dp(176), Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(16), 0, 0, dp(24))
            }
        )

        // Right cluster: L/R shoulders on top, B/A below.
        val cluster = FrameLayout(this)
        controlsOverlay.addView(
            cluster,
            FrameLayout.LayoutParams(dp(232), dp(196), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(12), dp(24))
            }
        )
        fun action(label: String, bit: Int, size: Int, gravity: Int, end: Int, top: Int, bottom: Int) {
            val v = ActionButtonView(this, label, bit, ::onButton)
            cluster.addView(
                v,
                FrameLayout.LayoutParams(dp(size), dp(size), gravity).apply {
                    setMargins(0, dp(top), dp(end), dp(bottom))
                }
            )
        }
        action("L", NativeBridge.Pad.L, 54, Gravity.TOP or Gravity.START, 0, 0, 0)
        action("R", NativeBridge.Pad.R, 54, Gravity.TOP or Gravity.END, 0, 0, 0)
        action("B", NativeBridge.Pad.B, 62, Gravity.BOTTOM or Gravity.END, 84, 0, 4)
        action("A", NativeBridge.Pad.A, 62, Gravity.BOTTOM or Gravity.END, 4, 0, 40)

        // Start / Select pills, bottom center.
        val pills = FrameLayout(this)
        controlsOverlay.addView(
            pills,
            FrameLayout.LayoutParams(dp(160), dp(30), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                setMargins(0, 0, 0, dp(14))
            }
        )
        val select = PillView(this, "SELECT", NativeBridge.Pad.SELECT, ::onButton)
        pills.addView(select, FrameLayout.LayoutParams(dp(74), dp(28), Gravity.START))
        val start = PillView(this, "START", NativeBridge.Pad.START, ::onButton)
        pills.addView(start, FrameLayout.LayoutParams(dp(74), dp(28), Gravity.END))
    }

    /* ---------------- Menu ---------------- */

    @Deprecated("Back opens the in-game menu")
    override fun onBackPressed() {
        showMenu()
    }

    private fun showMenu() {
        if (!coreReady) {
            finish()
            return
        }
        val wasPaused = paused
        paused = true
        val ffLabel = when (ffSpeed) {
            2 -> getString(R.string.ff_2x)
            4 -> getString(R.string.ff_4x)
            else -> getString(R.string.ff_off)
        }
        val items = arrayOf(
            getString(R.string.menu_save_state),
            getString(R.string.menu_load_state),
            "${getString(R.string.menu_fast_forward)}: $ffLabel",
            getString(R.string.menu_cheats),
            getString(R.string.menu_opacity),
            getString(R.string.menu_reset),
            getString(R.string.menu_exit)
        )
        AlertDialog.Builder(this)
            .setTitle(romName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showSlotDialog(save = true)
                    1 -> showSlotDialog(save = false)
                    2 -> showFfDialog()
                    3 -> showCheatsDialog()
                    4 -> showOpacityDialog()
                    5 -> NativeBridge.nativeReset()
                    6 -> finish()
                }
            }
            .setOnDismissListener { if (!isFinishing) paused = wasPaused }
            .show()
    }

    private fun slotFile(slot: Int): File {
        val dir = File(filesDir, "states/$romKey").apply { mkdirs() }
        return File(dir, if (slot == 0) "quick.state" else "slot$slot.state")
    }

    private fun showSlotDialog(save: Boolean) {
        val labels = (0..5).map { slot ->
            val base = if (slot == 0) getString(R.string.slot_quick) else getString(R.string.slot_n, slot)
            val f = slotFile(slot)
            val suffix = if (f.exists()) {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(f.lastModified()))
            } else {
                getString(R.string.slot_empty)
            }
            "$base — $suffix"
        }
        AlertDialog.Builder(this)
            .setTitle(if (save) R.string.menu_save_state else R.string.menu_load_state)
            .setItems(labels.toTypedArray()) { _, which ->
                if (save) saveStateTo(which) else loadStateFrom(which)
            }
            .show()
    }

    private fun saveStateTo(slot: Int) {
        io.execute {
            val data = NativeBridge.nativeSaveState()
            var ok = false
            if (data != null) {
                try {
                    slotFile(slot).writeBytes(data)
                    ok = true
                } catch (_: Exception) {
                }
            }
            runOnUiThread {
                if (ok) Toast.makeText(this, R.string.state_saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadStateFrom(slot: Int) {
        io.execute {
            val f = slotFile(slot)
            var ok = false
            if (f.exists()) {
                try {
                    ok = NativeBridge.nativeLoadState(f.readBytes())
                } catch (_: Exception) {
                }
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) R.string.state_loaded else R.string.state_load_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showFfDialog() {
        val labels = arrayOf(
            getString(R.string.ff_off), getString(R.string.ff_2x), getString(R.string.ff_4x)
        )
        val current = when (ffSpeed) { 2 -> 1; 4 -> 2; else -> 0 }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_fast_forward)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                setFastForward(intArrayOf(1, 2, 4)[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun setFastForward(speed: Int) {
        ffSpeed = speed
        NativeBridge.nativeSetFastForward(speed > 1)
        if (speed > 1) {
            audioTrack?.pause()
            audioTrack?.flush()
            ffBadge.text = "FF ${speed}×"
            ffBadge.visibility = View.VISIBLE
        } else {
            audioTrack?.play()
            ffBadge.visibility = View.GONE
        }
    }

    private fun showCheatsDialog() {
        val input = EditText(this).apply {
            setText(cheatText)
            hint = getString(R.string.cheats_hint)
            setSingleLine(false)
            maxLines = 5
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.cheats_title)
            .setView(input)
            .setPositiveButton(R.string.cheats_apply) { _, _ ->
                cheatText = input.text.toString().trim()
                if (cheatText.isEmpty()) {
                    NativeBridge.nativeClearCheats()
                } else {
                    NativeBridge.nativeApplyCheat(cheatText)
                }
                Toast.makeText(this, R.string.cheats_applied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.cheats_clear) { _, _ ->
                cheatText = ""
                NativeBridge.nativeClearCheats()
                Toast.makeText(this, R.string.cheats_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showOpacityDialog() {
        val seek = SeekBar(this).apply {
            max = 215
            progress = ControlsStyle.opacity - 40
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                ControlsStyle.opacity = progress + 40
                refreshControlsAlpha()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {
                getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putInt(ControlsStyle.PREF_KEY, ControlsStyle.opacity).apply()
            }
        })
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_opacity)
            .setView(seek)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun refreshControlsAlpha() {
        fun walk(v: View) {
            v.invalidate()
            if (v is FrameLayout) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(controlsOverlay)
    }

    /* ---------------- Lifecycle ---------------- */

    override fun onPause() {
        super.onPause()
        paused = true
        saveSramNow()
    }

    override fun onResume() {
        super.onResume()
        if (coreReady) paused = false
        applyImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onDestroy() {
        destroyed = true
        emuRunning = false
        try { emuThread?.join(600) } catch (_: Exception) {}
        try { audioThread?.join(300) } catch (_: Exception) {}
        try { renderThread?.join(300) } catch (_: Exception) {}
        if (coreReady) NativeBridge.nativeShutdown()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun sanitizeKey(name: String): String {
        val base = name.substringBeforeLast('.', name).ifBlank { "game" }
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
    }
}
