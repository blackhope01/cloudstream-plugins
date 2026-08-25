package com.blackhope01.extractors

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

open class SibnetExtractor : ExtractorApi() {
    override val name            = "SibNet"
    override val mainUrl         = "https://video.sibnet.ru"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef  = referer ?: ""
        val iSource = app.get(url, referer=extRef).text
        var m3uLink = Regex("""player.src\(\[\{src: \"([^\"]+)""").find(iSource)?.groupValues?.get(1) ?: throw ErrorLoadingException("m3u link not found")

        m3uLink = "${mainUrl}${m3uLink}"

        val dilLabel = when {
            referer?.contains("/dublaj/") == true -> "Türkçe Dublaj"
            else -> "Türkçe Altyazılı"
        }
        val displayName = "$name - $dilLabel"

       Log.d(name, "Dil >> $displayName")


        callback.invoke(
            newExtractorLink(
                source  = this.name,
                name    = displayName,
                url     = m3uLink,
            ) {
                this.referer = url
            }
        )
    }
}