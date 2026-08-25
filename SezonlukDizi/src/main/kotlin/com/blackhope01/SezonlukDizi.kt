package com.blackhope01

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject

class SezonlukDizi : MainAPI() {
    override var mainUrl = "https://sezonlukdizi.cc"
    override var name = "SezonlukDizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler.asp?s=1&tur=bilimkurgu" to "Bilim Kurgu",
        "${mainUrl}/diziler.asp?s=1&tur=aksiyon" to "Aksiyon",
        "${mainUrl}/diziler.asp?s=1&tur=komedi" to "Komedi",
        "${mainUrl}/diziler.asp?s=1&tur=dram" to "Dram",
        "${mainUrl}/diziler.asp?s=1&tur=gerilim" to "Gerilim",
        "${mainUrl}/diziler.asp?s=1" to "Tüm Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(name, "getMainPage - Sayfa: $page, Kategori: ${request.name}")
        val url = request.data.replace(Regex("""s=\d+"""), "s=$page")
        val document = app.get(url).document
        val home = document.select("a[href^='/diziler/']").mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        Log.d(name, "getMainPage - ${home.size} içerik bulundu")
        return newHomePageResponse(request.name, home)
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/diziler/")) return null
        val title = this.selectFirst("img")?.attr("alt")?.trim()
            ?: this.text().trim()
            ?: return null
        val img = this.selectFirst("img")
        val poster = fixUrlNull(img?.attr("data-src")) ?: fixUrlNull(img?.attr("src"))
        val text = this.text()
        val year = Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
        val imdbText = Regex("""IMDb\s*([0-9,\.]+)""").find(text)?.groupValues?.get(1)?.replace(",", ".")?.trim()
        val imdbScore = imdbText?.toFloatOrNull()
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
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
                    "Referer" to mainUrl
                )
            ).text

            val json = JSONObject(response)
            val results = json.optJSONObject("results") ?: return emptyList()
            val diziler = results.optJSONObject("diziler")?.optJSONArray("results") ?: return emptyList()

            val searchResponses = mutableListOf<SearchResponse>()
            for (i in 0 until diziler.length()) {
                val item = diziler.getJSONObject(i)
                val titleWithYear = item.optString("title")
                val url = fixUrlNull(item.optString("url")) ?: continue
                val poster = fixUrlNull(item.optString("image"))
                val imdb = item.optDouble("imdb", -1.0).takeIf { it >= 0 }?.toFloat()

                val yearMatch = Regex("""\((\d{4})\)$""").find(titleWithYear)
                val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
                val title = titleWithYear.replace(Regex("""\(\d{4}\)$"""), "").trim()

                searchResponses.add(
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                        this.score = imdb?.let { Score.from10(it) }
                    }
                )
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
        val document = app.get(url).document

        val title       = document.selectFirst("#dizibilgisi .header")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("#dizibilgisi img")?.attr("data-src")) ?: return null
        val year        = document.selectFirst("#dizibilgisi .extra.content .right.floated")?.text()?.trim()?.split("-")?.first()?.toIntOrNull()
        val description = document.selectFirst("span#tartismayorum-konu p")?.text()?.trim()
        val tags        = document.select("#dizibilgisi .description .ui.mini.labels a[href*='tur=']").mapNotNull { it.text().trim() }
        val ratingText  = document.selectFirst(".ui.label.imdb .detail")?.text()?.replace(",", ".")?.trim()
        val rating      = ratingText?.toFloatOrNull()
        val duration    = document.selectFirst("#dizibilgisi .description .ui.mini.labels .orange.label")?.text()?.trim()?.substringBefore(" Dk.")?.toIntOrNull()

        val endpoint    = url.split("/").last()

        val actorsReq  = app.get("${mainUrl}/oyuncular/${endpoint}").document
        val actors = actorsReq.select(".ui.five.column.doubling.grid .column .ui.card").mapNotNull { card ->
            val name = card.selectFirst(".header")?.text()?.trim() ?: return@mapNotNull null
            val imgTag = card.selectFirst("img")
            val image = fixUrlNull(imgTag?.attr("src"))
                ?: "https://ui-avatars.com/api/?name=${name.replace(" ", "+")}&size=100&background=111111&color=fff"
            val character = card.selectFirst(".extra.content")?.text()
            Pair(Actor(name, image), character)
        }

        val episodesReq = app.get("${mainUrl}/bolumler/${endpoint}").document
        val episodes    = mutableListOf<Episode>()
        for (sezon in episodesReq.select("table.unstackable")) {
            for (bolum in sezon.select("tbody tr")) {
                val epName    = bolum.selectFirst("td:nth-of-type(4) a")?.text()?.trim() ?: continue
                val epHref    = fixUrlNull(bolum.selectFirst("td:nth-of-type(4) a")?.attr("href")) ?: continue
                val epEpisode = bolum.selectFirst("td:nth-of-type(3)")?.text()?.substringBefore(".Bölüm")?.trim()?.toIntOrNull()
                val epSeason  = bolum.selectFirst("td:nth-of-type(2)")?.text()?.substringBefore(".Sezon")?.trim()?.toIntOrNull()

                episodes.add(newEpisode(epHref) {
                    this.name    = epName
                    this.season  = epSeason
                    this.episode = epEpisode
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.score     = rating?.let { Score.from10(it) }
            this.duration  = duration
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(name, "loadLinks başladı - Data: $data")
        var found = false

        try {
            val bolumDoc = app.get(data).document

            val bolumId = bolumDoc.selectFirst("#dilsec")?.attr("data-id")
                ?: bolumDoc.toString().let { html ->
                    Regex("""data-id=["'](\d+)["']""").find(html)?.groupValues?.get(1)
                }
                ?: return false

            // Dublaj referer: /slug/1-sezon-1-bolum.html -> /slug/dublaj/1-sezon-1-bolum.html
            val lastSlash = data.lastIndexOf("/")
            val base = data.substring(0, lastSlash)      // https://.../chernobyl
            val bolumFile = data.substring(lastSlash + 1) // 1-sezon-1-bolum.html
            val dublajReferer = "$base/dublaj/$bolumFile"

            val diller = listOf(
                "0" to ("Türkçe Dublaj" to dublajReferer),
                "1" to ("Türkçe Altyazılı" to data)
            )

            for ((dil, pair) in diller) {
                val (dilLabel, refererUrl) = pair
                try {
                    val altResp = app.post(
                        url = "${mainUrl}/ajax/dataAlternatif22.asp",
                        data = mapOf("bid" to bolumId, "dil" to dil),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "Referer" to refererUrl
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
                            val embedResp = app.post(
                                url = "${mainUrl}/ajax/dataEmbed22.asp",
                                data = mapOf("id" to altId),
                                headers = mapOf(
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "Content-Type" to "application/x-www-form-urlencoded",
                                    "Referer" to refererUrl
                                )
                            ).text

                            if (embedResp.contains("reCAPTCHA", true) ||
                                embedResp.contains("reCAPTCHADATA", true) ||
                                embedResp.contains("<iframe", true).not()) {
                                Log.d(name, "loadLinks - $baslik reCAPTCHA veya iframe yok, atlanıyor")
                                continue
                            }

                            val iframeMatch = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(embedResp)
                            val iframeSrc = iframeMatch?.groupValues?.get(1)

                            if (!iframeSrc.isNullOrBlank()) {
                                val finalUrl = when {
                                    iframeSrc.startsWith("//") -> "https:$iframeSrc"
                                    iframeSrc.startsWith("http") -> iframeSrc
                                    else -> fixUrl(iframeSrc)
                                }

                                val displayName = "$baslik - $dilLabel"
                                Log.d(name, "loadLinks - Extractor: $finalUrl ($displayName)")
                                loadExtractor(finalUrl, refererUrl, subtitleCallback, callback)
                                found = true
                            }
                        } catch (e: Exception) {
                            Log.e(name, "loadLinks - Alternatif hata ($dilLabel): ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(name, "loadLinks - Dil hata ($dilLabel): ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(name, "loadLinks - Genel hata: ${e.message}")
        }

        Log.d(name, "loadLinks tamamlandı - Found: $found")
        return found
    }
}