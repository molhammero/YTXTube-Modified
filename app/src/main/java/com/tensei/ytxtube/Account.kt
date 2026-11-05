package com.tensei.ytxtube

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

@Immutable
data class Profile(val name: String, val handle: String?, val avatar: String?)

data class PlaylistRef(val id: String, val title: String, val thumbnail: String?, val subtitle: String?)

fun highResMusicThumbnail(url: String?): String? {
    if (url.isNullOrBlank()) return null
    var u = url
    if (u.contains("googleusercontent.com")) {
        u = u.replace(Regex("=w\\d+-h\\d+[^&?]*"), "=w800-h800-l90-rj")
             .replace(Regex("=s\\d+[^&?]*"), "=s800-c")
    } else if (u.contains("ytimg.com/vi/")) {
        u = u.replace("/default.jpg", "/maxresdefault.jpg")
             .replace("/mqdefault.jpg", "/maxresdefault.jpg")
             .replace("/hqdefault.jpg", "/maxresdefault.jpg")
             .replace("/sddefault.jpg", "/maxresdefault.jpg")
    }
    return u
}

@Immutable
data class MusicShelfItem(
    val title: String,
    val subtitle: String,
    val thumbnail: String?,
    val videoId: String?,
    val browseId: String?,
    val playlistId: String? = null,
) {
    fun toVideoItem(): VideoItem? = videoId?.let { vid ->
        VideoItem(
            id = vid,
            title = title,
            channel = subtitle,
            channelUrl = null,
            thumbnail = highResMusicThumbnail(thumbnail),
            avatar = null,
            durationSec = 0L,
            views = -1L,
            uploaded = "YouTube Music",
        )
    }
}

@Immutable
data class MusicShelf(
    val title: String,
    val subtitle: String? = null,
    val items: List<MusicShelfItem>,
)

@Immutable
data class ChannelRef(val id: String, val name: String, val avatar: String?) {
    val url: String get() = "https://www.youtube.com/channel/$id"
}


@Immutable
data class FeedPage(
    val videos: List<VideoItem>,
    val next: String?,
    val channelHeader: ChannelHeaderMeta? = null,
    val entries: List<FeedEntry> = emptyList(),
)

/** One home-feed row in the order YouTube returned it: a video or a community post. */
@Immutable
sealed interface FeedEntry {
    val key: String

    @Immutable
    data class Video(val video: VideoItem) : FeedEntry {
        override val key: String get() = "video:${video.id}"
    }

    @Immutable
    data class Post(val post: CommunityPost) : FeedEntry {
        override val key: String get() = "post:${post.id}"
    }
}


@Immutable
data class LyricLine(val timeMs: Long, val text: String)


@Immutable
data class LyricsDoc(val lines: List<LyricLine>) {
    val synced: Boolean get() = lines.any { it.timeMs >= 0 }
    val plain: String get() = lines.joinToString("\n") { it.text }
}

@Immutable
data class ChannelHeaderMeta(val handle: String?, val subscribers: Long, val videoCount: Long, val verified: Boolean = false)

enum class LikeStatus { Like, Dislike, None }

@Immutable
data class VideoAccountState(val like: LikeStatus, val subscribed: Boolean?, val channelId: String?)

class NotSignedInException : IOException("Not signed in")









@Immutable
data class AccountSummary(
    val id: String,
    val name: String,
    val handle: String?,
    val avatar: String?,
    val email: String? = null,
)

object Account {
    var profile by mutableStateOf<Profile?>(null)
        private set
    val signedIn get() = profile != null

    /** Every saved session, in the order the user added them. */
    var accounts by mutableStateOf<List<AccountSummary>>(emptyList())
        private set

    var activeAccountId by mutableStateOf<String?>(null)
        private set

    private data class StoredAccount(
        val id: String,
        val name: String,
        val handle: String?,
        val avatar: String?,
        val cookies: String,
        val clientVersion: String,
        val visitor: String?,
        val email: String? = null,
    )

    private var store: SecureStore? = null
    private val entries = mutableListOf<StoredAccount>()
    private val cookies = linkedMapOf<String, String>()
    private var clientVersion = FALLBACK_CLIENT_VERSION
    private var visitorData: String? = null

    fun init(context: Context) {
        val s = SecureStore(context, "account")
        store = s
        entries.clear()
        var accountsSanitized = false
        parseAccounts(s.get(KEY_ACCOUNTS)).let { list ->
            entries.addAll(list.map { entry ->
                val nameClean = isCleanAccountName(entry.name)
                val handleClean = isCleanAccountName(entry.handle)
                if (!nameClean || !handleClean) {
                    accountsSanitized = true
                    val cleanHandle = entry.handle?.takeIf { isCleanAccountName(it) }
                    val cleanName = when {
                        nameClean -> entry.name
                        !entry.email.isNullOrBlank() -> entry.email
                        cleanHandle != null -> cleanHandle
                        else -> "Google Account"
                    }
                    entry.copy(name = cleanName, handle = cleanHandle ?: entry.email)
                } else entry
            })
        }
        if (accountsSanitized) {
            persistAccounts()
        }

        // Migrate the single-session format used before multi-account support.
        if (entries.isEmpty()) {
            val legacy = s.get(KEY_COOKIES)
            val name = s.get(KEY_NAME)
            if (legacy != null && name != null && looksSignedIn(legacy)) {
                entries += StoredAccount(
                    id = accountId(legacy),
                    name = name,
                    handle = s.get(KEY_HANDLE),
                    avatar = s.get(KEY_AVATAR),
                    cookies = legacy,
                    clientVersion = s.get(KEY_CLIENT_VERSION) ?: FALLBACK_CLIENT_VERSION,
                    visitor = s.get(KEY_VISITOR),
                )
            }
        }

        val wanted = s.get(KEY_ACTIVE) ?: entries.firstOrNull()?.id
        val active = entries.firstOrNull { it.id == wanted } ?: entries.firstOrNull()
        if (active != null) {
            load(active)
            activeAccountId = active.id
            profile = Profile(active.name, active.handle, active.avatar)
        } else {
            activeAccountId = null
            profile = null
        }
        publish()

        if (entries.isNotEmpty()) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                val emailRegex = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")
                entries.forEachIndexed { i, e ->
                    if (!isCleanAccountName(e.name) || e.email.isNullOrBlank()) {
                        runCatching {
                            val req = Request.Builder().url("https://www.youtube.com/?hl=en")
                                .header("User-Agent", Net.USER_AGENT)
                                .header("Cookie", e.cookies)
                                .build()
                            Net.client.newCall(req).execute().use { r ->
                                val html = r.body?.string().orEmpty()
                                val emailMatch = emailRegex.find(html)?.value
                                val nameMatch = Regex(""""accountName":\{"simpleText":"([^"]+)"""").find(html)?.groupValues?.get(1)
                                val handleMatch = Regex(""""channelHandle":\{"simpleText":"([^"]+)"""").find(html)?.groupValues?.get(1)
                                val cleanName = nameMatch?.takeIf { isCleanAccountName(it) } ?: emailMatch ?: handleMatch?.takeIf { isCleanAccountName(it) }
                                if (cleanName != null || emailMatch != null) {
                                    withContext(Dispatchers.Main) {
                                        val cur = entries.getOrNull(i)
                                        if (cur != null && cur.id == e.id) {
                                            entries[i] = cur.copy(
                                                name = cleanName ?: cur.name,
                                                handle = handleMatch?.takeIf { isCleanAccountName(it) } ?: cur.handle ?: emailMatch,
                                                email = emailMatch ?: cur.email,
                                            )
                                            if (activeAccountId == cur.id) {
                                                profile = Profile(entries[i].name, entries[i].handle, entries[i].avatar)
                                            }
                                            persistAccounts()
                                            publish()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun looksSignedIn(cookieHeader: String?): Boolean {
        val names = cookieHeader.orEmpty().split(';').map { it.substringBefore('=').trim() }.toSet()
        return "SAPISID" in names || "__Secure-3PAPISID" in names || "SID" in names
    }

    suspend fun completeSignIn(cookieHeader: String, explicitEmail: String? = null) = withContext(Dispatchers.IO) {
        saveActive()
        synchronized(cookies) {
            cookies.clear()
            parseCookieHeader(cookieHeader, cookies)
        }
        persistCookies()
        refreshClientConfig()
        val rawP = fetchProfileFromNetwork()
        val emailRegex = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")
        val detectedEmail = explicitEmail
            ?: emailRegex.find(rawP?.handle.orEmpty())?.value
            ?: emailRegex.find(rawP?.name.orEmpty())?.value
            ?: emailRegex.find(cookieHeader)?.value
        val displayName = when {
            rawP != null && isCleanAccountName(rawP.name) -> rawP.name
            !detectedEmail.isNullOrBlank() -> detectedEmail
            !rawP?.handle.isNullOrBlank() && isCleanAccountName(rawP.handle) -> rawP.handle
            else -> "Google Account"
        }
        val p = Profile(displayName, rawP?.handle ?: detectedEmail, rawP?.avatar)
        val id = accountId(cookieHeader)
        withContext(Dispatchers.Main) {
            val entry = StoredAccount(
                id = id,
                name = p.name,
                handle = p.handle,
                avatar = p.avatar,
                cookies = cookieHeader(),
                clientVersion = clientVersion,
                visitor = visitorData,
                email = detectedEmail,
            )
            val index = entries.indexOfFirst { it.id == id || sameSession(it.cookies, cookieHeader) }
            if (index >= 0) entries[index] = entry else entries += entry
            activeAccountId = id
            profile = p
            store?.apply {
                put(KEY_NAME, p.name); put(KEY_HANDLE, p.handle); put(KEY_AVATAR, p.avatar)
            }
            persistAccounts()
            publish()
        }
    }

            private val BLACKLISTED_ACCOUNT_NAMES = setOf(
        "your data in youtube",
        "your channel",
        "youtube studio",
        "switch account",
        "sign out",
        "purchases and memberships",
        "settings",
        "help",
        "send feedback",
        "feedback",
        "appearance",
        "location",
        "language",
        "restricted mode",
        "keyboard shortcuts",
        "time watched",
        "youtube account",
        "google account",
        "dark theme",
        "light theme",
        "device theme",
        "theme",
        "dark",
        "light"
    )

    fun isCleanAccountName(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val s = raw.trim().lowercase()
        if (s in BLACKLISTED_ACCOUNT_NAMES) return false
        if (BLACKLISTED_ACCOUNT_NAMES.any { s.contains(it) }) return false
        return true
    }

        private fun fetchProfileFromNetwork(): Profile? {
        val emailRegex = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")
        var found: Profile? = null

        runCatching {
            val menu = InnerTube.post("account/account_menu", JSONObject())
            val menuStr = menu.toString()
            val emailFromPayload = emailRegex.find(menuStr)?.value

            var headerFound: Profile? = null
            menu.walk { key, obj ->
                if (headerFound == null && (key.contains("AccountHeader", ignoreCase = true) || obj.has("accountPhoto") || obj.has("email") || obj.has("channelHandle") || obj.has("accountName"))) {
                    val rawName = obj.text("accountName")
                        ?: obj.text("channelName")
                        ?: obj.optString("accountName").ifBlank { null }
                    val email = obj.text("email")
                        ?: obj.optString("email").ifBlank { null }
                        ?: emailFromPayload
                    val handle = obj.text("channelHandle")
                        ?: obj.optString("channelHandle").ifBlank { null }
                    val avatar = obj.optJSONObject("accountPhoto")?.thumbnails()?.lastOrNull()
                        ?: obj.optJSONObject("avatar")?.thumbnails()?.lastOrNull()
                        ?: obj.thumbnails()?.lastOrNull()

                    val cleanName = if (isCleanAccountName(rawName)) rawName else null
                    val cleanHandle = if (isCleanAccountName(handle)) handle else null
                    val displayName = cleanName ?: email ?: cleanHandle
                    if (!displayName.isNullOrBlank()) {
                        headerFound = Profile(name = displayName, handle = cleanHandle ?: email, avatar = avatar)
                    }
                }
            }
            if (headerFound != null) found = headerFound

            if (found == null && !emailFromPayload.isNullOrBlank()) {
                found = Profile(name = emailFromPayload, handle = emailFromPayload, avatar = null)
            }
        }
        if (found != null) return found

        runCatching {
            val guide = InnerTube.post("guide", JSONObject())
            val guideStr = guide.toString()
            val emailFromGuide = emailRegex.find(guideStr)?.value

            guide.walk { key, obj ->
                if (found == null && (key.contains("AccountHeader", ignoreCase = true) || key.contains("accountPhoto") || obj.has("channelHandle") || obj.has("accountName"))) {
                    val rawName = obj.text("accountName") ?: obj.text("title")
                    val handle = obj.text("channelHandle") ?: obj.text("subtitle") ?: emailFromGuide
                    val avatar = obj.optJSONObject("thumbnail")?.thumbnails()?.lastOrNull()
                        ?: obj.thumbnails()?.lastOrNull()
                    val cleanName = if (isCleanAccountName(rawName)) rawName else null
                    val cleanHandle = if (isCleanAccountName(handle)) handle else null
                    val displayName = cleanName ?: emailFromGuide ?: cleanHandle
                    if (!displayName.isNullOrBlank()) {
                        found = Profile(name = displayName, handle = cleanHandle ?: emailFromGuide, avatar = avatar)
                    }
                }
            }
            if (found == null && !emailFromGuide.isNullOrBlank()) {
                found = Profile(name = emailFromGuide, handle = emailFromGuide, avatar = null)
            }
        }
        if (found != null) return found

        runCatching {
            val req = Request.Builder().url("https://www.youtube.com/?hl=en")
                .header("User-Agent", Net.USER_AGENT)
                .header("Cookie", cookieHeader())
                .build()
            Net.client.newCall(req).execute().use { r ->
                val html = r.body?.string().orEmpty()
                val emailMatch = emailRegex.find(html)?.value
                val nameMatch = Regex(""""accountName":\{"simpleText":"([^"]+)"""").find(html)
                val handleMatch = Regex(""""channelHandle":\{"simpleText":"([^"]+)"""").find(html)
                val avatarMatch = Regex(""""accountPhoto":\{"thumbnails":\[\{"url":"([^"]+)"""").find(html)
                    ?: Regex(""""avatar":\{"thumbnails":\[\{"url":"([^"]+)"""").find(html)
                val rawName = nameMatch?.groupValues?.get(1)
                val handle = handleMatch?.groupValues?.get(1) ?: emailMatch
                val avatar = avatarMatch?.groupValues?.get(1)?.replace("&amp;", "&")
                val cleanName = if (isCleanAccountName(rawName)) rawName else null
                val cleanHandle = if (isCleanAccountName(handle)) handle else null
                val displayName = cleanName ?: emailMatch ?: cleanHandle
                if (!displayName.isNullOrBlank()) {
                    found = Profile(name = displayName, handle = cleanHandle ?: emailMatch, avatar = avatar)
                }
            }
        }
        return found
    }

    /** Switches the active session. Returns false when the account was already active. */
    fun switchTo(id: String): Boolean {
        if (id == activeAccountId) return false
        val target = entries.firstOrNull { it.id == id } ?: return false
        saveActive()
        load(target)
        activeAccountId = id
        profile = Profile(target.name, target.handle, target.avatar)
        persistAccounts()
        publish()
        StreamCache.invalidateAll()
        CaptionTracks.invalidateAll()
        runCatching {
            android.webkit.CookieManager.getInstance().apply {
                removeAllCookies(null)
                target.cookies.split(';').forEach { c ->
                    val pair = c.trim()
                    if (pair.isNotEmpty()) {
                        setCookie("https://www.youtube.com", pair)
                        setCookie("https://m.youtube.com", pair)
                        setCookie("https://youtube.com", pair)
                    }
                }
                flush()
            }
        }
        return true
    }

    fun signOut() {
        saveActive()
        val id = activeAccountId
        entries.removeAll { it.id == id }
        synchronized(cookies) { cookies.clear() }
        if (entries.isNotEmpty()) {
            val next = entries.first()
            load(next)
            activeAccountId = next.id
            profile = Profile(next.name, next.handle, next.avatar)
        } else {
            activeAccountId = null
            profile = null
            clientVersion = FALLBACK_CLIENT_VERSION
            visitorData = null
        }
        store?.apply {
            put(KEY_COOKIES, null); put(KEY_CLIENT_VERSION, null); put(KEY_VISITOR, null)
            put(KEY_NAME, null); put(KEY_HANDLE, null); put(KEY_AVATAR, null)
        }
        persistAccounts()
        publish()
        StreamCache.invalidateAll()
        CaptionTracks.invalidateAll()
        android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() }
    }

    /** Removes a saved account without touching the active session. */
    fun removeAccount(id: String) {
        if (id == activeAccountId) {
            signOut()
            return
        }
        entries.removeAll { it.id == id }
        persistAccounts()
        publish()
    }

    private fun saveActive() {
        val id = activeAccountId ?: return
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return
        val old = entries[index]
        entries[index] = old.copy(
            name = profile?.name ?: old.name,
            handle = profile?.handle ?: old.handle,
            avatar = profile?.avatar ?: old.avatar,
            cookies = cookieHeader(),
            clientVersion = clientVersion,
            visitor = visitorData,
        )
    }

    private fun load(entry: StoredAccount) {
        synchronized(cookies) {
            cookies.clear()
            parseCookieHeader(entry.cookies, cookies)
        }
        clientVersion = entry.clientVersion.ifBlank { FALLBACK_CLIENT_VERSION }
        visitorData = entry.visitor
    }

        private fun publish() {
        accounts = entries.map {
            val cleanName = if (isCleanAccountName(it.name)) it.name else (it.email ?: it.handle ?: "Google Account")
            AccountSummary(it.id, cleanName, it.handle ?: it.email, it.avatar, it.email)
        }
    }

    private fun parseAccounts(raw: String?): List<StoredAccount> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val cookieHeader = obj.optString("cookies").takeIf { it.isNotBlank() } ?: continue
                    if (!looksSignedIn(cookieHeader)) continue
                    val rawName = obj.optString("name")
                    val rawHandle = obj.optString("handle").takeIf { it.isNotBlank() }
                    val rawEmail = obj.optString("email").takeIf { it.isNotBlank() }
                        ?: Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""").find(rawHandle.orEmpty())?.value
                        ?: Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""").find(rawName)?.value
                    val cleanName = if (isCleanAccountName(rawName)) rawName else (rawEmail ?: rawHandle?.takeIf { isCleanAccountName(it) } ?: "Google Account")
                    add(
                        StoredAccount(
                            id = id,
                            name = cleanName,
                            handle = rawHandle?.takeIf { isCleanAccountName(it) } ?: rawEmail,
                            avatar = obj.optString("avatar").takeIf { it.isNotBlank() },
                            cookies = cookieHeader,
                            clientVersion = obj.optString("clientVersion").ifBlank { FALLBACK_CLIENT_VERSION },
                            visitor = obj.optString("visitor").takeIf { it.isNotBlank() },
                            email = rawEmail,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistAccounts() {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("handle", entry.handle ?: "")
                    .put("avatar", entry.avatar ?: "")
                    .put("cookies", entry.cookies)
                    .put("clientVersion", entry.clientVersion)
                    .put("visitor", entry.visitor ?: "")
                    .put("email", entry.email ?: ""),
            )
        }
        store?.put(KEY_ACCOUNTS, array.toString())
        store?.put(KEY_ACTIVE, activeAccountId)
    }

    private fun accountId(cookieHeader: String): String {
        val identity = identityCookie(cookieHeader) ?: cookieHeader
        val digest = MessageDigest.getInstance("SHA-1").digest(identity.toByteArray())
        return "acct-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun sameSession(a: String, b: String): Boolean {
        val left = identityCookie(a) ?: return false
        return left == identityCookie(b)
    }

    private fun identityCookie(header: String): String? {
        val map = linkedMapOf<String, String>()
        parseCookieHeader(header, map)
        return map["SAPISID"] ?: map["__Secure-3PAPISID"] ?: map["SID"]
    }

    private fun refreshClientConfig() {
        runCatching {
            val request = Request.Builder().url("https://www.youtube.com/?hl=en")
                .header("User-Agent", Net.USER_AGENT)
                .header("Cookie", cookieHeader())
                .build()
            Net.client.newCall(request).execute().use { r ->
                absorbSetCookies(r.headers("Set-Cookie"))
                val html = r.body?.string().orEmpty()
                Regex("\"INNERTUBE_CLIENT_VERSION\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { clientVersion = it }
                Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)?.let { visitorData = it }
            }
            store?.put(KEY_CLIENT_VERSION, clientVersion)
            store?.put(KEY_VISITOR, visitorData)
        }
    }

    internal fun clientVersion() = clientVersion
    internal fun visitorData() = visitorData

    internal fun cookieHeader(): String = synchronized(cookies) { cookies.entries.joinToString("; ") { "${it.key}=${it.value}" } }


    internal fun authorization(origin: String = ORIGIN): String? {
        val ts = System.currentTimeMillis() / 1000
        fun hash(secret: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("$ts $secret $origin".toByteArray())
            return "${ts}_" + digest.joinToString("") { "%02x".format(it) }
        }
        val (sapisid, p1, p3) = synchronized(cookies) {
            Triple(cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"], cookies["__Secure-1PAPISID"], cookies["__Secure-3PAPISID"])
        }
        sapisid ?: return null
        return buildString {
            append("SAPISIDHASH ").append(hash(sapisid))
            p1?.let { append(" SAPISID1PHASH ").append(hash(it)) }
            p3?.let { append(" SAPISID3PHASH ").append(hash(it)) }
        }
    }


    internal fun absorbSetCookies(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        var changed = false
        synchronized(cookies) {
            setCookies.forEach { header ->
                val pair = header.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isEmpty()) return@forEach
                if (cookies[name] != value) { cookies[name] = value; changed = true }
            }
        }
        if (changed) persistCookies()
    }

    private fun persistCookies() = store?.put(KEY_COOKIES, cookieHeader())

    private fun parseCookieHeader(header: String, into: MutableMap<String, String>) {
        header.split(';').forEach { part ->
            val name = part.substringBefore('=').trim()
            if (name.isNotEmpty()) into[name] = part.substringAfter('=', "").trim()
        }
    }

    const val ORIGIN = "https://www.youtube.com"
    private const val FALLBACK_CLIENT_VERSION = "2.20260901.00.00"
    private const val KEY_COOKIES = "cookies"
    private const val KEY_CLIENT_VERSION = "client_version"
    private const val KEY_VISITOR = "visitor"
    private const val KEY_NAME = "name"
    private const val KEY_HANDLE = "handle"
    private const val KEY_AVATAR = "avatar"
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_ACTIVE = "active_account"
}






object InnerTube {
    private const val BASE = "https://www.youtube.com/youtubei/v1/"
    private val JSON_TYPE = "application/json".toMediaType()


    internal fun postAny(endpoint: String, body: JSONObject): JSONObject =
        if (Account.signedIn) post(endpoint, body) else post(endpoint, body, anonymous = true)

    




    internal fun playerResponse(videoId: String): JSONObject = postAny(
        "player",
        JSONObject()
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true),
    )

    internal fun post(endpoint: String, body: JSONObject, anonymous: Boolean = false): JSONObject {
        val auth = if (anonymous) null else Account.authorization() ?: throw NotSignedInException()
        body.put(
            "context",
            JSONObject().put(
                "client",
                JSONObject()
                    .put("clientName", "WEB")
                    .put("clientVersion", Account.clientVersion())
                    .put("hl", LanguageSettings.effectiveLanguageCode())
                    .put("gl", LanguageSettings.effectiveCountryCode())
                    .apply { Account.visitorData()?.let { put("visitorData", it) } },
            ),
        )
        val langCode = LanguageSettings.effectiveLanguageCode()
        val countryCode = LanguageSettings.effectiveCountryCode()
        val prefCookie = "PREF=hl=" + langCode + "&gl=" + countryCode
        val cookieVal = if (auth != null) "${Account.cookieHeader()}; $prefCookie" else prefCookie
        val request = Request.Builder()
            .url("$BASE$endpoint?prettyPrint=false&hl=$langCode&gl=$countryCode")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .header("User-Agent", Net.USER_AGENT)
            .header("Accept-Language", "$langCode-$countryCode,$langCode;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Cookie", cookieVal)
            .apply { if (auth != null) header("Authorization", auth) }
            .header("Origin", Account.ORIGIN)
            .header("X-Origin", Account.ORIGIN)
            .header("Referer", Account.ORIGIN + "/")
            .header("X-Goog-AuthUser", "0")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", Account.clientVersion())
            .apply { Account.visitorData()?.let { header("X-Goog-Visitor-Id", it) } }
            .build()
        Net.client.newCall(request).execute().use { r ->
            if (auth != null) Account.absorbSetCookies(r.headers("Set-Cookie"))
            val text = r.body?.string().orEmpty()
            if (r.code == 401 || r.code == 403) throw IOException("YouTube rejected the session (${r.code}). Try signing in again.")
            if (!r.isSuccessful) throw IOException("YouTube returned ${r.code}")
            return JSONObject(text)
        }
    }

    private const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1/"
    private const val MUSIC_ORIGIN = "https://music.youtube.com"
    private const val WEB_REMIX_CLIENT_NAME = "67"
    private const val WEB_REMIX_CLIENT_VERSION = "1.20250101.01.00"

    internal fun postMusic(endpoint: String, body: JSONObject = JSONObject(), anonymous: Boolean = false): JSONObject {
        val auth = if (anonymous || !Account.signedIn) null else Account.authorization(MUSIC_ORIGIN)
        val clientContext = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", WEB_REMIX_CLIENT_VERSION)
            .put("hl", LanguageSettings.effectiveLanguageCode())
            .put("gl", LanguageSettings.effectiveCountryCode())
        Account.visitorData()?.let { clientContext.put("visitorData", it) }

        body.put(
            "context",
            JSONObject()
                .put("client", clientContext)
                .put("user", JSONObject().put("lockedSafetyMode", false))
        )

        val mlangCode = LanguageSettings.effectiveLanguageCode()
        val mcountryCode = LanguageSettings.effectiveCountryCode()
        val mprefCookie = "PREF=hl=" + mlangCode + "&gl=" + mcountryCode
        val mcookieVal = if (auth != null) "${Account.cookieHeader()}; $mprefCookie" else mprefCookie
        val reqBuilder = Request.Builder()
            .url("$MUSIC_BASE$endpoint?prettyPrint=false&hl=$mlangCode&gl=$mcountryCode")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .header("User-Agent", Net.USER_AGENT)
            .header("Accept-Language", "$mlangCode-$mcountryCode,$mlangCode;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Origin", MUSIC_ORIGIN)
            .header("X-Origin", MUSIC_ORIGIN)
            .header("Referer", "$MUSIC_ORIGIN/")
            .header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_NAME)
            .header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)
            .header("X-Goog-AuthUser", "0")
            .header("Cookie", mcookieVal)

        Account.visitorData()?.let { reqBuilder.header("X-Goog-Visitor-Id", it) }
        if (auth != null) {
            reqBuilder.header("Cookie", Account.cookieHeader())
            reqBuilder.header("Authorization", auth)
        }

        Net.client.newCall(reqBuilder.build()).execute().use { r ->
            if (auth != null) Account.absorbSetCookies(r.headers("Set-Cookie"))
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("YouTube Music returned ${r.code}")
            return JSONObject(text)
        }
    }


    suspend fun musicHome(): List<MusicShelf> = withContext(Dispatchers.IO) {
        val root = postMusic("browse", JSONObject().put("browseId", "FEmusic_home"))
        parseMusicShelves(root)
    }


    suspend fun musicHistory(): List<VideoItem> = withContext(Dispatchers.IO) {
        if (!Account.signedIn) return@withContext emptyList()
        val root = runCatching { postMusic("browse", JSONObject().put("browseId", "FEmusic_history")) }.getOrNull()
            ?: return@withContext emptyList()
        collectMusicSongs(root).distinctBy { it.id }
    }


    suspend fun musicLiked(): List<VideoItem> = withContext(Dispatchers.IO) {
        if (!Account.signedIn) return@withContext emptyList()
        val list = runCatching { playlist("LM").videos.take(50) }
            .getOrElse {
                runCatching { playlist("LL").videos.filter { isMusicTrack(it) }.take(50) }
                    .getOrDefault(emptyList())
            }
        list.map { it.copy(thumbnail = highResMusicThumbnail(it.thumbnail)) }
    }

    




    suspend fun musicLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationSec: Long = 0L,
    ): LyricsDoc? = withContext(Dispatchers.IO) {
        val cleanTitle = title.replace(Regex("(?i)\\(official.*?\\)|\\[official.*?\\]|\\(audio\\)|\\[audio\\]|\\(video\\)|\\[video\\]|\\(lyric.*?\\)"), "")
            .replace(Regex("(?i)ft\\..*|feat\\..*"), "")
            .trim()
        val cleanArtist = artist.split(",", "·", "&", "feat.", "ft.", " - ").firstOrNull()?.trim() ?: artist.trim()


        var plainFallback: LyricsDoc? = null
        runCatching {
            val encT = java.net.URLEncoder.encode(cleanTitle, "UTF-8")
            val encA = java.net.URLEncoder.encode(cleanArtist, "UTF-8")
            val url = "https://lrclib.net/api/get?track_name=$encT&artist_name=$encA"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "YTXTube/1.1 (https://github.com/Koustubh12345/YTXTube)")
                .build()
            Net.client.newCall(req).execute().use { r ->
                if (r.isSuccessful) {
                    val json = JSONObject(r.body?.string().orEmpty())
                    json.optString("syncedLyrics").takeIf { it.isNotBlank() }?.let { lrc ->
                        val doc = parseLyrics(lrc)
                        if (doc.synced) return@withContext doc
                    }
                    json.optString("plainLyrics").takeIf { it.isNotBlank() }?.let { plain ->
                        plainFallback = parseLyrics(plain)
                    }
                }
            }
        }


        runCatching {
            val nextBody = JSONObject().put("videoId", videoId)
            val nextResp = postMusic("next", nextBody)
            val tabs = nextResp.optJSONObject("contents")
                ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                ?.optJSONObject("tabbedRenderer")
                ?.optJSONObject("watchNextTabbedResultsRenderer")
                ?.optJSONArray("tabs")
            var lyricsBrowseId: String? = null
            if (tabs != null) {
                for (i in 0 until tabs.length()) {
                    val tab = tabs.optJSONObject(i)?.optJSONObject("tabRenderer") ?: continue
                    val endpoint = tab.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")
                    val bId = endpoint?.optString("browseId")
                    val tName = tab.optString("title")
                    if (bId != null && (bId.startsWith("MPLYt") || tName.equals("Lyrics", ignoreCase = true))) {
                        lyricsBrowseId = bId
                        break
                    }
                }
            }
            if (lyricsBrowseId != null) {
                val browseResp = postMusic("browse", JSONObject().put("browseId", lyricsBrowseId))
                val sectionList = browseResp.optJSONObject("contents")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")
                val shelf = sectionList?.optJSONObject(0)?.optJSONObject("musicDescriptionShelfRenderer")
                val runs = shelf?.optJSONObject("description")?.optJSONArray("runs")
                if (runs != null && runs.length() > 0) {
                    val sb = java.lang.StringBuilder()
                    for (k in 0 until runs.length()) {
                        sb.append(runs.optJSONObject(k)?.optString("text").orEmpty())
                    }
                    val ytLyrics = sb.toString().trim()
                    if (ytLyrics.isNotBlank() && !ytLyrics.startsWith("Provided to YouTube by")) {
                        val doc = parseLyrics(ytLyrics)
                        if (doc.synced) return@withContext doc
                        if (plainFallback == null) plainFallback = doc
                    }
                }
            }
        }

        // One more chance at a synced match. This runs even when YouTube Music gave us plain
        // lyrics: a synced lrclib result is what drives the karaoke highlight.
        var foundSynced: LyricsDoc? = null
        runCatching {
            val searchUrl = "https://lrclib.net/api/search?q=" +
                java.net.URLEncoder.encode("$cleanTitle $cleanArtist", "UTF-8")
            val searchReq = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "YTXTube/1.1 (https://github.com/Koustubh12345/YTXTube)")
                .build()
            Net.client.newCall(searchReq).execute().use { r ->
                if (!r.isSuccessful) return@runCatching
                val text = r.body?.string().orEmpty()
                if (!text.startsWith("[")) return@runCatching
                val arr = JSONArray(text)
                var bestSynced: LyricsDoc? = null
                var bestDelta = Long.MAX_VALUE
                var plain: LyricsDoc? = null
                for (i in 0 until arr.length()) {
                    val entry = arr.optJSONObject(i) ?: continue
                    val doc = entry.optString("syncedLyrics").takeIf { it.isNotBlank() }?.let(::parseLyrics)
                    if (doc != null && doc.synced) {
                        val itemDuration = entry.optLong("duration", -1L)
                        val delta = if (durationSec > 0 && itemDuration > 0) {
                            kotlin.math.abs(itemDuration - durationSec)
                        } else {
                            0L
                        }
                        if (delta < bestDelta) {
                            bestDelta = delta
                            bestSynced = doc
                        }
                    }
                    if (plain == null) {
                        plain = entry.optString("plainLyrics").takeIf { it.isNotBlank() }?.let(::parseLyrics)
                    }
                }
                if (bestSynced != null) {
                    foundSynced = bestSynced
                } else if (plainFallback == null) {
                    plainFallback = plain
                }
            }
        }

        foundSynced?.let { return@withContext it }
        plainFallback?.let { return@withContext it }
        return@withContext null
    }

    



    private fun parseLyrics(raw: String): LyricsDoc {
        val timeTag = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
        val timed = mutableListOf<LyricLine>()
        val plain = mutableListOf<String>()
        raw.lineSequence().forEach { row ->
            val matches = timeTag.findAll(row).toList()
            val text = row.replace(timeTag, "").trim()
            if (matches.isNotEmpty()) {
                if (text.isBlank()) return@forEach
                matches.forEach { m ->
                    val min = m.groupValues[1].toLongOrNull() ?: return@forEach
                    val sec = m.groupValues[2].toLongOrNull() ?: return@forEach
                    val fracRaw = m.groupValues[3]
                    val frac = when (fracRaw.length) {
                        0 -> 0L
                        1 -> fracRaw.toLong() * 100
                        2 -> fracRaw.toLong() * 10
                        else -> fracRaw.take(3).toLong()
                    }
                    timed += LyricLine(min * 60_000 + sec * 1000 + frac, text)
                }
            } else {

                if (text.isBlank() || row.trim().matches(Regex("^\\[[a-zA-Z#]+:.*]$"))) return@forEach
                plain += text
            }
        }
        return if (timed.isNotEmpty()) {
            LyricsDoc(timed.sortedBy { it.timeMs })
        } else {
            LyricsDoc(plain.map { LyricLine(-1, it) })
        }
    }

    







        private val SHORTS_TOPICS = listOf(
        "#shorts",
        "Trending shorts",
        "Viral shorts",
        "Funny shorts",
        "Gaming shorts",
        "Music shorts",
        "Entertainment shorts",
        "Tech shorts",
        "Shorts",
    )

    private suspend fun loadInfiniteShortsFallback(next: String?, sessionSeen: Set<String>): FeedPage {
        if (next == null) {
            val trending = runCatching {
                YouTube.trending().videos.filter { it.durationSec in 1..180 && it.id !in sessionSeen }
            }.getOrNull()
            if (!trending.isNullOrEmpty()) {
                return FeedPage(trending, "anon_shorts:0:start")
            }
            val search = runCatching {
                YouTube.search(SHORTS_TOPICS[0]).filter { it.durationSec in 1..180 && it.id !in sessionSeen }
            }.getOrDefault(emptyList())
            return FeedPage(search, "anon_shorts:1:start")
        }

        val parts = next.removePrefix("anon_shorts:").split(":", limit = 2)
        val idx = parts.getOrNull(0)?.toIntOrNull() ?: 0
        for (i in 0..3) {
            val topicIdx = (idx + i) % SHORTS_TOPICS.size
            val query = SHORTS_TOPICS[topicIdx]
            val items = runCatching {
                YouTube.search(query).filter { it.durationSec in 1..180 && it.id !in sessionSeen }
            }.getOrNull()
            if (!items.isNullOrEmpty()) {
                val nextToken = "anon_shorts:${(topicIdx + 1) % SHORTS_TOPICS.size}:start"
                return FeedPage(items, nextToken)
            }
        }
        val randomFallback = runCatching {
            YouTube.search(SHORTS_TOPICS.random()).filter { it.durationSec in 1..180 }
        }.getOrDefault(emptyList())
        return FeedPage(randomFallback, "anon_shorts:${(idx + 1) % SHORTS_TOPICS.size}:start")
    }

    suspend fun shorts(next: String? = null, sessionSeen: Set<String> = emptySet()): FeedPage = withContext(Dispatchers.IO) {
        val allSeen = SeenShorts.allSeen() + sessionSeen
        val result = LinkedHashMap<String, VideoItem>()
        var continuationToken: String? = null

        if (Account.signedIn) {
            val shelves = listOf("FEshorts", "FEwhat_to_watch", "FEsubscriptions", "FEhistory")
            if (next != null) {
                if (next.startsWith("shelf:")) {
                    val parts = next.removePrefix("shelf:").split(":", limit = 2)
                    var idx = parts.getOrNull(0)?.toIntOrNull() ?: 0
                    var token = parts.getOrNull(1)

                    var attempts = 0
                    while (result.size < 15 && attempts < 8) {
                        attempts++
                        if (!token.isNullOrBlank() && token != "start") {
                            val page = runCatching {
                                parseShorts(post("browse", JSONObject().put("continuation", token)))
                            }.getOrNull()
                            if (page != null) {
                                val fresh = page.videos.filter { it.id !in allSeen }
                                fresh.forEach { result.putIfAbsent(it.id, it) }
                                token = page.next
                            } else {
                                token = null
                            }
                        } else {
                            idx = (idx + 1) % shelves.size
                            val shelf = shelves[idx]
                            val page = runCatching {
                                parseShorts(post("browse", JSONObject().put("browseId", shelf)))
                            }.getOrNull()
                            if (page != null) {
                                val fresh = page.videos.filter { it.id !in allSeen }
                                fresh.forEach { result.putIfAbsent(it.id, it) }
                                token = page.next
                            }
                        }
                    }
                    continuationToken = if (!token.isNullOrBlank()) "shelf:$idx:$token" else "shelf:${(idx + 1) % shelves.size}:start"
                } else if (!next.startsWith("anon_shorts:")) {
                    val page = runCatching {
                        parseShorts(post("browse", JSONObject().put("continuation", next)))
                    }.getOrNull()
                    if (page != null) {
                        val fresh = page.videos.filter { it.id !in allSeen }
                        fresh.forEach { result.putIfAbsent(it.id, it) }
                        continuationToken = page.next
                    }
                }
            } else {
                val pages = shelves.map { browseId ->
                    async(Dispatchers.IO) {
                        runCatching {
                            parseShorts(post("browse", JSONObject().put("browseId", browseId)))
                        }.getOrNull()
                    }
                }.awaitAll().filterNotNull()

                pages.forEachIndexed { idx, p ->
                    val fresh = p.videos.filter { it.id !in allSeen }
                    fresh.forEach { result.putIfAbsent(it.id, it) }
                    if (continuationToken == null && !p.next.isNullOrBlank()) {
                        continuationToken = "shelf:$idx:${p.next}"
                    }
                }

                var currentToken = continuationToken
                var walkAttempts = 0
                while (result.size < 15 && walkAttempts < 6) {
                    walkAttempts++
                    if (currentToken != null && currentToken.startsWith("shelf:")) {
                        val parts = currentToken.removePrefix("shelf:").split(":", limit = 2)
                        val sIdx = parts.getOrNull(0)?.toIntOrNull() ?: 0
                        val sTok = parts.getOrNull(1)
                        if (!sTok.isNullOrBlank() && sTok != "start") {
                            val morePage = runCatching {
                                parseShorts(post("browse", JSONObject().put("continuation", sTok)))
                            }.getOrNull()
                            if (morePage != null) {
                                val fresh = morePage.videos.filter { it.id !in allSeen }
                                fresh.forEach { result.putIfAbsent(it.id, it) }
                                currentToken = if (!morePage.next.isNullOrBlank()) "shelf:$sIdx:${morePage.next}" else "shelf:${(sIdx + 1) % shelves.size}:start"
                            } else {
                                currentToken = "shelf:${(sIdx + 1) % shelves.size}:start"
                            }
                        } else {
                            val shelf = shelves[(sIdx + 1) % shelves.size]
                            val morePage = runCatching {
                                parseShorts(post("browse", JSONObject().put("browseId", shelf)))
                            }.getOrNull()
                            if (morePage != null) {
                                val fresh = morePage.videos.filter { it.id !in allSeen }
                                fresh.forEach { result.putIfAbsent(it.id, it) }
                                currentToken = if (!morePage.next.isNullOrBlank()) "shelf:${(sIdx + 1) % shelves.size}:${morePage.next}" else "shelf:${(sIdx + 2) % shelves.size}:start"
                            }
                        }
                    } else break
                }
                continuationToken = currentToken
            }

            if (result.isNotEmpty()) {
                return@withContext FeedPage(result.values.toList(), continuationToken ?: "shelf:0:start")
            }
        }

        val fallback = loadInfiniteShortsFallback(next, allSeen)
        fallback.videos.forEach { result.putIfAbsent(it.id, it) }
        FeedPage(result.values.toList(), fallback.next)
    }

    suspend fun newShorts(sessionSeen: Set<String>, token: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val allSeen = SeenShorts.allSeen() + sessionSeen
        val result = LinkedHashMap<String, VideoItem>()
        var continuationToken: String? = null

        if (Account.signedIn) {
            val shelves = listOf("FEshorts", "FEwhat_to_watch", "FEsubscriptions", "FEhistory").shuffled()
            for (browseId in shelves) {
                if (result.size >= 15) break
                val page = runCatching {
                    parseShorts(post("browse", JSONObject().put("browseId", browseId)))
                }.getOrNull()
                if (page != null) {
                    val fresh = page.videos.filter { it.id !in allSeen }
                    fresh.forEach { result.putIfAbsent(it.id, it) }
                    if (continuationToken == null) continuationToken = page.next
                }
            }
        }
        if (result.size < 15) {
            val fallback = loadInfiniteShortsFallback(token, allSeen)
            fallback.videos.forEach { result.putIfAbsent(it.id, it) }
            if (continuationToken == null) continuationToken = fallback.next
        }
        FeedPage(result.values.toList(), continuationToken ?: "shelf:0:start")
    }

    internal fun parseShorts(root: JSONObject): FeedPage {
        val out = LinkedHashMap<String, VideoItem>()
        var next: String? = root.optJSONObject("continuationEndpoint")
            ?.optJSONObject("continuationCommand")?.optString("token")?.ifBlank { null }
            ?: root.optJSONObject("continuationEndpoint")
                ?.optJSONObject("reelWatchSequenceEndpoint")?.optString("sequenceParams")?.ifBlank { null }
            ?: root.optString("continuation").takeIf { it.isNotBlank() }
        root.walk { key, obj ->
            when (key) {
                "reelItemRenderer" -> {
                    val id = obj.optString("videoId").ifBlank { return@walk }
                    val title = obj.text("headline") ?: obj.text("title") ?: "Short"
                    out.getOrPut(id) {
                        VideoItem(
                            id = id,
                            title = title,
                            channel = obj.text("shortBylineText")?.substringBefore(" • ").orEmpty(),
                            channelUrl = obj.collectStrings("browseId").firstOrNull { it.startsWith("UC") }
                                ?.let { "https://www.youtube.com/channel/$it" },
                            thumbnail = obj.bestThumbnailUrl() ?: feedThumbnailUrl(id),
                            avatar = null,
                            durationSec = obj.text("lengthText")?.let(::parseDuration) ?: 0L,
                            views = parseViews(obj.text("viewCountText")),
                            uploaded = null,
                        )
                    }
                }
                "shortsLockupViewModel" -> {
                    val endpoint = obj.optJSONObject("onTap")?.optJSONObject("innertubeCommand")
                    val entity = obj.optString("entityId")
                    val id = endpoint?.optJSONObject("reelWatchEndpoint")?.optString("videoId")
                        ?: endpoint?.optJSONObject("watchEndpoint")?.optString("videoId")
                        ?: obj.collectStrings("videoId").firstOrNull { it.length == 11 }
                        ?: entity.substringAfter("shorts-shelf-item-", entity).takeIf { it.length == 11 }
                        ?: return@walk
                    val meta = obj.optJSONObject("overlayMetadata")
                    val title = meta?.optJSONObject("primaryText")?.optString("content")?.ifBlank { null } ?: "Short"
                    val viewsText = meta?.optJSONObject("secondaryText")?.optString("content")
                    out.getOrPut(id) {
                        VideoItem(
                            id = id,
                            title = title,
                            channel = "",
                            channelUrl = null,
                            thumbnail = obj.bestThumbnailUrl() ?: feedThumbnailUrl(id),
                            avatar = null,
                            durationSec = 0L,
                            views = parseViews(viewsText),
                            uploaded = null,
                        )
                    }
                }

                "lockupViewModel" -> {
                    val command = obj.optJSONObject("rendererContext")?.optJSONObject("commandContext")
                        ?.optJSONObject("onTap")?.optJSONObject("innertubeCommand")
                        ?: obj.optJSONObject("onTap")?.optJSONObject("innertubeCommand")
                    val reel = command?.optJSONObject("reelWatchEndpoint")
                    val url = command?.optJSONObject("commandMetadata")
                        ?.optJSONObject("webCommandMetadata")?.optString("url").orEmpty()
                    val sources = obj.optJSONObject("contentImage")
                        ?.optJSONObject("thumbnailViewModel")
                        ?.optJSONObject("image")?.optJSONArray("sources")
                    val sample = sources?.optJSONObject((sources.length() - 1).coerceAtLeast(0))
                    val thumbWidth = sample?.optInt("width") ?: 0
                    val thumbHeight = sample?.optInt("height") ?: 0
                    val contentType = obj.optString("contentType")
                    val portrait = thumbWidth > 0 && thumbHeight > thumbWidth * 6 / 5
                    val isShort = reel != null || contentType.contains("SHORT", ignoreCase = true) ||
                        url.contains("/shorts/") || portrait
                    if (!isShort) return@walk
                    val id = reel?.optString("videoId")?.ifBlank { null }
                        ?: command?.optJSONObject("watchEndpoint")?.optString("videoId")?.ifBlank { null }
                        ?: obj.optString("contentId").takeIf { it.length == 11 }
                        ?: return@walk
                    val meta = obj.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                    val title = meta?.optJSONObject("title")?.optString("content")?.ifBlank { null } ?: "Short"
                    val parts = mutableListOf<String>()
                    meta?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")
                        ?.optJSONArray("metadataRows")?.let { rows ->
                            for (r in 0 until rows.length()) {
                                val ps = rows.optJSONObject(r)?.optJSONArray("metadataParts") ?: continue
                                for (p in 0 until ps.length()) {
                                    ps.optJSONObject(p)?.optJSONObject("text")
                                        ?.optString("content")?.ifBlank { null }?.let(parts::add)
                                }
                            }
                        }
                    val channel = parts.firstOrNull {
                        !it.contains("view") && !it.contains("ago") && !it.contains("watching")
                    }.orEmpty()
                    out.getOrPut(id) {
                        VideoItem(
                            id = id,
                            title = title,
                            channel = channel,
                            channelUrl = obj.collectStrings("browseId")
                                .firstOrNull { it.startsWith("UC") }
                                ?.let { "https://www.youtube.com/channel/$it" },
                            thumbnail = sources?.widestSourceUrl() ?: feedThumbnailUrl(id),
                            avatar = null,
                            durationSec = 0L,
                            views = parseViews(parts.firstOrNull { it.contains("view") }),
                            uploaded = null,
                        )
                    }
                }
                "videoRenderer" -> {
                    val id = obj.optString("videoId").ifBlank { return@walk }
                    val url = obj.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("commandMetadata")
                        ?.optJSONObject("webCommandMetadata")?.optString("url").orEmpty()
                    val thumbs = obj.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    val sample = thumbs?.optJSONObject((thumbs.length() - 1).coerceAtLeast(0))
                    val tw = sample?.optInt("width") ?: 0
                    val th = sample?.optInt("height") ?: 0
                    val portrait = tw > 0 && th > tw * 6 / 5
                    val shortStyle = obj.collectStrings("style").any { it == "SHORTS" } ||
                        url.contains("/shorts/") || portrait
                    if (!shortStyle) return@walk
                    out.getOrPut(id) {
                        VideoItem(
                            id = id,
                            title = obj.text("title") ?: "Short",
                            channel = obj.text("ownerText")?.substringBefore(" • ").orEmpty(),
                            channelUrl = obj.collectStrings("browseId").firstOrNull { it.startsWith("UC") }
                                ?.let { "https://www.youtube.com/channel/$it" },
                            thumbnail = obj.bestThumbnailUrl() ?: feedThumbnailUrl(id),
                            avatar = null,
                            durationSec = obj.text("lengthText")?.let(::parseDuration) ?: 0L,
                            views = parseViews(obj.text("viewCountText")),
                            uploaded = null,
                        )
                    }
                }
                "reelPlayerOverlayRenderer", "reelWatchEndpoint" -> {
                    val id = obj.optString("videoId").ifBlank {
                        obj.optJSONObject("reelWatchEndpoint")?.optString("videoId").orEmpty()
                    }
                    if (id.isNotBlank()) out.getOrPut(id) {
                        VideoItem(
                            id = id,
                            title = obj.text("headline") ?: "Short",
                            channel = "",
                            channelUrl = null,
                            thumbnail = obj.bestThumbnailUrl() ?: feedThumbnailUrl(id),
                            avatar = null,
                            durationSec = 0L,
                            views = -1,
                            uploaded = null,
                        )
                    }
                }
            }
            if (key == "continuationItemRenderer" || key == "continuationItemViewModel") {
                obj.collectStrings("token").firstOrNull()?.let { next = it }
            }
        }
        return FeedPage(out.values.toList(), next)
    }

    internal fun collectMusicSongs(root: JSONObject): List<VideoItem> {
        val out = mutableListOf<VideoItem>()
        root.walk { key, obj ->
            if (key == "musicResponsiveListItemRenderer") {
                val vid = obj.optJSONObject("playlistItemData")?.optString("videoId")
                    ?: obj.optJSONObject("overlay")
                        ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("musicPlayButtonRenderer")
                        ?.optJSONObject("playNavigationEndpoint")
                        ?.optJSONObject("watchEndpoint")?.optString("videoId")
                if (!vid.isNullOrEmpty()) {
                    val flexCols = obj.optJSONArray("flexColumns")
                    val col0 = flexCols?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    val col1 = flexCols?.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    val title = col0?.text("text") ?: "Track"
                    val artist = col1?.text("text") ?: "Unknown"
                    val thumb = highResMusicThumbnail(
                        obj.optJSONObject("thumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")?.thumbnails()?.lastOrNull()
                    )
                    out.add(
                        VideoItem(
                            id = vid,
                            title = title,
                            channel = artist,
                            channelUrl = null,
                            thumbnail = thumb,
                            avatar = null,
                            durationSec = 0L,
                            views = -1L,
                            uploaded = "YouTube Music",
                        )
                    )
                }
            } else if (key == "musicTwoRowItemRenderer") {
                val ep = obj.optJSONObject("navigationEndpoint")
                val vid = ep?.optJSONObject("watchEndpoint")?.optString("videoId")
                    ?.takeIf { it.isNotEmpty() }
                    ?: ep?.optJSONObject("browseEndpoint")?.optString("browseId")
                        ?.takeIf { it.startsWith("MPED") }?.removePrefix("MPED")
                if (!vid.isNullOrEmpty()) {
                    val title = obj.text("title") ?: "Track"
                    val subtitle = obj.text("subtitle") ?: ""
                    val thumb = highResMusicThumbnail(
                        obj.optJSONObject("thumbnailRenderer")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")?.thumbnails()?.lastOrNull()
                    )
                    out.add(
                        VideoItem(
                            id = vid,
                            title = title,
                            channel = subtitle,
                            channelUrl = null,
                            thumbnail = thumb,
                            avatar = null,
                            durationSec = 0L,
                            views = -1L,
                            uploaded = "YouTube Music",
                        )
                    )
                }
            } else if (key == "playlistPanelVideoRenderer") {
                val vid = obj.optString("videoId").takeIf { it.isNotEmpty() }
                if (!vid.isNullOrEmpty()) {
                    val title = obj.text("title") ?: "Track"
                    val artist = obj.text("longBylineText") ?: obj.text("shortBylineText") ?: ""
                    val thumb = highResMusicThumbnail(
                        obj.optJSONObject("thumbnail")?.thumbnails()?.lastOrNull()
                    )
                    out.add(
                        VideoItem(
                            id = vid,
                            title = title,
                            channel = artist,
                            channelUrl = null,
                            thumbnail = thumb,
                            avatar = null,
                            durationSec = 0L,
                            views = -1L,
                            uploaded = "YouTube Music",
                        )
                    )
                }
            }
        }
        return out
    }

    suspend fun resolveMusicItem(item: MusicShelfItem): VideoItem? = withContext(Dispatchers.IO) {
        if (!item.videoId.isNullOrEmpty()) {
            return@withContext item.toVideoItem()
        }
        val pid = item.playlistId
        if (!pid.isNullOrEmpty()) {
            runCatching {
                val nextResp = postMusic("next", JSONObject().put("playlistId", pid))
                val songs = collectMusicSongs(nextResp)
                if (songs.isNotEmpty()) return@withContext songs.first()
            }
            runCatching {
                val browseResp = postMusic("browse", JSONObject().put("browseId", if (pid.startsWith("VL")) pid else "VL$pid"))
                val songs = collectMusicSongs(browseResp)
                if (songs.isNotEmpty()) return@withContext songs.first()
            }
        }
        val bid = item.browseId
        if (!bid.isNullOrEmpty()) {
            runCatching {
                val browseResp = postMusic("browse", JSONObject().put("browseId", bid))
                val songs = collectMusicSongs(browseResp)
                if (songs.isNotEmpty()) return@withContext songs.first()
            }
        }

        runCatching {
            val q = "${item.title} ${item.subtitle}".trim()
            val results = YouTube.searchMusic(q)
            if (results.isNotEmpty()) return@withContext results.first()
        }
        null
    }

    



    suspend fun musicItemSongs(item: MusicShelfItem): List<VideoItem> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, VideoItem>()
        fun addAll(list: List<VideoItem>) {
            list.forEach { if (it.id.length == 11) out.putIfAbsent(it.id, it) }
        }

        val pid = item.playlistId
        val bid = item.browseId
        if (!pid.isNullOrEmpty()) {
            runCatching { addAll(collectMusicSongs(postMusic("next", JSONObject().put("playlistId", pid)))) }
            if (out.isEmpty()) {
                runCatching {
                    addAll(
                        collectMusicSongs(
                            postMusic("browse", JSONObject().put("browseId", if (pid.startsWith("VL")) pid else "VL$pid")),
                        ),
                    )
                }
            }
        }
        if (out.isEmpty() && !bid.isNullOrEmpty()) {
            runCatching { addAll(collectMusicSongs(postMusic("browse", JSONObject().put("browseId", bid)))) }

            if (out.isEmpty() && bid.startsWith("UC")) {
                runCatching {
                    val body = JSONObject().put("browseId", bid).put("params", "ggMGKgQIARAA")
                    addAll(collectMusicSongs(postMusic("browse", body)))
                }
            }
        }

        if (out.isEmpty() && !item.videoId.isNullOrEmpty()) {
            runCatching {
                val body = JSONObject()
                    .put("videoId", item.videoId)
                    .put("playlistId", "RDAMVM${item.videoId}")
                addAll(collectMusicSongs(postMusic("next", body)))
            }
        }
        if (out.isEmpty()) {
            runCatching {
                addAll(YouTube.searchMusic("${item.title} ${item.subtitle}".trim()).take(30))
            }
        }
        out.values.toList()
    }

    internal fun parseMusicShelves(root: JSONObject): List<MusicShelf> {
        val out = mutableListOf<MusicShelf>()
        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
            ?: return out
        val sectionList = tabs.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
            ?: return out

        for (i in 0 until sectionList.length()) {
            val section = sectionList.optJSONObject(i) ?: continue
            val carousel = section.optJSONObject("musicCarouselShelfRenderer")
            val plain = section.optJSONObject("musicShelfRenderer")
            val shelfObj = carousel ?: plain ?: continue

            val headerObj = shelfObj.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                ?: shelfObj.optJSONObject("title")
            val title = headerObj?.text("title") ?: shelfObj.text("title") ?: continue
            val strapline = headerObj?.text("strapline")

            val contentsArr = shelfObj.optJSONArray("contents") ?: continue
            val items = mutableListOf<MusicShelfItem>()
            for (j in 0 until contentsArr.length()) {
                val itemObj = contentsArr.optJSONObject(j) ?: continue
                val twoRow = itemObj.optJSONObject("musicTwoRowItemRenderer")
                val responsive = itemObj.optJSONObject("musicResponsiveListItemRenderer")

                if (twoRow != null) {
                    val itemTitle = twoRow.text("title") ?: continue
                    val itemSub = twoRow.text("subtitle") ?: ""
                    val ep = twoRow.optJSONObject("navigationEndpoint")
                    val playEp = twoRow.optJSONObject("thumbnailOverlay")
                        ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("musicPlayButtonRenderer")
                        ?.optJSONObject("playNavigationEndpoint")
                    val watchEp = playEp?.optJSONObject("watchEndpoint") ?: ep?.optJSONObject("watchEndpoint")
                    val watchPlaylistEp = playEp?.optJSONObject("watchPlaylistEndpoint") ?: ep?.optJSONObject("watchPlaylistEndpoint")

                    val vid = watchEp?.optString("videoId")?.takeIf { it.isNotEmpty() }
                        ?: ep?.optJSONObject("browseEndpoint")?.optString("browseId")?.takeIf { it.startsWith("MPED") }?.removePrefix("MPED")
                    val playlistId = watchPlaylistEp?.optString("playlistId")?.takeIf { it.isNotEmpty() }
                        ?: watchEp?.optString("playlistId")?.takeIf { it.isNotEmpty() }
                        ?: ep?.optJSONObject("browseEndpoint")?.optString("browseId")?.takeIf { it.startsWith("VL") }?.removePrefix("VL")
                    val browseId = ep?.optJSONObject("browseEndpoint")?.optString("browseId")
                        ?.takeUnless { it.startsWith("MPED") }
                    val thumb = highResMusicThumbnail(
                        twoRow.optJSONObject("thumbnailRenderer")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")?.thumbnails()?.lastOrNull()
                    )
                    items.add(MusicShelfItem(itemTitle, itemSub, thumb, vid, browseId, playlistId))
                } else if (responsive != null) {
                    val playEp = responsive.optJSONObject("overlay")
                        ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("musicPlayButtonRenderer")
                        ?.optJSONObject("playNavigationEndpoint")
                    val vid = responsive.optJSONObject("playlistItemData")?.optString("videoId")
                        ?: playEp?.optJSONObject("watchEndpoint")?.optString("videoId")
                    val playlistId = responsive.optJSONObject("playlistItemData")?.optString("playlistId")
                        ?: playEp?.optJSONObject("watchEndpoint")?.optString("playlistId")
                        ?: playEp?.optJSONObject("watchPlaylistEndpoint")?.optString("playlistId")
                    val flexCols = responsive.optJSONArray("flexColumns")
                    val col0 = flexCols?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    val col1 = flexCols?.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    val itemTitle = col0?.text("text") ?: continue
                    val itemSub = col1?.text("text") ?: ""
                    val thumb = highResMusicThumbnail(
                        responsive.optJSONObject("thumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")?.thumbnails()?.lastOrNull()
                    )
                    items.add(MusicShelfItem(itemTitle, itemSub, thumb, vid, null, playlistId))
                }
            }
            if (items.isNotEmpty()) {
                out.add(MusicShelf(title, strapline, items))
            }
        }
        return out
    }



    suspend fun search(query: String, filters: SearchFilters, continuation: String? = null): SearchPage =
        withContext(Dispatchers.IO) {
            val body = if (continuation != null) {
                JSONObject().put("continuation", continuation)
            } else {
                JSONObject().put("query", query).apply {
                    filters.params()?.let { put("params", it) }
                }
            }
            parseSearch(postAny("search", body))
        }

    suspend fun browse(browseId: String, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val body = if (continuation != null) JSONObject().put("continuation", continuation) else JSONObject().put("browseId", browseId)
        parseFeed(post("browse", body))
    }

    suspend fun home(next: String? = null) = browse("FEwhat_to_watch", next)

    





    private val POPULAR_TOPICS = listOf(
        "Trending",
        "Viral videos",
        "Must watch",
        "Popular videos",
        "Mind blowing discoveries",
        "Top music hits",
        "Tech & AI innovations",
        "Cinema & movie trailers",
        "Epic nature & wildlife",
        "Gaming highlights",
        "Amazing talents & skills",
        "Science & space exploration",
        "Stand-up comedy clips",
        "Documentary deep dives",
        "Greatest sports moments",
        "Creative animation",
        "Automotive & supercars",
        "World wonders & travel",
        "Top podcasts & interviews",
        "Interesting facts",
        "Live music performances",
        "Gadget reviews & setups",
    )

    private val anonSeenVideoIds = LinkedHashSet<String>()

    suspend fun homeFeed(next: String? = null): FeedPage = withContext(Dispatchers.IO) {
        // Signed in: this is the real personalised FEwhat_to_watch feed.
        if (Account.signedIn && (next == null || !next.startsWith("anon_feed:"))) {
            val body = if (next != null) JSONObject().put("continuation", next)
                       else JSONObject().put("browseId", "FEwhat_to_watch")
            val raw = post("browse", body)
            val personal = runCatching { parseFeed(raw) }.getOrNull()
            if (personal != null && (personal.videos.isNotEmpty() || personal.next != null)) {
                WatchSyncLog.note(
                    "home page videos=${personal.videos.size} posts=${personal.entries.count { it is FeedEntry.Post }} next=${personal.next != null}",
                )
                return@withContext personal
            }
        }

        // Signed out (or the personalised request failed/ended): load unlimited infinite feed
        loadAnonymousInfiniteHomeFeed(next)
    }

    /**
     * The web home feed never carries community posts, so fetch the newest post of a few
     * channels shown in the feed. Runs off the critical path so the feed paints first.
     */
    suspend fun communityPostsFor(channelIds: List<String>, limit: Int = 3): List<CommunityPost> =
        withContext(Dispatchers.IO) {
            kotlinx.coroutines.coroutineScope {
                channelIds.map { id ->
                    async {
                        runCatching {
                            kotlinx.coroutines.withTimeout(6000) { channelCommunity(id).posts.firstOrNull() }
                        }.getOrNull()
                    }
                }.map { it.await() }.filterNotNull().take(limit)
            }
        }

    private suspend fun loadAnonymousInfiniteHomeFeed(next: String?): FeedPage = kotlinx.coroutines.coroutineScope {
        // Initial page: deliver a dynamic, diverse, and unique mix each time
        if (next == null) {
            val randomTopics = POPULAR_TOPICS.shuffled().take(3)
            val trendingDeferred = async { runCatching { YouTube.trending(null) }.getOrNull() }
            val topic1Deferred = async { runCatching { YouTube.search(randomTopics[0]) }.getOrDefault(emptyList()) }
            val topic2Deferred = async { runCatching { YouTube.search(randomTopics[1]) }.getOrDefault(emptyList()) }
            val topic3Deferred = async { runCatching { YouTube.search(randomTopics[2]) }.getOrDefault(emptyList()) }

            val trendingVideos = trendingDeferred.await()?.videos.orEmpty()
            val list1 = topic1Deferred.await()
            val list2 = topic2Deferred.await()
            val list3 = topic3Deferred.await()

            val combined = ArrayList<VideoItem>()
            val maxLen = maxOf(trendingVideos.size, list1.size, list2.size, list3.size)
            for (i in 0 until maxLen) {
                if (i < trendingVideos.size) combined.add(trendingVideos[i])
                if (i < list1.size) combined.add(list1[i])
                if (i < list2.size) combined.add(list2[i])
                if (i < list3.size) combined.add(list3[i])
            }

            val unique = combined.distinctBy { it.id }
            val fresh = synchronized(anonSeenVideoIds) {
                unique.filter { it.id !in anonSeenVideoIds }
            }
            val result = if (fresh.size >= 12) fresh else unique

            synchronized(anonSeenVideoIds) {
                result.forEach { anonSeenVideoIds.add(it.id) }
                if (anonSeenVideoIds.size > 800) {
                    val excess = anonSeenVideoIds.size - 500
                    val iter = anonSeenVideoIds.iterator()
                    repeat(excess) { if (iter.hasNext()) { iter.next(); iter.remove() } }
                }
            }

            val nextIdx = (0 until POPULAR_TOPICS.size).random()
            return@coroutineScope FeedPage(result, "anon_feed:$nextIdx:start")
        }

        // Continuing pagination token: "anon_feed:<topicIndexOrTrend>:<pageUrlOrMarker>"
        val parts = next.removePrefix("anon_feed:").split(":", limit = 2)
        val categoryOrIdx = parts.getOrNull(0) ?: "0"
        val cursor = parts.getOrNull(1)

        if (categoryOrIdx == "trend" && !cursor.isNullOrBlank() && cursor != "start") {
            val moreTrending = runCatching { YouTube.trending(cursor) }.getOrNull()
            if (moreTrending != null && moreTrending.videos.isNotEmpty()) {
                val nextToken = if (!moreTrending.next.isNullOrBlank()) "anon_feed:trend:${moreTrending.next}" else "anon_feed:0:start"
                return@coroutineScope FeedPage(moreTrending.videos, nextToken)
            }
        }

        val topicIdx = categoryOrIdx.toIntOrNull() ?: 0
        val pageUrl = if (cursor == "start") null else cursor

        // Try consecutive topics to guarantee fresh results even if a query returns empty
        for (attempt in 0..3) {
            val currentIdx = (topicIdx + attempt) % POPULAR_TOPICS.size
            val query = POPULAR_TOPICS[currentIdx]
            val pageToFetch = if (attempt == 0) pageUrl else null

            val result = runCatching { YouTube.searchPage(query, pageToFetch) }.getOrNull()
            if (result != null && result.videos.isNotEmpty()) {
                val filtered = synchronized(anonSeenVideoIds) {
                    result.videos.filter { it.id !in anonSeenVideoIds }
                }.ifEmpty { result.videos }

                synchronized(anonSeenVideoIds) {
                    filtered.forEach { anonSeenVideoIds.add(it.id) }
                }

                val nextToken = if (!result.next.isNullOrBlank()) {
                    "anon_feed:$currentIdx:${result.next}"
                } else {
                    val nextIdx = (currentIdx + 1) % POPULAR_TOPICS.size
                    "anon_feed:$nextIdx:start"
                }
                return@coroutineScope FeedPage(filtered, nextToken)
            }
        }

        // Infinite fallback: query a random topic so scrolling never terminates
        val randomTopic = POPULAR_TOPICS.random()
        val fallbackVideos = runCatching { YouTube.search(randomTopic) }.getOrDefault(emptyList())
        val nextIdx = (topicIdx + 1) % POPULAR_TOPICS.size
        return@coroutineScope FeedPage(fallbackVideos, "anon_feed:$nextIdx:start")
    }

    suspend fun channelVideos(channelId: String, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val body = if (continuation != null) JSONObject().put("continuation", continuation)
        else JSONObject().put("browseId", channelId).put("params", CHANNEL_VIDEOS_TAB)
        val root = postAny("browse", body)
        val page = parseFeed(root)
        val header = if (continuation == null) parseChannelHeader(root) else null
        FeedPage(page.videos, page.next, header)
    }

    suspend fun channelLive(channelId: String, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val body = if (continuation != null) JSONObject().put("continuation", continuation)
        else JSONObject().put("browseId", channelId).put("params", CHANNEL_LIVE_TAB)
        val root = postAny("browse", body)
        val page = parseFeed(root)
        FeedPage(page.videos, page.next)
    }

    suspend fun channelCommunity(channelId: String, continuation: String? = null): CommunityPage = withContext(Dispatchers.IO) {
        val body = if (continuation != null) JSONObject().put("continuation", continuation)
        else JSONObject().put("browseId", channelId).put("params", CHANNEL_COMMUNITY_TAB)
        val root = postAny("browse", body)
        parseCommunity(root)
    }

    suspend fun channelShorts(channelId: String, continuation: String? = null): FeedPage = withContext(Dispatchers.IO) {
        val body = if (continuation != null) JSONObject().put("continuation", continuation)
        else JSONObject().put("browseId", channelId).put("params", CHANNEL_SHORTS_TAB)
        parseShorts(postAny("browse", body))
    }


    private const val CHANNEL_VIDEOS_TAB = "EgZ2aWRlb3PyBgQKAjoA"
    private const val CHANNEL_SHORTS_TAB = "EgZzaG9ydHPyBgUKA5oBAA%3D%3D"
    private const val CHANNEL_LIVE_TAB = "EgZzdHJlYW1z8gYECgJ6AA%3D%3D"
    private const val CHANNEL_COMMUNITY_TAB = "Egljb21tdW5pdHnyBgQKAkoA"
    suspend fun subscriptionsFeed(next: String? = null) = browse("FEsubscriptions", next)
    suspend fun history(next: String? = null) = browse("FEhistory", next)
    suspend fun playlist(id: String, next: String? = null) = browse("VL$id", next)


    suspend fun channelAvatarUrl(channelId: String): String? = withContext(Dispatchers.IO) {
        val root = postAny("browse", JSONObject().put("browseId", channelId))
        parseChannelAvatar(root.optJSONObject("header"))
    }

    internal fun parseChannelAvatar(header: JSONObject?): String? {
        if (header == null) return null
        var url: String? = null
        header.walk { key, obj ->
            if (url != null) return@walk
            if (key == "avatarViewModel" || key == "decoratedAvatarViewModel") {
                obj.optJSONObject("image")?.optJSONArray("sources")?.widestSourceUrl()?.let { url = it }
            }
        }
        return url?.let { normalizeChannelAvatarUrl(absoluteUrl(it)) }
    }

    suspend fun channels(): List<ChannelRef> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, ChannelRef>()
        post("browse", JSONObject().put("browseId", "FEchannels")).walk { key, obj ->
            if (key == "channelRenderer" || key == "gridChannelRenderer") {
                val id = obj.optString("channelId").ifBlank { return@walk }
                out.getOrPut(id) {
                    ChannelRef(id, obj.text("title") ?: "", obj.thumbnails()?.lastOrNull()?.let(::absoluteUrl))
                }
            }
        }
        out.values.toList()
    }

    suspend fun playlists(): List<PlaylistRef> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, PlaylistRef>()
        post("browse", JSONObject().put("browseId", "FEplaylist_aggregation")).walk { key, obj ->
            when (key) {
                "gridPlaylistRenderer", "playlistRenderer" -> {
                    val id = obj.optString("playlistId").ifBlank { return@walk }
                    out.getOrPut(id) {
                        PlaylistRef(id, obj.text("title") ?: "", obj.thumbnails()?.lastOrNull()?.let(::absoluteUrl), obj.text("videoCountText") ?: obj.text("videoCountShortText"))
                    }
                }
                "lockupViewModel" -> if (obj.optString("contentType") == "LOCKUP_CONTENT_TYPE_PLAYLIST") {
                    val id = obj.optString("contentId").ifBlank { return@walk }
                    val meta = obj.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                    out.getOrPut(id) {
                        PlaylistRef(
                            id = id,
                            title = meta?.optJSONObject("title")?.optString("content").orEmpty(),
                            thumbnail = obj.firstImageSource(),
                            subtitle = obj.collectStrings("text").firstOrNull { it.contains("video", ignoreCase = true) },
                        )
                    }
                }
            }
        }
        out.values.filterNot { it.id == "WL" || it.id == "LL" }
    }


    suspend fun videoState(videoId: String): VideoAccountState = withContext(Dispatchers.IO) {
        val next = post("next", JSONObject().put("videoId", videoId))
        var like = LikeStatus.None
        var subscribed: Boolean? = null
        var channelId: String? = null
        next.walk { key, obj ->
            when (key) {
                "likeStatusEntity" -> like = when (obj.optString("likeStatus")) {
                    "LIKE" -> LikeStatus.Like
                    "DISLIKE" -> LikeStatus.Dislike
                    else -> like
                }
                "subscribeButtonRenderer" -> {
                    if (obj.has("subscribed")) subscribed = obj.optBoolean("subscribed")
                    obj.optString("channelId").ifBlank { null }?.let { channelId = it }
                }
                "subscriptionStateEntity" -> if (obj.has("subscribed")) subscribed = obj.optBoolean("subscribed")
                "videoOwnerRenderer" -> obj.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")?.takeIf { it.startsWith("UC") }?.let { if (channelId == null) channelId = it }
            }
        }
        VideoAccountState(like, subscribed, channelId)
    }


    suspend fun channelSubscribed(channelId: String): Boolean? = withContext(Dispatchers.IO) {
        var subscribed: Boolean? = null
        post("browse", JSONObject().put("browseId", channelId)).walk { key, obj ->
            if ((key == "subscribeButtonRenderer" || key == "subscriptionStateEntity") && obj.has("subscribed") && subscribed == null) {
                subscribed = obj.optBoolean("subscribed")
            }
        }
        subscribed
    }



    suspend fun setLike(videoId: String, status: LikeStatus) = withContext(Dispatchers.IO) {
        val endpoint = when (status) {
            LikeStatus.Like -> "like/like"
            LikeStatus.Dislike -> "like/dislike"
            LikeStatus.None -> "like/removelike"
        }
        post(endpoint, JSONObject().put("target", JSONObject().put("videoId", videoId)))
        Unit
    }

    /**
     * Community post likes are not videos, so the like/like target rejects a post id.
     * Replay the command YouTube itself put on the post's like button instead.
     */
    suspend fun setPostLike(communityPost: CommunityPost, liked: Boolean) = withContext(Dispatchers.IO) {
        val raw = if (liked) communityPost.likeAction else communityPost.unlikeAction
        try {
            val request = actionRequest(raw) ?: (if (liked) "like/like" else "like/removelike") to
                JSONObject().put("target", JSONObject().put("videoId", communityPost.id))
            post(request.first, request.second)
        } catch (t: Throwable) {
            WatchSyncLog.note("post like err ${t.message}")
            throw t
        }
    }

    /** Turns a button's navigation endpoint payload into an InnerTube api path + request body. */
    private fun actionRequest(raw: String?): Pair<String, JSONObject>? {
        val o = runCatching { JSONObject(raw ?: return null) }.getOrNull() ?: return null
        val api = o.optJSONObject("commandMetadata")?.optJSONObject("webCommandMetadata")
            ?.optString("apiUrl")?.removePrefix("/youtubei/v1/")
            ?.substringBefore('?')?.ifBlank { null } ?: return null
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!key.endsWith("Endpoint")) continue
            val payload = o.optJSONObject(key) ?: continue
            val body = when (key) {
                "likeEndpoint" -> payload
                "performCommentActionEndpoint", "commentActionEndpoint" -> {
                    val token = payload.optString("action").ifBlank { null }
                    when {
                        token != null -> JSONObject().put("actions", JSONArray().put(token))
                        payload.has("actions") -> JSONObject().put("actions", payload.optJSONArray("actions"))
                        else -> payload
                    }
                }
                else -> payload
            }
            return api to body
        }
        return null
    }

    suspend fun setSubscribed(channelId: String, subscribed: Boolean) = withContext(Dispatchers.IO) {
        post(
            if (subscribed) "subscription/subscribe" else "subscription/unsubscribe",
            JSONObject().put("channelIds", JSONArray().put(channelId)),
        )
        Unit
    }

    suspend fun setInWatchLater(videoId: String, add: Boolean) = withContext(Dispatchers.IO) {
        val action = if (add) JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", videoId)
        else JSONObject().put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID").put("removedVideoId", videoId)
        post("browse/edit_playlist", JSONObject().put("playlistId", "WL").put("actions", JSONArray().put(action)))
        Unit
    }


    class WatchSession(
        val videoId: String,
        val watchtimeUrl: String,
        val cpn: String,
        val playbackUrl: String?,
        val lengthSec: Double,
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val visitorData: String? = null,
    )

    internal data class WatchTracking(
        val playbackUrl: String?,
        val watchtimeUrl: String,
        val clientName: String = "IOS",
        val clientVersion: String = IOS_CLIENT_VERSION,
        val userAgent: String = IOS_USER_AGENT,
        val visitorData: String? = null,
    )

    @Volatile
    private var lastSession: WatchSession? = null

    private data class PlayerClient(
        val name: String,
        val version: String,
        val id: String,
        val userAgent: String,
        val extra: JSONObject,
    )

    /**
     * The WEB player answers UNPLAYABLE without a PO token and returns no playbackTracking.
     * Android and iOS still return the stats URLs; Android is first because SmartTube's
     * from-scratch Android history writes are the proven case.
     */
    private fun playerTracking(videoId: String): WatchTracking? {
        // WEB is the client that shares this app's cookie sign-in. When the account
        // response carries playbackTracking, its ei/vm/of belong to the signed-in
        // session and are the closest match to SmartTube's authenticated player info.
        val web = runCatching {
            parseWatchTracking(playerResponse(videoId))?.copy(
                clientName = "WEB",
                clientVersion = Account.clientVersion(),
                userAgent = Net.USER_AGENT,
            )
        }.onFailure { android.util.Log.w("YTXTube", "watch tracking (WEB) for $videoId", it) }
            .getOrNull()
        if (web != null) return web

        val clients = listOf(
            PlayerClient("ANDROID", ANDROID_CLIENT_VERSION, "3", ANDROID_USER_AGENT, JSONObject().put("androidSdkVersion", 30)),
            PlayerClient("IOS", IOS_CLIENT_VERSION, "5", IOS_USER_AGENT, JSONObject().put("deviceModel", "iPhone16,2")),
        )
        for (client in clients) {
            val tracking = runCatching { fetchWatchTracking(videoId, client) }
                .onFailure { android.util.Log.w("YTXTube", "watch tracking (${client.name}) for $videoId", it) }
                .getOrNull()
            if (tracking != null) return tracking
        }
        return null
    }

    private data class TvPageData(
        val visitorData: String?,
        val livingRoomPoTokenId: String?,
        val signatureTimestamp: String?,
    )

    @Volatile private var tvPageCache: TvPageData? = null
    @Volatile private var tvPageCacheAt: Long = 0L

    private fun tvPageData(userAgent: String): TvPageData? {
        val cached = tvPageCache
        if (cached != null && System.currentTimeMillis() - tvPageCacheAt < 5 * 60_000L) return cached
        val request = Request.Builder()
            .url("https://www.youtube.com/tv")
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                WatchSyncLog.note("tv-oauth tv-page HTTP ${response.code}")
                return null
            }
            val html = response.body?.string().orEmpty()
            val visitor = Regex("\"visitorData\":\"(.*?)\"")
                .find(html)?.groupValues?.get(1)
                ?.replace("\\u003d", "=")
                ?.replace("\\u0026", "&")
            val living = Regex("\"LIVING_ROOM_PO_TOKEN_ID\":\"(.*?)\"")
                .find(html)?.groupValues?.get(1)
                ?.replace("\\u003d", "=")
            val playerPath = Regex("\"player_url\":\"(.*?)\"")
                .find(html)?.groupValues?.get(1)
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&")
            val timestamp = playerPath?.let { fetchSignatureTimestamp(it, userAgent) }
            val data = TvPageData(visitor, living, timestamp)
            if (living != null && timestamp != null) {
                tvPageCache = data
                tvPageCacheAt = System.currentTimeMillis()
            } else {
                if (living == null) WatchSyncLog.note("tv-oauth tv-page missing livingRoom id")
                if (timestamp == null) WatchSyncLog.note("tv-oauth tv-page missing sts")
            }
            return data
        }
    }

    private fun fetchSignatureTimestamp(playerPath: String, userAgent: String): String? {
        val url = when {
            playerPath.startsWith("http://") || playerPath.startsWith("https://") -> playerPath
            playerPath.startsWith("//") -> "https:$playerPath"
            playerPath.startsWith("/") -> "https://www.youtube.com$playerPath"
            else -> return null
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                WatchSyncLog.note("tv-oauth player-js HTTP ${response.code}")
                return null
            }
            val js = response.body?.string().orEmpty()
            val raw = Regex("signatureTimestamp:(\\d+)").find(js)?.groupValues?.get(1) ?: return null
            WatchSyncLog.note("tv-oauth tv-page sts=$raw")
            return raw
        }
    }

    private fun fetchWatchTracking(
        videoId: String,
        client: PlayerClient,
        useTvAuth: Boolean = false,
        stsOverride: String? = null,
    ): WatchTracking? {
        val langCode = LanguageSettings.effectiveLanguageCode()
        val countryCode = LanguageSettings.effectiveCountryCode()
        val tvData = if (useTvAuth && client.name == "TVHTML5") tvPageData(client.userAgent) else null
        val clientContext = JSONObject()
            .put("clientName", client.name)
            .put("clientVersion", client.version)
            .put("hl", langCode)
            .put("gl", countryCode)
            .put("userAgent", client.userAgent)
            .apply {
                val keys = client.extra.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, client.extra.opt(key))
                }
            }
            .apply {
                val visitor = if (useTvAuth && client.name == "TVHTML5") tvData?.visitorData else Account.visitorData()
                visitor?.let { put("visitorData", it) }
            }
            .apply {
                if (useTvAuth && client.name == "TVHTML5") {
                    tvData?.livingRoomPoTokenId?.let {
                        put("tvAppInfo", JSONObject().put("livingRoomPoTokenId", it))
                    }
                }
            }
        val body = JSONObject()
            .put("context", JSONObject().put("client", clientContext))
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        if (useTvAuth && client.name == "TVHTML5") {
            body.put("cpn", newCpn())
            (stsOverride ?: tvData?.signatureTimestamp)?.toIntOrNull()?.let { timestamp ->
                body.put(
                    "playbackContext",
                    JSONObject()
                        .put(
                            "contentPlaybackContext",
                            JSONObject()
                                .put("html5Preference", "HTML5_PREF_WANTS")
                                .put("lactMilliseconds", 60000)
                                .put("isInlinePlaybackNoAd", true)
                                .put("signatureTimestamp", timestamp),
                        )
                        .put(
                            "devicePlaybackCapabilities",
                            JSONObject()
                                .put("supportsVp9Encoding", true)
                                .put("supportXhr", false),
                        ),
                )
            }
        }
        val prefCookie = "PREF=hl=$langCode&gl=$countryCode"
        val cookieVal = if (useTvAuth) null
        else if (Account.signedIn) "${Account.cookieHeader()}; $prefCookie" else prefCookie
        val oauth = if (useTvAuth) {
            runCatching { TvAuth.authorizationHeader() }
                .onFailure { WatchSyncLog.note("tv-oauth token for player failed: ${it.message ?: it.javaClass.simpleName}") }
                .getOrNull()
        } else null
        if (useTvAuth && oauth == null) return null
        val auth = if (useTvAuth) oauth else if (Account.signedIn) Account.authorization() else null
        val request = Request.Builder()
            .url("${BASE}player?prettyPrint=false&hl=$langCode&gl=$countryCode")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .header("User-Agent", client.userAgent)
            .header("Accept-Language", "$langCode-$countryCode,$langCode;q=0.9,en-US;q=0.8,en;q=0.7")
            .apply { cookieVal?.let { header("Cookie", it) } }
            .apply { auth?.let { header("Authorization", it) } }
            .apply {
                if (!useTvAuth) {
                    header("Origin", Account.ORIGIN)
                    header("X-Origin", Account.ORIGIN)
                    header("Referer", Account.ORIGIN + "/")
                }
            }
            .header("X-Goog-AuthUser", "0")
            .header("X-Youtube-Client-Name", client.id)
            .header("X-Youtube-Client-Version", client.version)
            .apply {
                if (client.name == "TVHTML5" || client.name == "ANDROID") {
                    header("X-Goog-Api-Format-Version", "2")
                }
                val visitor = if (useTvAuth && client.name == "TVHTML5") tvData?.visitorData else Account.visitorData()
                visitor?.let { header("X-Goog-Visitor-Id", it) }
            }
            .build()
        Net.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (useTvAuth) {
                    val message = runCatching {
                        JSONObject(text).optJSONObject("error")?.optString("message")
                    }.getOrNull()?.take(120)
                    val suffix = if (message.isNullOrBlank()) "" else ": $message"
                    WatchSyncLog.note("tv-oauth ${client.name} HTTP ${response.code}$suffix")
                }
                return null
            }
            val root = runCatching { JSONObject(text) }.getOrNull()
            if (root == null) {
                if (useTvAuth) WatchSyncLog.note("tv-oauth ${client.name} invalid JSON")
                return null
            }
            val tracking = parseWatchTracking(root)
            if (tracking == null && useTvAuth) {
                val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                val reason = root.optJSONObject("playabilityStatus")?.optString("reason").orEmpty().take(120)
                val statusText = status.ifBlank { "?" }
                val suffix = if (reason.isBlank()) "" else " reason=$reason"
                val sts = stsOverride ?: tvData?.signatureTimestamp ?: "none"
                WatchSyncLog.note("tv-oauth ${client.name} no tracking status=$statusText sts=$sts$suffix")
            }
            return tracking
                ?.copy(clientName = client.name, clientVersion = client.version, userAgent = client.userAgent)
        }
    }

    /**
     * SmartTube's signed TV video-info request. The modern player endpoint does not
     * necessarily bind playbackTracking to the OAuth token; this endpoint does, and
     * its ei/vm/of values are what the stats endpoint needs for history attachment.
     */
    private fun signedTvTracking(videoId: String, authHeader: String): WatchTracking? {
        val rawToken = authHeader.removePrefix("Bearer ").trim()
        if (rawToken.isBlank()) return null
        val langCode = LanguageSettings.effectiveLanguageCode()
        val countryCode = LanguageSettings.effectiveCountryCode()
        val version = "7.20201103.00.00"
        val userAgent =
            "Mozilla/5.0 (Unknown; Linux armv7l) AppleWebKit/537.1+ (KHTML, like Gecko) Safari/537.1+ LG Browser/6.00.00(+mouse+3D+SCREEN+TUNER; LGE; 42LA660S-ZA; 04.25.05; 0x00000001;); LG NetCast.TV-2013 /04.25.05 (LG, 42LA660S-ZA, wired)"
        val token = java.net.URLEncoder.encode(rawToken, "UTF-8")
        val url = "${Account.ORIGIN}/get_video_info?ps=leanback&el=leanback" +
            "&eurl=https%3A%2F%2Fwww.youtube.com%2Ftv" +
            "&video_id=$videoId&hl=$langCode&access_token=$token" +
            "&c=TVHTML5&cver=$version&html5=1"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .header("User-Agent", userAgent)
            .header("Accept-Language", "$langCode-$countryCode,$langCode;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        Net.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                WatchSyncLog.note("tv-oauth signed video-info HTTP ${response.code}")
                return null
            }
            val root = signedPlayerResponse(text)
            if (root == null) {
                WatchSyncLog.note("tv-oauth signed video-info no player response")
                return null
            }
            val tracking = parseWatchTracking(root)
            if (tracking == null) {
                val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                val reason = root.optJSONObject("playabilityStatus")?.optString("reason").orEmpty().take(120)
                val statusText = status.ifBlank { "?" }
                val suffix = if (reason.isBlank()) "" else " reason=$reason"
                WatchSyncLog.note("tv-oauth signed video-info no tracking status=$statusText$suffix")
                return null
            }
            return tracking.copy(clientName = "TVHTML5", clientVersion = version, userAgent = userAgent)
        }
    }

    private fun signedPlayerResponse(body: String): JSONObject? {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) return runCatching { JSONObject(trimmed) }.getOrNull()
        val encoded = runCatching {
            android.net.Uri.parse("https://www.youtube.com/?$body").getQueryParameter("player_response")
        }.getOrNull() ?: return null
        return runCatching { JSONObject(encoded) }.getOrNull()
    }

    /**
     * The tracking tokens and the bearer token must belong to the same TV session.
     * A fallback/web player response can produce a 204 stats request that YouTube
     * accepts without attaching the row to the account.
     */
    private fun playerTrackingOauth(videoId: String): WatchTracking? {
        if (!TvAuth.isLinked) return null
        val tv = PlayerClient(
            name = "TVHTML5",
            version = "7.20260213.00.00",
            id = "7",
            userAgent = "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15",
            extra = JSONObject()
                .put("clientScreen", "WATCH")
                .put("platform", "TV")
                .put("osName", "Tizen")
                .put("osVersion", "4.0.0.2")
                .put("deviceMake", "Samsung")
                .put("deviceModel", "SmartTV"),
        )
        val android = PlayerClient(
            name = "ANDROID",
            version = ANDROID_CLIENT_VERSION,
            id = "3",
            userAgent = ANDROID_USER_AGENT,
            extra = JSONObject().put("androidSdkVersion", 30),
        )
        var tracking: WatchTracking? = null
        for (client in listOf(tv, android)) {
            val variants: List<String?> = if (client.name == "TVHTML5") {
                val raw = tvPageData(client.userAgent)?.signatureTimestamp
                // Verified on the device: the TV client accepts the player timestamp with
                // the 001 suffix and rejects the bare five-digit value as UNPLAYABLE, so
                // send the working form first and keep the bare value only as a fallback.
                listOfNotNull(raw?.let { it + "001" }, raw).distinct().ifEmpty { listOf(null) }
            } else {
                listOf(null)
            }
            for (sts in variants) {
                if (client.name == "TVHTML5") {
                    WatchSyncLog.note("tv-oauth TVHTML5 attempt sts=${sts ?: "none"}")
                }
                tracking = runCatching { fetchWatchTracking(videoId, client, useTvAuth = true, stsOverride = sts) }
                    .onFailure { WatchSyncLog.note("tv-oauth ${client.name} player failed: ${it.message ?: it.javaClass.simpleName}") }
                    .getOrNull()
                if (tracking != null) break
            }
            if (tracking != null) break
        }
        if (tracking == null) {
            WatchSyncLog.note("tv-oauth player tracking unavailable; using fallback")
        } else {
            WatchSyncLog.note("tv-oauth player tracking ${tracking.clientName} ${watchTrackingDetails(tracking)}")
        }
        return tracking
    }

    internal fun parseWatchTracking(root: JSONObject): WatchTracking? {
        val tracking = root.optJSONObject("playbackTracking") ?: return null
        val watchtime = tracking.optJSONObject("videostatsWatchtimeUrl")
            ?.optString("baseUrl")?.ifBlank { null } ?: return null
        val playback = tracking.optJSONObject("videostatsPlaybackUrl")
            ?.optString("baseUrl")?.ifBlank { null }
        val visitor = root.optJSONObject("responseContext")
            ?.optString("visitorData")?.ifBlank { null }
        return WatchTracking(playback, watchtime, visitorData = visitor)
    }

    /** Same player response that supplied the streams, so ei/vm/of match the playback. */
    internal fun trackingFromPlayerResponse(root: JSONObject): WatchTracking? =
        parseWatchTracking(root)?.copy(
            clientName = "ANDROID",
            clientVersion = ANDROID_CLIENT_VERSION,
            userAgent = ANDROID_USER_AGENT,
        )

    private const val IOS_CLIENT_VERSION = "20.10.4"
    private const val IOS_USER_AGENT =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X; en_US)"
    private const val ANDROID_CLIENT_VERSION = "20.10.38"
    private const val ANDROID_USER_AGENT =
        "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"

    




    fun startWatch(videoId: String, durationSec: Long = 0L): WatchSession? {
        val oauthTracking = playerTrackingOauth(videoId)
        val cached = StreamCache.tracking(videoId)
        val fallback = if (oauthTracking == null && cached == null) {
            WatchSyncLog.note("cache tracking missing; checking the response that rescued playback")
            fallbackTracking(videoId)
        } else null
        val tracking = oauthTracking ?: cached ?: fallback ?: playerTracking(videoId)
        if (tracking == null) {
            WatchSyncLog.note("startWatch id=$videoId signedIn=${Account.signedIn} duration=${durationSec}s FAILED: no playbackTracking (cache=no, fallback=no, player=no)")
            android.util.Log.w("YTXTube", "no playback tracking for $videoId")
            return null
        }
        val source = when {
            oauthTracking != null -> "tv-oauth"
            cached != null -> "stream-cache"
            fallback != null -> "player-fallback"
            else -> "player-request"
        }
        WatchSyncLog.note("startWatch id=$videoId signedIn=${Account.signedIn} duration=${durationSec}s source=$source client=${tracking.clientName} ${watchTrackingDetails(tracking)}")
        val cpn = newCpn()
        // The short markWatched pair below creates the record. A bare opening ping has no
        // ei/vm/of, so it cannot create history and would only consume the cpn.
        val length = trackingLength(tracking.watchtimeUrl).takeIf { it > 1.0 } ?: durationSec.toDouble()
        val session = WatchSession(
            videoId = videoId,
            watchtimeUrl = tracking.watchtimeUrl,
            cpn = cpn,
            playbackUrl = tracking.playbackUrl,
            lengthSec = length,
            clientName = tracking.clientName,
            clientVersion = tracking.clientVersion,
            userAgent = tracking.userAgent,
            visitorData = tracking.visitorData,
        )
        WatchSyncLog.note("session video=$videoId cpn=$cpn len=${formatSeconds(length)} client=${tracking.clientName}")
        lastSession = session
        // SmartTube creates the long watch record when playback starts, then updates it.
        runCatching { createWatchRecord(session) }
            .onSuccess {
                WatchSyncLog.note("long watch record request finished")
                verifyLater(videoId)
            }
            .onFailure {
                WatchSyncLog.note("long watch record failed: ${it.message ?: it.javaClass.simpleName}")
                android.util.Log.w("YTXTube", "watch record for $videoId", it)
            }
        return session
    }

    private fun watchTrackingDetails(tracking: WatchTracking): String {
        val url = tracking.watchtimeUrl.toHttpUrlOrNull()
        return "ei=${url?.queryParameter("ei") ?: "-"} vm=${url?.queryParameter("vm") ?: "-"} " +
            "of=${url?.queryParameter("of") ?: "-"} visitor=${tracking.visitorData?.take(28) ?: "-"}"
    }

    /**
     * PlayerFallback is what rescued this video's streams when NewPipe's original
     * player response was blocked. Its playbackTracking is therefore the response
     * that actually started playback, unlike the extractor's retained response.
     */
    private fun fallbackTracking(videoId: String): WatchTracking? {
        val root = runCatching { PlayerFallback.playerResponse(videoId) }
            .onFailure { WatchSyncLog.note("player-fallback error: ${it.message ?: it.javaClass.simpleName}") }
            .getOrNull() ?: return null
        val tracking = parseWatchTracking(root)
        if (tracking == null) {
            val status = root.optJSONObject("playabilityStatus")?.optString("status") ?: "unknown"
            WatchSyncLog.note("player-fallback response has no playbackTracking (status=$status)")
            return null
        }
        WatchSyncLog.note("player-fallback ${watchTrackingDetails(tracking)}")
        return tracking.copy(
            clientName = "FALLBACK",
            clientVersion = Account.clientVersion(),
            userAgent = Net.USER_AGENT,
        )
    }

    private fun newCpn(): String =
        (1..16).map { CPN_ALPHABET[Random.nextInt(CPN_ALPHABET.length)] }.joinToString("")

    /** Diagnostic entry point used by the in-app panel to force the final pair now. */
    internal fun markLastNow(): String {
        val original = lastSession ?: return "No watch session yet; play a video first."
        val session = freshSessionForManualMark(original)
        lastSession = session
        WatchSyncLog.note("manual final mark requested id=${session.videoId} client=${session.clientName}")
        return runCatching {
            if (markWatched(session)) {
                "Final mark sent for ${session.videoId}; history is checked in +8s and +30s."
            } else {
                "Final mark was not accepted; check the log for the missing tokens."
            }
        }.getOrElse {
            "Final mark failed: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    /**
     * The manual button must not reuse a session whose ei/vm/of came from an
     * older web/fallback player response. Re-fetch TV tracking with the linked
     * OAuth session before sending, while keeping the video length for final=1.
     */
    private fun freshSessionForManualMark(original: WatchSession): WatchSession {
        if (!TvAuth.isLinked) return original
        val tracking = playerTrackingOauth(original.videoId) ?: return original
        val cpn = newCpn()
        WatchSyncLog.note("manual tracking source=tv-oauth client=${tracking.clientName} cpn=$cpn")
        return WatchSession(
            videoId = original.videoId,
            watchtimeUrl = tracking.watchtimeUrl,
            cpn = cpn,
            playbackUrl = tracking.playbackUrl,
            lengthSec = original.lengthSec,
            clientName = tracking.clientName,
            clientVersion = tracking.clientVersion,
            userAgent = tracking.userAgent,
            visitorData = tracking.visitorData,
        )
    }

    /**
     * SmartTube's proven "fully watched" pair (TrackingService.createWatchRecordShort/
     * updateWatchTimeShort): hit www.youtube.com with final=1 and the authenticated
     * ei/vm/of tokens from the player, playback first and then watchtime.
     */
    internal fun markWatched(session: WatchSession): Boolean {
        WatchSyncLog.note("markWatched id=${session.videoId} len=${formatSeconds(session.lengthSec)} cpn=${session.cpn}")
        if (session.lengthSec < 2.0) {
            WatchSyncLog.note("markWatched skipped: length < 2s")
            return false
        }
        val params = trackingParams(session) ?: run {
            WatchSyncLog.note("markWatched skipped: ei/vm/of missing from watchtime URL")
            return false
        }
        var failure: Throwable? = null
        var accepted = false
        for (watchtime in listOf(false, true)) {
            val url = markUrl(
                session.videoId,
                session.cpn,
                session.lengthSec,
                params.eventId,
                params.visitorData,
                params.ofParam,
                watchtime,
            ) ?: continue
            val kind = if (watchtime) "short watchtime" else "short playback"
            runCatching { sendStats(url, session, kind) }
                .onSuccess {
                    accepted = true
                    WatchSyncLog.note("$kind accepted")
                }
                .onFailure {
                    failure = it
                    WatchSyncLog.note("$kind failed: ${it.message ?: it.javaClass.simpleName}")
                }
        }
        if (accepted) {
            WatchSyncLog.note("short pair complete; scheduling official history check")
            verifyLater(session.videoId)
        }
        failure?.let { throw it }
        return accepted
    }

    private val historyChecks = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ytx-history-check").apply { isDaemon = true }
    }

    private fun verifyLater(videoId: String) {
        if (!Account.signedIn) {
            WatchSyncLog.note("history check skipped: signed out")
            return
        }
        for (delaySec in longArrayOf(8L, 30L)) {
            historyChecks.schedule(
                {
                    runCatching {
                        kotlinx.coroutines.runBlocking {
                            verifyHistoryNow(videoId, "auto +${delaySec}s")
                        }
                    }.onFailure {
                        WatchSyncLog.note("history check error (+${delaySec}s): ${it.message ?: it.javaClass.simpleName}")
                    }
                },
                delaySec,
                java.util.concurrent.TimeUnit.SECONDS,
            )
        }
    }

    /** Direct server history read: it never includes LocalWatchHistory. */
    internal suspend fun verifyHistoryNow(videoId: String, reason: String = "manual"): Boolean? =
        withContext(Dispatchers.IO) {
            if (!Account.signedIn) {
                WatchSyncLog.note("history check ($reason): signed out")
                return@withContext null
            }
            var next: String? = null
            var page = 0
            while (page < 3) {
                val feed = history(next)
                val found = feed.videos.any { it.id == videoId }
                WatchSyncLog.note(
                    "history check ($reason) page ${page + 1}: found=$found items=${feed.videos.size}",
                )
                if (found) return@withContext true
                next = feed.next ?: return@withContext false
                page++
            }
            false
        }

    internal fun markUrl(
        videoId: String,
        cpn: String,
        lengthSec: Double,
        eventId: String,
        visitorData: String,
        ofParam: String,
        watchtime: Boolean,
    ): String? {
        val length = String.format(java.util.Locale.US, "%.3f", lengthSec.coerceAtLeast(0.0))
        val path = if (watchtime) "watchtime" else "playback"
        val builder = "https://www.youtube.com/api/stats/$path".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("ns", "yt")
            ?.addQueryParameter("ver", "2")
            ?.addQueryParameter("final", "1")
            ?.addQueryParameter("docid", videoId)
            ?.addQueryParameter("cpn", cpn)
            ?.addQueryParameter("ei", eventId)
            ?.addQueryParameter("vm", visitorData)
            ?.addQueryParameter("of", ofParam)
            ?: return null
        if (watchtime) {
            builder.addQueryParameter("st", length)
            builder.addQueryParameter("et", length)
        }
        return builder.build().toString()
    }

    private data class TrackingParams(val eventId: String, val visitorData: String, val ofParam: String)

    private fun trackingParams(session: WatchSession): TrackingParams? {
        val url = session.watchtimeUrl.toHttpUrlOrNull() ?: return null
        return TrackingParams(
            url.queryParameter("ei") ?: return null,
            url.queryParameter("vm") ?: return null,
            url.queryParameter("of") ?: return null,
        )
    }

    private fun createWatchRecord(session: WatchSession) {
        val params = trackingParams(session) ?: run {
            WatchSyncLog.note("long record skipped: ei/vm/of missing")
            return
        }
        val length = formatSeconds(session.lengthSec)
        val url = "https://www.youtube.com/api/stats/playback".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("ns", "yt")
            ?.addQueryParameter("ver", "2")
            ?.addQueryParameter("docid", session.videoId)
            ?.addQueryParameter("len", length)
            ?.addQueryParameter("cmt", "0.000")
            ?.addQueryParameter("cpn", session.cpn)
            ?.addQueryParameter("ei", params.eventId)
            ?.addQueryParameter("vm", params.visitorData)
            ?.addQueryParameter("of", params.ofParam)
            ?.build()?.toString() ?: return
        WatchSyncLog.note("long record sending len=$length cpn=${session.cpn} ei=${params.eventId} vm=${params.visitorData} of=${params.ofParam}")
        sendStats(url, session, "watch record")
    }

    private data class StatsAuth(val header: String?, val label: String, val oauth: Boolean)

    private fun sendStats(url: String, session: WatchSession, label: String) {
        val visitor = session.visitorData ?: Account.visitorData()
        val attempts = buildList {
            runCatching { TvAuth.authorizationHeader() }
                .onFailure { WatchSyncLog.note("tv oauth token unavailable: ${it.message ?: it.javaClass.simpleName}") }
                .getOrNull()
                ?.let { add(StatsAuth(it, "tv-oauth", true)) }
            if (Account.signedIn) {
                Account.authorization()?.let { add(StatsAuth(it, "cookie+sapisidhash", false)) }
                add(StatsAuth(null, "cookie-only", false))
            }
        }
        if (attempts.isEmpty()) throw IOException("$label failed: not signed in")
        var failure: Throwable? = null
        // TV OAuth is the path that SmartTube uses for stats. Keep the old signed-in
        // cookie fallback so a failed TV link cannot stop local playback tracking.
        for (attempt in attempts) {
            val result = runCatching {
                sendStatsOnce(url, session, label, visitor, attempt)
            }
            if (result.isSuccess) {
                WatchSyncLog.note("stats $label accepted with ${attempt.label}")
                return
            }
            failure = result.exceptionOrNull()
            WatchSyncLog.note("stats $label ${attempt.label} failed: ${failure?.message ?: failure?.javaClass?.simpleName}")
        }
        throw failure ?: IOException("$label failed")
    }

    private fun sendStatsOnce(
        url: String,
        session: WatchSession,
        label: String,
        visitor: String?,
        auth: StatsAuth,
    ) {
        val builder = Request.Builder().url(url)
            .header("User-Agent", if (auth.oauth) TvAuth.STATS_USER_AGENT else session.userAgent)
            .header("Origin", Account.ORIGIN)
            .header("X-Origin", Account.ORIGIN)
            .header("Referer", Account.ORIGIN + "/")
            .header("X-Goog-AuthUser", "0")
            .apply { visitor?.let { header("X-Goog-Visitor-Id", it) } }
        if (!auth.oauth) builder.header("Cookie", Account.cookieHeader())
        auth.header?.let { builder.header("Authorization", it) }
        val request = builder.build()
        val query = url.toHttpUrlOrNull()
        WatchSyncLog.note(
            "stats $label request docid=${query?.queryParameter("docid")} cpn=${query?.queryParameter("cpn")} " +
                "len=${query?.queryParameter("len")} st=${query?.queryParameter("st")} et=${query?.queryParameter("et")} " +
                "final=${query?.queryParameter("final")} ei=${query?.queryParameter("ei")} " +
                "vm=${query?.queryParameter("vm")} of=${query?.queryParameter("of")} auth=${auth.label}",
        )
        Net.client.newCall(request).execute().use { r ->
            if (!auth.oauth) Account.absorbSetCookies(r.headers("Set-Cookie"))
            val body = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
            WatchSyncLog.note(
                "stats $label HTTP ${r.code} setCookies=${r.headers("Set-Cookie").size} " +
                    "bytes=${body.length} body=${body.take(180).replace('\n', ' ')}",
            )
            if (!r.isSuccessful) throw IOException("$label ${r.code}")
        }
    }

    private fun formatSeconds(value: Double): String =
        String.format(java.util.Locale.US, "%.3f", value.coerceAtLeast(0.0))

    private fun trackingLength(url: String): Double =
        Regex("[?&]len=([0-9.]+)").find(url)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun withDocId(url: String, videoId: String): String =
        if ("docid=" in url) url else url + (if ('?' in url) "&" else "?") + "docid=" + videoId

    





    fun pingWatchtime(session: WatchSession, positionSec: Double, starts: List<Double>, ends: List<Double>, playing: Boolean, elapsedSec: Double, final: Boolean) {
        WatchSyncLog.note("pingWatchtime id=${session.videoId} final=$final playing=$playing position=${formatSeconds(positionSec)} stretches=${starts.size}/${ends.size}")
        val params = trackingParams(session) ?: run {
            WatchSyncLog.note("pingWatchtime skipped: ei/vm/of missing")
            return
        }
        val length = formatSeconds(session.lengthSec)
        val builder = "https://www.youtube.com/api/stats/watchtime".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("ns", "yt")
            ?.addQueryParameter("ver", "2")
            ?.addQueryParameter("docid", session.videoId)
            ?.addQueryParameter("len", length)
            ?.addQueryParameter("cpn", session.cpn)
            ?.addQueryParameter("ei", params.eventId)
            ?.addQueryParameter("vm", params.visitorData)
            ?.addQueryParameter("of", params.ofParam)
            ?: return
        if (final) {
            builder.addQueryParameter("st", "0.000")
            builder.addQueryParameter("et", length)
            builder.addQueryParameter("cmt", length)
            builder.addQueryParameter("final", "1")
        } else {
            val start = starts.firstOrNull() ?: 0.0
            val end = positionSec.coerceAtLeast(0.0)
            builder.addQueryParameter("st", formatSeconds(start))
            builder.addQueryParameter("et", formatSeconds(end))
            builder.addQueryParameter("cmt", formatSeconds(end))
        }
        sendStats(builder.build().toString(), session, "watch time")
    }

    private fun ping(base: String, query: String, userAgent: String = Net.USER_AGENT) {
        val auth = if (Account.signedIn) Account.authorization() else null
        val request = Request.Builder().url(base + (if ('?' in base) "&" else "?") + query)
            .header("User-Agent", userAgent)
            .header("Cookie", Account.cookieHeader())
            .apply { auth?.let { header("Authorization", it) } }
            .header("Referer", Account.ORIGIN + "/")
            .build()
        Net.client.newCall(request).execute().use { r ->
            Account.absorbSetCookies(r.headers("Set-Cookie"))
            if (!r.isSuccessful) throw IOException("stats ping ${r.code}")
        }
    }

    private const val CPN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"



    



    internal fun parseFeed(root: JSONObject): FeedPage {
        val videos = LinkedHashMap<String, VideoItem>()
        val entries = ArrayList<FeedEntry>()
        val posts = HashSet<String>()
        var next: String? = null
        root.walk { key, obj ->
            when (key) {


                "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer" ->
                    if (!isMembersOnly(obj) && !isMovie(obj)) parseVideoRenderer(obj)?.let { video ->
                        if (videos.putIfAbsent(video.id, video) == null) entries.add(FeedEntry.Video(video))
                    }
                "lockupViewModel" -> if (obj.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO" && !isMembersOnly(obj) && !isMovie(obj)) {
                    parseLockup(obj)?.let { video ->
                        if (videos.putIfAbsent(video.id, video) == null) entries.add(FeedEntry.Video(video))
                    }
                }
                "backstagePostRenderer", "postRenderer", "sharedPostRenderer" ->
                    parsePost(obj)?.let { post -> if (posts.add(post.id)) entries.add(FeedEntry.Post(post)) }


                "continuationItemRenderer", "continuationItemViewModel" ->
                    obj.collectStrings("token").firstOrNull()?.let { next = it }
            }
        }
        return FeedPage(videos.values.toList(), next, entries = entries)
    }

    /**
     * Filtered search keeps the result order YouTube returned: video, channel and
     * playlist rows can be mixed in a single page.
     */
    internal fun parseSearch(root: JSONObject): SearchPage {
        val items = LinkedHashMap<String, SearchItem>()
        var next: String? = null
        root.walk { key, obj ->
            when (key) {
                "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer" ->
                    if (!isMembersOnly(obj) && !isMovie(obj)) parseVideoRenderer(obj)?.let { video ->
                        items.putIfAbsent("video:${video.id}", SearchItem.Video(video))
                    }
                "lockupViewModel" -> if (obj.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO" &&
                    !isMembersOnly(obj) && !isMovie(obj)
                ) {
                    parseLockup(obj)?.let { video ->
                        items.putIfAbsent("video:${video.id}", SearchItem.Video(video))
                    }
                } else if (obj.optString("contentType") == "LOCKUP_CONTENT_TYPE_PLAYLIST") {
                    parsePlaylistLockup(obj)?.let { playlist ->
                        items.putIfAbsent(playlist.key, playlist)
                    }
                }
                "channelRenderer" -> parseChannelRenderer(obj)?.let { channel ->
                    items.putIfAbsent(channel.key, channel)
                }
                "playlistRenderer" -> parsePlaylistRenderer(obj)?.let { playlist ->
                    items.putIfAbsent(playlist.key, playlist)
                }
                "continuationItemRenderer", "continuationItemViewModel" ->
                    obj.collectStrings("token").firstOrNull()?.let { next = it }
            }
        }
        return SearchPage(items.values.toList(), next)
    }

    private fun parseChannelRenderer(o: JSONObject): SearchItem.Channel? {
        val id = o.optString("channelId").ifBlank { return null }
        val name = o.text("title")?.takeIf { it.isNotBlank() } ?: return null
        val avatar = o.optJSONObject("thumbnail")?.thumbnails()
            ?.maxByOrNull { channelAvatarEdgePx(it) }
            ?.let { sharpenChannelAvatarUrl(absoluteUrl(it), 176) }
        val subscribers = o.text("subscriberCountText") ?: o.text("videoCountText")
        return SearchItem.Channel(id, name, avatar, subscribers)
    }

    private fun parsePlaylistLockup(o: JSONObject): SearchItem.Playlist? {
        val id = o.optString("contentId").ifBlank { return null }
        val metadata = o.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel") ?: return null
        val title = metadata.optJSONObject("title")?.optString("content")?.ifBlank { null } ?: return null
        val thumbnail = o.optJSONObject("contentImage")
            ?.optJSONObject("collectionThumbnailViewModel")
            ?.optJSONObject("primaryThumbnail")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONObject("image")
            ?.optJSONArray("sources")
            ?.widestSourceUrl()
            ?: o.optJSONObject("contentImage")
                ?.collectStrings("url")
                ?.firstOrNull { it.contains("ytimg.com/vi/") || it.contains("ggpht") }
        val parts = searchMetadataParts(metadata)
        val videoCount = o.collectStrings("text").firstOrNull { SEARCH_PLAYLIST_COUNT.matches(it) }
        return SearchItem.Playlist(
            id = id,
            title = title,
            thumbnail = thumbnail?.let(::absoluteUrl),
            channel = parts.firstOrNull(),
            videoCount = videoCount,
        )
    }

    private fun parsePlaylistRenderer(o: JSONObject): SearchItem.Playlist? {
        val id = o.optString("playlistId").ifBlank { return null }
        val title = o.text("title")?.takeIf { it.isNotBlank() } ?: return null
        return SearchItem.Playlist(
            id = id,
            title = title,
            thumbnail = o.thumbnails()?.lastOrNull()?.let(::absoluteUrl),
            channel = o.text("shortBylineText"),
            videoCount = o.text("videoCount"),
        )
    }

    private fun searchMetadataParts(metadata: JSONObject): List<String> {
        val out = mutableListOf<String>()
        metadata.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows")
            ?.let { rows ->
                for (r in 0 until rows.length()) {
                    val parts = rows.optJSONObject(r)?.optJSONArray("metadataParts") ?: continue
                    for (p in 0 until parts.length()) {
                        parts.optJSONObject(p)
                            ?.optJSONObject("text")
                            ?.optString("content")
                            ?.ifBlank { null }
                            ?.let(out::add)
                    }
                }
            }
        return out
    }

    private val SEARCH_PLAYLIST_COUNT =
        Regex("""^\d[\d,.]*\s+(videos?|episodes?|songs?|tracks?)$""", RegexOption.IGNORE_CASE)

    internal fun isMembersOnly(o: JSONObject): Boolean =
        o.collectStrings("badgeStyle").any { it.contains("MEMBERS_ONLY") } ||
            o.collectStrings("style").any { it == "BADGE_STYLE_TYPE_MEMBERS_ONLY" }

    






    internal fun isMovie(o: JSONObject): Boolean {
        if (o.collectStrings("style").any { it == "BADGE_STYLE_TYPE_YPC" || it == "BADGE_STYLE_TYPE_MEDIA" }) return true
        var movie = false
        o.walk { key, badge ->
            if (key != "badgeViewModel") return@walk
            val text = badge.optString("badgeText")
            if (CONTENT_RATING.matches(text) ||
                (badge.optString("badgeStyle") == "BADGE_COMMERCE" && !text.equals("Fundraiser", ignoreCase = true))
            ) movie = true
        }
        return movie
    }

    private val CONTENT_RATING = Regex("^(G|PG|PG-13|R|NC-17|NR|TV-(Y|Y7|G|PG|14|MA))$")

    private fun parseVideoRenderer(o: JSONObject): VideoItem? {
        val id = o.optString("videoId").ifBlank { return null }
        val byline = o.optJSONObject("ownerText") ?: o.optJSONObject("shortBylineText") ?: o.optJSONObject("longBylineText")
        val channelRun = byline?.optJSONArray("runs")?.optJSONObject(0)
        val channelId = channelRun?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
        val duration = o.text("lengthText")
            ?: o.optString("lengthSeconds").toLongOrNull()?.let { formatDuration(it * 1000) }
            ?: o.optJSONArray("thumbnailOverlays")?.let { arr ->
                (0 until arr.length()).firstNotNullOfOrNull { arr.optJSONObject(it)?.optJSONObject("thumbnailOverlayTimeStatusRenderer")?.let { r -> r.text("text") } }
            }

        val infoRuns = o.optJSONObject("videoInfo")?.optJSONArray("runs")?.let { runs ->
            (0 until runs.length()).map { runs.optJSONObject(it).optString("text") }.filter { it.isNotBlank() && it.trim() != "•" }
        }.orEmpty()
        val isLive = o.collectStrings("style").any { it.contains("LIVE", ignoreCase = true) } ||
            o.collectStrings("badgeText").any { it.equals("LIVE", ignoreCase = true) } ||
            o.collectStrings("text").any { it.equals("LIVE", ignoreCase = true) } ||
            (o.text("viewCountText") ?: "").contains("watching", ignoreCase = true)
        val avatar = pickChannelAvatarUrl(o)
        return VideoItem(
            id = id,
            title = o.text("title") ?: return null,
            channel = channelRun?.optString("text").orEmpty(),
            channelUrl = channelId?.takeIf { it.startsWith("UC") }?.let { "https://www.youtube.com/channel/$it" },
            thumbnail = videoThumbnailUrl(o, id),
            avatar = avatar?.let { sharpenChannelAvatarUrl(absoluteUrl(it), 96) },
            durationSec = if (isLive) 0L else parseDuration(duration),
            views = parseViews(o.text("viewCountText") ?: o.text("shortViewCountText") ?: infoRuns.firstOrNull { it.contains("view") || it.contains("watching") }),
            uploaded = if (isLive) I18n.t("live_now", "Live") else (o.text("publishedTimeText") ?: infoRuns.firstOrNull { it.contains("ago") || it.contains("पहले") || it.contains("hace") || it.contains("назад") } ?: infoRuns.lastOrNull())?.let { I18n.translateTimeAgo(it) },
            isLive = isLive,
        )
    }

    private fun parseLockup(o: JSONObject): VideoItem? {
        val id = o.optString("contentId").ifBlank { return null }
        val meta = o.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel") ?: return null
        val title = meta.optJSONObject("title")?.optString("content")?.ifBlank { null } ?: return null

        val parts = mutableListOf<String>()
        meta.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")?.let { rows ->
            for (r in 0 until rows.length()) {
                val ps = rows.optJSONObject(r)?.optJSONArray("metadataParts") ?: continue
                for (p in 0 until ps.length()) ps.optJSONObject(p)?.optJSONObject("text")?.optString("content")?.ifBlank { null }?.let(parts::add)
            }
        }
        val isLive = o.collectStrings("badgeText").any { it.equals("LIVE", ignoreCase = true) } ||
            o.collectStrings("text").any { it.equals("LIVE", ignoreCase = true) } ||
            parts.any { it.contains("watching", ignoreCase = true) }
        val avatar = meta.bestChannelAvatarUrl()
        val duration = o.optJSONObject("contentImage")?.collectStrings("text")?.firstOrNull { DURATION.matches(it) }
        return VideoItem(
            id = id,
            title = title,
            channel = parts.firstOrNull { !it.contains("view") && !it.contains("ago") && !it.contains("watching") }.orEmpty(),
            channelUrl = o.collectStrings("browseId").firstOrNull { it.startsWith("UC") }?.let { "https://www.youtube.com/channel/$it" },
            thumbnail = videoThumbnailUrl(o, id),
            avatar = avatar?.let { sharpenChannelAvatarUrl(absoluteUrl(it), 96) },
            durationSec = if (isLive) 0L else parseDuration(duration),
            views = parseViews(parts.firstOrNull { it.contains("view") || it.contains("watching") }),
            uploaded = if (isLive) I18n.t("live_now", "Live") else (parts.firstOrNull { it.contains("ago") || it.contains("पहले") || it.contains("hace") || it.contains("назад") } ?: parts.getOrNull(2))?.let { I18n.translateTimeAgo(it) },
            isLive = isLive,
        )
    }

    internal fun parseCommunity(root: JSONObject): CommunityPage {
        val posts = LinkedHashMap<String, CommunityPost>()
        var next: String? = null
        root.walk { key, obj ->
            when (key) {
                "backstagePostRenderer", "sharedPostRenderer", "postRenderer" ->
                    parsePost(obj)?.let { posts.putIfAbsent(it.id, it) }
                "continuationItemRenderer", "continuationItemViewModel" ->
                    obj.collectStrings("token").firstOrNull()?.let { next = it }
            }
        }
        return CommunityPage(posts.values.toList(), next)
    }

    /** Shared parser for community posts, used by the home feed and the channel tab. */
    internal fun parsePost(o: JSONObject): CommunityPost? {
        val id = o.optString("postId").ifBlank { o.optString("id") }.ifBlank { return null }
        val text = o.text("contentText").orEmpty()
        val author = o.text("authorText").orEmpty()
        val avatar = o.optJSONObject("authorThumbnail")?.thumbnails()?.lastOrNull()
            ?: pickChannelAvatarUrl(o)
        val attachment = o.optJSONObject("backstageAttachment")
        val image = attachment?.optJSONObject("backstageImageRenderer")
            ?.optJSONObject("image")?.thumbnails()?.lastOrNull()
            ?: attachment?.optJSONObject("videoRenderer")
                ?.optJSONObject("thumbnail")?.thumbnails()?.lastOrNull()
        val published = o.text("publishedTimeText")
        val likes = o.optJSONObject("voteCount")
            ?.optJSONObject("accessibility")?.optJSONObject("accessibilityData")
            ?.optString("label")?.ifBlank { null }
            ?: o.text("voteCount")
        if (text.isBlank() && image == null && attachment == null) return null
        val voteStatus = o.optString("voteStatus")
        val toggle = o.optJSONObject("actionButtons")?.optJSONObject("commentActionButtonsRenderer")
            ?.optJSONObject("likeButton")?.optJSONObject("toggleButtonRenderer")
        val liked = voteStatus.equals("VOTE_STATUS_LIKED", ignoreCase = true) ||
            toggle?.optBoolean("isToggled", false) == true
        val likeAction = (toggle?.optJSONObject("defaultServiceEndpoint")
            ?: toggle?.optJSONObject("defaultNavigationEndpoint"))?.toString()
        val unlikeAction = (toggle?.optJSONObject("toggledServiceEndpoint")
            ?: toggle?.optJSONObject("toggledNavigationEndpoint"))?.toString()
        return CommunityPost(
            id = id,
            author = author,
            avatar = avatar,
            text = text,
            image = image,
            published = published,
            likes = likes?.substringBefore(" like")?.trim()?.ifBlank { null },
            liked = liked,
            likeAction = likeAction,
            unlikeAction = unlikeAction,
        )
    }

    private fun pickChannelAvatarUrl(o: JSONObject): String? {
        o.optJSONObject("channelThumbnailSupportedRenderers")
            ?.optJSONObject("channelThumbnailWithLinkRenderer")?.let { r ->
                r.thumbnails()?.lastOrNull()?.let { return it }
                r.optJSONObject("thumbnail")?.thumbnails()?.lastOrNull()?.let { return it }
            }
        o.optJSONObject("channelThumbnail")?.thumbnails()?.lastOrNull()?.let { return it }
        o.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")?.bestChannelAvatarUrl()?.let { return it }
        o.bestChannelAvatarUrl()?.let { return it }
        var found: String? = null
        o.forEachObject { obj ->
            if (found != null) return@forEachObject
            obj.optJSONArray("sources")?.widestSourceUrl()?.takeIf { isChannelAvatarUrl(it) }?.let { found = it }
        }
        return found
    }

    private fun isChannelAvatarUrl(url: String): Boolean = when {
        "ytimg.com/vi/" in url -> false
        "/ytc/" in url -> true
        "googleusercontent.com" in url && "-c-k-c0x00ffffff-no-rj" in url -> true
        "ggpht.com" in url && "=s" in url && "=w" !in url -> true
        else -> false
    }

    private fun JSONObject.bestChannelAvatarUrl(): String? =
        optJSONObject("image")
            ?.optJSONObject("decoratedAvatarViewModel")
            ?.optJSONObject("avatar")
            ?.optJSONObject("avatarViewModel")
            ?.optJSONObject("image")
            ?.optJSONArray("sources")
            ?.widestSourceUrl()
            ?: optJSONObject("image")?.collectStrings("url")
                ?.filter { "ggpht.com" in it || "googleusercontent.com" in it }
                ?.maxByOrNull { channelAvatarEdgePx(it) }

    internal fun pickBestThumbnailUrl(renderer: JSONObject): String? {
        renderer.bestThumbnailUrl()?.let { return it }
        renderer.optJSONObject("contentImage")?.bestThumbnailUrl()?.let { return it }
        renderer.optJSONObject("contentImage")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONObject("image")
            ?.optJSONArray("sources")
            ?.let { pickWidestSourceUrl(it) }
            ?.let { return it }
        return renderer.optJSONObject("contentImage")?.collectStrings("url")
            ?.filter { "ytimg.com/vi/" in it || "googleusercontent.com" in it || "ggpht.com" in it }
            ?.maxWithOrNull(compareBy({ ytimgThumbnailWidth(it) }, { it.length }))
    }

    private fun pickWidestSourceUrl(arr: JSONArray): String? = arr.widestSourceUrl()

    private fun videoThumbnailUrl(renderer: JSONObject, videoId: String): String =
        sharpenFeedThumbnail(pickBestThumbnailUrl(renderer)?.let(::absoluteUrl), videoId)

    private val DURATION = Regex("^\\d{1,2}(:\\d{2}){1,2}$")

    private fun parseDuration(text: String?): Long {
        val t = text?.trim() ?: return 0
        if (!DURATION.matches(t)) return 0
        return t.split(':').fold(0L) { acc, part -> acc * 60 + (part.toLongOrNull() ?: 0) }
    }


    internal fun parseViews(text: String?): Long {
        val t = text?.lowercase()?.replace(",", "")?.trim() ?: return -1
        if (t.startsWith("no ") || t.startsWith("कोई ") || t.startsWith("sin ")) return 0
        if ("करोड़" in t || "cr" in t) {
            val num = Regex("([\\d.]+)").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return -1
            return (num * 10_000_000).toLong()
        }
        if ("लाख" in t || "lakh" in t) {
            val num = Regex("([\\d.]+)").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return -1
            return (num * 100_000).toLong()
        }
        if ("हज़ार" in t) {
            val num = Regex("([\\d.]+)").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return -1
            return (num * 1_000).toLong()
        }
        val m = Regex("([\\d.]+)\\s*([kmb]?)").find(t) ?: return -1
        val n = m.groupValues[1].toDoubleOrNull() ?: return -1
        val mult = when (m.groupValues[2]) { "k" -> 1e3; "m" -> 1e6; "b" -> 1e9; else -> 1.0 }
        return (n * mult).toLong()
    }

    private fun absoluteUrl(url: String) = if (url.startsWith("//")) "https:$url" else url

    internal fun parseChannelHeader(root: JSONObject): ChannelHeaderMeta? {
        var handle: String? = null
        var subsText: String? = null
        var videosText: String? = null
        fun noteLine(line: String?) {
            val t = line?.trim()?.ifBlank { null } ?: return
            val lower = t.lowercase()
            if (subsText == null && "subscriber" in lower) subsText = t
            if (videosText == null) {
                VIDEO_COUNT_IN_LINE.find(lower)?.value?.let { videosText = it }
            }
        }
        fun scanMetadataParts(arr: JSONArray?) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                noteLine(arr.optJSONObject(i)?.optJSONObject("text")?.optString("content"))
            }
        }
        root.walk { _, obj ->
            noteLine(obj.text("subscriberCountText"))
            noteLine(obj.text("videoCountText"))
            noteLine(obj.optJSONObject("text")?.optString("content"))
            scanMetadataParts(obj.optJSONArray("metadataParts"))
            obj.optJSONArray("metadataRows")?.let { rows ->
                for (r in 0 until rows.length()) {
                    scanMetadataParts(rows.optJSONObject(r)?.optJSONArray("metadataParts"))
                }
            }
            obj.optString("vanityChannelUrl").takeIf { "@" in it }?.let { url ->
                handle = handle ?: url.substringAfter("@").substringBefore("?").substringBefore("/").ifBlank { null }
            }
            obj.optString("canonicalBaseUrl").takeIf { it.startsWith("/@") }?.let { path ->
                handle = handle ?: path.removePrefix("/@").substringBefore("/").ifBlank { null }
            }
        }
        if (subsText == null || videosText == null) {
            for (line in root.collectStrings("content") + root.collectStrings("simpleText")) {
                noteLine(line)
            }
        }
        val subscribers = parseViews(subsText)
        val videoCount = parseViews(videosText)
        val verified = root.optJSONObject("header")?.let { parseChannelVerified(it) } ?: false
        if (handle == null && subscribers < 0 && videoCount < 0 && !verified) return null
        return ChannelHeaderMeta(handle, subscribers.coerceAtLeast(0), videoCount.coerceAtLeast(-1), verified)
    }


    internal fun parseChannelVerified(headerRoot: JSONObject): Boolean {
        headerRoot.optJSONObject("pageHeaderRenderer")?.optJSONObject("content")
            ?.optJSONObject("pageHeaderViewModel")?.optJSONObject("title")
            ?.optJSONObject("dynamicTextViewModel")?.optJSONObject("rendererContext")
            ?.optJSONObject("accessibilityContext")?.optString("label")?.let { label ->
                if (VERIFIED_CHANNEL_LABEL.containsMatchIn(label)) return true
            }
        var found = false
        headerRoot.walk { key, obj ->
            if (found) return@walk
            if (key == "metadataBadgeRenderer" && isVerifiedChannelBadge(obj.optString("style"))) found = true
        }
        return found
    }

    private fun isVerifiedChannelBadge(style: String): Boolean =
        style == "BADGE_STYLE_TYPE_VERIFIED" || style == "BADGE_STYLE_TYPE_VERIFIED_ARTIST"

    private val VERIFIED_CHANNEL_LABEL = Regex("verified|official artist", RegexOption.IGNORE_CASE)
    private val VIDEO_COUNT_IN_LINE = Regex("""[\d,.]+[kmb]?\s+videos\b""", RegexOption.IGNORE_CASE)
}




internal fun JSONObject.walk(visit: (key: String, obj: JSONObject) -> Unit) {
    val keys = keys()
    while (keys.hasNext()) {
        val k = keys.next()
        when (val v = opt(k)) {
            is JSONObject -> { visit(k, v); v.walk(visit) }
            is JSONArray -> v.walkArray(visit)
        }
    }
}

private fun JSONArray.walkArray(visit: (key: String, obj: JSONObject) -> Unit) {
    for (i in 0 until length()) {
        when (val v = opt(i)) {
            is JSONObject -> v.walk(visit)
            is JSONArray -> v.walkArray(visit)
        }
    }
}


internal fun JSONObject.forEachObject(visit: (JSONObject) -> Unit) {
    visit(this)
    val keys = keys()
    while (keys.hasNext()) {
        when (val v = opt(keys.next())) {
            is JSONObject -> v.forEachObject(visit)
            is JSONArray -> v.forEachObjectInArray(visit)
        }
    }
}

private fun JSONArray.forEachObjectInArray(visit: (JSONObject) -> Unit) {
    for (i in 0 until length()) {
        when (val v = opt(i)) {
            is JSONObject -> v.forEachObject(visit)
            is JSONArray -> v.forEachObjectInArray(visit)
        }
    }
}


internal fun JSONObject.text(key: String): String? {
    val o = optJSONObject(key) ?: return optString(key).ifBlank { null }
    o.optString("simpleText").ifBlank { null }?.let { return it }
    o.optString("content").ifBlank { null }?.let { return it }
    val runs = o.optJSONArray("runs") ?: return null
    return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }.ifBlank { null }
}


internal fun JSONObject.thumbnails(): List<String>? {
    val arr = optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: optJSONArray("thumbnails") ?: return null
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("url")?.ifBlank { null } }
}

internal fun JSONArray.widestSourceUrl(): String? {
    var best: String? = null
    var bestW = -1
    for (i in 0 until length()) {
        val entry = optJSONObject(i) ?: continue
        val url = entry.optString("url").ifBlank { continue }
        val w = entry.optInt("width", channelAvatarEdgePx(url).takeIf { it > 0 } ?: ytimgThumbnailWidth(url))
        if (w > bestW) {
            bestW = w
            best = url
        }
    }
    return best ?: optJSONObject(length() - 1)?.optString("url")?.ifBlank { null }
}


internal fun JSONObject.bestThumbnailUrl(): String? {
    val arr = optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: optJSONArray("thumbnails") ?: return null
    var best: String? = null
    var bestW = -1
    for (i in 0 until arr.length()) {
        val entry = arr.optJSONObject(i) ?: continue
        val url = entry.optString("url").ifBlank { continue }
        val w = entry.optInt("width", 0)
        if (w > bestW) {
            bestW = w
            best = url
        }
    }
    return best ?: arr.optJSONObject(arr.length() - 1)?.optString("url")?.ifBlank { null }
}

private fun JSONObject.firstImageSource(): String? = collectStrings("url").firstOrNull { it.contains("ytimg") || it.contains("ggpht") }


internal fun JSONObject.collectStrings(field: String): List<String> {
    val out = mutableListOf<String>()
    fun visitObj(o: JSONObject) {
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            when (val v = o.opt(k)) {
                is String -> if (k == field && v.isNotBlank()) out += v
                is JSONObject -> visitObj(v)
                is JSONArray -> for (i in 0 until v.length()) (v.opt(i) as? JSONObject)?.let(::visitObj)
            }
        }
    }
    visitObj(this)
    return out
}
