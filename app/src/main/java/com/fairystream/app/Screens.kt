package com.fairystream.app

import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val TopGap = 116.dp
private const val AUTO_SLIDE_MS = 6_000L
private const val BUFFER_MIN_MS = 15_000
private const val BUFFER_MAX_MS = 20_000
private const val BUFFER_START_MS = 1_500
private const val BUFFER_REBUFFER_MS = 3_000

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    series: List<SeriesItem>,
    favorites: Set<String>,
    history: List<Watched>,
    loading: Boolean,
    error: String?,
    onOpen: (SeriesItem) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val browsing = query.isBlank()
    val results = remember(series, query) {
        val needle = query.trim()
        if (needle.isEmpty()) series else series.filter { it.title.contains(needle, ignoreCase = true) }
    }
    val featured = remember(series) { series.take(5) }
    val favoriteItems = remember(series, favorites) { series.filter { it.slug in favorites } }
    val recent = remember(history) { history.map { it.series } }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(searchOpen) {
        if (searchOpen) runCatching { focusRequester.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(Fairy.Ink)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            if (!searchOpen && featured.isNotEmpty()) {
                item(key = "hero") {
                    Hero(featured, favorites, onOpen, onToggleFavorite)
                }
            } else {
                item(key = "top-gap") { Spacer(Modifier.height(TopGap)) }
            }

            item(key = "search") {
                AnimatedVisibility(
                    visible = searchOpen,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    SearchField(
                        value = query,
                        onValueChange = { query = it },
                        onDone = { focusManager.clearFocus() },
                        focusRequester = focusRequester,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }

            if (browsing && recent.isNotEmpty()) {
                item(key = "title-recent") { SectionTitle("ดูต่อ") }
                item(key = "row-recent") { PosterRow(recent, favorites, onOpen) }
            }
            if (browsing && favoriteItems.isNotEmpty()) {
                item(key = "title-favorites") { SectionTitle("รายการโปรด") }
                item(key = "row-favorites") { PosterRow(favoriteItems, favorites, onOpen) }
            }
            if (browsing && series.isNotEmpty()) {
                item(key = "title-new") { SectionTitle("มาใหม่") }
                item(key = "row-new") { PosterRow(series.take(12), favorites, onOpen) }
            }

            if (loading && series.isEmpty()) {
                item(key = "skeleton") { SkeletonGrid(columns = 3, rows = 3) }
            }
            if (error != null && series.isEmpty()) {
                item(key = "error") { ErrorState(error, onRefresh) }
            }

            if (results.isNotEmpty()) {
                item(key = "title-all") {
                    SectionTitle(
                        if (browsing) "ทั้งหมด ${series.size} เรื่อง"
                        else "ผลการค้นหา ${results.size} เรื่อง"
                    )
                }
                items(results.chunked(3), key = { row -> row.first().slug }) { row ->
                    PosterGridRow(row, favorites, onOpen)
                }
            } else if (!browsing) {
                item(key = "empty") { EmptyResult(query) }
            }
        }

        HomeTopBar(
            searchOpen = searchOpen,
            loading = loading,
            onToggleSearch = {
                searchOpen = !searchOpen
                if (!searchOpen) {
                    query = ""
                    focusManager.clearFocus()
                }
            },
            onRefresh = onRefresh,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun HomeTopBar(
    searchOpen: Boolean,
    loading: Boolean,
    onToggleSearch: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(Brush.verticalGradient(listOf(Fairy.Ink, Color.Transparent)))
            .padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "FairyStream",
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    brush = Fairy.Brand,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
            )
            CircleIcon(
                icon = if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                description = if (searchOpen) "ปิดการค้นหา" else "ค้นหา",
                onClick = onToggleSearch
            )
            Spacer(Modifier.width(8.dp))
            CircleIcon(
                icon = Icons.Default.Refresh,
                description = "โหลดใหม่",
                onClick = onRefresh
            )
        }
        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                color = Fairy.Violet,
                trackColor = Fairy.Raised
            )
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.focusRequester(focusRequester),
        placeholder = { Text("ค้นหาเรื่องที่อยากดู", color = Fairy.Muted) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = Fairy.Muted) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "ล้างคำค้น",
                    tint = Fairy.Muted,
                    modifier = Modifier.clickable { onValueChange("") }
                )
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Fairy.Panel,
            unfocusedContainerColor = Fairy.Panel,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Fairy.Violet,
            focusedTextColor = Fairy.Mist,
            unfocusedTextColor = Fairy.Mist
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onDone() })
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hero(
    featured: List<SeriesItem>,
    favorites: Set<String>,
    onOpen: (SeriesItem) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    val pager = rememberPagerState(pageCount = { featured.size })
    LaunchedEffect(featured.size) {
        while (featured.size > 1) {
            delay(AUTO_SLIDE_MS)
            if (!pager.isScrollInProgress) {
                pager.animateScrollToPage((pager.currentPage + 1) % featured.size)
            }
        }
    }
    val index = pager.currentPage.coerceIn(0, featured.lastIndex)

    Box(Modifier.fillMaxWidth().height(560.dp)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            Cover(featured[page], Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Fairy.HeroScrim))
        AnimatedContent(
            targetState = index,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            transitionSpec = {
                (fadeIn(tween(480)) + slideInVertically(tween(480)) { it / 6 }) togetherWith
                    fadeOut(tween(180))
            },
            label = "hero-caption"
        ) { i ->
            val s = featured[i.coerceAtMost(featured.lastIndex)]
            val liked = s.slug in favorites
            Column {
                Text(
                    text = s.title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = Fairy.Mist,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PillButton(
                        text = "เปิดดู",
                        icon = Icons.Default.PlayArrow,
                        filled = true,
                        onClick = { onOpen(s) }
                    )
                    PillButton(
                        text = if (liked) "ในรายการแล้ว" else "รายการโปรด",
                        icon = if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        filled = false,
                        onClick = { onToggleFavorite(s.slug) }
                    )
                }
                Spacer(Modifier.height(20.dp))
                PageDots(count = featured.size, selected = i.coerceAtMost(featured.lastIndex))
            }
        }
    }
}

@Composable
private fun PageDots(count: Int, selected: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { i ->
            val active = i == selected
            val dotWidth by animateDpAsState(
                targetValue = if (active) 24.dp else 6.dp,
                label = "dot-width"
            )
            Box(
                Modifier
                    .height(6.dp)
                    .width(dotWidth)
                    .clip(CircleShape)
                    .background(if (active) Fairy.Mist else Fairy.Mist.copy(alpha = 0.3f))
            )
        }
    }
}

@Composable
private fun PosterRow(list: List<SeriesItem>, favorites: Set<String>, onOpen: (SeriesItem) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(list, key = { it.slug }) { series ->
            Poster(
                series = series,
                favorite = series.slug in favorites,
                onClick = { onOpen(series) },
                modifier = Modifier.width(128.dp)
            )
        }
    }
}

@Composable
private fun PosterGridRow(list: List<SeriesItem>, favorites: Set<String>, onOpen: (SeriesItem) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        list.forEach { series ->
            Poster(
                series = series,
                favorite = series.slug in favorites,
                onClick = { onOpen(series) },
                modifier = Modifier.weight(1f)
            )
        }
        repeat(3 - list.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun EmptyResult(query: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "ไม่พบ \"$query\"",
            style = MaterialTheme.typography.titleMedium,
            color = Fairy.Mist
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "ลองพิมพ์ชื่อให้สั้นลง",
            style = MaterialTheme.typography.bodyMedium,
            color = Fairy.Muted
        )
    }
}

@Composable
fun DetailScreen(
    series: SeriesItem,
    episodes: List<EpisodeItem>,
    loading: Boolean,
    error: String?,
    resumeId: String?,
    favorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlay: (Int) -> Unit,
    onRetry: () -> Unit
) {
    val resumeIndex = resumeId
        ?.let { id -> episodes.indexOfFirst { it.watchId == id } }
        ?.takeIf { it >= 0 }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Fairy.Ink),
        contentPadding = PaddingValues(bottom = 48.dp)
    ) {
        item(key = "header") {
            DetailHeader(
                series = series,
                count = episodes.size,
                loading = loading,
                favorite = favorite,
                onBack = onBack,
                onToggleFavorite = onToggleFavorite
            )
        }

        if (episodes.isNotEmpty()) {
            item(key = "actions") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PillButton(
                        text = if (resumeIndex != null) "ดูต่อ ${episodes[resumeIndex].title}" else "เล่นตอนแรก",
                        icon = Icons.Default.PlayArrow,
                        filled = true,
                        onClick = { onPlay(resumeIndex ?: 0) }
                    )
                }
            }
            item(key = "episodes-title") { SectionTitle("ตอนทั้งหมด") }
            itemsIndexed(episodes, key = { _, episode -> episode.watchId }) { index, episode ->
                EpisodeRow(
                    number = episodeNumber(episode.title, index),
                    title = episode.title,
                    watched = index == resumeIndex,
                    onClick = { onPlay(index) }
                )
            }
        } else {
            item(key = "state") {
                when {
                    loading -> Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Fairy.Violet)
                    }
                    error != null -> ErrorState(error, onRetry)
                    else -> ErrorState("ยังไม่พบตอนของเรื่องนี้", onRetry)
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(
    series: SeriesItem,
    count: Int,
    loading: Boolean,
    favorite: Boolean,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Box(Modifier.fillMaxWidth().height(360.dp)) {
        Cover(series, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Fairy.HeroScrim))
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircleIcon(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                description = "กลับ",
                onClick = onBack
            )
            CircleIcon(
                icon = if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                description = "รายการโปรด",
                onClick = onToggleFavorite,
                tint = if (favorite) Fairy.Rose else Color.White
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp)
        ) {
            Text(
                text = series.title,
                style = MaterialTheme.typography.headlineLarge,
                color = Fairy.Mist,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    count > 0 -> "$count ตอน"
                    loading -> "กำลังโหลดตอน"
                    else -> "ยังไม่มีตอน"
                },
                style = MaterialTheme.typography.labelLarge,
                color = Fairy.Muted
            )
        }
    }
}

@Composable
private fun EpisodeRow(number: String, title: String, watched: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (watched) Fairy.Raised else Fairy.Panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (watched) Fairy.Violet else Fairy.Raised),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.titleMedium,
                color = if (watched) Fairy.Ink else Fairy.Mist
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Fairy.Mist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (watched) {
                Text(
                    text = "ดูล่าสุด",
                    style = MaterialTheme.typography.labelLarge,
                    color = Fairy.Violet
                )
            }
        }
        Icon(Icons.Default.PlayArrow, null, tint = Fairy.Muted)
    }
}

private fun episodeNumber(title: String, index: Int): String =
    Regex("\\d+").find(title)?.value ?: (index + 1).toString()

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    series: SeriesItem,
    episodes: List<EpisodeItem>,
    index: Int,
    onBack: () -> Unit,
    onEpisode: (Int) -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val episode = episodes[index]
    val hasNext = index + 1 < episodes.size
    val latestOnEpisode by rememberUpdatedState(onEpisode)
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember {
        ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS, BUFFER_START_MS, BUFFER_REBUFFER_MS)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()
    }

    var qualities by remember(episode.watchId) { mutableStateOf<List<Quality>>(emptyList()) }
    var current by remember(episode.watchId) { mutableStateOf<Quality?>(null) }
    var loading by remember(episode.watchId) { mutableStateOf(true) }
    var failure by remember(episode.watchId) { mutableStateOf<String?>(null) }
    var attempt by remember(episode.watchId) { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }

    fun load(quality: Quality, positionMs: Long) {
        current = quality
        val mime = when {
            quality.url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            quality.url.contains(".mp4", ignoreCase = true) -> MimeTypes.VIDEO_MP4
            else -> null
        }
        val item = MediaItem.Builder()
            .setUri(quality.url)
            .apply { if (mime != null) setMimeType(mime) }
            .build()
        player.setMediaItem(item, positionMs)
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    DisposableEffect(player, index) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && hasNext) {
                    latestOnEpisode(index + 1)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                failure = "เปิดตอนนี้ไม่ได้ ลองอีกครั้งหรือเลือกคุณภาพอื่น"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(activity) {
        val window = activity?.window
        val previousOrientation = activity?.requestedOrientation
        if (activity != null && window != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (activity != null && window != null) {
                activity.requestedOrientation = previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(episode.watchId, attempt) {
        loading = true
        failure = null
        player.stop()
        player.clearMediaItems()
        try {
            val list = withContext(Dispatchers.IO) { FairyApi.resolveQualities(episode.watchId) }
            qualities = list
            val pick = list.firstOrNull() ?: throw IllegalStateException("ไม่พบลิงก์วิดีโอ")
            load(pick, C.TIME_UNSET)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message ?: "เปิดตอนนี้ไม่ได้ ลองอีกครั้ง"
        } finally {
            loading = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = true } },
            update = { view ->
                view.player = player
                view.useController = !loading
            },
            modifier = Modifier.fillMaxSize()
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircleIcon(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                description = "กลับ",
                onClick = onBack
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = series.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Fairy.Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = Fairy.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (qualities.size > 1) {
                Box {
                    PillButton(
                        text = current?.label ?: "คุณภาพ",
                        icon = Icons.Default.Settings,
                        filled = false,
                        onClick = { menuOpen = true }
                    )
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        qualities.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = option.label,
                                        color = if (option == current) Fairy.Violet else Fairy.Mist
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    if (option != current) load(option, player.currentPosition)
                                }
                            )
                        }
                    }
                }
            }
            if (hasNext) {
                CircleIcon(
                    icon = Icons.Default.SkipNext,
                    description = "ตอนถัดไป",
                    onClick = { onEpisode(index + 1) }
                )
            }
        }

        if (loading) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CircularProgressIndicator(color = Fairy.Violet)
                Text(
                    text = "กำลังเตรียมวิดีโอ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Fairy.Muted
                )
            }
        }

        val message = failure
        if (!loading && message != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Fairy.Muted,
                    textAlign = TextAlign.Center
                )
                PillButton(
                    text = "ลองอีกครั้ง",
                    icon = Icons.Default.Refresh,
                    filled = true,
                    onClick = { attempt += 1 }
                )
            }
        }
    }
}
