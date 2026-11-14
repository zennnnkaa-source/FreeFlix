package com.fairystream.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val COVER_CONCURRENCY = 4
private const val HISTORY_LIMIT = 20

data class Watched(val series: SeriesItem, val watchId: String, val episode: String)

sealed interface Screen {
    data object Home : Screen
    data class Detail(val series: SeriesItem) : Screen
    data class Watch(val series: SeriesItem, val index: Int) : Screen
}

class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("fairystream", Context.MODE_PRIVATE)

    fun favorites(): Set<String> =
        prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty().toSet()

    fun saveFavorites(slugs: Set<String>) {
        prefs.edit().putStringSet(KEY_FAVORITES, HashSet(slugs)).apply()
    }

    fun history(): List<Watched> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { array.getJSONObject(it).toWatched() }
        }.getOrDefault(emptyList())
    }

    fun pushHistory(entry: Watched) {
        val merged = listOf(entry) + history().filterNot { it.series.slug == entry.series.slug }
        val array = JSONArray()
        merged.take(HISTORY_LIMIT).forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_HISTORY = "history"
    }
}

private fun Watched.toJson(): JSONObject = JSONObject()
    .put("title", series.title)
    .put("slug", series.slug)
    .put("thumb", series.thumb.orEmpty())
    .put("watchId", watchId)
    .put("episode", episode)

private fun JSONObject.toWatched(): Watched = Watched(
    series = SeriesItem(
        title = getString("title"),
        slug = getString("slug"),
        thumb = optString("thumb").ifEmpty { null }
    ),
    watchId = getString("watchId"),
    episode = optString("episode")
)

class FairyViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LocalStore(application)
    private val coverCache = HashMap<String, String>()
    private var homeJob: Job? = null
    private var detailJob: Job? = null

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var series by mutableStateOf<List<SeriesItem>>(emptyList())
        private set
    var episodes by mutableStateOf<List<EpisodeItem>>(emptyList())
        private set
    var homeLoading by mutableStateOf(false)
        private set
    var homeError by mutableStateOf<String?>(null)
        private set
    var detailLoading by mutableStateOf(false)
        private set
    var detailError by mutableStateOf<String?>(null)
        private set
    var favorites by mutableStateOf(store.favorites())
        private set
    var history by mutableStateOf(store.history())
        private set

    init {
        loadHome()
    }

    fun loadHome() {
        homeJob?.cancel()
        homeLoading = true
        homeError = null
        homeJob = viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { FairyApi.fetchHome() }
                series = items.map { item ->
                    if (item.thumb != null) item
                    else coverCache[item.slug]?.let { item.copy(thumb = it) } ?: item
                }
                if (items.isEmpty()) {
                    homeError = "ไม่พบรายการเรื่องจากหน้าแรก"
                } else {
                    fillCovers(series)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                homeError = "โหลดรายการไม่ได้ เช็กการเชื่อมต่อแล้วลองอีกครั้ง"
            } finally {
                homeLoading = false
            }
        }
    }

    private fun fillCovers(items: List<SeriesItem>) {
        viewModelScope.launch {
            val gate = Semaphore(COVER_CONCURRENCY)
            val covers = coroutineScope {
                items
                    .filter { it.thumb == null && it.slug !in coverCache }
                    .map { item ->
                        async(Dispatchers.IO) {
                            gate.withPermit {
                                runCatching { FairyApi.fetchCover(item.slug) }
                                    .getOrNull()
                                    ?.let { item.slug to it }
                            }
                        }
                    }
                    .awaitAll()
                    .filterNotNull()
                    .toMap()
            }
            if (covers.isNotEmpty()) {
                coverCache.putAll(covers)
                series = series.map { s -> covers[s.slug]?.let { s.copy(thumb = it) } ?: s }
            }
        }
    }

    fun openSeries(item: SeriesItem) {
        detailJob?.cancel()
        episodes = emptyList()
        detailError = null
        detailLoading = true
        screen = Screen.Detail(item)
        detailJob = viewModelScope.launch {
            try {
                episodes = withContext(Dispatchers.IO) { FairyApi.fetchEpisodes(item.slug) }
                if (episodes.isEmpty()) {
                    detailError = "ยังไม่พบตอนของเรื่องนี้"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                detailError = "โหลดตอนไม่ได้ ลองอีกครั้ง"
            } finally {
                detailLoading = false
            }
        }
    }

    fun play(index: Int) {
        val item = currentSeries() ?: return
        val episode = episodes.getOrNull(index) ?: return
        store.pushHistory(Watched(item, episode.watchId, episode.title))
        history = store.history()
        screen = Screen.Watch(item, index)
    }

    fun back() {
        when (val current = screen) {
            is Screen.Watch -> screen = Screen.Detail(current.series)
            is Screen.Detail -> {
                detailJob?.cancel()
                episodes = emptyList()
                detailError = null
                screen = Screen.Home
            }
            Screen.Home -> Unit
        }
    }

    fun toggleFavorite(slug: String) {
        favorites = if (slug in favorites) favorites - slug else favorites + slug
        store.saveFavorites(favorites)
    }

    private fun currentSeries(): SeriesItem? = when (val current = screen) {
        is Screen.Detail -> current.series
        is Screen.Watch -> current.series
        Screen.Home -> null
    }
}
