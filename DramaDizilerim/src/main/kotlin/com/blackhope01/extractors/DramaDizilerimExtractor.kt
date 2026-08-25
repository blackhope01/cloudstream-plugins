package com.blackhope01.extractors

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile

class DramaDizilerimExtractor : ExtractorApi() {
    override val name            = "DramaDizilerim"
    override val mainUrl         = "https://dramadizilerim.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: ""
        val doc = app.get(url, referer = extRef).document

        val videoUrl = doc.selectFirst("source[src]")?.attr("src")
            ?.replace("&amp;", "&")
            ?: throw ErrorLoadingException("Extractor <source> tag not found")

        if (videoUrl.endsWith(".m3u8")) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = videoUrl,
                ) {
                    this.referer = url
                    this.type    = ExtractorLinkType.M3U8
                    this.quality = Qualities.Unknown.value
                }
            )
        } else {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = videoUrl,
                ) {
                    this.referer = url
                    this.type    = ExtractorLinkType.VIDEO
                }
            )
        }
    }
}