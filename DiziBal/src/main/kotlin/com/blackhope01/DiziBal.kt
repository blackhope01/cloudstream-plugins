package com.blackhope01

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder
import java.net.URLEncoder

class DiziBal : MainAPI() {
    override var mainUrl              = "https://dizibal.org"
    override var name                 = "DiziBal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val apiUrl = "$mainUrl/api"
    private val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    override val mainPage = mainPageOf(
        "$apiUrl/movies?sort=-release_date&limit=20&page=" to "Yeni Filmler",
        "$apiUrl/series?network=Netflix&limit=20&page=" to "Netflix Dizileri",
        "$apiUrl/series?network=Prime%20Video&limit=20&page=" to "Prime Video Dizileri",
        "$apiUrl/series?network=Disney%2B&limit=20&page=" to "Disney+ Dizileri",
        "$apiUrl/series?network=Apple%20TV&limit=20&page=" to "Apple TV Dizileri",
        "$apiUrl/series?network=Hulu&limit=20&page=" to "Hulu Dizileri",
        "$apiUrl/series?network=HBO&limit=20&page=" to "HBO Dizileri",
        "$apiUrl/series?network=GA%C4%B0N&limit=20&page=" to "GAİN Dizileri",
        "$apiUrl/series?network=Exxen&limit=20&page=" to "Exxen Dizileri",
        "$apiUrl/series?network=BluTV&limit=20&page=" to "BluTV Dizileri",
        "$apiUrl/series?network=TOD&limit=20&page=" to "TOD Dizileri",
        "$apiUrl/series?network=puhutv&limit=20&page=" to "puhutv Dizileri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val url = request.data + page
            val response = app.get(url)
            val res = response.parsedSafe<WListResponse>()
            if (res == null) {
                newHomePageResponse(emptyList())
            } else {
                val items = res.data?.mapNotNull { it.toSearchResponse() } ?: emptyList()
                val hasNext = (res.pagination?.page ?: 1) < (res.pagination?.totalPages ?: 1)
                newHomePageResponse(request.name, items, hasNext)
            }
        } catch (e: Exception) {
            newHomePageResponse(emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val moviesUrl = "$apiUrl/movies?search=$encodedQuery&page=1&limit=20"
        val seriesUrl = "$apiUrl/series?search=$encodedQuery&page=1&limit=20"
        val animeUrl = "$apiUrl/anime?search=$encodedQuery&page=1&limit=20"

        val movies = app.get(moviesUrl).parsedSafe<WListResponse>()?.data?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        val series = app.get(seriesUrl).parsedSafe<WListResponse>()?.data?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        val animes = app.get(animeUrl).parsedSafe<WListResponse>()?.data?.mapNotNull { it.toSearchResponse() } ?: emptyList()

        return movies + series + animes
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val parts = url.split("||")
            if (parts.size < 2) throw Exception("Bozuk URL")

            val typeStr = parts[0]
            val slug = parts[1]
            val dbId = if (parts.size > 2) parts[2] else ""

            val isSeries = typeStr.contains(TvType.TvSeries.name) || (typeStr.contains(TvType.Anime.name) && dbId.isNotEmpty())

            val detailUrl = if (isSeries) "$apiUrl/series/$slug" else "$apiUrl/movies/$slug"

            val res = app.get(detailUrl).parsedSafe<WDetailResponse>()?.data ?: throw Exception("Boş yanıt geldi")

            val titleStr = res.title_tr ?: res.title_en ?: res.title ?: res.name_tr ?: res.name_en ?: res.name ?: throw Exception("Başlık bulunamadı")
            val posterStr = res.poster_url ?: res.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" } ?: res.backdrop_url ?: res.backdrop_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            val descStr = res.overview_tr ?: res.overview_en ?: res.overview
            val yearInt = res.release_date?.substringBefore("-")?.toIntOrNull() ?: res.first_air_date?.substringBefore("-")?.toIntOrNull()
            val ratingDbl = res.vote_average
            val tagsList = res.genres?.mapNotNull { it.name }

            if (isSeries) {
                val episodes = mutableListOf<Episode>()
                val fetchDbId = res._id ?: dbId
                res.seasons?.forEach { season ->
                    val sNum = season.season_number ?: return@forEach
                    val seasonUrl = "$apiUrl/series/$slug/seasons/$sNum"
                    val seasonData = app.get(seasonUrl).parsedSafe<WSeasonResponse>()?.data ?: return@forEach

                    seasonData.episodes?.forEach { ep ->
                        val eNum = ep.episode_number ?: return@forEach
                        val epName = ep.name_tr ?: ep.name_en ?: ep.name
                        val epUrl = "$apiUrl/series/$fetchDbId/seasons/$sNum/episodes/$eNum/stream"
                        episodes.add(
                            newEpisode(epUrl) {
                                this.name = epName
                                this.season = sNum
                                this.episode = eNum
                            }
                        )
                    }
                }

                newTvSeriesLoadResponse(titleStr, url, TvType.TvSeries, episodes) {
                    this.posterUrl = posterStr
                    this.plot = descStr
                    this.year = yearInt
                    this.tags = tagsList
                    if (ratingDbl != null && ratingDbl > 0) {
                        try { this.score = Score.from10(ratingDbl.toString()) } catch (e: Exception) {}
                    }
                }
            } else {
                val streamUrl = res.streamUrl
                val sourceUrl = if (!streamUrl.isNullOrEmpty()) streamUrl else url

                newMovieLoadResponse(titleStr, url, TvType.Movie, sourceUrl) {
                    this.posterUrl = posterStr
                    this.plot = descStr
                    this.year = yearInt
                    this.tags = tagsList
                    if (ratingDbl != null && ratingDbl > 0) {
                        try { this.score = Score.from10(ratingDbl.toString()) } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var streamUrl = data
            Log.d(name, "loadLinks başladı, data: $data")

            if (data.startsWith("$apiUrl/series/")) {
                Log.d(name, "Dizi bölümü stream URL'si alınıyor: $data")
                val res = app.get(data).parsedSafe<WStreamResponse>()?.data
                streamUrl = res?.streamUrl ?: throw Exception("Gerekli veri bulunamadı")
                Log.d(name, "Alınan streamUrl: $streamUrl")
            }

            if (streamUrl.contains("/embed-")) {
                Log.d(name, "Embed URL tespit edildi: $streamUrl")
                val embedRes = app.get(streamUrl, referer = apiUrl)
                val html = embedRes.text
                val fetchPath = Regex("""fetch\(['"](/dl\?op=get_stream.*?)['"]\)""").find(html)?.groupValues?.get(1)
                Log.d(name, "fetchPath: $fetchPath")

                if (fetchPath != null) {
                    val host = streamUrl.substringBefore("/embed-")
                    val jsonUrl = "$host$fetchPath"
                    val fileId = Regex("""cookie\('file_id',\s*'(\d+)'""").find(html)?.groupValues?.get(1)
                    Log.d(name, "fileId: $fileId, jsonUrl: $jsonUrl")

                    val customCookies = mutableMapOf<String, String>()
                    if (fileId != null) customCookies["file_id"] = fileId
                    customCookies["aff"] = "1"
                    customCookies["ref_url"] = "dizibal.com"

                    val headers = mapOf(
                        "Accept" to "application/json, text/plain, */*",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "same-origin",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                    val streamRes = app.get(jsonUrl, referer = streamUrl, headers = headers, cookies = customCookies)
                    Log.d(name, "JSON yanıtı alındı, uzunluk: ${streamRes.text.length}")

                    val urlToStream = Regex(""""url":"([^"]+)"""").find(streamRes.text)?.groupValues?.get(1)?.replace("\\/", "/")
                    Log.d(name, "urlToStream: $urlToStream")

                    if (urlToStream != null && urlToStream.isNotBlank()) {
                        callback(
                            newExtractorLink(
                                source = "DiziBal",
                                name = "DiziBal HD",
                                url = urlToStream,
                                type = if(urlToStream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = streamUrl
                            }
                        )
                        Log.d(name, "Extractor link eklendi")

                        val subs = Regex(""""subtitle":"([^"]+)"""").find(html)?.groupValues?.get(1)
                        if (subs != null) {
                            subs.split(",").forEach { subEntry ->
                                val subParts = subEntry.split("]")
                                if (subParts.size > 1) {
                                    val lang = subParts[0].replace("[", "").trim()
                                    val subUrlPart = subParts[1].trim()
                                    val subUrl = if (subUrlPart.startsWith("http")) subUrlPart else host + subUrlPart
                                    subtitleCallback(SubtitleFile(lang, subUrl))
                                    Log.d(name, "Altyazı eklendi: $lang -> $subUrl")
                                }
                            }
                        }
                    } else {
                        Log.d(name, "urlToStream bulunamadı veya boş")
                    }
                } else {
                    Log.d(name, "fetchPath bulunamadı")
                }

                loadExtractor(streamUrl, subtitleCallback, callback)
                Log.d(name, "loadExtractor tamamlandı")
                true
            } else {
                Log.d(name, "streamUrl embed içermiyor: $streamUrl")
                throw Exception("Gerekli veri bulunamadı")
            }
        } catch (e: Exception) {
            Log.e(name, "loadLinks hatası: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun WItem.toSearchResponse(): SearchResponse? {
        val titleStr = this.title_tr ?: this.title_en ?: this.title ?: this.name_tr ?: this.name_en ?: this.name ?: return null
        val posterStr = this.poster_url ?: this.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
        val slugStr = this.slug ?: return null
        val dbId = this._id ?: ""

        val isSeries = !this.name.isNullOrEmpty() || !this.name_tr.isNullOrEmpty()
        val isAnime = this.isAnime == true

        val type = when {
            isAnime && isSeries -> TvType.Anime
            isSeries -> TvType.TvSeries
            isAnime && !isSeries -> TvType.Anime
            else -> TvType.Movie
        }

        val urlData = "$type||$slugStr||$dbId"

        return newMovieSearchResponse(titleStr, urlData, type) {
            this.posterUrl = posterStr
        }
    }

    data class WListResponse(
        @JsonProperty("success") val success: Boolean?,
        @JsonProperty("data") val data: List<WItem>?,
        @JsonProperty("pagination") val pagination: WPagination?
    )

    data class WDetailResponse(
        @JsonProperty("success") val success: Boolean?,
        @JsonProperty("data") val data: WItemDetail?
    )

    data class WStreamResponse(
        @JsonProperty("success") val success: Boolean?,
        @JsonProperty("data") val data: WStreamData?
    )

    data class WSeasonResponse(
        @JsonProperty("success") val success: Boolean?,
        @JsonProperty("data") val data: WSeason?
    )

    data class WPagination(
        @JsonProperty("page") val page: Int?,
        @JsonProperty("limit") val limit: Int?,
        @JsonProperty("total") val total: Int?,
        @JsonProperty("totalPages") val totalPages: Int?
    )

    data class WItem(
        @JsonProperty("_id") val _id: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("title_tr") val title_tr: String?,
        @JsonProperty("title_en") val title_en: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("name_tr") val name_tr: String?,
        @JsonProperty("name_en") val name_en: String?,
        @JsonProperty("poster_url") val poster_url: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("backdrop_url") val backdrop_url: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("isAnime") val isAnime: Boolean?
    )

    data class WItemDetail(
        @JsonProperty("_id") val _id: String?,
        @JsonProperty("slug") val slug: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("title_tr") val title_tr: String?,
        @JsonProperty("title_en") val title_en: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("name_tr") val name_tr: String?,
        @JsonProperty("name_en") val name_en: String?,
        @JsonProperty("poster_url") val poster_url: String?,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("backdrop_url") val backdrop_url: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("overview_tr") val overview_tr: String?,
        @JsonProperty("overview_en") val overview_en: String?,
        @JsonProperty("release_date") val release_date: String?,
        @JsonProperty("first_air_date") val first_air_date: String?,
        @JsonProperty("vote_average") val vote_average: Double?,
        @JsonProperty("genres") val genres: List<WGenre>?,
        @JsonProperty("streamUrl") val streamUrl: String?,
        @JsonProperty("seasons") val seasons: List<WSeason>?
    )

    data class WGenre(@JsonProperty("name") val name: String?)

    data class WSeason(
        @JsonProperty("season_number") val season_number: Int?,
        @JsonProperty("episodes") val episodes: List<WEpisode>?
    )

    data class WEpisode(
        @JsonProperty("episode_number") val episode_number: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("name_tr") val name_tr: String?,
        @JsonProperty("name_en") val name_en: String?
    )

    data class WStreamData(
        @JsonProperty("streamUrl") val streamUrl: String?
    )
}