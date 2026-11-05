package com.tensei.ytxtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException

/**
 * YouTube refuses server-side caption translation (`tlang`) with HTTP 429 even when the
 * original track downloads normally. So translated captions are built here instead: the
 * original cues keep their exact timings and only their text is translated.
 */
object CaptionTranslator {
    private const val TAG = "CaptionTranslator"
    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val MAX_CHARS = 1_500
    private const val MAX_CUES = 25
    private const val MAX_ATTEMPTS = 3

    /** YouTube language codes whose Google Translate spelling differs. */
    private val codeMap = mapOf(
        "zh-Hans" to "zh-CN",
        "zh-Hant" to "zh-TW",
        "fil" to "tl",
        "jv" to "jw",
    )

    fun googleCode(code: String): String = codeMap[code] ?: code

    suspend fun translate(cues: List<ParsedCue>, target: String): List<ParsedCue> =
        withContext(Dispatchers.IO) {
            if (cues.isEmpty()) return@withContext cues
            val lang = googleCode(target)
            val out = ArrayList<ParsedCue>(cues.size)
            var index = 0
            while (index < cues.size) {
                var end = index
                var chars = 0
                while (end < cues.size && end - index < MAX_CUES) {
                    val length = cues[end].text.length + 1
                    if (end > index && chars + length > MAX_CHARS) break
                    chars += length
                    end++
                }
                out += translateChunk(cues.subList(index, end), lang)
                index = end
            }
            out
        }

    private fun translateChunk(chunk: List<ParsedCue>, lang: String): List<ParsedCue> {
        val joined = chunk.joinToString("\n") { it.text.replace('\n', ' ').trim() }
        val rawLines = request(joined, lang).split('\n')
        val lines = if (rawLines.size == chunk.size + 1 && rawLines.last().isBlank()) {
            rawLines.dropLast(1)
        } else {
            rawLines
        }
        if (lines.size == chunk.size) {
            return chunk.mapIndexed { i, cue ->
                cue.copy(text = lines[i].trim().ifBlank { cue.text })
            }
        }

        // The service occasionally reflows a batch; one request per cue keeps timings exact.
        return chunk.map { cue ->
            if (cue.text.isBlank()) {
                cue
            } else {
                val text = request(cue.text.replace('\n', ' ').trim(), lang).trim()
                cue.copy(text = text.ifBlank { cue.text })
            }
        }
    }

    private fun request(text: String, lang: String): String {
        var last: Exception? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                return requestOnce(text, lang)
            } catch (e: Exception) {
                last = e
                val code = (e as? HttpStatusException)?.code
                val retryable = code == 429 || (code != null && code >= 500)
                if (!retryable || attempt == MAX_ATTEMPTS - 1) break
                Thread.sleep(700L * (attempt + 1))
            }
        }
        throw last ?: IOException("Translation failed")
    }

    private fun requestOnce(text: String, lang: String): String {
        val base = ENDPOINT.toHttpUrlOrNull() ?: throw IOException("Bad translation endpoint")
        val url = base.newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", "auto")
            .addQueryParameter("tl", lang)
            .addQueryParameter("dt", "t")
            .addQueryParameter("q", text)
            .build()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Net.USER_AGENT)
            .build()
        Net.client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val body = response.body?.string().orEmpty()
            val root = runCatching { JSONArray(body) }.getOrNull()
                ?: throw IOException("Unexpected translation response")
            val segments = root.optJSONArray(0)
                ?: throw IOException("Unexpected translation response")
            val out = StringBuilder()
            for (i in 0 until segments.length()) {
                val segment = segments.optJSONArray(i) ?: continue
                out.append(segment.optString(0))
            }
            if (out.isEmpty()) throw IOException("Empty translation response")
            return out.toString()
        }
    }

    private class HttpStatusException(val code: Int) : IOException("HTTP $code")
}
