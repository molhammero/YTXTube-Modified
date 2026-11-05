package com.tensei.ytxtube

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device log for the watch-history pipeline. It deliberately records only the
 * stats parameters and HTTP results; cookie and Authorization headers never enter it.
 */
object WatchSyncLog {
    private const val PREFS = "watch_sync_diagnostics"
    private const val KEY = "lines"
    private const val LIMIT = 300
    private const val BUILD_TAG = "tv-oauth8"

    private var prefs: SharedPreferences? = null
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var lastId: String? = null
    private var lastTitle: String? = null

    var version by mutableStateOf(0)
        private set

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(lock) {
            if (prefs != null) return
            prefs = p
            runCatching {
                val arr = JSONArray(p.getString(KEY, "[]"))
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let { lines.addLast(it) }
                }
            }
            while (lines.size > LIMIT) lines.removeFirst()
        }
    }

    fun note(message: String) {
        val clean = message.replace('\n', ' ').replace('\r', ' ').take(700)
        val line = "${stamp()}  $clean"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > LIMIT) lines.removeFirst()
            val arr = JSONArray()
            lines.forEach { arr.put(it) }
            prefs?.edit()?.putString(KEY, arr.toString())?.apply()
        }
        version++
    }

    fun setLastVideo(id: String, title: String?) {
        synchronized(lock) {
            lastId = id
            lastTitle = title?.replace('\n', ' ')?.replace('\r', ' ')?.take(160)
        }
    }

    fun lastVideo(): Pair<String, String?>? = synchronized(lock) {
        lastId?.let { it to lastTitle }
    }

    fun report(maxLines: Int = Int.MAX_VALUE): String = synchronized(lock) {
        val total = lines.size
        val shown = if (total > maxLines) lines.toList().takeLast(maxLines) else lines.toList()
        buildString {
            appendLine("YTXTube watch-sync report")
            appendLine("app ${BuildConfig.VERSION_NAME}")
            appendLine("build $BUILD_TAG")
            appendLine("generated ${stamp()}")
            appendLine("signedIn ${Account.signedIn}")
            appendLine("lastVideo ${lastId ?: "none"}${lastTitle?.let { " / $it" } ?: ""}")
            appendLine("cookies and Authorization headers are never logged")
            if (shown.size < total) appendLine("showing last ${shown.size} of $total lines")
            appendLine("-----")
            shown.forEach { appendLine(it) }
        }
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            prefs?.edit()?.remove(KEY)?.apply()
        }
        version++
    }

    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
}
