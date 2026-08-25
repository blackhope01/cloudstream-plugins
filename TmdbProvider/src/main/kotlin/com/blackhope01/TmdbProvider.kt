package com.blackhope01

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset

// ═══════════════════════════════════════════════════════════
// 🔑 TMDB API KEY
// ═══════════════════════════════════════════════════════════
private const val TMDB_API_KEY = "TMDB_API_KEY"

private const val BASE_URL = "https://api.themoviedb.org/3"
private const val IMAGE_BASE = "https://image.tmdb.org/t/p/original"
private const val LANGUAGE = "tr-TR"

class TmdbProvider : MainAPI() {

    override var mainUrl = "https://www.themoviedb.org"
    override var name = "TMDB Provider"
    override var lang = "tr"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // ───────────────────────────────────────────────────────
    // 🌐 GÜVENLİ NETWORK YARDIMCISI
    // ───────────────────────────────────────────────────────
    // Tüm TMDB çağrıları buradan geçer. Ağ hatası, timeout ya da
    // bozuk JSON durumunda uygulamayı çökertmek yerine null döner.
    private suspend fun fetchJson(url: String): JSONObject? {
        return try {
            JSONObject(app.get(url).text)
        } catch (e: Exception) {
            null
        }
    }

    // "2024-05-01" -> 2024. Kısa/bozuk tarihlerde crash yerine null döner.
    private fun parseYear(dateStr: String?): Int? {
        if (dateStr.isNullOrBlank() || dateStr.length < 4) return null
        return dateStr.substring(0, 4).toIntOrNull()
    }

    private fun posterUrlOf(path: String?): String? =
        if (!path.isNullOrBlank()) "$IMAGE_BASE$path" else null

    // ───────────────────────────────────────────────────────
    // 1️⃣ ANA SAYFA
    // ───────────────────────────────────────────────────────
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val popularMovies = getMovieList("/movie/popular", page)
        val popularTv = getTvList("/tv/popular", page)

        return newHomePageResponse(
            listOf(
                HomePageList("Popüler Filmler", popularMovies),
                HomePageList("Popüler Diziler", popularTv)
            )
        )
    }

    // ───────────────────────────────────────────────────────
    // 2️⃣ ARAMA
    // ───────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val url =
            "$BASE_URL/search/multi?api_key=$TMDB_API_KEY&query=${urlEncode(query)}&language=$LANGUAGE"
        val json = fetchJson(url) ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()

        val list = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val mediaType = item.optString("media_type")
            val id = item.optInt("id")
            val title = item.optString("title").ifEmpty { item.optString("name") }
            if (id == 0 || title.isBlank()) continue

            val posterUrl = posterUrlOf(item.optString("poster_path"))
            val year = parseYear(
                item.optString("release_date").ifEmpty { item.optString("first_air_date") }
            )

            when (mediaType) {
                "movie" -> {
                    list.add(
                        newMovieSearchResponse(
                            name = title,
                            url = "$mainUrl/movie/$id",
                            fix = true
                        ).apply {
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    )
                }
                "tv" -> {
                    list.add(
                        newTvSeriesSearchResponse(
                            name = title,
                            url = "$mainUrl/tv/$id",
                            fix = true
                        ).apply {
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    )
                }
            }
        }
        return list
    }

    // ───────────────────────────────────────────────────────
    // 3️⃣ DETAY (load)
    // ───────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        val path = url.removePrefix(mainUrl).trimStart('/')
        val parts = path.split("/")
        if (parts.size < 2) return null
        val mediaType = parts[0]
        val id = parts[1].toIntOrNull() ?: return null

        return when (mediaType) {
            "movie" -> loadMovie(id)
            "tv" -> loadTvSeries(id)
            else -> null
        }
    }

    // ───────────────────────────────────────────────────────
    // 🎭 ORTAK PARSE YARDIMCILARI (film + dizi ikisinde de kullanılır)
    // ───────────────────────────────────────────────────────
    private fun parseGenres(json: JSONObject): List<String> {
        val genresArray = json.optJSONArray("genres") ?: return emptyList()
        val genres = mutableListOf<String>()
        for (i in 0 until genresArray.length()) {
            val name = genresArray.optJSONObject(i)?.optString("name")
            if (!name.isNullOrBlank()) genres.add(name)
        }
        return genres
    }

    private fun parseCast(json: JSONObject): List<ActorData> {
        val castArray = json.optJSONObject("credits")?.optJSONArray("cast") ?: return emptyList()
        val cast = mutableListOf<ActorData>()
        for (i in 0 until castArray.length()) {
            val actorObj = castArray.optJSONObject(i) ?: continue
            val actorName = actorObj.optString("name")
            if (actorName.isBlank()) continue
            val character = actorObj.optString("character")
            cast.add(
                ActorData(
                    actor = Actor(name = actorName, image = posterUrlOf(actorObj.optString("profile_path"))),
                    roleString = character.ifBlank { null }
                )
            )
        }
        return cast
    }

    private fun parseTrailers(json: JSONObject): MutableList<TrailerData> {
        val videos = json.optJSONObject("videos")?.optJSONArray("results") ?: return mutableListOf()
        val trailers = mutableListOf<TrailerData>()
        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue
            val key = video.optString("key")
            if (key.isBlank()) continue
            trailers.add(
                TrailerData(
                    extractorUrl = "https://www.youtube.com/watch?v=$key",
                    referer = "https://www.youtube.com",
                    raw = false
                )
            )
        }
        return trailers
    }

    private fun parseImdbId(json: JSONObject): String =
        json.optJSONObject("external_ids")?.optString("imdb_id") ?: ""

    // TMDB "status" alanı -> CloudStream ShowStatus
    // Ended / Canceled  -> Tamamlandı
    // Returning Series / In Production / Planned / Pilot -> Devam ediyor
    private fun parseShowStatus(json: JSONObject): ShowStatus? {
        return when (json.optString("status")) {
            "Ended", "Canceled" -> ShowStatus.Completed
            "Returning Series", "In Production", "Planned", "Pilot" -> ShowStatus.Ongoing
            else -> null
        }
    }

    // ───────────────────────────────────────────────────────
    // 🎬 FILM DETAYI
    // ───────────────────────────────────────────────────────
    private suspend fun loadMovie(id: Int): MovieLoadResponse? {
        val url =
            "$BASE_URL/movie/$id?api_key=$TMDB_API_KEY&language=$LANGUAGE&append_to_response=credits,videos,similar,external_ids"
        val json = fetchJson(url) ?: return null

        val title = json.optString("title")
        if (title.isBlank()) return null

        val overview = json.optString("overview")
        val year = parseYear(json.optString("release_date"))
        val runtime = json.optInt("runtime")
        val voteAverage = json.optDouble("vote_average", 0.0)

        val posterUrl = posterUrlOf(json.optString("poster_path"))
        val backgroundUrl = posterUrlOf(json.optString("backdrop_path"))
        val genres = parseGenres(json)
        val cast = parseCast(json)
        val trailers = parseTrailers(json)

        // Öneriler
        val similarArray = json.optJSONObject("similar")?.optJSONArray("results")
        val recommendations = mutableListOf<SearchResponse>()
        if (similarArray != null) {
            for (i in 0 until similarArray.length()) {
                val item = similarArray.optJSONObject(i) ?: continue
                val simTitle = item.optString("title")
                val simId = item.optInt("id")
                if (simId == 0 || simTitle.isBlank()) continue
                recommendations.add(
                    newMovieSearchResponse(
                        name = simTitle,
                        url = "$mainUrl/movie/$simId",
                        fix = true
                    ).apply {
                        this.posterUrl = posterUrlOf(item.optString("poster_path"))
                    }
                )
            }
        }

        val score = Score.from10(voteAverage.toFloat())

        return newMovieLoadResponse(
            name = title,
            url = "$mainUrl/movie/$id",
            type = TvType.Movie,
            dataUrl = "$mainUrl/movie/$id"
        ).apply {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backgroundUrl
            this.plot = overview
            this.year = year
            this.duration = runtime
            this.score = score
            this.tags = genres
            this.actors = cast
            this.trailers = trailers
            this.recommendations = recommendations
            this.syncData = mutableMapOf(
                "tmdb_id" to id.toString(),
                "imdb_id" to parseImdbId(json)
            )
        }
    }

    // ───────────────────────────────────────────────────────
    // 📺 DİZİ DETAYI
    // ───────────────────────────────────────────────────────
    private suspend fun loadTvSeries(id: Int): TvSeriesLoadResponse? {
        val url =
            "$BASE_URL/tv/$id?api_key=$TMDB_API_KEY&language=$LANGUAGE&append_to_response=credits,videos,similar,external_ids"
        val json = fetchJson(url) ?: return null

        val name = json.optString("name")
        if (name.isBlank()) return null

        val overview = json.optString("overview")
        val year = parseYear(json.optString("first_air_date"))
        val episodeRunTime = json.optJSONArray("episode_run_time")?.optInt(0) ?: 30
        val voteAverage = json.optDouble("vote_average", 0.0)
        val seasonsArray = json.optJSONArray("seasons") ?: JSONArray()

        val posterUrl = posterUrlOf(json.optString("poster_path"))
        val backgroundUrl = posterUrlOf(json.optString("backdrop_path"))
        val genres = parseGenres(json)
        val cast = parseCast(json)
        val trailers = parseTrailers(json)

        // Öneriler
        val similarArray = json.optJSONObject("similar")?.optJSONArray("results")
        val recommendations = mutableListOf<SearchResponse>()
        if (similarArray != null) {
            for (i in 0 until similarArray.length()) {
                val item = similarArray.optJSONObject(i) ?: continue
                val simTitle = item.optString("name")
                val simId = item.optInt("id")
                if (simId == 0 || simTitle.isBlank()) continue
                recommendations.add(
                    newTvSeriesSearchResponse(
                        name = simTitle,
                        url = "$mainUrl/tv/$simId",
                        fix = true
                    ).apply {
                        this.posterUrl = posterUrlOf(item.optString("poster_path"))
                    }
                )
            }
        }

        val score = Score.from10(voteAverage.toFloat())

        // Sonraki bölüm
        val nextEpisodeObj = json.optJSONObject("next_episode_to_air")
        val nextAiring = nextEpisodeObj?.let { obj ->
            val airDate = obj.optString("air_date")
            val unixTime = try {
                if (airDate.isNotBlank()) {
                    LocalDate.parse(airDate).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
                } else null
            } catch (_: Exception) { null }

            unixTime?.let {
                NextAiring(
                    unixTime = it,
                    episode = obj.optInt("episode_number"),
                    season = obj.optInt("season_number")
                )
            }
        }

        // Bölüm listesi — sezon detayları artık PARALEL çekiliyor
        // (önceden sırayla çekiliyordu, çok sezonlu dizilerde yavaştı)
        val episodes = coroutineScope {
            (0 until seasonsArray.length()).map { i ->
                async {
                    val seasonObj = seasonsArray.optJSONObject(i) ?: return@async emptyList<Episode>()
                    val seasonNumber = seasonObj.optInt("season_number")
                    if (seasonNumber < 0) return@async emptyList<Episode>()

                    val seasonUrl =
                        "$BASE_URL/tv/$id/season/$seasonNumber?api_key=$TMDB_API_KEY&language=$LANGUAGE"
                    val seasonJson = fetchJson(seasonUrl) ?: return@async emptyList<Episode>()
                    val episodeArray = seasonJson.optJSONArray("episodes") ?: return@async emptyList<Episode>()

                    (0 until episodeArray.length()).mapNotNull { j ->
                        val epObj = episodeArray.optJSONObject(j) ?: return@mapNotNull null
                        val epNumber = epObj.optInt("episode_number")
                        val epName = epObj.optString("name").ifEmpty { "Bölüm $epNumber" }
                        val epAirDate = epObj.optString("air_date")
                        val epRuntime = epObj.optInt("runtime")

                        val epUnixTime = try {
                            if (epAirDate.isNotBlank()) {
                                LocalDate.parse(epAirDate).atStartOfDay(ZoneOffset.UTC).toEpochSecond() * 1000
                            } else null
                        } catch (_: Exception) { null }

                        newEpisode(
                            url = "$mainUrl/tv/$id/season/$seasonNumber/episode/$epNumber"
                        ).apply {
                            this.name = epName
                            this.season = seasonNumber
                            this.episode = epNumber
                            this.posterUrl = posterUrlOf(epObj.optString("still_path"))
                            this.description = epObj.optString("overview")
                            this.date = epUnixTime
                            this.runTime = if (epRuntime > 0) epRuntime else null
                        }
                    }
                }
            }.awaitAll()
                .flatten()
                .sortedWith(compareBy({ it.season }, { it.episode }))
        }

        // Sezon isimleri
        val seasonNames = mutableListOf<SeasonData>()
        for (i in 0 until seasonsArray.length()) {
            val seasonObj = seasonsArray.optJSONObject(i) ?: continue
            val seasonNumber = seasonObj.optInt("season_number")
            if (seasonNumber < 0) continue
            val sName = seasonObj.optString("name").ifEmpty { "Sezon $seasonNumber" }
            seasonNames.add(SeasonData(season = seasonNumber, name = sName))
        }

        return newTvSeriesLoadResponse(
            name = name,
            url = "$mainUrl/tv/$id",
            type = TvType.TvSeries,
            episodes = episodes
        ).apply {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backgroundUrl
            this.plot = overview
            this.year = year
            this.duration = episodeRunTime
            this.score = score
            this.tags = genres
            this.actors = cast
            this.trailers = trailers
            this.recommendations = recommendations
            this.nextAiring = nextAiring
            this.seasonNames = seasonNames
            this.showStatus = parseShowStatus(json)
            this.syncData = mutableMapOf(
                "tmdb_id" to id.toString(),
                "imdb_id" to parseImdbId(json)
            )
        }
    }

    // ───────────────────────────────────────────────────────
    // 4️⃣ VIDEO LİNKLERİ (loadLinks)
    // ───────────────────────────────────────────────────────
    // ⚠️ NOT: Bu eklenti şu an sadece TMDB metadata'sı çekiyor,
    // gerçek video/altyazı kaynağı sağlamıyor. Bir kaynak siteden
    // (örn. bir Türkçe izleme sitesi) link çekmek istiyorsan
    // hangi siteyi kullanacağımı söyle, o kısmı da ekleyeyim.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }

    // ───────────────────────────────────────────────────────
    // YARDIMCI FONKSİYONLAR
    // ───────────────────────────────────────────────────────
    private suspend fun getMovieList(endpoint: String, page: Int): List<SearchResponse> {
        val url = "$BASE_URL$endpoint?api_key=$TMDB_API_KEY&language=$LANGUAGE&page=$page"
        val json = fetchJson(url) ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()

        val list = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optInt("id")
            val title = item.optString("title")
            if (id == 0 || title.isBlank()) continue
            list.add(
                newMovieSearchResponse(
                    name = title,
                    url = "$mainUrl/movie/$id",
                    fix = true
                ).apply {
                    this.posterUrl = posterUrlOf(item.optString("poster_path"))
                    this.year = parseYear(item.optString("release_date"))
                }
            )
        }
        return list
    }

    private suspend fun getTvList(endpoint: String, page: Int): List<SearchResponse> {
        val url = "$BASE_URL$endpoint?api_key=$TMDB_API_KEY&language=$LANGUAGE&page=$page"
        val json = fetchJson(url) ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()

        val list = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optInt("id")
            val name = item.optString("name")
            if (id == 0 || name.isBlank()) continue
            list.add(
                newTvSeriesSearchResponse(
                    name = name,
                    url = "$mainUrl/tv/$id",
                    fix = true
                ).apply {
                    this.posterUrl = posterUrlOf(item.optString("poster_path"))
                    this.year = parseYear(item.optString("first_air_date"))
                }
            )
        }
        return list
    }

    private fun urlEncode(str: String): String {
        return java.net.URLEncoder.encode(str, "UTF-8")
    }
}