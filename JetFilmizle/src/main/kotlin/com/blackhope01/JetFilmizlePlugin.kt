package com.blackhope01

import android.content.Context
import com.blackhope01.extractors.FembedOnlineExtractor
import com.blackhope01.extractors.JetCdnExtractor
import com.blackhope01.extractors.PlayerXExtractor
import com.blackhope01.extractors.VideoParkExtractor
import com.blackhope01.extractors.VipExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JetFilmizlePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JetFilmizle())

        registerExtractorAPI(PlayerXExtractor())
        registerExtractorAPI(VideoParkExtractor())
        registerExtractorAPI(VipExtractor())
        registerExtractorAPI(FembedOnlineExtractor())
        registerExtractorAPI(JetCdnExtractor())
    }
}
