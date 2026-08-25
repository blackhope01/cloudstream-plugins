package com.blackhope01

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.nodes.Element

class JetFilmizle : MainAPI() {
    override var mainUrl = "https://jetfilmizle.now"
    override var name = "JetFilmizle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Ana sayfa kategorileri
    override val mainPage = mainPageOf(
        "${mainUrl}/filmler/sayfa-" to "En Yeni Filmler",
        "${mainUrl}/diziler/sayfa-" to "En Yeni Diziler",
        "${mainUrl}/filmler/en-cok-izlenenler/sayfa-" to "En Çok İzlenen Filmler",
        "${mainUrl}/diziler/siralama-en-cok-izlenen/sayfa-" to "En Çok İzlenen Diziler",
        "${mainUrl}/saglayici/netflix?sayfa=" to "Netflix",
        "${mainUrl}/yerli-filmler/sayfa-" to "Yerli Komedi Filmler",
        "${mainUrl}/tur/komedi/film/sayfa-" to "Komedi Filmler",
        "${mainUrl}/tur/fantastik/film/sayfa-" to "Fantastik Filmler",
        "${mainUrl}/tur/fantastik/dizi/sayfa-" to "Fantastik Diziler",
        "${mainUrl}/tur/bilim-kurgu/film/sayfa-" to "Bilim Kurgu Filmler",
        "${mainUrl}/tur/bilim-kurgu/dizi/sayfa-" to "Bilim Kurgu Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(name, "getMainPage başladı - Sayfa: $page, Kategori: ${request.name}")

        // Yerli filmler kategorisi için özel parametre ekle
        val url = if (request.name == "Yerli Komedi Filmler") {
            "${request.data}${page}?kategori=47"
        } else {
            "${request.data}${page}"
        }

        val document = app.get(url).document
        val home = document.select(".row-cols-2 .col .film-card").mapNotNull { it.toSearchResult() }

        Log.d(name, "getMainPage tamamlandı - ${home.size} içerik bulundu")
        return newHomePageResponse(request.name, home)
    }

    // HTML elementini arama sonucuna dönüştür
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3 a")?.text().toString()
        val href = fixUrlNull(this.selectFirst(".card-body a")?.attr("href")) ?: return null
        val score = this.selectFirst(".rating-year-imdb .text-warning")?.text()?.trim()

        // Poster URL'sini al (lazy loading için data-src öncelikli)
        var posterUrl = fixUrlNull(this.selectFirst(".film-poster img")?.attr("data-src"))
        if (posterUrl == null) {
            posterUrl = fixUrlNull(this.selectFirst(".film-poster img")?.attr("src"))
        }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.score = Score.from10(score)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(name, "search başladı - Sorgu: $query")

        val response = app.get(
            "${mainUrl}/arama-json?q=$query",
            referer = "${mainUrl}/",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
            )
        ).text

        try {
            // JSON verisini HTML içerisinden ayıkla
            val jsonStr = response.substringAfter("<body>").substringBefore("</body>")
            val json = JSONObject(jsonStr)
            val results = json.optJSONArray("results")

            if (results == null || results.length() == 0) {
                Log.d(name, "search - Sonuç bulunamadı")
                return emptyList()
            }

            val searchResponses = mutableListOf<SearchResponse>()

            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)

                val title = item.optString("title")
                val url = item.optString("url")
                val poster = item.optString("poster")
                val year = item.optString("year").toIntOrNull()
                val rating = item.optString("rating")
                val type = item.optString("type")

                val fullUrl = fixUrlNull("$mainUrl/$url") ?: continue
                val fullPoster = fixUrlNull(poster)

                val tvType = when (type) {
                    "dizi" -> TvType.TvSeries
                    else -> TvType.Movie
                }

                searchResponses.add(
                    newMovieSearchResponse(title, fullUrl, tvType) {
                        this.posterUrl = fullPoster
                        this.year = year
                        this.score = Score.from10(rating)
                    }
                )
            }

            Log.d(name, "search tamamlandı - ${searchResponses.size} sonuç bulundu")
            return searchResponses

        } catch (e: Exception) {
            Log.e(name, "search hatası: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        Log.d(name, "quickSearch başladı - Sorgu: $query")
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load başladı - URL: $url")

        val document = app.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
        ).document

        // Temel bilgileri çek
        val title = document.selectFirst(".col-12 .film-title")?.text()?.substringBefore(" izle")?.trim()
        if (title == null) {
            Log.e(name, "load - Başlık bulunamadı")
            return null
        }

        val poster = fixUrlNull(document.selectFirst(".film-bilgileri-section img")?.attr("data-src"))
            ?: fixUrlNull(document.selectFirst(".film-bilgileri-section img")?.attr("src"))

        val year = document.selectFirst(".col-md-6 > .detail-item:nth-child(2) span")?.text()?.trim()?.toIntOrNull()

        val description = document.selectFirst(".description-text p:nth-child(2)")?.text()?.trim()
            ?: document.selectFirst(".description-text p:nth-child(1)")?.text()?.trim()

        val tags = document.select(".col-md-6 > .detail-item:nth-child(4) .categories-container-details a")
            .map { it.text() }

        val rating = document.selectFirst(".film-ratings-container b")?.text()?.split(" ")?.last()

        val duration = document.selectFirst(".col-md-6 > .detail-item:nth-child(3) span")?.text()
            ?.replace("dakika", "")
            ?.replace("dk", "")
            ?.trim()
            ?.toIntOrNull()

        // Oyuncu listesini oluştur
        val actors = document.select(".oyuncular-section .actors-grid .col").map {
            val name = it.selectFirst(".text-decoration-none")?.text() ?: ""
            val image = it.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                ?: "https://ui-avatars.com/api/?name=${name.replace(" ", "+")}&size=100&background=111111&color=fff"
            val character = it.selectFirst(".karakter-badge")?.text()
            Pair(Actor(name, image), character)
        }

        // Benzer film önerileri
        val recommendations = document.select(".similar-film-item").mapNotNull { item ->
            val recName = item.selectFirst(".card-title a")?.text()?.substringBefore(" izle")?.trim()
                ?: return@mapNotNull null

            val recHref = fixUrlNull(item.selectFirst(".film-card-wrapper a")?.attr("href")) ?: return@mapNotNull null

            val recPosterUrl = fixUrlNull(item.selectFirst("img")?.attr("data-src"))
                ?: fixUrlNull(item.selectFirst("img")?.attr("src"))
                ?: return@mapNotNull null

            newMovieSearchResponse(recName, recHref, TvType.Movie) {
                this.posterUrl = recPosterUrl
            }
        }

        Log.d(name, "load tamamlandı - Başlık: $title, Tür: ${if (url.contains("/dizi/")) "Dizi" else "Film"}")
        return if (url.contains("/dizi/")) {
            // Dizi detay sayfası - bölümleri listele
            val playerTypes = document.select("button.player-type-btn").map { it.attr("data-player-type") }.distinct()
            val episodes = mutableListOf<Episode>()

            for (playerType in playerTypes) {
                val typeEpisodes = document.select("button.episode-btn[data-player-type=$playerType]").mapNotNull { btn ->
                    val epSeason = btn.attr("data-season").toIntOrNull() ?: return@mapNotNull null
                    val epEpisode = btn.attr("data-episode").toIntOrNull() ?: return@mapNotNull null
                    val sourceIndex = btn.attr("data-source-index").toIntOrNull() ?: return@mapNotNull null

                    val epHref = "$url?sezon=$epSeason&bolum=$epEpisode&index=$sourceIndex&type=$playerType"

                    newEpisode(epHref) {
                        this.name = "${epSeason}.Sezon ${epEpisode}.Bölüm"
                        this.season = epSeason
                        this.episode = epEpisode
                    }
                }
                episodes.addAll(typeEpisodes)
            }

            // Tekrar eden bölümleri temizle ve sırala
            val sortedEpisodes = episodes.distinctBy { "${it.season}-${it.episode}-${it.name}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
            }
        } else {
            // Film detay sayfası
            val trailer = document.selectFirst("#trailerIframe")?.attr("data-src") ?: ""

            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
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

        try {
            val document = app.get(
                data,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "$mainUrl/"
                )
            ).document

            // Film ID'sini sayfadan al
            val filmIdInput = document.selectFirst("input[name=\"film_id\"]")
            val filmId = filmIdInput?.attr("value")

            if (filmId.isNullOrBlank()) {
                Log.e(name, "loadLinks - Film ID bulunamadı!")
                return false
            }

            Log.d(name, "loadLinks - Film ID: $filmId")

            val isEpisode = data.contains("?sezon=") && data.contains("&bolum=")

            if (isEpisode) {
                // Belirli bir bölüm için oynatma linkini al
                val sourceIndex = data.substringAfter("index=").substringBefore("&")
                val playerType = data.substringAfter("type=").substringBefore("&").ifEmpty { "dublaj" }
                val sezon = data.substringAfter("sezon=").substringBefore("&")
                val bolum = data.substringAfter("bolum=").substringBefore("&")
                val label = "S${sezon}B${bolum}"

                Log.d(name, "loadLinks - Bölüm oynatılıyor: $label (index=$sourceIndex, type=$playerType)")

                loadPlayerForSource(filmId, sourceIndex, playerType, label, data, subtitleCallback, callback)

                Log.d(name, "loadLinks tamamlandı - Bölüm işlendi")
                return true
            }

            val isDizi = data.contains("/dizi/")

            if (isDizi) {
                // Dizi ana sayfası - her dil seçeneği için ilk bölümün linkini al
                val playerTypes = document.select("button.player-type-btn").mapNotNull { btn ->
                    val label = btn.text().trim()
                    val pType = btn.attr("data-player-type")
                    if (label.isNotBlank() && pType.isNotBlank()) label to pType else null
                }

                Log.d(name, "loadLinks - Dizi ana sayfası, ${playerTypes.size} dil seçeneği bulundu")

                val processedUrls = mutableSetOf<String>()

                playerTypes.forEach { (dilLabel, pType) ->
                    val firstEpisode = document.select("button.episode-btn[data-player-type=$pType]").firstOrNull()

                    if (firstEpisode != null) {
                        val sourceIndex = firstEpisode.attr("data-source-index")

                        if (sourceIndex.isNotBlank() && processedUrls.add("$pType-$sourceIndex")) {
                            Log.d(name, "loadLinks - Dil seçeneği deneniyor: $dilLabel")
                            loadPlayerForSource(filmId, sourceIndex, pType, dilLabel, data, subtitleCallback, callback)
                        }
                    }
                }

                Log.d(name, "loadLinks tamamlandı - ${playerTypes.size} dil seçeneği işlendi")
                return true
            }

            // Film sayfası - tüm kaynakları dene
            Log.d(name, "loadLinks - Film sayfası, kaynaklar taranıyor")
            val processedNames = mutableSetOf<String>()

            document.select("button.player-source-btn").forEach { btn ->
                val label = btn.text().trim()
                val pType = btn.attr("data-player-type").ifEmpty { "dublaj" }
                val sourceIndex = btn.attr("data-source-index")

                if (label.isBlank() || sourceIndex.isBlank()) return@forEach
                if (!processedNames.add(label)) return@forEach

                Log.d(name, "loadLinks - Kaynak deneniyor: $label")
                loadPlayerForSource(filmId, sourceIndex, pType, label, data, subtitleCallback, callback)
            }

            Log.d(name, "loadLinks tamamlandı - ${processedNames.size} kaynak işlendi")
            return true

        } catch (e: Exception) {
            Log.e(name, "loadLinks - Genel hata: ${e.message}")
            return false
        }
    }

    /**
     * Ortak yardımcı fonksiyon: jetplayer POST isteği atar, iframe linkini çıkarır ve loadExtractor'ı çağırır.
     * Bölüm, dizi ana sayfa ve film sayfalarındaki tüm kaynaklar için kullanılır.
     */
    private suspend fun loadPlayerForSource(
        filmId: String,
        sourceIndex: String,
        playerType: String,
        label: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val apiResponse = app.post(
                url = "$mainUrl/jetplayer",
                data = mapOf(
                    "film_id" to filmId,
                    "source_index" to sourceIndex,
                    "player_type" to playerType
                ),
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Origin" to mainUrl,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                referer = referer
            ).document

            val iframe = apiResponse.selectFirst("iframe")
            val iframeSrc = iframe?.attr("src")

            if (!iframeSrc.isNullOrBlank() && iframeSrc != "about:blank") {
                val playerUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                val urlWithLabel = "$playerUrl|label=$label|type=$playerType"
                //Log.d(name, "loadLinks - Kaynak linki bulundu: $playerUrl")
                //loadExtractor(urlWithLabel, referer, subtitleCallback, callback)
                loadExtractor(playerUrl, referer, subtitleCallback, callback)
            } else {
                Log.d(name, "loadLinks - Kaynak için iframe bulunamadı: $label")
            }
        } catch (e: Exception) {
            Log.e(name, "loadLinks - Kaynak linki alınamadı ($label): ${e.message}")
        }
    }
}