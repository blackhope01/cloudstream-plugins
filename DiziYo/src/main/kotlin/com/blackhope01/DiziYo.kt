package com.blackhope01

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class DiziYo : MainAPI() {
    override var mainUrl = "https://www.diziyo.so"
    override var name = "DiziYo"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmler/" to "Filmler",
        "$mainUrl/diziler/" to "Diziler",
        "$mainUrl/diziler/?sirala=populer" to "Popüler Diziler",
        "$mainUrl/filmler/dil/yerli/" to "Yerli Filmler",
        "$mainUrl/filmler/?tur=komedi&sirala=populer" to "Komedi Filmler"
    )

    // ============================================================
    // ANA SAYFA / KATALOG
    // ============================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(name, "getMainPage - Sayfa: $page, Kategori: ${request.name}")

        val pageUrl = if (page == 1) {
            request.data
        } else {
            if (request.data.contains("?")) {
                "${request.data}&sayfa=$page"
            } else {
                "${request.data}?sayfa=$page"
            }
        }


        val document = app.get(pageUrl).document
        val elements = document.select("article.dzy-title-card, a.dzy-episode-card")
        val home = elements.mapNotNull { it.toSearchResult() }
        val hasNext = document.select("a.dzy-site-pager__arrow[rel=next]").isNotEmpty()

        Log.d(name, "getMainPage - ${home.size} içerik bulundu, hasNext=$hasNext")
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Katalog kartı (.dzy-title-card)
        val titleLink = this.selectFirst("a.dzy-title-card__link")
        if (titleLink != null) {
            val href = fixUrlNull(titleLink.attr("href")) ?: return null
            val title = titleLink.selectFirst(".dzy-title-card__name")?.text()?.trim()
                ?: titleLink.selectFirst("strong")?.text()?.trim()
                ?: return null

            val poster = fixUrlNull(titleLink.selectFirst(".dzy-title-card__poster img")?.attr("src"))
            val imdbText = titleLink.selectFirst(".dzy-title-card__imdb")?.text()?.trim()
            val imdbScore = imdbText?.let {
                Regex("""([0-9]+(?:[.,][0-9]+)?)""").find(it)?.groupValues?.get(1)
                    ?.replace(',', '.')?.toFloatOrNull()
            }

            val facts = titleLink.select(".dzy-title-card__facts b")
            val year = facts.firstOrNull()?.text()?.trim()?.toIntOrNull()
                ?: Regex("""\b(19|20)\d{2}\b""").find(titleLink.select(".dzy-title-card__facts").text())
                    ?.value?.toIntOrNull()

            val type = titleLink.selectFirst(".dzy-title-card__type")?.text()?.trim() ?: ""

            return when {
                type.contains("Film", ignoreCase = true) -> {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                        if (imdbScore != null) this.score = Score.from10(imdbScore)
                    }
                }
                type.contains("Dizi", ignoreCase = true) || type.contains("Anime", ignoreCase = true) -> {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                        if (imdbScore != null) this.score = Score.from10(imdbScore)
                    }
                }
                else -> {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                        if (imdbScore != null) this.score = Score.from10(imdbScore)
                    }
                }
            }
        }

        // Bölüm kartı (.dzy-episode-card)
        val episodeLink = this.selectFirst("a.dzy-episode-card")
        if (episodeLink != null) {
            val href = fixUrlNull(episodeLink.attr("href")) ?: return null
            val title = episodeLink.selectFirst(".dzy-episode-card__overlay strong")?.text()?.trim()
                ?: return null
            val poster = episodeLink.selectFirst(".dzy-episode-card__image")?.attr("style")
                ?.let { Regex("""background-image:url\('([^']+)'\)""").find(it)?.groupValues?.get(1) }
                ?.let { fixUrlNull(it) }

            return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }

        return null
    }

    // ============================================================
    // ARAMA
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(name, "search başladı - Sorgu: $query")
        return try {
            val document = app.get("$mainUrl/arama/?q=$query").document
            val results = document.select("article.dzy-title-card").mapNotNull { it.toSearchResult() }
            Log.d(name, "search - ${results.size} sonuç bulundu")
            results
        } catch (e: Exception) {
            Log.e(name, "search hatası: ${e.message}")
            emptyList()
        }
    }

    // ============================================================
    // DETAY
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load başladı - URL: $url")

        val document = app.get(url).document

        val title = document.selectFirst(".dzy-detail__heading h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
        if (title.isNullOrBlank()) {
            Log.e(name, "load - Başlık bulunamadı")
            return null
        }

        val poster = fixUrlNull(document.selectFirst(".dzy-detail__poster img")?.attr("src"))

        val description = document.selectFirst(".dzy-summary__text")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val year = document.selectFirst(".dzy-detail__fact--year")?.text()?.trim()?.toIntOrNull()

        val imdbText = document.selectFirst(".dzy-detail__fact--rating")?.text()?.trim()
        val rating = imdbText?.let {
            Regex("""([0-9]+(?:[.,][0-9]+)?)""").find(it)?.groupValues?.get(1)
                ?.replace(',', '.')?.toFloatOrNull()
        }

        val genres = document.select(".dzy-detail__fact--genre").map { it.text().trim() }.distinct()
        val countries = document.select(".dzy-detail__fact--country").map { it.text().trim() }.distinct()
        val tags = (genres + countries).distinct()

        val runtimeText = document.selectFirst(".dzy-detail__fact--runtime")?.text()?.trim()
        val duration = runtimeText?.let { Regex("""(\d+)\s*dk""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        val actors: List<Pair<Actor, String?>> = document.select(".cast-card").mapNotNull { card ->
            val name = card.selectFirst(".actor-name")?.text()?.trim() ?: return@mapNotNull null
            val image = fixUrlNull(card.selectFirst(".actor-ph img")?.attr("src"))
            val role = card.selectFirst(".actor-role")?.text()?.trim()
            Pair(Actor(name, image), role)
        }

        // Durum bilgisi (Sona Erdi / Devam Ediyor)
        val statusText = document.selectFirst(".dzy-detail__fact--status")?.text()?.trim()
        val showStatus = when (statusText) {
            "Sona Erdi" -> ShowStatus.Completed
            "Devam Ediyor" -> ShowStatus.Ongoing
            else -> null
        }

        // Fragman: dialog içindeki iframe'in data-src veya src'sini al
        val trailerRaw = document.selectFirst("dialog[data-trailer-dialog] .trailer-frame iframe")?.attr("data-src")
            ?: document.selectFirst("dialog[data-trailer-dialog] .trailer-frame iframe")?.attr("src")
            ?: document.selectFirst(".trailer-frame iframe")?.attr("data-src")
            ?: document.selectFirst(".trailer-frame iframe")?.attr("src")

        // Embed URL'yi YouTube izleme URL'sine çevir
        val trailer = when {
            trailerRaw.isNullOrBlank() -> ""
            trailerRaw.contains("youtube-nocookie.com/embed/") -> {
                val videoId = trailerRaw.substringAfterLast("/").substringBefore("?")
                "https://www.youtube.com/watch?v=$videoId"
            }
            trailerRaw.contains("youtube.com/embed/") -> {
                val videoId = trailerRaw.substringAfterLast("/").substringBefore("?")
                "https://www.youtube.com/watch?v=$videoId"
            }
            trailerRaw.startsWith("//") -> "https:$trailerRaw"
            trailerRaw.startsWith("http") -> trailerRaw
            else -> fixUrl(trailerRaw)
        }

        // Benzer keşifler (önerilenler)
        val recommendationSection = document.selectFirst("section:has(.section-head h2:contains(Benzer keşifler))")
        val recommendations = recommendationSection
            ?.select("article.dzy-title-card")
            ?.mapNotNull { it.toSearchResult() }
            ?: emptyList()

        val isSeries = document.selectFirst("section[data-dzy-title-type=series]") != null ||
                document.selectFirst(".dzy-detail[data-dzy-title-type=series]") != null ||
                document.selectFirst(".dzy-seasons") != null

        return if (isSeries) {
            // Dizi: Tüm sezonların bölümlerini topla
            val episodeList = mutableListOf<Episode>()

            val seasonUrls = document.select(".dzy-season-tabs button[data-season-url]")
                .mapNotNull { it.attr("data-season-url") }
                .distinct()

            if (seasonUrls.isNotEmpty()) {
                seasonUrls.forEach { seasonUrl ->
                    try {
                        val fullSeasonUrl = fixUrl(seasonUrl) ?: seasonUrl
                        val seasonDoc = app.get(fullSeasonUrl).document

                        seasonDoc.select(".dzy-episode-row").forEach { row ->
                            val epHref = fixUrlNull(row.attr("href")) ?: return@forEach
                            val label = row.selectFirst(".dzy-episode-row__label")?.text()?.trim()
                                ?: row.selectFirst("strong")?.text()?.trim()
                                ?: return@forEach

                            val season = Regex("""(\d+)\.\s*Sezon""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                            val episode = Regex("""(\d+)\.\s*Bölüm""").find(label)?.groupValues?.get(1)?.toIntOrNull()

                            if (season != null && episode != null) {
                                episodeList.add(
                                    newEpisode(epHref) {
                                        this.name = label
                                        this.season = season
                                        this.episode = episode
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Sezon yüklenirken hata: $seasonUrl - ${e.message}")
                    }
                }
            } else {
                document.select(".dzy-episode-row").forEach { row ->
                    val epHref = fixUrlNull(row.attr("href")) ?: return@forEach
                    val label = row.selectFirst(".dzy-episode-row__label")?.text()?.trim()
                        ?: row.selectFirst("strong")?.text()?.trim()
                        ?: return@forEach

                    val season = Regex("""(\d+)\.\s*Sezon""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                    val episode = Regex("""(\d+)\.\s*Bölüm""").find(label)?.groupValues?.get(1)?.toIntOrNull()

                    if (season != null && episode != null) {
                        episodeList.add(
                            newEpisode(epHref) {
                                this.name = label
                                this.season = season
                                this.episode = episode
                            }
                        )
                    }
                }
            }

            val sortedEpisodes = episodeList.distinctBy { "${it.season}-${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (rating != null) this.score = Score.from10(rating)
                if (showStatus != null) this.showStatus = showStatus
                this.recommendations = recommendations
                addActors(actors)
                if (trailer.isNotBlank()) addTrailer(trailer)
            }
        } else {
            // Film
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                if (rating != null) this.score = Score.from10(rating)
                if (duration != null) this.duration = duration
                this.recommendations = recommendations
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
        val playerSources = document.select(".dzy-player__source")

        if (playerSources.isEmpty()) {
            Log.e(name, "loadLinks - Player kaynağı bulunamadı")
            return false
        }

        playerSources.forEach { sourceEl ->
            try {
                val source = sourceEl.attr("data-player-source") ?: return@forEach

                // Dil rozetini belirle
                val langTag = when {
                    sourceEl.selectFirst("b.dzy-player__language.tr_dub") != null -> "#dublaj"
                    sourceEl.selectFirst("b.dzy-player__language.tr_sub") != null -> "#altyazi"
                    else -> ""
                }

                // Gate sayfasını al ve session cookie'lerini sakla
                val gateRes = app.get(source)
                val gateDoc = gateRes.document
                val formAction = gateDoc.selectFirst("form")?.attr("action")
                val token = gateDoc.selectFirst("input[name=_token]")?.attr("value")

                if (formAction.isNullOrBlank() || token.isNullOrBlank()) {
                    Log.e(name, "Authorize form bilgisi bulunamadı: $source")
                    return@forEach
                }

                val authUrl = fixUrl(formAction) ?: formAction
                val sessionCookies = gateRes.cookies.toMutableMap()

                // Authorize POST isteği
                val authResponse = app.post(
                    authUrl,
                    requestBody = "_token=$token".toRequestBody(
                        "application/x-www-form-urlencoded".toMediaType()
                    ),
                    cookies = sessionCookies,
                    referer = source,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                )

                if (!authResponse.isSuccessful) {
                    Log.e(name, "Authorize başarısız: ${authResponse.code}")
                    return@forEach
                }

                // Cookie'leri güncelle
                if (authResponse.cookies.isNotEmpty()) {
                    sessionCookies.putAll(authResponse.cookies)
                }

                // Watch URL'ini bul
                val watchUrl = authResponse.url
                if (watchUrl.isNullOrBlank() || !watchUrl.contains("/watch/")) {
                    Log.e(name, "Watch URL bulunamadı: $authUrl")
                    return@forEach
                }

                // Embed URL'yi bul
                var embedUrl: String? = Jsoup.parse(authResponse.text)
                    .selectFirst("iframe[data-provider-frame]")?.attr("src")

                if (embedUrl.isNullOrBlank()) {
                    val watchRes = app.get(watchUrl, cookies = sessionCookies, referer = source)
                    embedUrl = watchRes.document.selectFirst("iframe[data-provider-frame]")?.attr("src")
                }

                if (embedUrl.isNullOrBlank()) {
                    Log.e(name, "Embed URL bulunamadı: $watchUrl")
                    return@forEach
                }

                // Dil hash'ini ekle
                val finalUrl = if (langTag.isNotEmpty()) "$embedUrl$langTag" else embedUrl
                Log.d(name, "Embed URL: $finalUrl")

                loadExtractor(finalUrl, data, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(name, "Player çözümleme hatası: ${e.message}")
            }
        }

        return true
    }
}