package com.tensei.ytxtube

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.memory.MemoryCache
import com.tensei.ytxtube.potoken.PoTokenGenerator
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor

class YTXTubeApplication : Application(), ImageLoaderFactory {

    companion object {
        lateinit var appContext: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        TvMode.init(this)
        LanguageSettings.init(this)
        GlassQuality.init(this)
        GlassSettings.init(this)
        NetworkSpeed.init(this)
        PlaybackBandwidth.init(this)
        NewPipe.init(NewPipeDownloader.instance)


        val poTokenGenerator = PoTokenGenerator()
        // Do not hold the generator lock while the app starts: the first video must not
        // wait for a WebView that may be unable to finish BotGuard initialization.
        YoutubeStreamExtractor.setPoTokenProvider(YtxPoTokenProvider(poTokenGenerator))


        YoutubeStreamExtractor.setFetchIosClient(true)
        HdrItags.install()
        ThemeSettings.init(this)
        HdrSupport.init(this)
        QualityPreference.init(this)
        PlaybackSpeed.init(this)
        AudioOnly.init(this)
        AudioTrackPreference.init(this)
        CaptionPreference.init(this)
        VideoGestureSettings.init(this)
        PlayerControlSettings.init(this)
        PipSettings.init(this)
        MiniPlayerSettings.init(this)
        ResumeStore.init(this)
        Account.init(this)
        TvAuth.init(this)
        LocalWatchHistory.init(this)
        WatchSyncLog.init(this)
        WatchSyncLog.note("app start signedIn=${Account.signedIn}")
        SponsorBlock.init(this)
        SearchHistory.init(this)
        Downloads.init(this)

        getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : android.net.ConnectivityManager.NetworkCallback() {
                private var last: android.net.Network? = null
                override fun onAvailable(network: android.net.Network) {
                    if (last != null && last != network) StreamCache.invalidateAll()
                    last = network
                }
            },
        )
    }


    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(180)
        // Community posts can carry animated GIF / animated WebP images.
        .components {
            add(GifDecoder.Factory())
            if (android.os.Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        // Thumbnails come from YouTube's CDN. Send the same browser headers as the rest of
        // the app so signed CDN links are not rejected on their signature alone.
        .okHttpClient {
            Net.client.newBuilder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val host = request.url.host
                    val withHeaders = if (
                        host.endsWith("ytimg.com") ||
                        host.endsWith("ggpht.com") ||
                        host.endsWith("googleusercontent.com")
                    ) {
                        request.newBuilder()
                            .header("User-Agent", Net.USER_AGENT)
                            .header("Referer", "https://www.youtube.com/")
                            .build()
                    } else {
                        request
                    }
                    chain.proceed(withHeaders)
                }
                .build()
        }
        .build()
}
