package com.blackhope01

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element


class DramaDizilerim : MainAPI() {
    override var mainUrl = "https://dramadizilerim.com"
    override var name = "DramaDizilerim"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // ============================================================
    // ANA SAYFA KATEGORILERI
    // ============================================================
    override val mainPage = mainPageOf(
        "$mainUrl/p/netshort/" to "NetShort",
        "$mainUrl/p/dramabox/" to "DramaBox",
        "$mainUrl/p/reelshort/" to "ReelShort",
        "$mainUrl/p/freereels/" to "FreeReels",
        "$mainUrl/p/goodshort//" to "GoodShort",
        "$mainUrl/p/flextv/" to "FlexTV",
        "$mainUrl/p/shortmax/" to "ShortMax"
    )

    private val passwordLock = PasswordLock(
        correctPassword = "blackhope01",
        prefsName = "dramadizilerim_prefs"
    )

    // ============================================================
    // ANA SAYFA - Parse (Ana sayfa + Kategori + Platform)
    // ============================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (!passwordLock.ensureUnlocked()) {
            throw ErrorLoadingException("Şifre doğrulanmadı")
        }

        Log.d(name, "getMainPage - Sayfa: $page, Kategori: ${request.name}")

        val document = app.get(request.data).document
        val home = mutableListOf<SearchResponse>()

        // 1. Spotlight / Hero kartları (sadece ana sayfa)
        val spotlights = document.select(".hero-spotlight-grid a.spotlight-card")
            .mapNotNull { it.toSearchResult() }
        home.addAll(spotlights)

        // 2. Top 10 kartları (sadece ana sayfa)
        val top10 = document.select(".top10-ranking-grid a.top10-rank-card")
            .mapNotNull { it.toSearchResult() }
        home.addAll(top10)

        // 3. Standart video grid (ana sayfa - Son Eklenenler)
        val stdGrid = document.select(".video-cards-grid a.standard-video-card")
            .mapNotNull { it.toSearchResult() }
        home.addAll(stdGrid)

        // 4. Video grid (kategori sayfaları /dizi, platform sayfaları /p/xxx, arama)
        val videoGrid = document.select(".video-grid article.video-card a.video-link")
            .mapNotNull { it.toSearchResult() }
        home.addAll(videoGrid)

        // 5. Fallback - eğer yukarıdakiler boşsa, genel dizi linklerini dene
        if (home.isEmpty()) {
            val fallback = document.select("a[href*='/dizi/']")
                .filter { it.selectFirst("img") != null }
                .mapNotNull { it.toSearchResult() }
            home.addAll(fallback)
        }

        // Tekrarları kaldır (aynı URL)
        val uniqueHome = home.distinctBy { it.url }

        val hasNext = document.select("a[rel=next], .pagination a.next, .load-more").isNotEmpty()

        Log.d(name, "getMainPage - ${uniqueHome.size} içerik bulundu, hasNext=$hasNext")
        return newHomePageResponse(request.name, uniqueHome, hasNext = hasNext)
    }

    // ============================================================
    // ARAMA SONUCU DONUSTURME
    // ============================================================
    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null

        // Başlık - önce metin başlıklarını dene
        var title = this.selectFirst(
            "h2.spotlight-title, h3.top10-card-title, h3.std-card-title, h3.video-title, .video-title, .title"
        )?.text()?.trim()?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("alt")?.trim()?.let { alt ->
                val genericAlts = setOf("poster", "image", "img", "cover", "thumbnail", "thumb", "")
                if (alt.lowercase() in genericAlts) null else alt
            }
            ?: return null

        // Başlığın sonunda "poster" varsa sil (büyük/küçük harf duyarsız)
        title = title.replace(Regex("""\s*poster\s*$""", RegexOption.IGNORE_CASE), "").trim()

        // Poster
        val poster = fixUrlNull(
            this.selectFirst("img")?.attr("src")
                ?: this.selectFirst("img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("data-original")
        )

        // Tür belirleme
        val typeText =
            this.selectFirst(".badge-type, .video-meta .badge")?.text()?.trim()?.lowercase()
        val tvType = when {
            typeText?.contains("film") == true -> TvType.Movie
            else -> TvType.TvSeries
        }

        // IMDB puanı
        val ratingText = this.selectFirst(".spotlight-imdb")?.text()
            ?: this.select(".std-card-meta span, .video-meta .meta-item").firstOrNull()?.text()
        val rating = ratingText?.let {
            Regex("""([0-9]+(?:\.[0-9]+)?)""").find(it)?.groupValues?.get(1)
        }?.toFloatOrNull()

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = poster
            this.score = rating?.let { Score.from10(it) }
        }
    }

    // ============================================================
    // ARAMA - HTML Parse (Arama sayfasi: /search?q=...)
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(name, "search başladı - Sorgu: $query")
        return try {
            val document = app.get("$mainUrl/search?q=${query}").document

            val results = document.select(".video-grid article.video-card a.video-link")
                .mapNotNull { it.toSearchResult() }

            Log.d(name, "search tamamlandı - ${results.size} sonuç bulundu")
            results
        } catch (e: Exception) {
            Log.e(name, "search hatası: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        Log.d(name, "quickSearch başladı - Sorgu: $query")
        return search(query)
    }

    // ============================================================
    // FILM/DIZI DETAY - Parse (JSON-LD + HTML Fallback)
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load başladı - URL: $url")

        val document = app.get(url).document

        // ===== BAŞLIK =====
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        if (title == null) {
            Log.e(name, "load - Başlık bulunamadı")
            return null
        }

        // ===== POSTER =====
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst(".series-poster img, .detail-poster img, .poster img, .video-poster-wrapper img")?.attr("src")
        )

        // ===== OZET =====
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst(".series-description, .detail-synopsis, .synopsis, [x-ref='content']")?.text()?.trim()

        // ===== TURLER =====
        val tags = document.select("a[href*='/tur/'], .genre-tag, .series-genre a, .tag")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // ===== IMDB =====
        val rating = document.selectFirst(".imdb-score, .series-rating, a[href*='imdb.com']")?.text()?.trim()?.let {
            Regex("""([0-9]+(?:\.[0-9]+)?)""").find(it)?.groupValues?.get(1)
        }

        // ===== YIL =====
        val year = document.select("a[href*='/yil/'], .series-year, .year").mapNotNull {
            it.text().trim().toIntOrNull()
        }.firstOrNull()

        // ===== OYUNCULAR =====
        val actors = document.select("a[href*='/oyuncu/'], .cast-item, .actor-card, .actor").mapNotNull {
            val actorName = it.selectFirst("h4, .actor-name, .name")?.text()?.trim()
                ?: it.selectFirst("img")?.attr("alt")?.trim()
            val actorImg = fixUrlNull(it.selectFirst("img")?.attr("src"))
            if (actorName.isNullOrBlank()) null else Actor(actorName, actorImg)
        }

        // ===== FRAGMAN =====
        val trailer = document.selectFirst("iframe[src*='youtube.com/embed/'], iframe[src*='youtu.be']")?.attr("src")?.let {
            when {
                it.startsWith("//") -> "https:$it"
                it.startsWith("http") -> it
                else -> fixUrl(it)
            }
        } ?: ""

        // ===== ONERILER =====
        val recommendations = document.select(".video-cards-grid > a, .related-series a, .similar-item a, .video-grid article.video-card a.video-link")
            .mapNotNull { it.toSearchResult() }

        val isSeries = url.contains("/dizi/")

        Log.d(name, "load tamamlandi - Baslik: $title, Yil: $year, IMDb: $rating, Tur: ${if (isSeries) "Dizi" else "Film"}")

        return if (isSeries) {
            // ===== DIZI DETAY =====
            val episodes = mutableListOf<Episode>()

            // 1. Yeni HTML yapısı: .wp-season-panel içindeki .wp-ecard'lar
            document.select(".wp-season-panel").forEach { seasonPanel ->
                val seasonNum = seasonPanel.attr("data-season").toIntOrNull()
                    ?: Regex("""(\d+)""").find(seasonPanel.selectFirst(".wp-season-title, .wp-season-toggle")?.text() ?: "")?.value?.toIntOrNull()
                    ?: 1

                seasonPanel.select(".wp-ecard").forEach { card ->
                    val epHref = fixUrlNull(card.attr("href")) ?: return@forEach
                    val epNum = card.attr("data-episode").toIntOrNull()
                        ?: card.selectFirst(".wp-enum")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                        ?: 1
                    val epName = card.selectFirst(".wp-etitle")?.text()?.trim()
                        ?: card.selectFirst(".wp-enum")?.text()?.trim()
                        ?: "Bölüm $epNum"
                    val epPoster = fixUrlNull(
                        card.selectFirst(".wp-ethumb img")?.attr("src")
                            ?: card.selectFirst(".wp-ethumb img")?.attr("data-src")
                    )

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = epPoster
                        }
                    )
                }
            }

            // 2. Fallback: Eski bolum- linkleri
            if (episodes.isEmpty()) {
                document.select("a[href*='/bolum-'], .episode-item a, .episode-link").forEach { epLink ->
                    val epHref = fixUrlNull(epLink.attr("href")) ?: return@forEach
                    val epName = epLink.text().trim().ifBlank { "Bölüm" }
                    val epNum = Regex("""/bolum-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""Bölüm\s*(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                        ?: 1

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epName
                            this.season = 1
                            this.episode = epNum
                        }
                    )
                }
            }

            // 3. Son fallback: JSON-LD schema
            if (episodes.isEmpty()) {
                document.select("script[type=application/ld+json]").forEach { script ->
                    try {
                        val json = script.data()
                        val map = parseJson<Map<String, Any?>>(json)
                        if (map["@type"]?.toString()?.contains("TVSeries") == true) {
                            val series = parseJson<JsonLdTVSeries>(json)
                            series.containsSeason?.forEach { season ->
                                val seasonNum = season.seasonNumber ?: 1
                                season.episode?.forEach { ep ->
                                    val epUrl = ep.url?.let { fixUrlNull(it) } ?: return@forEach
                                    val epNum = ep.episodeNumber ?: 1
                                    val epName = ep.name?.trim()?.ifBlank { "Bölüm $epNum" } ?: "Bölüm $epNum"
                                    episodes.add(
                                        newEpisode(epUrl) {
                                            this.name = epName
                                            this.season = seasonNum
                                            this.episode = epNum
                                        }
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) { }
                }
            }

            val sortedEpisodes = episodes.distinctBy { "${it.season}-${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            Log.d(name, "load - Toplam ${sortedEpisodes.size} bölüm bulundu")

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = rating?.let { Score.from10(it.toFloat()) }
                this.recommendations = recommendations
                addActors(actors)
                if (trailer.isNotBlank()) addTrailer(trailer)
            }
        } else {
            // ===== FILM DETAY =====
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = rating?.let { Score.from10(it.toFloat()) }
                this.recommendations = recommendations
                addActors(actors)
                if (trailer.isNotBlank()) addTrailer(trailer)
            }
        }
    }

    // ============================================================
// JSON-LD Data Classes
// ============================================================
    data class JsonLdImage(
        val url: String? = null
    )

    data class JsonLdAggregateRating(
        val ratingValue: Any? = null,
        val bestRating: Any? = null,
        val worstRating: Any? = null,
        val ratingCount: Any? = null
    )

    data class JsonLdEpisode(
        val name: String? = null,
        val episodeNumber: Int? = null,
        val seasonNumber: Int? = null,
        val url: String? = null,
        val image: Any? = null,
        val description: String? = null
    )

    data class JsonLdSeason(
        val seasonNumber: Int? = null,
        val numberOfEpisodes: Int? = null,
        val episode: List<JsonLdEpisode>? = null
    )

    data class JsonLdTVSeries(
        val name: String? = null,
        val description: String? = null,
        val image: Any? = null,
        val url: String? = null,
        val aggregateRating: JsonLdAggregateRating? = null,
        val datePublished: String? = null,
        val dateModified: String? = null,
        val genre: Any? = null,
        val inLanguage: String? = null,
        val numberOfSeasons: Int? = null,
        val numberOfEpisodes: Int? = null,
        val containsSeason: List<JsonLdSeason>? = null
    )

    // ============================================================
    // VIDEO/LINK - Embed URL Bulma
    // ============================================================
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks başladı - Data: $data")

        val document = app.get(data).document

        // 1. Ana oynatıcı iframe'i (aktif bölüm)
        val iframeSrc = document.selectFirst(
            "iframe[src*='embed.php'], iframe[src*='embed/'], " +
                    ".video-player iframe[src], #player iframe[src], " +
                    ".player-wrap iframe[src], .plyr iframe[src]"
        )?.attr("src")

        if (!iframeSrc.isNullOrBlank()) {
            val embedUrl = when {
                iframeSrc.startsWith("//") -> "https:$iframeSrc"
                iframeSrc.startsWith("http") -> iframeSrc
                else -> fixUrl(iframeSrc)
            }
            Log.d(name, "loadLinks - Embed URL: $embedUrl")
            loadExtractor(embedUrl, data, subtitleCallback, callback)
            return true
        }

        // 2. Fallback: JSON-LD VideoObject embedUrl
        document.select("script[type=application/ld+json]").forEach { script ->
            try {
                val json = script.data()
                val map = parseJson<Map<String, Any?>>(json)
                if (map["@type"]?.toString()?.contains("VideoObject") == true) {
                    val embedUrl = map["embedUrl"]?.toString()?.let { fixUrlNull(it) }
                    if (!embedUrl.isNullOrBlank()) {
                        Log.d(name, "loadLinks - JSON-LD Embed: $embedUrl")
                        loadExtractor(embedUrl, data, subtitleCallback, callback)
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(name, "JSON-LD parse hatası: ${e.message}")
            }
        }

        // 3. Son fallback: data URL'sinin kendisi
        Log.d(name, "loadLinks - Fallback: data URL deneniyor")
        loadExtractor(data, data, subtitleCallback, callback)

        return true
    }
}