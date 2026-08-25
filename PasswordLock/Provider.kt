package com.blackhope01

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app

class Provider : MainAPI() {

    override var mainUrl = "https://example.com"
    override var name = "YourProvider"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Yeni Eklenenler",
        "$mainUrl/populer/" to "Popüler"
    )

    // START
    private val passwordLock = PasswordLock(
        correctPassword = "1234",
        prefsName = "yourprovider_prefs"
    )
    // END

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        // START
        if (!passwordLock.ensureUnlocked()) {
            throw ErrorLoadingException("Şifre doğrulanmadı")
        }
        // END

        val pageUrl = if (page == 1) request.data
        else request.data.trimEnd('/') + "/page/$page/"

        val document = app.get(pageUrl).document

        val home = document.select("div.single-item").mapNotNull { it.toSearchResult() }
        val hasNext = document.select("div.paginate-links a.next").isNotEmpty()

        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a")?.attr("title") ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val poster = this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }
}