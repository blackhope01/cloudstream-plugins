package com.blackhope01.extractors

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

open class JetCdnExtractor : ExtractorApi() {
    override val name = "JetCdn"
    override val mainUrl = "https://jetcdn.org"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val parts = url.split("|label=")
        val actualUrl = parts[0]
        val label = if (parts.size > 1) parts[1].substringBefore("|") else "JetCdn"

        Log.d(name, "Baslangic: $label - $actualUrl")

        try {
            // URL tipini kontrol et
            if (actualUrl.contains("/gold/")) {
                // Gold tipi (film) - API yöntemi
                handleGoldType(actualUrl, label, callback)
            } else if (actualUrl.contains("/dizi/")) {
                // Dizi tipi - HTML scraping yöntemi
                handleDiziType(actualUrl, label, referer, subtitleCallback, callback)
            } else {
                Log.e(name, "$label: Bilinmeyen URL tipi")
            }

        } catch (e: Exception) {
            Log.e(name, "$label: Hata - ${e.message}")
        }
    }

    // Gold tipi (film) için - API yöntemi
    private suspend fun handleGoldType(
        actualUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = actualUrl.substringAfter("?id=").substringBefore("&")
        val timestamp = System.currentTimeMillis()

        val apiUrl = "https://jetcdn.org/gold/stream.php?id=$id&t=$timestamp"

        val response = app.get(
            apiUrl,
            referer = actualUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "application/json",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).text

        val json = JSONObject(response)
        val success = json.optBoolean("success", false)

        if (!success) {
            Log.e(name, "$label: API basarisiz")
            return
        }

        val formats = json.optJSONArray("formats")
        if (formats == null || formats.length() == 0) {
            Log.e(name, "$label: Format bulunamadi")
            return
        }

        for (i in 0 until formats.length()) {
            val format = formats.getJSONObject(i)
            val videoUrl = format.optString("url").takeIf { it.isNotBlank() } ?: continue
            val qualityLabel = format.optString("quality")
            val width = format.optInt("width", 0)

            val quality = when {
                qualityLabel.contains("1080") || width >= 1080 -> Qualities.P1080.value
                qualityLabel.contains("720") || width >= 720 -> Qualities.P720.value
                qualityLabel.contains("480") || width >= 480 -> Qualities.P480.value
                qualityLabel.contains("360") || width >= 360 -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            callback.invoke(
                ExtractorLink(
                    name,
                    "$name $qualityLabel ($label)",
                    videoUrl.replace("\\/", "/"),
                    actualUrl,
                    quality,
                    mapOf(
                        "Referer" to actualUrl,
                        "Origin" to mainUrl
                    ),
                    null,
                    ExtractorLinkType.VIDEO
                )
            )
            Log.d(name, "$label: $qualityLabel eklendi")
        }

        Log.d(name, "$label: Gold tipi tamamlandi (${formats.length()} kaynak)")
    }

    // Dizi tipi için - HTML scraping yöntemi
    private suspend fun handleDiziType(
        actualUrl: String,
        label: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(
            actualUrl,
            referer = referer ?: "https://jetfilmizle.net/",
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        ).text

        // videoUrlOriginal (orijinal dil)
        val originalRegex = Regex("""videoUrlOriginal:\s*"([^"]+)"""")
        val originalUrl = originalRegex.find(response)?.groupValues?.get(1)

        // videoUrlTurkish (Türkçe dublaj)
        val turkishRegex = Regex("""videoUrlTurkish:\s*"([^"]+)"""")
        val turkishUrl = turkishRegex.find(response)?.groupValues?.get(1)

        var videoAdded = false

        // Orijinal sesli videoyu ekle
        if (!originalUrl.isNullOrBlank()) {
            callback.invoke(
                ExtractorLink(
                    name,
                    "$name Orijinal ($label)",
                    originalUrl.replace("\\/", "/"),
                    actualUrl,
                    Qualities.Unknown.value,
                    mapOf(
                        "Referer" to actualUrl,
                        "Origin" to mainUrl
                    ),
                    null,
                    ExtractorLinkType.M3U8
                )
            )
            Log.d(name, "$label: Orijinal ses eklendi")
            videoAdded = true
        }

        // Türkçe dublaj videoyu ekle
        if (!turkishUrl.isNullOrBlank() && turkishUrl != originalUrl) {
            callback.invoke(
                ExtractorLink(
                    name,
                    "$name Türkçe Dublaj ($label)",
                    turkishUrl.replace("\\/", "/"),
                    actualUrl,
                    Qualities.Unknown.value,
                    mapOf(
                        "Referer" to actualUrl,
                        "Origin" to mainUrl
                    ),
                    null,
                    ExtractorLinkType.M3U8
                )
            )
            Log.d(name, "$label: Turkce dublaj eklendi")
            videoAdded = true
        }

        // Altyazıları ekle
        val subtitlesRegex = Regex("""subtitles:\s*(\[[^\]]+\])""")
        val subtitlesMatch = subtitlesRegex.find(response)

        if (subtitlesMatch != null) {
            try {
                val subtitlesJson = JSONObject("{\"subtitles\":${subtitlesMatch.groupValues[1]}}")
                val subtitlesArray = subtitlesJson.optJSONArray("subtitles")

                if (subtitlesArray != null) {
                    for (i in 0 until subtitlesArray.length()) {
                        val sub = subtitlesArray.getJSONObject(i)
                        val subUrl = sub.optString("file").takeIf { it.isNotBlank() } ?: continue
                        val subLabel = sub.optString("label")

                        val lang = when {
                            subLabel.contains("Türkçe", true) -> "Türkçe"
                            subLabel.contains("English", true) -> "English"
                            else -> subLabel
                        }

                        subtitleCallback(SubtitleFile(lang, subUrl.replace("\\/", "/")))
                        Log.d(name, "$label: Altyazi eklendi - $lang")
                    }
                }
            } catch (e: Exception) {
                Log.e(name, "$label: Altyazi parse hatasi - ${e.message}")
            }
        }

        if (!videoAdded) {
            Log.e(name, "$label: Video kaynagi bulunamadi")
        } else {
            Log.d(name, "$label: Dizi tipi tamamlandi")
        }
    }
}