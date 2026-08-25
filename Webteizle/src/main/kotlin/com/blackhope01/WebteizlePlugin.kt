package com.blackhope01

import android.content.Context
import com.blackhope01.extractors.VidMolyExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin


@CloudstreamPlugin
class WebteizlePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Webteizle())
        registerExtractorAPI(VidMolyExtractor())
    }
}
