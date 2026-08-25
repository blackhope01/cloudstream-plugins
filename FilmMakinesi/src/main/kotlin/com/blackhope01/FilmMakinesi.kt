package com.blackhope01

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class FilmMakinesi : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ============================================================
    // ANA SAYFA KATEGORILERI
    // ============================================================
    override val mainPage = mainPageOf(
        "${mainUrl}/filmler-1/" to "Son Filmler",
        "${mainUrl}/yabanci-dizi-izle-1/" to "Son Diziler",
        "${mainUrl}/tur/aksiyon-fm1/film/" to "Aksiyon",
        "${mainUrl}/tur/korku-fm2/film/" to "Korku",
        "${mainUrl}/tur/bilim-kurgu-fm3/film/" to "Bilim Kurgu",
        "${mainUrl}/tur/komedi-fm1/film/" to "Komedi",
        "${mainUrl}/tur/gerilim-fm1/film/" to "Gerilim",
        "${mainUrl}/tur/macera-fm1/film/" to "Macera",
        "${mainUrl}/tur/fantastik-fm1/film/" to "Fantastik"
    )

    // ============================================================
    // ANA SAYFA - Pagination: /sayfa/2/
    // ============================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/sayfa/$page/"
        val document = app.get(url).document
        val items = document.select("a.item")
        val list = items.mapNotNull { it.toSearchResult() }
        val hasNext = document.select("ul.pagination li a[href]").isNotEmpty()
        return newHomePageResponse(request.name, list, hasNext = hasNext)
    }

    // ============================================================
    // ARAMA - /arama/?s=query
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama/?s=${query}").document
        return document.select("a.item").mapNotNull { it.toSearchResult() }
    }

    // ============================================================
    // ARAMA SONUCU DONUSTURME
    // ============================================================
    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.attr("data-title")
            .ifBlank { this.selectFirst(".item-footer .title")?.text()?.trim() }
            ?: return null
        val poster = fixUrlNull(this.selectFirst(".thumbnail-outer img")?.attr("src"))
        val year = this.selectFirst(".item-footer .info span")?.text()?.toIntOrNull()
        val score = this.attr("data-score").toFloatOrNull()
        val type = if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = score?.let { Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = score?.let { Score.from10(it) }
            }
        }
    }

    // ============================================================
    // FILM/DIZI DETAY
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // === BASLIK ===
        val title = document.selectFirst("h1.title")?.ownText()?.trim()
            ?.removeSuffix(" izle")
            ?: return null

        // === POSTER ===
        val poster = fixUrlNull(document.selectFirst("#info--box .cover img")?.attr("src"))

        // === YIL ===
        val year = document.selectFirst("h1.title .date a")?.text()?.toIntOrNull()

        // === IMDB ===
        val imdbText = document.selectFirst(".imdb b")?.text()
        val rating = imdbText?.toFloatOrNull()

        // === SURE ===
        val durationText = document.selectFirst(".time")?.text()
        val duration = durationText?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        // === TURLER ===
        val tags = document.select("#info--box .content .type a").map { it.text().trim() }

        // === ACIKLAMA ===
        val description = document.selectFirst(".info-description")?.text()?.trim()

        // === YONETMEN ===
        val director = document.selectFirst(".director a")?.text()?.trim()

        // === OYUNCULAR ===
        val actors = document.select("#cast .cast").mapNotNull { cast ->
            val actorName = cast.selectFirst(".cast-name")?.text()?.trim()
            val actorImg = fixUrlNull(cast.selectFirst("img")?.attr("src"))
            actorName?.let { Actor(it, actorImg) }
        }

        // === FRAGMAN ===
        val trailer = document.selectFirst(".trailer-button")?.attr("data-video_url") ?: ""

        // === ONERILER ===
        val recommendations = document.select(".related a.item").mapNotNull { it.toSearchResult() }

        val isSeries = url.contains("/dizi/")

        return if (isSeries) {
            // DIZI - Bolumleri topla
            val episodes = mutableListOf<Episode>()
            document.select("a.item-ep").forEach { ep ->
                val epHref = fixUrlNull(ep.attr("href")) ?: return@forEach

                // Bolum adi: "Freedom Day"
                val epName = ep.selectFirst(".ep-details")?.text()?.trim() ?: ""

                // Sezon/Bolum metni: "1. Sezon / 1. Bolum"
                val epTitleText = ep.selectFirst(".ep-title")?.text()?.trim() ?: ""

                // Sezon ve bolum numarasini .ep-title'dan cikar
                val seasonMatch = Regex("""(\d+)\.\s*Sezon""").find(epTitleText)
                val epMatch = Regex("""(\d+)\.\s*Bölüm""").find(epTitleText)

                // Fallback: URL'den de cikarabilir: /sezon-X/bolum-Y/
                val seasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""/sezon-(\d+)/""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1

                val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""/bolum-(\d+)/""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@forEach

                episodes.add(newEpisode(epHref) {
                    this.name = epName.ifBlank { "$seasonNum. Sezon $epNum. Bölüm" }
                    this.season = seasonNum
                    this.episode = epNum
                    this.description = epTitleText
                    this.posterUrl = poster  // <-- HER BOLUMUNE ANA POSTER ATANDI
                })
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = rating?.let { Score.from10(it) }
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                if (trailer.isNotBlank()) addTrailer(trailer)
            }
        } else {
            // FILM
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = rating?.let { Score.from10(it) }
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                if (trailer.isNotBlank()) addTrailer(trailer)
            }
        }
    }

    // ============================================================
    // VIDEO/LINK - Embed URL Bulma
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks çağrıldı, data: $data")

        val document = app.get(data, referer = mainUrl).document
        Log.d(name, "Sayfa yüklendi")

        val videoParts = document.select(".video-parts a[data-video_url]")
        Log.d(name, "Video part sayısı: ${videoParts.size}")

        if (videoParts.isNotEmpty()) {
            videoParts.forEachIndexed { index, part ->
                val embedUrl = part.attr("data-video_url")
                val label = part.text().trim()
                Log.d(name, "Part #$index - label: '$label', url: '$embedUrl'")
                if (embedUrl.isNotBlank()) {
                    loadExtractor(embedUrl, data, subtitleCallback, callback)
                }
            }
        } else {
            val iframeSrc = document.selectFirst(".after-player iframe")?.attr("data-src")
            Log.d(name, "Fallback iframe: $iframeSrc")
            if (iframeSrc != null) {
                loadExtractor(iframeSrc, data, subtitleCallback, callback)
            }
        }

        Log.d(name, "loadLinks tamamlandı")
        return true
    }
}