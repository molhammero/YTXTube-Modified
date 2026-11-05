package com.tensei.ytxtube

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch


@Stable
class FeedState(
    private val scope: CoroutineScope,
    




    private val freshOnRefresh: Boolean = false,
    private val loader: suspend (next: String?) -> FeedPage,
) {
    var videos by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var entries by mutableStateOf<List<FeedEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var generation by mutableStateOf(0)
        private set

    var refreshing by mutableStateOf(false)
        private set
    private var next: String? = null
    private var loaded = false

    private val shown = HashSet<String>()
    private var job: Job? = null
    val hasMore get() = next != null

    fun ensureLoaded() { if (!loaded && !loading) refresh() }

    fun refresh() {
        loaded = true
        loading = true
        refreshing = videos.isNotEmpty()
        error = null
        job?.cancel()
        job = scope.launch {
            runCatching { if (freshOnRefresh && shown.isNotEmpty()) loadFresh() else loader(null) }
                .onSuccess {
                    videos = it.videos
                    entries = it.entries.ifEmpty { it.videos.map { v -> FeedEntry.Video(v) } }
                    next = it.next
                    generation++
                    if (freshOnRefresh) it.videos.forEach { v -> shown += v.id }
                }
                .onFailure { error = friendly(it) }
            loading = false
            refreshing = false
        }
    }

    fun loadMore() {
        val token = next ?: return
        if (loading) return
        loading = true
        job = scope.launch {
            runCatching { loader(token) }
                .onSuccess { page ->
                    val seen = videos.mapTo(HashSet()) { it.id }
                    val added = page.videos.filter { seen.add(it.id) }
                    videos = videos + added
                    val seenKeys = entries.mapTo(HashSet()) { it.key }
                    val pageEntries = page.entries.ifEmpty { added.map { v -> FeedEntry.Video(v) } }
                    entries = entries + pageEntries.filter { seenKeys.add(it.key) }
                    if (freshOnRefresh) added.forEach { v -> shown += v.id }
                    next = page.next
                }
                .onFailure { error = friendly(it) }
            loading = false
        }
    }

    



    private suspend fun loadFresh(): FeedPage {
        val first = loader(null)
        val fresh = LinkedHashMap<String, VideoItem>()
        val entries = ArrayList<FeedEntry>()
        var token = first.next
        first.videos.filter { it.id !in shown }.forEach { fresh[it.id] = it }
        first.entries.forEach { entry ->
            when (entry) {
                is FeedEntry.Video -> if (entry.video.id in fresh) entries.add(entry)
                is FeedEntry.Post -> entries.add(entry)
            }
        }
        var pages = 1
        while (fresh.size < FRESH_TARGET && token != null && pages < FRESH_PAGES) {
            val page = loader(token)
            val added = page.videos.filter { it.id !in shown && fresh.putIfAbsent(it.id, it) == null }
            page.entries.forEach { entry ->
                when (entry) {
                    is FeedEntry.Video -> if (entry.video.id in fresh) entries.add(entry)
                    is FeedEntry.Post -> entries.add(entry)
                }
            }
            if (page.entries.isEmpty()) added.forEach { entries.add(FeedEntry.Video(it)) }
            token = page.next
            pages++
        }
        if (fresh.isEmpty() && entries.none { it is FeedEntry.Post }) return first
        return FeedPage(fresh.values.toList(), token, entries = entries)
    }

    /** Interleaves freshly fetched community posts between the videos already shown. */
    fun mergePosts(posts: List<CommunityPost>) {
        if (posts.isEmpty()) return
        val existing = entries.filterIsInstance<FeedEntry.Post>().mapTo(HashSet()) { it.post.id }
        val fresh = posts.filter { existing.add(it.id) }
        if (fresh.isEmpty()) return
        if (entries.isEmpty()) {
            entries = videos.map { FeedEntry.Video(it) }
        }
        val merged = ArrayList<FeedEntry>(entries.size + fresh.size)
        var videosSincePost = 0
        var nextPost = 0
        for (entry in entries) {
            merged.add(entry)
            if (entry is FeedEntry.Video) {
                videosSincePost++
                if (videosSincePost >= 4 && nextPost < fresh.size) {
                    merged.add(FeedEntry.Post(fresh[nextPost++]))
                    videosSincePost = 0
                }
            }
        }
        while (nextPost < fresh.size) merged.add(FeedEntry.Post(fresh[nextPost++]))
        entries = merged
        WatchSyncLog.note("home posts merged=${fresh.size}")
    }

    fun reset() {
        job?.cancel()
        shown.clear()
        videos = emptyList(); entries = emptyList(); next = null; loaded = false; loading = false; error = null
    }

    private companion object {
        const val FRESH_TARGET = 16
        const val FRESH_PAGES = 5
    }

    private fun friendly(t: Throwable) = when (t) {
        is NotSignedInException -> "You're signed out."
        else -> t.message?.takeIf { it.startsWith("YouTube") } ?: YouTube.friendlyError(t)
    }
}


@Stable
class LibraryData(scope: CoroutineScope) {

    val home = FeedState(scope, freshOnRefresh = true) { InnerTube.homeFeed(it) }
    val subscriptions = FeedState(scope) { InnerTube.subscriptionsFeed(it) }
    val history = FeedState(scope) { next ->
        val page = InnerTube.history(next)
        if (next == null) FeedPage(LocalWatchHistory.merge(page.videos), page.next) else page
    }
    var channels by mutableStateOf<List<ChannelRef>>(emptyList())
        private set
    var playlists by mutableStateOf<List<PlaylistRef>>(emptyList())
        private set
    private val scope = scope

    fun loadChannels() = scope.launch { runCatching { InnerTube.channels() }.onSuccess { channels = it } }
    fun loadPlaylists() = scope.launch { runCatching { InnerTube.playlists() }.onSuccess { playlists = it } }

    fun playlistFeed(id: String) = FeedState(scope) {
        if (id == "MDL") {
            val items = Downloads.musicDownloads.map { it.toVideoItem() }
            FeedPage(items, null)
        } else if (id == "DL") {
            FeedPage(emptyList(), null)
        } else if (id == "HISTORY") {
            val page = InnerTube.history(it)
            if (it == null) FeedPage(LocalWatchHistory.merge(page.videos), page.next) else page
        } else {
            InnerTube.playlist(id, it)
        }
    }

    fun reset() {
        home.reset(); subscriptions.reset(); history.reset()
        channels = emptyList(); playlists = emptyList()
    }
}






/** Marks the Library history shelf as stale after playback reported a watch. */
object WatchHistoryRefresh {
    var pending by mutableStateOf(false)
}

@Composable
fun rememberScrollToTop(signal: Int): LazyListState {
    val state = rememberLazyListState()
    val initial = remember { signal }
    LaunchedEffect(signal) { if (signal != initial) state.animateScrollToItem(0) }
    return state
}


@Composable
fun FeedList(
    feed: FeedState,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    header: LazyListScope.() -> Unit = {},
    scrollToTop: Int = 0,
    pullToRefresh: Boolean = true,

    indicatorTop: Dp = 0.dp,

    videos: List<VideoItem>? = null,

    entries: List<FeedEntry>? = null,

    filterScrollKey: Any? = null,

    channelAvatarSeed: Map<String, String?> = emptyMap(),
) {
    val c = MaterialTheme.colorScheme
    val listVideos = videos ?: feed.videos
    val entryItems = entries?.takeIf { it.isNotEmpty() }
    val avatarVideos = entryItems?.mapNotNull { (it as? FeedEntry.Video)?.video } ?: listVideos
    val scope = rememberCoroutineScope()
    LaunchedEffect(avatarVideos, channelAvatarSeed) {
        ChannelAvatars.resolve(scope, avatarVideos, channelAvatarSeed)
    }
    val list = rememberScrollToTop(scrollToTop)

    LaunchedEffect(feed.generation, filterScrollKey) { list.scrollToItem(0) }
    val nearEnd by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, listVideos.size, entryItems?.size) {
        if (nearEnd && (listVideos.isNotEmpty() || entryItems?.isNotEmpty() == true)) feed.loadMore()
    }
    PullToRefresh(enabled = pullToRefresh, refreshing = feed.refreshing, onRefresh = feed::refresh, topPadding = indicatorTop) {
    LazyColumn(state = list, contentPadding = contentPadding) {
        header()
        if (entryItems != null) {
            items(entryItems, key = { it.key }) { entry ->
                when (entry) {
                    is FeedEntry.Video -> VideoCard(
                        entry.video,
                        onClick = { onOpen(entry.video) },
                        modifier = Modifier.animateItem(),
                    )
                    is FeedEntry.Post -> CommunityCard(entry.post, modifier = Modifier.animateItem())
                }
            }
        } else {
            items(listVideos, key = { it.id }) { video ->
                VideoCard(
                    video,
                    onClick = { onOpen(video) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        when {


            feed.error != null && listVideos.isEmpty() && entryItems == null -> item {
                EmptyState(
                    icon = Icons.Rounded.CloudOff,
                    title = "Couldn't load this",
                    body = feed.error.orEmpty(),
                    action = { PillButton("Try again", { feed.refresh() }) },
                )
            }
            feed.loading || feed.hasMore -> item {
                Box(Modifier.fillMaxWidth().padding(28.dp), Alignment.Center) {
                    CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
    }
}





@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PullToRefresh(enabled: Boolean, refreshing: Boolean, onRefresh: () -> Unit, topPadding: Dp = 0.dp, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val c = MaterialTheme.colorScheme
    val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = pull,
        indicator = {
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                state = pull, isRefreshing = refreshing,
                containerColor = c.primaryContainer, color = c.onPrimaryContainer,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topPadding),
            )
        },
    ) { content() }
}
