package com.kyubiez96.kurogba

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

/** Library screen: pick a ROM via the system file picker, or reopen a recent one. */
class MainActivity : AppCompatActivity() {

    private lateinit var recentsList: ListView
    private lateinit var emptyLabel: TextView
    private var recents: MutableList<RecentRom> = mutableListOf()
    private lateinit var adapter: RecentsAdapter

    private val openRom = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onRomPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recentsList = findViewById(R.id.recentsList)
        emptyLabel = findViewById(R.id.emptyLabel)
        adapter = RecentsAdapter()
        recentsList.adapter = adapter
        recentsList.setOnItemClickListener { _, _, position, _ ->
            val rom = recents[position]
            launchGame(rom.uri, rom.name)
        }
        recentsList.setOnItemLongClickListener { _, _, position, _ ->
            val rom = recents[position]
            AlertDialog.Builder(this)
                .setTitle(rom.name)
                .setItems(arrayOf(getString(R.string.remove_recent))) { _, _ ->
                    RomLibrary.remove(this, rom.uri)
                    refreshRecents()
                }
                .show()
            true
        }

        findViewById<Button>(R.id.openRomButton).setOnClickListener {
            openRom.launch(arrayOf("*/*"))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRecents()
    }

    private fun refreshRecents() {
        recents = RomLibrary.load(this)
        adapter.notifyDataSetChanged()
        val empty = recents.isEmpty()
        emptyLabel.visibility = if (empty) View.VISIBLE else View.GONE
        recentsList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun onRomPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers don't offer persistable grants; the uri still
            // works for this session, which is acceptable.
        }
        val name = queryDisplayName(uri)
        RomLibrary.add(this, uri.toString(), name)
        launchGame(uri.toString(), name)
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "game.gba"
        try {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.let { name = it }
                }
            }
        } catch (_: Exception) {
        }
        return name
    }

    private fun launchGame(uri: String, name: String) {
        val intent = Intent(this, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_URI, uri)
            putExtra(GameActivity.EXTRA_NAME, name)
        }
        startActivity(intent)
    }

    private inner class RecentsAdapter : BaseAdapter() {
        override fun getCount(): Int = recents.size
        override fun getItem(position: Int): Any = recents[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_recent, parent, false)
            val rom = recents[position]
            view.findViewById<TextView>(R.id.recentTitle).text = rom.name
            val whenText = if (rom.lastPlayed > 0) {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(rom.lastPlayed))
            } else ""
            view.findViewById<TextView>(R.id.recentSub).text = whenText
            return view
        }
    }
}
