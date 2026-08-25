package com.blackhope01

import android.content.Context
import com.blackhope01.extractors.HdPlayerExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziMomPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziMom())

        registerExtractorAPI(HdPlayerExtractor())
    }
}
