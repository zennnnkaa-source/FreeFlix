package com.fairystream.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

data class SeriesItem(val title: String, val slug: String, val thumb: String?)

data class EpisodeItem(val title: String, val watchId: String)

data class Quality(val label: String, val url: String)

object FairyApi {
    const val BASE = "https://fairyanime.net"

    private const val PLAYER_HOST = "https://streaming.tonytonychopper.com"
    private const val ABYSS_HOST = "https://abysscdn.com"
    private const val MARIMO_REFERER = "https://player.marimo.me/"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val reservedSegments = setOf(
        "category", "member", "search", "scoretop", "genre", "blog",
        "page", "tag", "author", "feed", "wp-json", "xmlrpc"
    )
    private val playerHints = listOf("tonytonychopper", "playback", "marimo", "abysscdn")

    private val watchPathPattern = Regex("""/watch/([A-Za-z0-9_-]+)\.html""")
    private val dataIdPattern = Regex("""([A-Za-z0-9_-]{6,20})""")
    private val playbackPattern = Regex("""https?://streaming\.tonytonychopper\.com/playback/[fy]/[A-Za-z0-9_-]+/?""")
    private val marimoPattern = Regex("""https?://player\.marimo\.me/[^"'\s>]+""")
    private val abyssPattern = Regex("""https?://abysscdn\.com/\?v=[A-Za-z0-9_-]+""")
    private val abyssVideoPattern = Regex("""abysscdn\.com/\?v=([A-Za-z0-9_-]+)""")
    private val videoKeyPattern = Regex("""["']v["']\s*[:=]\s*["']([A-Za-z0-9_-]+)["']""")
    private val streamPattern = Regex(
        """(?:file|src|url|source)\s*[:=]\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""",
        RegexOption.IGNORE_CASE
    )
    private val chunkPattern = Regex("""#mp4/chunk/[^"'\s]+""")

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun fetch(url: String, referer: String? = null): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "th-TH,th;q=0.9,en-US;q=0.8,en;q=0.7")
            .apply { if (referer != null) header("Referer", referer) }
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} for $url")
            return response.body?.string() ?: error("empty body $url")
        }
    }

    fun fetchHome(): List<SeriesItem> {
        val doc = Jsoup.parse(fetch("$BASE/"), BASE)
        val found = linkedMapOf<String, SeriesItem>()
        doc.select("a[href]").forEach { anchor ->
            val href = anchor.absUrl("href").trimEnd('/')
            if (!href.startsWith(BASE)) return@forEach
            val path = href.removePrefix(BASE).trim('/')
            if (path.length < 3 || path.contains('/')) return@forEach
            val lowered = path.lowercase()
            if (lowered in reservedSegments || lowered.substringBefore('-') in reservedSegments) return@forEach

            val img = anchor.selectFirst("img")
            val title = img?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
                ?: anchor.text().trim().takeIf { it.isNotEmpty() }
                ?: path.replace('-', ' ')
            val thumb = img?.let { el ->
                listOf("src", "data-src")
                    .map { el.absUrl(it) }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("data:") }
            }
            val existing = found[path]
            if (existing == null || (existing.thumb == null && thumb != null)) {
                found[path] = SeriesItem(title, path, thumb)
            }
        }
        return found.values.toList()
    }

    fun fetchCover(slug: String): String? {
        val doc = Jsoup.parse(fetch("$BASE/$slug/"), BASE)
        return listOf("meta[property='og:image']", "meta[name='twitter:image']")
            .firstNotNullOfOrNull { selector ->
                doc.selectFirst(selector)?.absUrl("content")?.takeIf { it.isNotEmpty() }
            }
    }

    fun fetchEpisodes(slug: String): List<EpisodeItem> {
        val doc = Jsoup.parse(fetch("$BASE/$slug/"), BASE)
        val found = linkedMapOf<String, EpisodeItem>()

        doc.select("a[href*='/watch/']").forEach { anchor ->
            val id = watchPathPattern.find(anchor.absUrl("href"))?.groupValues?.get(1) ?: return@forEach
            val title = anchor.text().trim().ifBlank { "ตอน ${found.size + 1}" }
            found.putIfAbsent(id, EpisodeItem(title, id))
        }

        doc.select("[data-id], [data-watch], [data-episode], [data-url]").forEach { el ->
            val raw = listOf("data-id", "data-watch", "data-episode", "data-url")
                .map { el.attr(it) }
                .firstOrNull { it.isNotBlank() } ?: return@forEach
            val id = dataIdPattern.find(raw)?.groupValues?.get(1) ?: return@forEach
            found.putIfAbsent(id, EpisodeItem(el.text().trim().ifBlank { id }, id))
        }

        doc.select("script[src*='/base/']").forEach { script ->
            val id = script.absUrl("src").trimEnd('/').substringAfterLast('/')
            if (id.length in 6..20) {
                found.putIfAbsent(id, EpisodeItem("ตอน ${found.size + 1}", id))
            }
        }
        return found.values.toList()
    }

    fun resolveQualities(watchId: String): List<Quality> {
        val watchUrl = "$BASE/watch/$watchId.html"
        val watchHtml = fetch(watchUrl)
        val doc = Jsoup.parse(watchHtml, BASE)

        val candidates = linkedSetOf<String>()
        doc.select("iframe[src]").forEach { frame ->
            val src = frame.absUrl("src").trim()
            if (src.isNotEmpty() && playerHints.any { it in src }) candidates += src
        }
        playbackPattern.findAll(watchHtml).forEach { candidates += it.value }
        marimoPattern.findAll(watchHtml).forEach { candidates += it.value }
        abyssPattern.findAll(watchHtml).forEach { candidates += it.value }
        doc.select("script[src*='/base/']").forEach { script ->
            val id = script.absUrl("src").trimEnd('/').substringAfterLast('/')
            if (id.length in 5..24) {
                candidates += "$PLAYER_HOST/playback/f/$id/"
                candidates += "$PLAYER_HOST/playback/y/$id/"
            }
        }
        if (candidates.isEmpty()) {
            candidates += "$PLAYER_HOST/playback/f/$watchId/"
            candidates += "$PLAYER_HOST/playback/y/$watchId/"
        }

        val (playerUrl, playerHtml) = candidates.firstNotNullOfOrNull { url ->
            runCatching { url to fetch(url, referer = watchUrl) }.getOrNull()
        } ?: error("เครื่องเล่นของตอนนี้เข้าไม่ได้ ($watchId)")

        val videoId = abyssVideoPattern.find(playerHtml)?.groupValues?.get(1)
            ?: marimoPattern.find(playerHtml)?.value?.let { marimo ->
                val marimoHtml = fetch(marimo, referer = playerUrl)
                abyssVideoPattern.find(marimoHtml)?.groupValues?.get(1)
                    ?: videoKeyPattern.find(marimoHtml)?.groupValues?.get(1)
            }
            ?: videoKeyPattern.find(playerHtml)?.groupValues?.get(1)
            ?: error("หา video id จากเครื่องเล่นไม่เจอ")

        val pageUrl = "$ABYSS_HOST/?v=$videoId"
        val page = fetch(pageUrl, referer = MARIMO_REFERER)

        val qualities = streamPattern.findAll(page).mapTo(mutableListOf<Quality>()) { match ->
            val url = match.groupValues[1]
            Quality(labelOf(url), url)
        }
        if (qualities.isEmpty()) {
            chunkPattern.find(page)?.let { qualities += Quality("360p+", pageUrl + it.value) }
        }
        if (qualities.isEmpty()) qualities += Quality("Auto", pageUrl)
        return qualities.distinctBy { it.url }
    }

    private fun labelOf(url: String): String = when {
        "1080" in url -> "1080p"
        "720" in url -> "720p"
        "480" in url -> "480p"
        "360" in url -> "360p"
        else -> "Source"
    }
}
