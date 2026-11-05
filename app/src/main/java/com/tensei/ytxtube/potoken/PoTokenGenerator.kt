package com.tensei.ytxtube.potoken

import android.util.Log
import android.webkit.CookieManager
import com.tensei.ytxtube.YTXTubeApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class PoTokenGenerator {
  private val TAG = "PoTokenGenerator"

  private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
  private var webViewBadImpl = false

  private val webPoTokenGenLock = Mutex()
  private var webPoTokenSessionId: String? = null
  private var webPoTokenStreamingPot: String? = null
  private var webPoTokenGenerator: PoTokenWebView? = null

  /** A failed WebView must not make every video start pay the timeout again. */
  @Volatile private var retryAfterMs = 0L

  fun initialize() {
    if (!webViewSupported || webViewBadImpl) return
    kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
      try {
        val ready = withTimeoutOrNull(INIT_TIMEOUT_MS) {
          webPoTokenGenLock.withLock {
            if (webPoTokenGenerator == null) {
              Log.d(TAG, "Pre-initializing PoTokenWebView in background...")
              webPoTokenSessionId = "init-" + System.currentTimeMillis()
              webPoTokenGenerator =
                PoTokenWebView.getNewPoTokenGenerator(YTXTubeApplication.appContext)
              webPoTokenStreamingPot = webPoTokenGenerator!!.generatePoToken(webPoTokenSessionId!!)
            }
            true
          }
        }
        if (ready == null) {
          Log.w(TAG, "PoTokenWebView initialization timed out; deferring token generation")
          retryAfterMs = System.currentTimeMillis() + RETRY_COOLDOWN_MS
          closeGeneratorBestEffort()
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to pre-initialize PoTokenWebView", e)
        retryAfterMs = System.currentTimeMillis() + RETRY_COOLDOWN_MS
      }
    }
  }

  fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
    Log.d(TAG, "getWebClientPoToken called: videoId=$videoId, sessionId=$sessionId")
    Log.d(TAG, "WebView state: supported=$webViewSupported, badImpl=$webViewBadImpl")
    if (!webViewSupported || webViewBadImpl) {
      Log.d(TAG, "WebView not available: supported=$webViewSupported, badImpl=$webViewBadImpl")
      return null
    }
    if (System.currentTimeMillis() < retryAfterMs) {
      Log.d(TAG, "Skipping PoToken during cooldown")
      return null
    }

    return try {
      Log.d(TAG, "Calling runBlocking to generate poToken (timeout=${POTOKEN_TIMEOUT_MS}ms)...")
      val result = runBlocking {
        withTimeout(POTOKEN_TIMEOUT_MS) {
          getWebClientPoToken(videoId, sessionId, forceRecreate = false)
        }
      }
      retryAfterMs = 0L
      result
    } catch (e: TimeoutCancellationException) {
      Log.w(TAG, "poToken generation timed out after ${POTOKEN_TIMEOUT_MS}ms; proceeding without PoToken")
      retryAfterMs = System.currentTimeMillis() + RETRY_COOLDOWN_MS
      closeGeneratorBestEffort()
      null
    } catch (e: Exception) {
      Log.e(TAG, "poToken generation exception: ${e.javaClass.simpleName}: ${e.message}", e)
      when (e) {
        is BadWebViewException -> {
          Log.e(TAG, "Could not obtain poToken because WebView is broken", e)
          webViewBadImpl = true
          null
        }
        else -> throw e
      }
    }
  }

  /** Never wait for the generator lock without a bound: initialization may own it. */
  private fun closeGeneratorBestEffort() {
    runBlocking {
      withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
        webPoTokenGenLock.withLock {
          try {
            withContext(Dispatchers.Main) { webPoTokenGenerator?.close() }
          } catch (closeEx: Exception) {
            Log.e(TAG, "Exception closing PoTokenWebView during timeout cleanup", closeEx)
          }
          webPoTokenGenerator = null
          webPoTokenStreamingPot = null
          webPoTokenSessionId = null
        }
      }
    }
  }

  private companion object {
    const val POTOKEN_TIMEOUT_MS = 5_000L
    const val INIT_TIMEOUT_MS = 5_000L
    const val CLEANUP_TIMEOUT_MS = 750L
    const val RETRY_COOLDOWN_MS = 30_000L
  }

  private suspend fun getWebClientPoToken(
    videoId: String,
    sessionId: String,
    forceRecreate: Boolean
  ): PoTokenResult {
    Log.d(TAG, "Web poToken requested: videoId=$videoId, sessionId=$sessionId")

    val (poTokenGenerator, streamingPot, hasBeenRecreated) =
      webPoTokenGenLock.withLock {
        val shouldRecreate =
          forceRecreate ||
            webPoTokenGenerator == null ||
            webPoTokenGenerator!!.isExpired ||
            webPoTokenSessionId != sessionId

        if (shouldRecreate) {
          Log.d(TAG, "Creating new PoTokenWebView (forceRecreate=$forceRecreate)")
          webPoTokenSessionId = sessionId

          withContext(Dispatchers.Main) { webPoTokenGenerator?.close() }


          webPoTokenGenerator = PoTokenWebView.getNewPoTokenGenerator(YTXTubeApplication.appContext)



          webPoTokenStreamingPot = webPoTokenGenerator!!.generatePoToken(webPoTokenSessionId!!)
          Log.d(TAG, "Streaming poToken generated for sessionId=${webPoTokenSessionId?.take(20)}...")
        }

        Triple(webPoTokenGenerator!!, webPoTokenStreamingPot!!, shouldRecreate)
      }

    val playerPot =
      try {
        poTokenGenerator.generatePoToken(videoId)
      } catch (throwable: Throwable) {
        if (hasBeenRecreated) {
          throw throwable
        } else {
          Log.e(TAG, "Failed to obtain poToken, retrying", throwable)
          return getWebClientPoToken(videoId = videoId, sessionId = sessionId, forceRecreate = true)
        }
      }

    Log.d(TAG, "poToken generated successfully: session=${streamingPot.take(20)}..., video=${playerPot.take(20)}...")

    return PoTokenResult(
      playerRequestPoToken = playerPot,
      streamingDataPoToken = streamingPot,
    )
  }
}
