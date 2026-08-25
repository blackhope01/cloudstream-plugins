package com.blackhope01.extractors

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class EksenLoadExtractor : ExtractorApi() {
    override val name            = "Eksenload"
    override val mainUrl         = "https://eksenload.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val lang = url.substringAfterLast("#", "tr")
        val cleanUrl = url.substringBefore("#")

        val response = app.get(cleanUrl, referer = referer).text

        val dilLabel = when (lang) {
            "dublaj" -> "Türkçe Dublaj"
            "altyazi" -> "Türkçe Altyazılı"
            else -> "Türkçe Dublaj"
        }
        val displayName = "$name - $dilLabel"
        val extRef  = referer ?: ""
        val source  = app.get(url, referer = extRef).text

        // JWPlayer setup icindeki master.m3u8 linkini cek
        val m3uLink = Regex("""file:\s*"([^"]+master\.m3u8)"""").find(source)?.groupValues?.get(1)
            ?: Regex(""""file"\s*:\s*"([^"]+master\.m3u8)"""").find(source)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("m3u8 link not found")

        // Altyazi track'lerini cek (vtt dosyalari)
        val subtitleRegex = Regex("""\{[^}]*"file"\s*:\s*"([^"]+\.vtt)"[^}]*"label"\s*:\s*"([^"]+)"""")
        subtitleRegex.findAll(source).forEach { match ->
            val subUrl   = match.groupValues[1]
            val subLabel = match.groupValues[2]
            subtitleCallback.invoke(SubtitleFile(subLabel, subUrl))
        }

        Log.d(name, "m3u8 >> $m3uLink")

        callback.invoke(
            newExtractorLink(
                source  = this.name,
                name = displayName,
                url     = m3uLink,
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}