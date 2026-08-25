package com.blackhope01

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class LoveFilm : MainAPI() {
    override var mainUrl = "https://lovefilmizle.net"
    override var name = "LoveFilm"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Filmler",
        "$mainUrl/yerli-film/" to "Yerli Filmler",
        "$mainUrl/turkce-dublaj/" to "Türkçe Dublaj Filmler",
        "$mainUrl/turkce-altyazili/" to "Türkçe Altyazılı Filmler",
        "$mainUrl/yabanci-dizi-izle/" to "Yabancı Diziler",
        "$mainUrl/netflix-dizileri/" to "Netflix Dizileri",
        "$mainUrl/boxset-filmler-3/" to "Seri Filmler",
        "$mainUrl/yapim/2026/" to "2026 Filmleri"
    )

    // ============================================================
    // ANA SAYFA / KATALOG
    // ============================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(name, "getMainPage - Sayfa: $page, Kategori: ${request.name}")

        val pageUrl = if (page == 1) {
            request.data
        } else {
            request.data.trimEnd('/') + "/page/$page/"
        }

        val document = app.get(pageUrl).document
        val home = document.select("div.poster").mapNotNull { it.toSearchResult() }
        val hasNext = document.select(".wp-pagenavi a.nextpostslink").isNotEmpty()

        Log.d(name, "getMainPage - ${home.size} içerik bulundu, hasNext=$hasNext")
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        val title = this.selectFirst(".title")?.text()?.trim()
            ?: link.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val poster = fixUrlNull(
            this.selectFirst("img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src")
        )

        val imdbText = this.selectFirst(".poster-imdb")?.text()?.trim()
        val imdbScore = imdbText?.let {
            Regex("""([0-9]+(?:[.,][0-9]+)?)""").find(it)?.groupValues?.get(1)
                ?.replace(',', '.')?.toFloatOrNull()
        }

        val year = this.selectFirst(".icon-year")?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(this.text())?.value?.toIntOrNull()

        val langText = this.selectFirst(".poster-lang")?.text()?.trim() ?: ""
        val isSeries = langText.contains("Yabancı Dizi", ignoreCase = true)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                if (imdbScore != null) this.score = Score.from10(imdbScore)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                if (imdbScore != null) this.score = Score.from10(imdbScore)
            }
        }
    }

    // ============================================================
    // ARAMA
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(name, "search - Sorgu: $query")
        return try {
            val document = app.get("$mainUrl/?s=$query").document
            val results = document.select("div.poster").mapNotNull { it.toSearchResult() }
            Log.d(name, "search - ${results.size} sonuç bulundu")
            results
        } catch (e: Exception) {
            Log.e(name, "search hatası: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ============================================================
    // DETAY
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load başladı - URL: $url")

        val document = app.get(url).document

        val title = document.selectFirst("h1.movie-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
        if (title.isNullOrBlank()) {
            Log.e(name, "load - Başlık bulunamadı")
            return null
        }

        val poster = fixUrlNull(
            document.selectFirst(".block-poster-left img")?.attr("data-src")
                ?: document.selectFirst(".block-poster-left img")?.attr("src")
                ?: document.selectFirst(".poster img")?.attr("data-src")
                ?: document.selectFirst(".poster img")?.attr("src")
        )

        val description = document.selectFirst(".block-post")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val year = document.select("div.block-item:contains(Yıl) a")
            .firstOrNull()?.text()?.trim()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()

        val imdbText = document.select("div.block-item:contains(IMDB Puanı)")
            .firstOrNull()?.text()?.trim()
        val rating = imdbText?.let {
            Regex("""([0-9]+(?:[.,][0-9]+)?)""").find(it)?.groupValues?.get(1)
                ?.replace(',', '.')?.toFloatOrNull()
        }

        val genres = document.select("div.block-item:contains(Kategori) a")
            .map { it.text().trim() }.distinct()

        val actorElements = document.select("div.block-item:contains(Oyuncular) a")
        val actors: List<Pair<Actor, String?>> = actorElements.map { a ->
            Pair(Actor(a.text().trim(), null), null)
        }

        val duration = document.select("div.block-item:contains(Süre)")
            .firstOrNull()?.text()?.let {
                Regex("""(\d+)\s*dakika""").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }

        val trailerRaw = document.selectFirst(".btn.btn-black a[href*='youtube']")?.attr("href")
            ?: document.selectFirst("a[href*='youtube']")?.attr("href")
            ?: document.selectFirst("a[href*='youtu.be']")?.attr("href")
        val trailer = when {
            trailerRaw.isNullOrBlank() -> ""
            trailerRaw.contains("youtu.be/") -> {
                val videoId = trailerRaw.substringAfterLast("/").substringBefore("?")
                "https://www.youtube.com/watch?v=$videoId"
            }
            trailerRaw.contains("youtube.com/watch?v=") -> trailerRaw
            else -> trailerRaw
        }

        // Dizi mi film mi?
        val parts = document.select("ul.hdc-parts li a")
        val isSeries = parts.text().contains("Bölüm") ||
                document.select(".poster-lang").text().contains("Yabancı Dizi")

        if (isSeries) {
            val episodeList = mutableListOf<Episode>()
            val seasonNumber = Regex("""(\d+)\.?\s*Sezon""").find(title)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""sezon-(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1

            parts.forEach { part ->
                val epHref = fixUrlNull(part.attr("href")) ?: return@forEach
                val epText = part.text().trim()
                val epNumber = Regex("""(\d+)\.?\s*Bölüm""").find(epText)?.groupValues?.get(1)?.toIntOrNull()

                if (epNumber != null) {
                    episodeList.add(
                        newEpisode(epHref) {
                            this.name = epText
                            this.season = seasonNumber
                            this.episode = epNumber
                        }
                    )
                }
            }

            val sortedEpisodes = episodeList.distinctBy { "${it.season}-${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = genres
                if (rating != null) this.score = Score.from10(rating)
                if (duration != null) this.duration = duration
                addActors(actors)
                if (trailer.isNotBlank()) addTrailer(trailer)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = genres
                if (rating != null) this.score = Score.from10(rating)
                if (duration != null) this.duration = duration
                addActors(actors)
                if (trailer.isNotBlank()) addTrailer(trailer)
            }
        }
    }

    // ============================================================
    // LINKLER
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks başladı - Data: $data")

        val document = app.get(data).document

        // Alternatif part linkleri
        val partLinks = document.select("ul.hdc-parts li a")
            .mapNotNull { fixUrlNull(it.attr("href")) }
            .distinct()
        Log.d(name, "Part linkleri: ${partLinks.size}")

        suspend fun extractFromDoc(doc: org.jsoup.nodes.Document, sourceUrl: String): String? {
            // 1) Önce iframe src / data-src kontrolü
            doc.select("iframe").forEach { iframe ->
                val raw = iframe.attr("data-src").ifBlank { iframe.attr("src") }
                Log.d(name, "Iframe raw: $raw")

                if (raw.isNotBlank() && raw != "about:blank") {
                    val src = if (raw.startsWith("//")) "https:$raw" else raw
                    Log.d(name, "Iframe src: $src")

                    // bemoly.php?url= varsa linki çıkar
                    if (src.contains("bemoly.php?url=")) {
                        val decoded = java.net.URLDecoder.decode(
                            src.substringAfter("url=").substringBefore("&"),
                            "UTF-8"
                        )
                        if (decoded.isNotBlank()) return decoded
                    }

                    // Değilse doğrudan src'yi embed olarak döndür
                    return src
                }
            }

            // 2) iframe'de embed bulunamadıysa, tüm HTML'i tara
            val html = doc.html()

            // bemoly linki
            Regex("""bemoly\.php\?url=([^&"']+)""").find(html)?.let {
                val decoded = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8")
                if (decoded.isNotBlank()) {
                    Log.d(name, "bemoly embed URL: $decoded")
                    return decoded
                }
            }

            // ok.ru / odnoklassniki video embed
            Regex("""//(?:ok\.ru|odnoklassniki\.ru)/videoembed/\d+[^"'\s]*""").find(html)?.let {
                Log.d(name, "ok/odnoklassniki embed URL: ${it.value}")
                return "https:${it.value}"
            }

            // vidmoly embed
            Regex("""https?://vidmoly\.(?:net|biz)/embed-[a-zA-Z0-9]+\.html""").find(html)?.let {
                Log.d(name, "vidmoly embed URL: ${it.value}")
                return it.value
            }

            // vk video embed
            Regex("""//vk\.com/video_ext\.php\?[^"'\s]+""").find(html)?.let {
                Log.d(name, "vk embed URL: ${it.value}")
                return "https:${it.value}"
            }

            // doğrudan .m3u8 linki
            Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").find(html)?.let {
                Log.d(name, "m3u8 URL: ${it.value}")
                return it.value
            }

            Log.e(name, "Embed URL bulunamadı: $sourceUrl")
            return null
        }

        // Part linkleri varsa sırayla dene
        if (partLinks.isNotEmpty()) {
            partLinks.forEach { partUrl ->
                try {
                    Log.d(name, "Part işleniyor: $partUrl")
                    val partDoc = app.get(partUrl).document
                    val embedUrl = extractFromDoc(partDoc, partUrl)
                    if (!embedUrl.isNullOrBlank()) {
                        Log.d(name, "Embed bulundu, extractor çağrılıyor: $embedUrl")
                        loadExtractor(embedUrl, partUrl, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.e(name, "Part çözümleme hatası: $partUrl - ${e.message}")
                }
            }
            return true
        }

        // Tekil film sayfası
        val embedUrl = extractFromDoc(document, data)
        if (!embedUrl.isNullOrBlank()) {
            Log.d(name, "Embed bulundu, extractor çağrılıyor: $embedUrl")
            loadExtractor(embedUrl, data, subtitleCallback, callback)
        }

        return true
    }
}