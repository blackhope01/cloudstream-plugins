package com.blackhope01

import android.content.Context
import com.blackhope01.extractors.DramaDizilerimExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin


@CloudstreamPlugin
class DramaDizilerimPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DramaDizilerim())

        registerExtractorAPI(DramaDizilerimExtractor())
    }
}
