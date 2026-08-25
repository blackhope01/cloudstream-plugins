package com.blackhope01

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Webteizle : MainAPI() {
    override var mainUrl = "https://webteizle3.xyz"
    override var name = "Webteizle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/yeni-filmler/" to "Son Eklenen Filmler",
        "${mainUrl}/tavsiye-filmler/" to "Tavsiye Filmler",
        "${mainUrl}/odullu-filmler/" to "Ödüllü Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(name, "getMainPage - Sayfa: $page, Kategori: ${request.name}")
        val url = if (page == 1) request.data else "${request.data}${page}"
        val document = app.get(url).document
        val home = document.select("div.ui.four.doubling.cards > div.card.golgever")
            .mapNotNull { it.toSearchResult() }
        Log.d(name, "getMainPage - ${home.size} içerik bulundu")
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".filmname")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a.image")?.attr("href")) ?: return null
        val img = this.selectFirst("img")
        val poster = fixUrlNull(img?.attr("data-src")) ?: fixUrlNull(img?.attr("src"))
        val year = this.selectFirst(".year")?.text()?.toIntOrNull()
        val imdbText = this.selectFirst(".imdb")?.text()?.replace(",", ".")?.trim()
        val imdbScore = imdbText?.toFloatOrNull()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
            this.score = imdbScore?.let { Score.from10(it) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(name, "search başladı - Sorgu: $query")
        return try {
            val response = app.post(
                url = "${mainUrl}/ajax/arama.asp",
                data = mapOf("q" to query),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
                )
            ).text

            val json = JSONObject(response)
            val results = json.optJSONObject("results") ?: return emptyList()
            val searchResponses = mutableListOf<SearchResponse>()

            val filmler = results.optJSONObject("filmler")?.optJSONArray("results")
            if (filmler != null) {
                for (i in 0 until filmler.length()) {
                    val item = filmler.getJSONObject(i)
                    val titleWithYear = item.optString("title")
                    val url = fixUrlNull(item.optString("url")) ?: continue
                    val poster = fixUrlNull(item.optString("image"))
                    val yearMatch = Regex("\\((\\d{4})\\)$").find(titleWithYear)
                    val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
                    val title = titleWithYear.replace(Regex("\\(\\d{4}\\)$"), "").trim()
                    searchResponses.add(
                        newMovieSearchResponse(title, url, TvType.Movie) {
                            this.posterUrl = poster
                            this.year = year
                        }
                    )
                }
            }

            Log.d(name, "search tamamlandı - ${searchResponses.size} sonuç bulundu")
            searchResponses
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

        val h1 = document.selectFirst("h1")
        val fullTitle = h1?.text()?.replace(" izle", "")?.trim()
        if (fullTitle.isNullOrBlank()) {
            Log.e(name, "load - Başlık bulunamadı")
            return null
        }

        val yearMatch = Regex("\\((\\d{4})\\)$").find(fullTitle)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
        val title = fullTitle.replace(Regex("\\(\\d{4}\\)$"), "").trim()
        Log.d(name, "load - Başlık: $title, Yıl: $year")

        val poster = fixUrlNull(document.selectFirst(".five.wide.column .ui.card a.image img")?.attr("data-src"))
            ?: fixUrlNull(document.selectFirst(".five.wide.column .ui.card a.image img")?.attr("src"))

        val imdbText = document.selectFirst("a.ui.label.imdbp .detail")?.text()?.replace(",", ".")?.trim()
        val imdbScore = imdbText?.toFloatOrNull()

        val plot = document.selectFirst("blockquote[itemprop='description'] p")?.text()?.trim()

        val tags = document.select("td a[itemgroup='genre']").map { it.text().trim() }.filter { it.isNotEmpty() }

        val duration = document.select("tr").firstOrNull { row ->
            row.select("td").first()?.text()?.trim() == "Süre:"
        }?.select("td")?.get(1)?.text()?.replace("dakika", "")?.trim()?.toIntOrNull()

        val actors = document.select("div.ui.bottom.tab[data-tab='oyuncular'] .ui.card").mapNotNull { card ->
            val actorName = card.selectFirst("span.content")?.text()?.trim()
            val actorImage = fixUrlNull(card.selectFirst("img")?.attr("data-src"))
                ?: fixUrlNull(card.selectFirst("img")?.attr("src"))
            if (actorName.isNullOrBlank()) null else Actor(actorName, actorImage)
        }

        val youtubeId = document.selectFirst("button#fragman")?.attr("data-ytid")
            ?: document.selectFirst("div.ui.embed[data-source='youtube']")?.attr("data-id")
        val trailer = if (!youtubeId.isNullOrBlank()) "https://www.youtube.com/watch?v=$youtubeId" else null

        Log.d(name, "load tamamlandı - Başlık: $title, Yıl: $year")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.score = imdbScore?.let { Score.from10(it) }
            this.duration = duration
            addActors(actors)
            if (trailer != null) addTrailer(trailer)
        }
    }

    // Loadlinks function

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks başladı - Data: $data")
        var found = false

        try {
            val izlemeLinkleri = mutableListOf<String>()

            if (data.contains("/izle/dublaj/") || data.contains("/izle/altyazi/")) {
                izlemeLinkleri.add(data)
            } else {
                val slug = data.substringAfterLast('/')
                if (slug.isBlank()) return false
                izlemeLinkleri.add("${mainUrl}/izle/dublaj/$slug")
                izlemeLinkleri.add("${mainUrl}/izle/altyazi/$slug")
            }

            for (izlemeLinki in izlemeLinkleri) {
                try {
                    val doc = app.get(izlemeLinki).document

                    val filmId = doc.selectFirst("#dilsec")?.attr("data-id")
                        ?: doc.selectFirst("#wip")?.attr("data-id")
                        ?: doc.toString().let { html ->
                            Regex("""data-id=["'](\d+)["']""").find(html)?.groupValues?.get(1)
                        }
                        ?: continue

                    val dil = if (izlemeLinki.contains("/altyazi/")) "1" else "0"

                    val altResp = app.post(
                        url = "${mainUrl}/ajax/dataAlternatif3.asp",
                        data = mapOf(
                            "filmid" to filmId,
                            "dil" to dil,
                            "s" to "",
                            "b" to "",
                            "bot" to "0"
                        ),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "Referer" to izlemeLinki
                        )
                    ).text

                    val altJson = JSONObject(altResp)
                    val alternatifler = altJson.optJSONArray("data") ?: continue

                    for (i in 0 until alternatifler.length()) {
                        val alt = alternatifler.getJSONObject(i)
                        val altId = alt.optString("id")
                        val baslik = alt.optString("baslik")
                        if (altId.isBlank()) continue

                        try {
                            val playerUrls = mutableListOf<String>()

                            if (baslik.contains("Filemoon", true)) {
                                var fmEmbedResp = app.post(
                                    url = "${mainUrl}/ajax/dataEmbed.asp",
                                    data = mapOf("id" to altId),
                                    headers = mapOf(
                                        "X-Requested-With" to "XMLHttpRequest",
                                        "Content-Type" to "application/x-www-form-urlencoded",
                                        "Referer" to izlemeLinki
                                    )
                                ).text

                                if (fmEmbedResp.contains("reCAPTCHA") || fmEmbedResp.contains("reCAPTCHADATA")) {
                                    fmEmbedResp = app.post(
                                        url = "${mainUrl}/ajax/dataEmbed.asp",
                                        data = mapOf("id" to altId, "bot" to "1"),
                                        headers = mapOf(
                                            "X-Requested-With" to "XMLHttpRequest",
                                            "Content-Type" to "application/x-www-form-urlencoded",
                                            "Referer" to izlemeLinki
                                        )
                                    ).text
                                }

                                val fmMatch = Regex("""filemoon\('([^']+)'\s*,\s*'[^']*'\)""").find(fmEmbedResp)
                                val fmId = fmMatch?.groupValues?.get(1) ?: altId
                                playerUrls.add("https://bysezoxexe.com/e/$fmId")
                            } else {
                                val embedResp = app.post(
                                    url = "${mainUrl}/ajax/dataEmbed.asp",
                                    data = mapOf("id" to altId),
                                    headers = mapOf(
                                        "X-Requested-With" to "XMLHttpRequest",
                                        "Content-Type" to "application/x-www-form-urlencoded",
                                        "Referer" to izlemeLinki
                                    )
                                ).text

                                if (embedResp.contains("reCAPTCHA") || embedResp.contains("reCAPTCHADATA")) {
                                    val fallbackUrl = when {
                                        baslik.contains("VidMoly", true) -> "${mainUrl}/player/vidmoly.asp?v=$altId"
                                        baslik.contains("Pixel", true) -> "${mainUrl}/player/pixel.asp?v=$altId"
                                        baslik.contains("Ok.Ru", true) -> "${mainUrl}/player/ok.ru.asp?v=$altId"
                                        baslik.contains("MailRu", true) -> "${mainUrl}/player/video-mail-ru.asp?v=$altId"
                                        baslik.contains("Netu", true) -> "${mainUrl}/player/netu.asp?v=$altId"
                                        baslik.contains("Dzen", true) -> "${mainUrl}/player/dzen.asp?v=$altId"
                                        else -> null
                                    }
                                    if (fallbackUrl != null) playerUrls.add(fallbackUrl)
                                } else {
                                    val iframeDoc = Jsoup.parse(embedResp)
                                    val iframes = iframeDoc.select("iframe[src]")
                                        .mapNotNull { it.attr("src") }
                                        .filter { src ->
                                            val lower = src.lowercase()
                                            !lower.contains("recaptcha") && !lower.contains("captcha") && src.isNotBlank()
                                        }
                                        .map { src ->
                                            when {
                                                src.startsWith("//") -> "https:$src"
                                                src.startsWith("http") -> src
                                                else -> fixUrl(src)
                                            }
                                        }

                                    playerUrls.addAll(iframes)

                                    if (playerUrls.isEmpty()) {
                                        val playerMatch = Regex("""([a-zA-Z0-9_]+)\s*\(\s*'([^']+)'\s*,\s*'[^']*'\s*\)""").find(embedResp)
                                        if (playerMatch != null) {
                                            val playerFunc = playerMatch.groupValues[1].lowercase()
                                            val playerId = playerMatch.groupValues[2]
                                            val url = when (playerFunc) {
                                                "filemoon" -> "https://bysezoxexe.com/e/$playerId"
                                                "vidmoly" -> "${mainUrl}/player/vidmoly.asp?v=$playerId"
                                                "mailru" -> "${mainUrl}/player/video-mail-ru.asp?v=$playerId"
                                                "okru" -> "${mainUrl}/player/ok.ru.asp?v=$playerId"
                                                "pixel" -> "${mainUrl}/player/pixel.asp?v=$playerId"
                                                "netu" -> "${mainUrl}/player/netu.asp?v=$playerId"
                                                "dzen" -> "${mainUrl}/player/dzen.asp?v=$playerId"
                                                else -> "${mainUrl}/player/$playerFunc.asp?v=$playerId"
                                            }
                                            playerUrls.add(url)
                                        }
                                    }
                                }
                            }

                            for (playerUrl in playerUrls) {
                                val finalUrl = if (playerUrl.contains("/player/vidmoly.asp") || playerUrl.contains("vidmoly")) {
                                    try {
                                        val wrapperDoc = app.get(playerUrl, referer = izlemeLinki).document
                                        val realIframe = wrapperDoc.selectFirst("iframe[src]")?.attr("src")
                                        if (!realIframe.isNullOrBlank()) {
                                            when {
                                                realIframe.startsWith("//") -> "https:$realIframe"
                                                realIframe.startsWith("http") -> realIframe
                                                else -> fixUrl(realIframe)
                                            }
                                        } else null
                                    } catch (e: Exception) {
                                        Log.e(name, "loadLinks - VidMoly wrapper hatası: ${e.message}")
                                        null
                                    }
                                } else playerUrl

                                if (!finalUrl.isNullOrBlank()) {
                                    Log.d(name, "loadLinks - Extractor'a gönderiliyor: $finalUrl")
                                    loadExtractor(finalUrl, izlemeLinki, subtitleCallback, callback)
                                    found = true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(name, "loadLinks - Alternatif işlenirken hata: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(name, "loadLinks - İzleme linki işlenemedi: $izlemeLinki - ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(name, "loadLinks - Genel hata: ${e.message}")
        }

        return found
    }

}