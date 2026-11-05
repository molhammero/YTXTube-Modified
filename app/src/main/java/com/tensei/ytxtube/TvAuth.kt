package com.tensei.ytxtube

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Google TV OAuth device flow. SmartTube's stats requests use a TV OAuth bearer
 * token; web cookies alone do not attach a third-party stats write to the account.
 *
 * The OAuth client credentials are fetched at runtime from SmartTube's public
 * constants file, so no client secret is embedded in this app.
 */
object TvAuth {
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresIn: Int,
        val interval: Int,
    )

    private data class Credentials(val clientId: String, val clientSecret: String)

    const val STATS_USER_AGENT =
        "Mozilla/5.0 (DirectFB; Linux x86_64) Cobalt/120 (unlike Gecko) Starboard/1"

    private const val CONFIG_URL =
        "https://github.com/yuliskov/SmartTube/releases/download/latest/constants.json"
    private const val DEVICE_URL = "https://www.youtube.com/o/oauth2/device/code"
    private const val TOKEN_URL = "https://www.youtube.com/o/oauth2/token"
    private const val SCOPE =
        "https://www.googleapis.com/auth/youtube https://www.googleapis.com/auth/youtube.readonly"
    private const val DEVICE_GRANT = "http://oauth.net/grant_type/device/1.0"
    private const val KEY_REFRESH = "refresh"
    private const val KEY_DEVICE = "device"
    private val JSON = "application/json".toMediaType()

    private var store: SecureStore? = null
    @Volatile private var refreshToken: String? = null
    @Volatile private var accessToken: String? = null
    @Volatile private var accessExpiresAt: Long = 0L
    private var cachedClient: Credentials? = null
    private var clientFetchedAt: Long = 0L
    private var cachedDeviceId: String? = null

    fun init(context: Context) {
        val secure = SecureStore(context, "tv_auth")
        store = secure
        refreshToken = secure.get(KEY_REFRESH)
    }

    val isLinked: Boolean get() = !refreshToken.isNullOrBlank()

    /** Returns a code the user enters at https://www.google.com/device. */
    fun requestCode(): DeviceCode {
        val client = clientCredentials()
        val body = JSONObject()
            .put("client_id", client.clientId)
            .put("device_id", deviceId())
            .put("model_name", "ytlr::")
            .put("scope", SCOPE)
        val json = postJson(DEVICE_URL, body)
        val device = json.optString("device_code")
        if (device.isBlank()) throw IOException("TV auth: no device code")
        return DeviceCode(
            deviceCode = device,
            userCode = json.optString("user_code"),
            verificationUrl = json.optString("verification_url")
                .ifBlank { "https://www.google.com/device" },
            expiresIn = json.optInt("expires_in", 1800),
            interval = json.optInt("interval", 5),
        )
    }

    /**
     * False means Google has not approved the code yet. Other errors are thrown.
     */
    fun completeLink(code: DeviceCode): Boolean {
        val client = clientCredentials()
        val body = JSONObject()
            .put("code", code.deviceCode)
            .put("client_id", client.clientId)
            .put("client_secret", client.clientSecret)
            .put("grant_type", DEVICE_GRANT)
        val json = postJson(TOKEN_URL, body, allowError = true)
        val error = json.optString("error")
        if (error == "authorization_pending" || error == "slow_down") return false
        if (error.isNotBlank()) throw IOException("TV auth: $error")
        applyToken(json)
        return true
    }

    /** Returns a Bearer header, refreshing the stored refresh token if needed. */
    @Synchronized
    fun authorizationHeader(): String? {
        val now = System.currentTimeMillis()
        val current = accessToken
        if (!current.isNullOrBlank() && now < accessExpiresAt - 60_000L) {
            return "Bearer $current"
        }
        val refresh = refreshToken ?: return null
        val client = clientCredentials()
        val body = JSONObject()
            .put("refresh_token", refresh)
            .put("client_id", client.clientId)
            .put("client_secret", client.clientSecret)
            .put("grant_type", "refresh_token")
        applyToken(postJson(TOKEN_URL, body))
        return accessToken?.let { "Bearer $it" }
    }

    @Synchronized
    fun unlink() {
        refreshToken = null
        accessToken = null
        accessExpiresAt = 0L
        store?.put(KEY_REFRESH, null)
    }

    private fun applyToken(json: JSONObject) {
        val access = json.optString("access_token")
        if (access.isBlank()) throw IOException("TV auth: no access token")
        val refresh = json.optString("refresh_token").ifBlank { refreshToken }
        val expires = json.optLong("expires_in", 3600L)
        accessToken = access
        accessExpiresAt = System.currentTimeMillis() + expires * 1000L
        refreshToken = refresh
        store?.put(KEY_REFRESH, refresh)
    }

    private fun clientCredentials(): Credentials {
        val cached = cachedClient
        if (cached != null && System.currentTimeMillis() - clientFetchedAt < 60L * 60L * 1000L) {
            return cached
        }
        val request = Request.Builder()
            .url(CONFIG_URL)
            .header("User-Agent", "YTXTube/2.2")
            .build()
        Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("TV auth setup unavailable (${response.code})")
            }
            val json = JSONObject(response.body?.string().orEmpty())
            val id = json.optString("clientId")
            val secret = json.optString("clientSecret")
            if (id.isBlank() || secret.isBlank()) {
                throw IOException("TV auth setup is incomplete")
            }
            val credentials = Credentials(id, secret)
            cachedClient = credentials
            clientFetchedAt = System.currentTimeMillis()
            return credentials
        }
    }

    private fun deviceId(): String {
        cachedDeviceId?.let { return it }
        val secure = store ?: throw IOException("TV auth is not initialized")
        val existing = secure.get(KEY_DEVICE)
        if (!existing.isNullOrBlank()) {
            cachedDeviceId = existing
            return existing
        }
        val generated = UUID.randomUUID().toString().replace("-", "").take(16)
        secure.put(KEY_DEVICE, generated)
        cachedDeviceId = generated
        return generated
    }

    private fun postJson(url: String, body: JSONObject, allowError: Boolean = false): JSONObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("User-Agent", STATS_USER_AGENT)
            .build()
        Net.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw IOException("TV auth: invalid response (${response.code})")
            }
            if (!response.isSuccessful && !allowError) {
                throw IOException("TV auth: HTTP ${response.code}")
            }
            return json
        }
    }
}
