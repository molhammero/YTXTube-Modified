package com.tensei.ytxtube

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * YouTube only writes watch history for playback sessions started by its own
 * clients. Stats beacons from third-party apps are acknowledged with HTTP 204
 * but do not create a history row, so History is mirrored locally and merged
 * with whatever the account already has on the server.
 */
object LocalWatchHistory {
    private const val PREFS = "local_watch_history"
    private const val KEY = "items"
    private const val LIMIT = 500

    private data class Entry(val item: VideoItem, val watchedAt: Long)

    private var prefs: SharedPreferences? = null
    private val lock = Any()
    private var entries: List<Entry> = emptyList()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(lock) {
            if (prefs != null) return
            prefs = p
            entries = runCatching { decode(p.getString(KEY, null)) }.getOrDefault(emptyList())
        }
    }

    fun record(id: String, title: String?, channel: String?, thumbnail: String?, durationSec: Long) {
        if (id.isBlank()) return
        val item = VideoItem(
            id = id,
            title = title?.takeIf { it.isNotBlank() } ?: id,
            channel = channel.orEmpty(),
            channelUrl = null,
            thumbnail = thumbnail?.takeIf { it.isNotBlank() },
            avatar = null,
            durationSec = durationSec.coerceAtLeast(0L),
            views = -1L,
            uploaded = null,
        )
        synchronized(lock) {
            entries = (listOf(Entry(item, System.currentTimeMillis())) + entries.filterNot { it.item.id == id })
                .take(LIMIT)
            prefs?.edit()?.putString(KEY, encode(entries))?.apply()
        }
    }

    fun videos(): List<VideoItem> = synchronized(lock) { entries.map { it.item } }

    /** Newest local watches first, then the server page, without duplicates. */
    fun merge(server: List<VideoItem>): List<VideoItem> {
        val local = videos()
        if (local.isEmpty()) return server
        val seen = HashSet<String>(local.size + server.size)
        return (local + server).filter { seen.add(it.id) }
    }

    private fun encode(list: List<Entry>): String {
        val arr = JSONArray()
        list.forEach { entry ->
            val v = entry.item
            arr.put(
                JSONObject()
                    .put("id", v.id)
                    .put("title", v.title)
                    .put("channel", v.channel)
                    .put("thumb", v.thumbnail)
                    .put("duration", v.durationSec)
                    .put("at", entry.watchedAt),
            )
        }
        return arr.toString()
    }

    private fun decode(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = JSONArray(raw)
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
            out += Entry(
                VideoItem(
                    id = id,
                    title = o.optString("title").takeIf { it.isNotBlank() } ?: id,
                    channel = o.optString("channel"),
                    channelUrl = null,
                    thumbnail = o.optString("thumb").takeIf { it.isNotBlank() },
                    avatar = null,
                    durationSec = o.optLong("duration").coerceAtLeast(0L),
                    views = -1L,
                    uploaded = null,
                ),
                o.optLong("at"),
            )
        }
        return out.sortedByDescending { it.watchedAt }
    }
}
