package com.kyubiez96.kurogba

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A recently played ROM: a persistable SAF uri plus its display name. */
data class RecentRom(val uri: String, val name: String, val lastPlayed: Long)

/** Tiny recents store backed by SharedPreferences (no database needed). */
object RomLibrary {
    private const val PREFS = "rom_library"
    private const val KEY = "recents_json"
    private const val MAX_RECENTS = 24

    fun load(context: Context): MutableList<RecentRom> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableListOf()
        val out = mutableListOf<RecentRom>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += RecentRom(
                    uri = o.getString("uri"),
                    name = o.getString("name"),
                    lastPlayed = o.optLong("lastPlayed", 0L)
                )
            }
        } catch (_: Exception) {
            return mutableListOf()
        }
        return out
    }

    fun add(context: Context, uri: String, name: String) {
        val list = load(context)
        list.removeAll { it.uri == uri }
        list.add(0, RecentRom(uri, name, System.currentTimeMillis()))
        save(context, list.take(MAX_RECENTS))
    }

    fun remove(context: Context, uri: String) {
        val list = load(context)
        list.removeAll { it.uri == uri }
        save(context, list)
    }

    private fun save(context: Context, list: List<RecentRom>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().apply {
                put("uri", r.uri)
                put("name", r.name)
                put("lastPlayed", r.lastPlayed)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
