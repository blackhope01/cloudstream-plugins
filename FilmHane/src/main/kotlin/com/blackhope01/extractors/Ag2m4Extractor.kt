package com.blackhope01.extractors

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

open class Ag2m4Extractor : ExtractorApi() {
    override val name = "Ag2m4"
    override val mainUrl = "https://x.ag2m4.cfd"
    override val requiresReferer = true

    // Desktop tarayıcı headers'ları — kritik önemde
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: ""

        // 1) Embed HTML'yi TARAYICI gibi çek
        val embedHtml = app.get(url, referer = extRef, headers = browserHeaders).text

        // 2) Regex ile view_id ve hash'i çek
        val streamRegex = Regex("""/dl\?op=get_stream&view_id=(\d+)&hash=([a-f0-9\-]+)""")
        val match = streamRegex.find(embedHtml)
            ?: throw ErrorLoadingException("Stream API bilgisi bulunamadı")

        val viewId = match.groupValues[1]
        val hash = match.groupValues[2]
        val streamApi = "$mainUrl/dl?op=get_stream&view_id=$viewId&hash=$hash"

        Log.d(name, "Stream API: $streamApi")

        // 3) Stream API'yi AYNI headers'larla çağır
        val streamResponse = app.get(streamApi, referer = url, headers = browserHeaders)
        val streamJson = streamResponse.text

        Log.d(name, "Stream JSON: $streamJson")

        // 4) JSON'dan URL çek
        val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
        val streamUrl = urlRegex.find(streamJson)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Stream URL bulunamadı")

        Log.d(name, "Stream URL: $streamUrl")

        // 5) Kalite tespiti
        val quality = when {
            streamUrl.contains("1080", true) -> Qualities.P1080.value
            streamUrl.contains("720", true) -> Qualities.P720.value
            streamUrl.contains("480", true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "$name - ${if (quality >= Qualities.P1080.value) "1080p" else if (quality >= Qualities.P720.value) "720p" else "Auto"}",
                url = streamUrl
            ) {
                this.referer = "$mainUrl/"
                this.quality = quality
            }
        )

        // 6) Altyazı çekme (senin kodun aynen kalabilir)
        val subRegex = Regex("""subtitle\s*:\s*"\[([^\]]+)\]([^"]+)"""").find(embedHtml)
        if (subRegex != null) {
            val langName = subRegex.groupValues[1]
            val subUrl = subRegex.groupValues[2]
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = langName,
                    url = subUrl
                )
            )
            Log.d(name, "Altyazı eklendi: $langName -> $subUrl")
        }
    }
}