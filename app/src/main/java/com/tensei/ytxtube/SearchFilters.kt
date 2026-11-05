package com.tensei.ytxtube

import java.io.ByteArrayOutputStream

/**
 * Refinements that mirror the filter chips on youtube.com's search page.
 *
 * The "type" and "date" values are carried in the undocumented `params` field of
 * the InnerTube search request. It is a small protobuf: outer field 2 holds a
 * message whose field 1 is the upload date and field 2 is the result type.
 */
enum class SearchType(val label: String, val param: Int?) {
    All("All", null),
    Video("Videos", 1),
    Channel("Channels", 2),
    Playlist("Playlists", 3),
}

enum class SearchDate(val label: String, val param: Int?) {
    AnyTime("Any time", null),
    Today("Today", 2),
    ThisWeek("This week", 3),
    ThisMonth("This month", 4),
    ThisYear("This year", 5),
}

data class SearchFilters(
    val type: SearchType = SearchType.All,
    val date: SearchDate = SearchDate.AnyTime,
) {
    /** YouTube only offers the upload-date refinement for video results. */
    val dateApplies: Boolean get() = type == SearchType.All || type == SearchType.Video

    /** Encoded `params` query value, or null when nothing is filtered. */
    fun params(): String? {
        val dateParam = if (dateApplies) date.param else null
        val typeParam = type.param
        if (dateParam == null && typeParam == null) return null

        val inner = ByteArrayOutputStream()
        if (dateParam != null) {
            inner.write(0x08)
            writeVarint(inner, dateParam)
        }
        if (typeParam != null) {
            inner.write(0x10)
            writeVarint(inner, typeParam)
        }
        val innerBytes = inner.toByteArray()

        val message = ByteArrayOutputStream()
        message.write(0x12)
        writeVarint(message, innerBytes.size)
        message.write(innerBytes)
        return base64Url(message.toByteArray())
    }
}

fun SearchType.noun(): String = when (this) {
    SearchType.All -> "results"
    SearchType.Video -> "videos"
    SearchType.Channel -> "channels"
    SearchType.Playlist -> "playlists"
}

/** One row of a filtered search: a video, a channel or a playlist. */
sealed interface SearchItem {
    val key: String

    data class Video(val video: VideoItem) : SearchItem {
        override val key: String get() = "video:${video.id}"
    }

    data class Channel(
        val id: String,
        val name: String,
        val avatar: String?,
        val subscribers: String?,
    ) : SearchItem {
        override val key: String get() = "channel:$id"
    }

    data class Playlist(
        val id: String,
        val title: String,
        val thumbnail: String?,
        val channel: String?,
        val videoCount: String?,
    ) : SearchItem {
        override val key: String get() = "playlist:$id"
    }
}

data class SearchPage(val items: List<SearchItem>, val next: String?)

private const val BASE64URL =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

private fun base64Url(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size * 4 + 2) / 3)
    var i = 0
    while (i + 2 < bytes.size) {
        val n = ((bytes[i].toInt() and 0xFF) shl 16) or
            ((bytes[i + 1].toInt() and 0xFF) shl 8) or
            (bytes[i + 2].toInt() and 0xFF)
        out.append(BASE64URL[(n ushr 18) and 63])
            .append(BASE64URL[(n ushr 12) and 63])
            .append(BASE64URL[(n ushr 6) and 63])
            .append(BASE64URL[n and 63])
        i += 3
    }
    when (bytes.size - i) {
        1 -> {
            val n = (bytes[i].toInt() and 0xFF) shl 16
            out.append(BASE64URL[(n ushr 18) and 63]).append(BASE64URL[(n ushr 12) and 63])
        }
        2 -> {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            out.append(BASE64URL[(n ushr 18) and 63])
                .append(BASE64URL[(n ushr 12) and 63])
                .append(BASE64URL[(n ushr 6) and 63])
        }
    }
    return out.toString()
}

private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
    var v = value
    while (v and 0x7F.inv() != 0) {
        out.write((v and 0x7F) or 0x80)
        v = v ushr 7
    }
    out.write(v)
}
