package com.blackhope01

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TmdbPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TmdbProvider())

        //registerExtractorAPI(ExampleExtractor())
    }
}
