package com.blackhope01

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class FilmHane : MainAPI() {
    override var mainUrl = "https://www.filmhane.shop"
    override var name = "FilmHane"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler?page=1&sort=new" to "Yeni Filmler",
        "${mainUrl}/filmler?page=1&sort=views" to "Popüler Filmler",
        "${mainUrl}/filmler?page=1&sort=new&cat=aksiyon" to "Aksiyon Filmler",
        "${mainUrl}/filmler?page=1&sort=new&cat=bilim-kurgu" to "Bilim Kurgu Filmler",
        "${mainUrl}/diziler?cat=bilim-kurgu-fantazi" to "Bilim Kurgu & Fantazi Diziler",
        "${mainUrl}/diziler?cat=aksiyon-macera" to "Aksiyon & Macera Diziler",
        "${mainUrl}/diziler?cat=gizem" to "Gizem Dizileri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(name, "getMainPage - Sayfa: $page, Kategori: ${request.name}")
        val url = request.data.replace("page=1", "page=$page")
        val document = app.get(url).document
        val home = document.select(".hp-grid > a.card, .grid > a.card").mapNotNull { it.toSearchResult() }
        Log.d(name, "getMainPage - ${home.size} içerik bulundu")
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst(".meta .title")?.text()?.trim()
            ?: this.selectFirst(".title")?.text()?.trim()
            ?: return null
        val img = this.selectFirst(".cover img, img")
        val poster = fixUrlNull(img?.attr("src"))
        val catsText = this.selectFirst(".meta .cats, .cats")?.text()?.trim() ?: ""
        val year = Regex("""\b(19|20)\d{2}\b""").find(catsText)?.value?.toIntOrNull()
        val imdbText = this.selectFirst(".imdb-badge")?.text()?.trim()
        val imdbScore = imdbText?.toFloatOrNull()

        val isSeries = href.contains("/dizi/") || this.selectFirst(".type-tag.series") != null
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = imdbScore?.let { Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = imdbScore?.let { Score.from10(it) }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(name, "search başladı - Sorgu: $query")
        return try {
            val document = app.get("${mainUrl}/arama/?q=${query}").document
            val home = document.select(".grid > a.card, .hp-grid > a.card").mapNotNull { it.toSearchResult() }
            Log.d(name, "search tamamlandı - ${home.size} sonuç bulundu")
            home
        } catch (e: Exception) {
            Log.e(name, "search hatası: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        Log.d(name, "quickSearch başladı - Sorgu: $query")
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load başladı - URL: $url")
        val document = app.get(url).document

        val isSeries = url.contains("/dizi/")

        val title = document.selectFirst(".movie-details-box h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(document.selectFirst(".player-poster img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst(".sidebar-box img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        val imdbText = document.selectFirst(".movie-details-tags .tag.imdb")?.text()?.trim()
        val imdbScore = imdbText?.toFloatOrNull()

        val year = document.select(".movie-details-tags span, .movie-details-tags a").mapNotNull {
            Regex("""\b(19|20)\d{2}\b""").find(it.text())?.value?.toIntOrNull()
        }.firstOrNull()

        val plot = document.selectFirst(".movie-desc")?.text()?.trim()

        val tags = document.select(".movie-details-tags a[href^='/kategori/']").map { it.text().trim() }.filter { it.isNotEmpty() }

        val duration = document.selectFirst(".sidebar-box li:contains(Süre:) strong")?.text()?.trim()?.toIntOrNull()

        val actors = document.select(".movie-cast a, .cast-list a").mapNotNull { a ->
            val actorName = a.text().trim()
            if (actorName.isBlank()) null else Actor(actorName, null)
        }

        val recommendations = document.select(".player-main .hp-grid > a.card, .player-main .grid > a.card").mapNotNull { item ->
            val recName = item.selectFirst(".meta .title")?.text()?.trim()
                ?: item.selectFirst(".title")?.text()?.trim()
                ?: item.selectFirst("h4")?.text()?.trim()
                ?: return@mapNotNull null

            val recHref = fixUrlNull(item.attr("href")) ?: return@mapNotNull null

            val recPosterUrl = fixUrlNull(item.selectFirst("img")?.attr("src"))
                ?: fixUrlNull(item.selectFirst("img")?.attr("data-src"))

            val recIsSeries = recHref.contains("/dizi/")
            if (recIsSeries) {
                newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                    this.posterUrl = recPosterUrl
                }
            } else {
                newMovieSearchResponse(recName, recHref, TvType.Movie) {
                    this.posterUrl = recPosterUrl
                }
            }
        }

        Log.d(name, "load tamamlandı - Başlık: $title, Yıl: $year, Dizi: $isSeries")

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()

            document.select(".ep-list").forEach { seasonList ->
                seasonList.select("a.ep-item").forEach { epItem ->
                    val epHref = fixUrlNull(epItem.attr("href")) ?: return@forEach
                    val seasonNum = Regex("""/sezon-(\d+)/bolum-""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val episodeNum = Regex("""/bolum-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                    val epTitle = epItem.selectFirst(".ep-title")?.text()?.trim() ?: "Bölüm $episodeNum"
                    val epThumb = fixUrlNull(epItem.selectFirst(".ep-thumb img")?.attr("src"))

                    val dateText = epItem.select(".ep-info div").map { it.text().trim() }.firstOrNull {
                        Regex("""\d{4}-\d{2}-\d{2}""").matches(it)
                    }
                    val dateLong = try {
                        dateText?.let {
                            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(it)?.time
                        }
                    } catch (_: Exception) { null }

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = episodeNum
                            this.posterUrl = epThumb
                            this.date = dateLong
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = imdbScore?.let { Score.from10(it) }
                this.duration = duration
                addActors(actors)
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = imdbScore?.let { Score.from10(it) }
                this.duration = duration
                addActors(actors)
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks başladı - Data: $data")

        val document = app.get(data).document
        var embedUrl: String? = null

        // 1. template#embedTpl
        embedUrl = document.selectFirst("template#embedTpl")?.let {
            Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(it.html())?.groupValues?.get(1)
        }

        // 2. Doğrudan iframe
        if (embedUrl.isNullOrBlank()) {
            embedUrl = document.selectFirst("iframe[src]")?.attr("src")
        }

        // 3. data attribute'ları
        if (embedUrl.isNullOrBlank()) {
            document.selectFirst("[data-src], [data-embed], [data-url]")?.let { el ->
                embedUrl = el.attr("data-src").ifBlank { null }
                    ?: el.attr("data-embed").ifBlank { null }
                            ?: el.attr("data-url").ifBlank { null }
            }
        }

        // 4. Script'lerde embed URL ara
        if (embedUrl.isNullOrBlank()) {
            val patterns = listOf(
                Regex("""src\s*[:=]\s*["']([^"']*(?:embed|iframe|player)[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""iframe\.src\s*=\s*["']([^"']+)["']"""),
                Regex("""url\s*[:=]\s*["'](https?://[^"']+)["']""")
            )
            document.select("script").forEach { script ->
                val text = script.data()
                for (pattern in patterns) {
                    val match = pattern.find(text)
                    if (match != null) {
                        embedUrl = match.groupValues[1]
                        return@forEach
                    }
                }
            }
        }

        if (embedUrl.isNullOrBlank()) {
            Log.e(name, "loadLinks - Embed URL bulunamadı")
            return false
        }

        // URL düzelt
        val fixedUrl = when {
            embedUrl.startsWith("//") -> "https:$embedUrl"
            embedUrl.startsWith("http") -> embedUrl
            else -> fixUrl(embedUrl)
        }

        Log.d(name, "loadLinks - Embed URL: $fixedUrl")

        return loadExtractor(fixedUrl, data, subtitleCallback, callback)
    }
}