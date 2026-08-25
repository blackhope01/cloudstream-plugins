package com.blackhope01

import android.content.Context
import com.blackhope01.extractors.Ag2m4Extractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmHanePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmHane())

        registerExtractorAPI(Ag2m4Extractor())
    }
}
