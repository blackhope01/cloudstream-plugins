package com.blackhope01.extractors

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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
        Log.d(name, "getUrl çağrıldı -> url: $url, referer: $extRef")

        val doc = app.get(url, referer = extRef).document

        val rawUrl = doc.selectFirst("source[src]")?.attr("src")
            ?.replace("&amp;", "&")
            ?: run {
                Log.e(name, "Video URL bulunamadı -> $url")
                throw ErrorLoadingException("Extractor <source> tag not found")
            }

        // Proxy ise içindeki gerçek URL'yi çöz
        val decodedUrl = if (rawUrl.contains("hls_proxy.php") && rawUrl.contains("url=")) {
            val inner = rawUrl.substringAfter("url=").substringBefore("&")
            URLDecoder.decode(inner, StandardCharsets.UTF_8.name()).also {
                Log.d(name, "Proxy çözüldü -> $it")
            }
        } else {
            rawUrl
        }

        Log.d(name, "Video URL bulundu -> $decodedUrl")

        val isM3u8 = decodedUrl.contains(".m3u8") ||
                decodedUrl.contains("/hls/") ||
                rawUrl.contains("hls_proxy")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = rawUrl,
            ) {
                this.referer = url
                this.type    = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                this.quality = Qualities.Unknown.value
            }
        )

        Log.d(name, "Link gönderildi (${if (isM3u8) "M3U8" else "VIDEO"}) -> $rawUrl")
    }
}