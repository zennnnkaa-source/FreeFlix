package com.fairystream.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

data class SeriesItem(val title: String, val slug: String, val thumb: String?)

data class EpisodeItem(val title: String, val watchId: String)

data class Quality(val label: String, val url: String)

object FairyApi {
    const val BASE = "https://fairyanime.net"

    private const val PLAYER_HOST = "https://streaming.tonytonychopper.com"
    private const val ANIME_HOST = "https://anime.tonytonychopper.net"
    private const val ABYSS_HOST = "https://abysscdn.com"
    private const val MARIMO_REFERER = "https://player.marimo.me/"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val reservedSegments = setOf(
        "category", "member", "search", "scoretop", "genre", "blog",
        "page", "tag", "author", "feed", "wp-json", "xmlrpc"
    )
    private val playerHints = listOf("tonytonychopper", "playback", "marimo", "abysscdn", "anime.tonytonychopper")

    // ลิงก์ตอนบนเว็บเป็น /watch/<id>/ (ไม่มี .html) จึงรองรับทั้งสองแบบ
    private val watchPathPattern = Regex("""/watch/([A-Za-z0-9_-]+)(?:\.html)?/?""")
    // playback id อยู่ในสคริปต์ /base/<watchId>/ เช่น playback/f/<id>/ ซึ่งไม่จำเป็นต้องเท่ากับ id ของหน้า watch
    private val basePlaybackPattern = Regex("""playback/[a-z]/([A-Za-z0-9_-]+)/?""")
    private val animeVidPattern = Regex("""anime\.tonytonychopper\.net/v2/([A-Za-z0-9_-]+)""")
    private val mediaUrlPattern = Regex(
        """https?://[^\s"'<>]+?\.(?:mp4|m3u8)[^\s"'<>]*""",
        RegexOption.IGNORE_CASE
    )
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

            // การ์ดหนึ่งใบมีลิงก์ ico-play ที่ไม่มีข้อความ ชื่อจริงอยู่ใน h3 และปกอยู่ทั้งการ์ด จึงต้องดูที่ทั้งการ์ด
            val card: Element? = anchor.parents().firstOrNull { it.hasClass("latest-movie-img-container") }
                ?: anchor.parent()
            val title = card?.selectFirst("h3 a")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: anchor.text().trim().takeIf { it.isNotEmpty() }
                ?: card?.select("img")?.map { it.attr("alt").trim() }
                    ?.firstOrNull { it.isNotEmpty() && !it.equals("play", ignoreCase = true) }
                ?: path.replace('-', ' ')
            val thumb = card?.let { posterOf(it) }

            val existing = found[path]
            if (existing == null || (existing.thumb == null && thumb != null)) {
                found[path] = SeriesItem(title, path, thumb)
            }
        }
        return found.values.toList()
    }

    // ปกจริงอยู่ใน data-src (lazy load) ส่วน src เป็นรูป placeholder ที่ /assets/
    private fun posterOf(card: Element): String? =
        card.select("img").map { img ->
            img.absUrl("data-src").ifEmpty { img.absUrl("src") }
        }.firstOrNull { it.isNotEmpty() && !it.startsWith("data:") && "/assets/" !in it }

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
        val watchUrl = "$BASE/watch/$watchId/"
        val watchHtml = fetch(watchUrl)
        val doc = Jsoup.parse(watchHtml, BASE)

        // iframe ของเครื่องเล่นถูกสร้างด้วย JS จากสคริปต์ base จึงต้องโหลดสคริปต์นั้นมาอ่าน playback id
        val baseUrl = doc.select("script[src*='/base/']").firstOrNull()?.absUrl("src")
            ?.takeIf { it.isNotEmpty() }
            ?: "$BASE/base/$watchId/"
        val baseJs = runCatching { fetch(baseUrl, referer = watchUrl) }.getOrDefault("")
        val playbackIds = basePlaybackPattern.findAll(baseJs).map { it.groupValues[1] }.distinct().toList()

        val candidates = linkedSetOf<String>()
        doc.select("iframe[src]").forEach { frame ->
            val src = frame.absUrl("src").trim()
            if (src.isNotEmpty() && playerHints.any { it in src }) candidates += src
        }
        playbackIds.forEach { pid ->
            candidates += "$PLAYER_HOST/playback/f/$pid/"
            candidates += "$PLAYER_HOST/playback/v/$pid/"
        }
        playbackPattern.findAll(watchHtml).forEach { candidates += it.value }
        marimoPattern.findAll(watchHtml).forEach { candidates += it.value }
        abyssPattern.findAll(watchHtml).forEach { candidates += it.value }
        if (candidates.isEmpty()) {
            candidates += "$PLAYER_HOST/playback/f/$watchId/"
            candidates += "$PLAYER_HOST/playback/v/$watchId/"
        }

        return candidates.asSequence()
            .mapNotNull { url ->
                runCatching { extractQualities(url, watchUrl) }.getOrNull()?.takeIf { it.isNotEmpty() }
            }
            .firstOrNull()
            ?: error("เครื่องเล่นของตอนนี้เข้าไม่ได้ ($watchId)")
    }

    private fun extractQualities(playerUrl: String, referer: String): List<Quality> {
        val html = fetch(playerUrl, referer = referer)

        // ไฟล์ตรงที่อยู่ในคอนฟิกเครื่องเล่น เช่น file: "https://...mp4"
        val direct = streamPattern.findAll(html)
            .map { Quality(labelOf(it.groupValues[1]), it.groupValues[1]) }
            .distinctBy { it.url }
            .toList()
        if (direct.isNotEmpty()) return direct

        animeVidPattern.find(html)?.groupValues?.get(1)?.let { vid ->
            val anime = animeQualities(vid, playerUrl)
            if (anime.isNotEmpty()) return anime
        }

        val videoId = abyssVideoPattern.find(html)?.groupValues?.get(1)
            ?: marimoPattern.find(html)?.value?.let { marimo ->
                val marimoHtml = fetch(marimo, referer = playerUrl)
                abyssVideoPattern.find(marimoHtml)?.groupValues?.get(1)
                    ?: videoKeyPattern.find(marimoHtml)?.groupValues?.get(1)
            }
            ?: videoKeyPattern.find(html)?.groupValues?.get(1)
            ?: return emptyList()

        val pageUrl = "$ABYSS_HOST/?v=$videoId"
        val page = fetch(pageUrl, referer = MARIMO_REFERER)
        val qualities = streamPattern.findAll(page)
            .map { Quality(labelOf(it.groupValues[1]), it.groupValues[1]) }
            .distinctBy { it.url }
            .toMutableList()
        if (qualities.isEmpty()) {
            chunkPattern.find(page)?.let { qualities.add(Quality("360p+", pageUrl + it.value)) }
        }
        return qualities
    }

    // endpoint ของ anime.tonytonychopper.net: quality2/<vid>/<q>/ และ file2/<vid>/ ตอบเป็น text/plain
    // รูปแบบ body ยังไม่ได้ยืนยัน (HAR ไม่มี body) จึงรับได้ทั้ง URL เปล่าและ URL ที่ฝังอยู่ในข้อความ
    private fun animeQualities(vid: String, referer: String): List<Quality> {
        val found = mutableListOf<Quality>()
        for (q in listOf("1080", "720", "480", "360")) {
            val body = runCatching { fetch("$ANIME_HOST/quality2/$vid/$q/", referer = referer) }.getOrNull()
                ?: continue
            mediaUrlOf(body)?.let { found.add(Quality("${q}p", it)) }
        }
        if (found.isEmpty()) {
            val body = runCatching { fetch("$ANIME_HOST/file2/$vid/", referer = referer) }.getOrNull()
            val url = body?.let { mediaUrlOf(it) }
            if (url != null) found.add(Quality("Auto", url))
        }
        return found.distinctBy { it.url }
    }

    private fun mediaUrlOf(body: String): String? {
        val text = body.trim()
        if (text.startsWith("http") && !text.contains(' ') && !text.contains('\n')) return text
        return mediaUrlPattern.find(text)?.value
    }

    private fun labelOf(url: String): String = when {
        "1080" in url -> "1080p"
        "720" in url -> "720p"
        "480" in url -> "480p"
        "360" in url -> "360p"
        else -> "Source"
    }
}
