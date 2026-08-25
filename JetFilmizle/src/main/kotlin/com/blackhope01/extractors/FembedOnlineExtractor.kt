package com.blackhope01.extractors

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class FembedOnlineExtractor : ExtractorApi() {
    override val name = "FembedOnline"
    override val mainUrl = "https://fembed.online"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val parts = url.split("|label=")
        val actualUrl = parts[0]
        val label = if (parts.size > 1) parts[1].substringBefore("|") else "Fembed"

        Log.d(name, "1. $actualUrl ($label)")

        try {
            val response = app.get(
                actualUrl,
                referer = referer ?: "https://jetfilmizle.net/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to "https://jetfilmizle.net/"
                )
            ).text

            Log.d(name, "2. Sayfa yuklendi (${response.length} karakter)")

            val playerSourcesRegex = Regex("""var\s+playerSources\s*=\s*(\[[^\]]*\])""")
            val match = playerSourcesRegex.find(response)

            if (match != null) {
                val jsonArray = org.json.JSONArray(match.groupValues[1])
                Log.d(name, "3. playerSources bulundu, ${jsonArray.length()} kaynak")

                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    val streamUrl = item.optString("file").takeIf { it.isNotBlank() }
                    val type = item.optString("type")

                    if (!streamUrl.isNullOrBlank()) {
                        Log.d(name, "4. Stream URL: ${streamUrl.take(80)}...")

                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "$name ($label)",
                                url = streamUrl,
                                referer = actualUrl,
                                quality = Qualities.Unknown.value,
                                type = if (type == "hls") ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                    "Referer" to actualUrl,
                                    "Origin" to mainUrl
                                )
                            )
                        )
                        Log.d(name, "Tamamlandi!")
                        return
                    }
                }
            }

            Log.e(name, "Stream bulunamadi!")

        } catch (e: Exception) {
            Log.e(name, "Hata: ${e.message}", e)
        }
    }
}