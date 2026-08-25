package com.blackhope01

import android.content.Context
import com.blackhope01.extractors.OdnoklassnikiExtractor
import com.blackhope01.extractors.SibnetExtractor
import com.blackhope01.extractors.VidMolyExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin


@CloudstreamPlugin
class SezonlukDiziPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SezonlukDizi())
        registerExtractorAPI(VidMolyExtractor())
        registerExtractorAPI(SibnetExtractor())
        registerExtractorAPI(OdnoklassnikiExtractor())


    }
}
