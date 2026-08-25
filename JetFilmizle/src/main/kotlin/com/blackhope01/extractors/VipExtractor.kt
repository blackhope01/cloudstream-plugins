package com.blackhope01.extractors

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

open class VipExtractor : ExtractorApi() {
    override val name = "Vip"
    override val mainUrl = "https://empty-truth-cf2c.erikkalinina1994.workers.dev"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val parts = url.split("|label=")
        val actualUrl = parts[0]
        val label = if (parts.size > 1) parts[1].substringBefore("|") else "Vip"

        Log.d(name, "1. $actualUrl ($label)")

        try {
            val streamUrl = actualUrl.replace("/play/", "/stream/")
            Log.d(name, "2. Stream URL: $streamUrl")

            val ref = referer ?: "https://jetfilmizle.net/"

            val response = app.get(
                streamUrl,
                referer = ref,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept" to "*/*",
                    "Referer" to ref,
                    "Origin" to "https://jetfilmizle.net"
                )
            )

            if (response.text.trim().startsWith("#EXTM3U")) {
                Log.d(name, "m3u8 playlist alindi")

                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name ($label)",
                        url = streamUrl,
                        referer = actualUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Referer" to actualUrl,
                            "Origin" to actualUrl.substringBefore("/play/")
                        )
                    )
                )
                Log.d(name, "Tamamlandi!")
                return
            }

            val playerDataRegex = Regex("""const\s+playerData\s*=\s*(\{.*?\});""", setOf(RegexOption.DOT_MATCHES_ALL))
            playerDataRegex.find(response.text)?.let { match ->
                val json = JSONObject(match.groupValues[1])
                val videoUrl = json.optString("stream_url").takeIf { it.isNotBlank() }
                    ?: json.optString("file").takeIf { it.isNotBlank() }

                if (!videoUrl.isNullOrBlank()) {
                    Log.d(name, "playerData'dan stream alindi")
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name ($label)",
                            url = videoUrl,
                            referer = actualUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8,
                            headers = mapOf(
                                "Referer" to actualUrl,
                                "Origin" to actualUrl.substringBefore("/play/")
                            )
                        )
                    )
                    Log.d(name, "Tamamlandi!")
                    return
                }
            }

            Log.e(name, "Stream bulunamadi!")

        } catch (e: Exception) {
            Log.e(name, "Hata: ${e.message}", e)
        }
    }
}