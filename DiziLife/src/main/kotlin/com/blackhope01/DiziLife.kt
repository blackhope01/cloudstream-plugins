package com.blackhope01

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder


class DiziLife : MainAPI() {
    override var mainUrl = "https://dizi74.life"
    override var name = "Dizilife"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler?page=1" to "Filmler",
        "${mainUrl}/diziler?page=1" to "Diziler",
        "${mainUrl}/platform/netflix?series_page=1" to "Netflix",
        "${mainUrl}/platform/disney?series_page=1" to "Disney+",
        "${mainUrl}/platform/prime-video?series_page=1" to "Prime Video",
        "${mainUrl}/platform/hbo-max?series_page=1" to "HBO Max",
        "${mainUrl}/platform/apple-tv?series_page=1" to "Apple TV",
        "${mainUrl}/platform/tod?series_page=1" to "TOD",
        "${mainUrl}/platform/gain?series_page=1" to "GAİN",
        "${mainUrl}/platform/tabii?series_page=1" to "Tabii",
        "${mainUrl}/platform/blutv?series_page=1" to "BluTV",
        "${mainUrl}/platform/exxen?series_page=1" to "Exxen"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(name, "getMainPage - Sayfa: $page, Kategori: ${request.name}")

        // Platform sayfaları, ana listede sadece dizi içerikleri gösterilmesi için işaretlendi
        val isPlatform = request.data.contains("/platform/")

        val url = if (page > 1) {
            when {
                request.data.contains("series_page=") ->
                    request.data.replace(Regex("series_page=\\d+"), "series_page=$page")
                request.data.contains("movies_page=") ->
                    request.data.replace(Regex("movies_page=\\d+"), "movies_page=$page")
                request.data.contains("page=") ->
                    request.data.replace(Regex("page=\\d+"), "page=$page")
                else -> {
                    val separator = if (request.data.contains("?")) "&" else "?"
                    request.data + separator + "page=$page"
                }
            }
        } else {
            request.data
        }

        val document = app.get(url).document

        // Platform sayfalarında sadece dizi linklerini seç (filmler karışmasın)
        val selector = if (isPlatform) {
            "a[href*='/dizi/']"
        } else {
            "a[href*='/dizi/'], a[href*='/film/']"
        }

        val home = document.select(selector)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        Log.d(name, "getMainPage - ${home.size} içerik bulundu")
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null

        // Bölüm ve sezon linklerini önerilerden / aramalardan çıkar
        if (Regex("""/(sezon|bolum)/\d+""").containsMatchIn(href)) return null

        val title = this.selectFirst(".font-display")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null

        val poster = fixUrlNull(this.selectFirst("img")?.attr("src"))

        val imdbScore = this.selectFirst(".text-gold")?.text()?.trim()?.toFloatOrNull()
        val year = Regex("""\b(19|20)\d{2}\b""").find(this.text())?.value?.toIntOrNull()

        val isSeries = href.contains("/dizi/")

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
            val encodedQuery = URLEncoder.encode(query, "utf-8")
            val document = app.get("${mainUrl}/ara?q=${encodedQuery}").document
            val results = document.select("a[href*='/dizi/'], a[href*='/film/']")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            Log.d(name, "search tamamlandı - ${results.size} sonuç bulundu")
            results
        } catch (e: Exception) {
            Log.e(name, "search hatası: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load başladı - URL: $url")
        val document = app.get(url).document

        val isSeries = url.contains("/dizi/")

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null

        // Poster öncelikli: detay sayfasındaki poster görseli
        val poster = fixUrlNull(document.selectFirst("img[alt*='posteri']")?.attr("src"))
            ?: fixUrlNull(document.selectFirst(".bg-card img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))

        // IMDb puanı: başlık bloğundaki .text-gold elementinin kendi metninden al
        val headerBlock = document.selectFirst("h1")?.parent()
        val imdbScore = headerBlock?.selectFirst(".text-gold")?.ownText()?.trim()?.toFloatOrNull()

        // Yıl: başlık bloğundaki ilk .text-text-dim metninden
        val year = headerBlock?.selectFirst(".text-text-dim")?.text()?.let { text ->
            Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
        }

        val plot = document.selectFirst("p.text-text-dim")?.text()?.trim()

        // Türler: sayfadaki /tur/ bağlantılarından
        val tags = document.select("a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // Toplam süre (varsa)
        val totalDuration = Regex("""(\d+)\s*dk""")
            .find(document.text())
            ?.groupValues?.get(1)?.toIntOrNull()

        // Öneriler: yalnızca medya kartları (bölüm linkleri hariç)
        val recommendations = document.select("a[href*='/dizi/'], a[href*='/film/']")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        Log.d(name, "load tamamlandı - Başlık: $title, Yıl: $year, IMDb: $imdbScore, Dizi: $isSeries")

        return if (isSeries) {
            // Yalnızca içinde img bulunan bölüm linklerini işle (ana liste)
            val episodeElements = document.select("a[href*='/sezon/'][href*='/bolum/']")
                .filter { it.selectFirst("img") != null }

            val episodes = mutableListOf<Episode>()

            for (a in episodeElements) {
                val href = fixUrlNull(a.attr("href")) ?: continue
                val regex = Regex("""/sezon/(\d+)/bolum/(\d+)""")
                val match = regex.find(href) ?: continue
                val seasonNum = match.groupValues[1].toIntOrNull() ?: continue
                val episodeNum = match.groupValues[2].toIntOrNull() ?: continue

                val episodeTitle = a.selectFirst(".font-display")?.text()?.trim()
                    ?: a.selectFirst("img")?.attr("alt")?.trim()
                    ?: "Bölüm $episodeNum"

                val thumb = fixUrlNull(a.selectFirst("img")?.attr("src"))
                val episodeDescription = a.selectFirst("p")?.text()?.trim()

                episodes.add(
                    newEpisode(href) {
                        this.name = episodeTitle
                        this.season = seasonNum
                        this.episode = episodeNum
                        this.posterUrl = thumb
                        this.description = episodeDescription
                    }
                )
            }

            val uniqueEpisodes = episodes.distinctBy { it.data }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniqueEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags.toList()
                this.score = imdbScore?.let { Score.from10(it) }
                this.duration = totalDuration
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags.toList()
                this.score = imdbScore?.let { Score.from10(it) }
                this.duration = totalDuration
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
        val iframeSrc = document.selectFirst("iframe[src]")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            Log.e(name, "loadLinks - iframe URL bulunamadı")
            return false
        }

        // ID'yi çıkar: /player/{ID} kısmını yakala
        val videoId = Regex("""/player/([^/?]+)""").find(iframeSrc)?.groupValues?.get(1)
            ?: iframeSrc.substringAfterLast("/")

        if (videoId.isNullOrBlank()) {
            Log.e(name, "loadLinks - video ID çıkarılamadı")
            return false
        }

        val m3u8Url = "https://one.makesomthingup.click/$videoId/master.m3u8"
        Log.d(name, "loadLinks - m3u8 URL: $m3u8Url")

        // Doğrudan oynatılabilir HLS linki oluştur ve callback'e gönder
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = m3u8Url,
            ) {
                this.referer = iframeSrc
                this.type    = ExtractorLinkType.M3U8
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }
}