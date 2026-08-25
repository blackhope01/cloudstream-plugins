package com.blackhope01

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.blackhope01.extractors.CloseLoadExtractor
import com.blackhope01.extractors.RapidExtractor




@CloudstreamPlugin
class FilmMakinesiPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmMakinesi())

        registerExtractorAPI(CloseLoadExtractor())
        registerExtractorAPI(RapidExtractor())

    }
}
