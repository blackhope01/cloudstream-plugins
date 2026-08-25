package com.blackhope01

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import org.jsoup.Jsoup
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

class FilmEkseni : MainAPI() {
    override var mainUrl = "https://filmekseni.vip"
    override var name = "FilmEkseni"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ============================================================
    // ANA SAYFA KATEGORILERI
    // ============================================================
    override val mainPage = mainPageOf(
        "${mainUrl}/kategori/fragmanlar/" to "Yakında Gelecek",
        "${mainUrl}/kategori/tavsiye-filmler/" to "Tavsiye Filmler",
        "${mainUrl}/diziler/" to "Yeni Eklenen Diziler",
        "${mainUrl}/kategori/netflix-yapimlari/" to "Netflix Yapımları",
        "${mainUrl}/kategori/dc-yapimlari/" to "DC Yapımları",
        "${mainUrl}/kategori/marvel-yapimlari/" to "Marvel Yapımları",
        "${mainUrl}/ulke/yerli-film-izle/" to "Yerli Filmler",
        "${mainUrl}/tur/aile-filmleri/" to "Aile",
        "${mainUrl}/tur/bilim-kurgu-filmleri/" to "Bilim Kurgu",
        "${mainUrl}/tur/macera-filmleri/" to "Macera",
        "${mainUrl}/tur/aksiyon-filmleri/" to "Aksiyon",
        "${mainUrl}/tur/gerilim-filmleri/" to "Gerilim",
        "${mainUrl}/tur/komedi-filmleri/" to "Komedi",
        "${mainUrl}/tur/korku-filmleri/" to "Korku"
    )

    // ============================================================
    // ANA SAYFA - Film grid parse
    // ============================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(name, "getMainPage - Sayfa: $page, Kategori: ${request.name}")

        val (home, hasNext) = if (page == 1) {
            val document = app.get(request.data).document
            val elements = document.select("a.group\\/poster.block")
            val list = elements.mapNotNull { it.toSearchResult() }
            list to list.isNotEmpty()
        } else {
            val offset = (page - 1) * 24
            val (csrfToken, cookies) = getCsrfTokenAndCookies(request.data)

            // Yeni Eklenen Diziler için özel load-more endpoint
            val loadMoreUrl = if (request.data.endsWith("/diziler/")) {
                "${mainUrl}/diziler/load-more-latest"
            } else {
                request.data.removeSuffix("/") + "/load-more"
            }

            Log.d(name, "loadMore -> URL: $loadMoreUrl | offset: $offset | token: [${csrfToken.take(10)}...] | cookies: ${cookies.keys}")

            val jsonBody = """{"offset":$offset,"sort":"latest","content_type":""}"""
                .toRequestBody("application/json".toMediaType())

            val response = app.post(
                loadMoreUrl,
                headers = mapOf(
                    "x-csrf-token" to csrfToken,
                    "x-requested-with" to "XMLHttpRequest",
                    "referer" to request.data
                ),
                cookies = cookies,
                requestBody = jsonBody
            )

            val rawText = response.text
            Log.d(name, "loadMore raw response: $rawText")

            val json = try {
                response.parsed<LoadMoreResponse>()
            } catch (e: Exception) {
                Log.e(name, "loadMore parsed hatasi: ${e.message}")
                LoadMoreResponse()
            }

            val htmlDoc = Jsoup.parse(json.html)
            val elements = htmlDoc.select("a.group\\/poster.block")
            val list = elements.mapNotNull { it.toSearchResult() }
            list to json.hasMore
        }

        Log.d(name, "getMainPage - ${home.size} icerik bulundu, hasNext=$hasNext")
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private suspend fun getCsrfTokenAndCookies(url: String): Pair<String, Map<String, String>> {
        val response = app.get(url)
        val document = response.document

        document.selectFirst("meta[name=csrf-token]")?.attr("content")?.let {
            Log.d(name, "CSRF meta[csrf-token]: ${it.take(20)}...")
            if (it.isNotBlank()) return it to response.cookies
        }
        document.selectFirst("meta[name=_token]")?.attr("content")?.let {
            Log.d(name, "CSRF meta[_token]: ${it.take(20)}...")
            if (it.isNotBlank()) return it to response.cookies
        }
        (response.cookies["XSRF-TOKEN"] ?: response.cookies["xsrf-token"])?.let {
            Log.d(name, "CSRF cookie: ${it.take(20)}...")
            if (it.isNotBlank()) return it to response.cookies
        }
        document.selectFirst("input[name=_token]")?.attr("value")?.let {
            Log.d(name, "CSRF input: ${it.take(20)}...")
            if (it.isNotBlank()) return it to response.cookies
        }

        Log.e(name, "CSRF token BULUNAMADI! URL: $url")
        return "" to response.cookies
    }

    data class LoadMoreResponse(
        val html: String = "",
        val hasMore: Boolean = false,
        val nextOffset: Int? = null
    )

    // ============================================================
    // ARAMA SONUCU DONUSTURME
    // ============================================================
    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null

        val title = this.selectFirst("img")?.attr("alt")?.trim()
            ?: this.selectFirst("h3")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(this.selectFirst("img")?.attr("src"))

        val yearText = this.select("span.text-gray-300").text()
        val year = Regex("""\b(19|20)\d{2}\b""").find(yearText)?.value?.toIntOrNull()

        val imdbText = this.selectFirst("span.text-accent span:last-child")?.text()?.trim()
            ?: this.select("span.text-accent").lastOrNull()?.text()?.trim()
        val imdbScore = imdbText?.toFloatOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
            this.score = imdbScore?.let { Score.from10(it) }
        }
    }

    // ============================================================
    // ARAMA - JSON API Kullanimi
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(name, "search basladi - Sorgu: $query")
        return try {
            val response = app.get("${mainUrl}/api/search?q=${query}")
            val json = response.parsed<SearchApiResponse>()

            val results = json.data.mapNotNull { item ->
                val href = "/${item.slug}/"
                val year = item.releaseYear.toIntOrNull()

                newMovieSearchResponse(item.title, href, TvType.Movie) {
                    this.posterUrl = fixUrlNull(item.posterUrl)
                    this.year = year
                    this.score = if (item.imdbRating > 0) Score.from10(item.imdbRating.toFloat()) else null
                }
            }

            Log.d(name, "search tamamlandi - ${results.size} sonuc bulundu")
            results
        } catch (e: Exception) {
            Log.e(name, "search hatasi: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        Log.d(name, "quickSearch basladi - Sorgu: $query")
        return search(query)
    }

    // ============================================================
    // FILM/DIZI DETAY - Parse
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load basladi - URL: $url")

        val document = app.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0")
        ).document

        // ===== BASLIK =====
        val title = document.selectFirst("h1.text-4xl.font-bold")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        if (title == null) {
            Log.e(name, "load - Baslik bulunamadi")
            return null
        }

        // ===== POSTER (og:image + fallback) =====
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("div.aspect-\\[2\\/3\\] img")?.attr("src")
                ?: document.selectFirst("div[class*='aspect'] img[alt]")?.attr("src")
        )

        // ===== YIL =====
        val year = document.select("a[href*='/yil/']").mapNotNull {
            it.text().trim().toIntOrNull()
        }.firstOrNull()
        Log.d(name, "load - Yil: $year")

        // ===== OZET =====
        val description = document.selectFirst("div[x-ref='content']")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        // ===== TURLER (sadece bilgi kutusundakiler) =====
        val tags = document.select("div:containsOwn(Türler:) + div a[href*='/tur/'], div:containsOwn(Türler:) ~ div a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // Fallback: eğer yukarısı boşsa, daha genel ama menüyü hariç tutan seçici
        val finalTags = tags.ifEmpty {
            document.select("section a[href*='/tur/']")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() && it.length < 30 } // menü öğeleri genelde kısa değil, ama bu ek güvenlik
                .distinct()
        }
        Log.d(name, "load - Turler: $finalTags")

        // ===== IMDB =====
        val imdbText = document.selectFirst("a[href*='imdb.com']")?.text()?.trim()
        val rating = imdbText?.let {
            Regex("""([0-9]+(?:\.[0-9]+)?)""").find(it)?.groupValues?.get(1)
        }

        // ===== SURE =====
        val duration = document.select("span").map { it.ownText().trim() }
            .firstOrNull { it.matches(Regex("""\d+\s*dk""")) || it.matches(Regex("""\d+\s*dakika""")) }
            ?.replace(Regex("""[^0-9]"""), "")
            ?.toIntOrNull()
        Log.d(name, "load - Sure: $duration")

        // ===== OYUNCULAR =====
        val actorElements = document.select("a[href*='/oyuncu/']")
        Log.d(name, "load - Oyuncu element sayisi: ${actorElements.size}")

        val actors = actorElements.mapNotNull { a ->
            val actorName = a.selectFirst("h4")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim()
            val actorImg = fixUrlNull(a.selectFirst("img")?.attr("src"))
            val characterName = a.selectFirst("p")?.text()?.trim()

            if (actorName.isNullOrBlank()) {
                Log.w(name, "load - Oyuncu adi bos, atlaniyor")
                null
            } else {
                Log.d(name, "load - Oyuncu bulundu: $actorName | Karakter: $characterName | Resim: $actorImg")
                Pair(Actor(actorName, actorImg), characterName)
            }
        }
        Log.d(name, "load - Toplam gecerli oyuncu: ${actors.size}")

        // ===== FRAGMAN =====
        val trailerRaw = document.selectFirst("iframe[src*='youtube.com/embed/']")?.attr("src")
            ?: document.selectFirst("iframe[src*='youtu.be']")?.attr("src")
            ?: ""
        val trailer = when {
            trailerRaw.startsWith("//") -> "https:$trailerRaw"
            trailerRaw.startsWith("http") -> trailerRaw
            trailerRaw.isNotBlank() -> fixUrl(trailerRaw)
            else -> ""
        }
        Log.d(name, "load - Fragman URL: $trailer")

        // ===== ONERILER =====
        val recommendations = document.select("a.group\\/poster.block").mapNotNull { item ->
            val recName = item.selectFirst("img")?.attr("alt")?.trim()
                ?: item.selectFirst("h3")?.text()?.trim()
                ?: return@mapNotNull null

            val recHref = fixUrlNull(item.attr("href")) ?: return@mapNotNull null
            val recPoster = fixUrlNull(item.selectFirst("img")?.attr("src"))

            newMovieSearchResponse(recName, recHref, TvType.Movie) {
                this.posterUrl = recPoster
                val recYear = Regex("""\b(19|20)\d{2}\b""").find(item.text())?.value?.toIntOrNull()
                this.year = recYear
            }
        }

        val isSeries = url.contains("/dizi/")

        Log.d(name, "load tamamlandi - Baslik: $title, Yil: $year, IMDb: $rating, Sure: $duration, Tur: ${if (isSeries) "Dizi" else "Film"}")

        return if (isSeries) {
            // ===== DIZI DETAY =====
            val episodes = mutableListOf<Episode>()

            // Sezon tab'larını bul
            val seasonTabs = document.select("nav[aria-label='Sezonlar'] button, nav button[role='tab']")

            if (seasonTabs.isNotEmpty()) {
                // Her sezon panelini işle
                seasonTabs.forEach { tab ->
                    val seasonText = tab.text().trim() // "5. Sezon"
                    val seasonNum = Regex("""(\d+)\.\s*Sezon""").find(seasonText)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""(\d+)""").find(seasonText)?.value?.toIntOrNull()

                    if (seasonNum != null) {
                        // Bu sezona ait bölüm linklerini bul
                        // Tablo ve mobil liste yapısındaki tüm bölüm linkleri
                        val episodeLinks = document.select("a[href*='${url.removeSuffix("/")}/sezon-$seasonNum/bolum-']")

                        episodeLinks.forEach { epLink ->
                            val epHref = fixUrlNull(epLink.attr("href")) ?: return@forEach
                            val epName = epLink.text().trim()

                            // Bölüm numarasını URL'den çıkar: /bolum-1 -> 1
                            val epNum = Regex("""/bolum-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()

                            if (epNum != null) {
                                episodes.add(
                                    newEpisode(epHref) {
                                        this.name = epName
                                        this.season = seasonNum
                                        this.episode = epNum
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Eğer yukarıdaki seçici çalışmazsa, tüm bölüm linklerini topla ve sezon/bölümü URL'den çıkar
            if (episodes.isEmpty()) {
                document.select("a[href*='/sezon-'][href*='/bolum-']").forEach { epLink ->
                    val epHref = fixUrlNull(epLink.attr("href")) ?: return@forEach
                    val epName = epLink.text().trim()

                    val seasonMatch = Regex("""/sezon-(\d+)""").find(epHref)
                    val epMatch = Regex("""/bolum-(\d+)""").find(epHref)

                    val seasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            }

            val sortedEpisodes = episodes.distinctBy { "${it.season}-${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = finalTags
                this.score = rating?.let { Score.from10(it.toFloat()) }
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                if (trailer.isNotBlank()) {
                    addTrailer(trailer)
                }
            }
        } else {
            // ===== FILM DETAY =====
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = finalTags
                this.score = rating?.let { Score.from10(it.toFloat()) }
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                if (trailer.isNotBlank()) {
                    addTrailer(trailer)
                }
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
        Log.d(name, "loadLinks basladi - Data: $data")

        val document = app.get(data).document

        val videoPlayerDiv = document.selectFirst("div[data-video-player]")
        val videoDataAttr = videoPlayerDiv?.attr("x-data")

        if (videoDataAttr != null) {
            val jsonMatch = Regex("""videoPlayerData\(JSON\.parse\('([^']+)'\)""").find(videoDataAttr)
            if (jsonMatch != null) {
                val jsonStr = jsonMatch.groupValues[1]
                    .replace("\\u0022", "\"")
                    .replace("\\\\", "\\")

                try {
                    val videoData = parseJson<VideoPlayerData>(jsonStr)

                    // Dil gruplarini ayri ayri isle
                    val allVideos = mutableListOf<Pair<VideoItem, String>>()
                    videoData.dual?.forEach { allVideos.add(it to "dual") }
                    videoData.tr?.forEach { allVideos.add(it to "tr") }
                    videoData.eng?.forEach { allVideos.add(it to "eng") }
                    videoData.en?.forEach { allVideos.add(it to "en") }
                    videoData.yerli?.forEach { allVideos.add(it to "yerli") }

                    Log.d(name, "loadLinks - Toplam video kaynagi: ${allVideos.size}")

                    allVideos.forEach { (video, group) ->
                        val videoLink = video.link
                        val videoTemplate = video.template
                        val videoSlug = video.slug
                        val serviceName = video.service_name

                        if (videoLink.isNotBlank() && videoTemplate.isNotBlank()) {
                            try {
                                val decodedTemplate = String(
                                    android.util.Base64.decode(videoTemplate, android.util.Base64.DEFAULT),
                                    Charsets.UTF_8
                                )

                                val iframeMatch = Regex("""data-src=["']([^"']+)["']""").find(decodedTemplate)
                                    ?: Regex("""src=["']([^"']+)["']""").find(decodedTemplate)

                                var embedUrl = iframeMatch?.groupValues?.get(1)?.let { src ->
                                    src.replace("{url}", videoLink)
                                        .replace("{slug}", videoSlug)
                                }

                                embedUrl = embedUrl?.let { url ->
                                    when {
                                        url.startsWith("//") -> "https:$url"
                                        url.startsWith("http") -> url
                                        else -> "https://$url"
                                    }
                                }

                                if (!embedUrl.isNullOrBlank()) {
                                    // Dil tag'i belirle
                                    val langTag = when {
                                        video.note?.contains("dublaj", ignoreCase = true) == true -> "#dublaj"
                                        video.note?.contains("altyazi", ignoreCase = true) == true -> "#altyazi"
                                        group == "dual" -> "#dublaj"
                                        group == "eng" || group == "en" -> "#altyazi"
                                        else -> "#tr"
                                    }
                                    val embedUrlWithLang = "$embedUrl$langTag"

                                    Log.d(name, "loadLinks - Embed URL: $embedUrlWithLang (Service: $serviceName, Group: $group)")
                                    loadExtractor(embedUrlWithLang, data, subtitleCallback, callback)
                                }
                            } catch (ex: Exception) {
                                Log.e(name, "loadLinks template parse hatasi: ${ex.message}")
                            }
                        }
                    }
                } catch (ex: Exception) {
                    Log.e(name, "loadLinks JSON parse hatasi: ${ex.message}")
                }
            }
        }

        val iframeSrc = document.selectFirst("iframe")?.attr("src")
        if (iframeSrc != null && videoDataAttr == null) {
            val altEmbedUrl = when {
                iframeSrc.startsWith("//") -> "https:$iframeSrc"
                iframeSrc.startsWith("http") -> iframeSrc
                else -> fixUrl(iframeSrc)
            }
            Log.d(name, "loadLinks - Alternatif Embed URL: $altEmbedUrl")
            loadExtractor(altEmbedUrl, data, subtitleCallback, callback)
        }

        return true
    }
    // ============================================================
    // DATA CLASSES
    // ============================================================
    data class SearchApiResponse(
        val data: List<SearchResultItem> = emptyList()
    )

    data class SearchResultItem(
        val id: String = "",
        val title: String = "",
        val slug: String = "",
        val posterUrl: String = "",
        val releaseYear: String = "",
        val imdbRating: Double = 0.0,
        val contentableType: String = ""
    )

    data class VideoPlayerData(
        val dual: List<VideoItem>? = null,
        val tr: List<VideoItem>? = null,
        val eng: List<VideoItem>? = null,
        val en: List<VideoItem>? = null,
        val yerli: List<VideoItem>? = null
    )

    data class VideoItem(
        val link: String = "",
        val service_slug: String = "",
        val service_name: String = "",
        val quality: String = "",
        val note: String? = null,
        val template: String = "",
        val slug: String = ""
    )
}