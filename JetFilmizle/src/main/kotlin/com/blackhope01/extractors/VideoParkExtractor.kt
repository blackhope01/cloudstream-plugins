package com.blackhope01.extractors

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

open class VideoParkExtractor : ExtractorApi() {
    override val name = "VideoPark"
    override val mainUrl = "https://videopark.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val parts = url.split("|label=")
        val actualUrl = parts[0]
        val playerName = if (parts.size > 1) parts[1].substringBefore("|") else "Fast"

        Log.d(name, "Baslangic: $playerName - $actualUrl")

        try {
            val response = app.get(
                actualUrl,
                referer = referer ?: "https://jetfilmizle.now/",
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            ).text

            // OPlay (VIDEO_DATA) kontrolu
            val videoDataRegex = Regex("""const\s+VIDEO_DATA\s*=\s*(\{.*?\});""", setOf(RegexOption.DOT_MATCHES_ALL))
            val videoDataMatch = videoDataRegex.find(response)

            if (videoDataMatch != null) {
                Log.d(name, "$playerName: OPlay player yapisi tespit edildi")
                val json = JSONObject(videoDataMatch.groupValues[1])

                // HLS kaynagi
                val hlsUrl = json.optJSONObject("hlsSource")?.optString("file")?.takeIf { it.isNotBlank() }
                if (!hlsUrl.isNullOrBlank()) {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "$name HLS ($playerName)",
                            hlsUrl.replace("\\/", "/"),
                            actualUrl,
                            Qualities.Unknown.value,
                            mapOf("Referer" to actualUrl),
                            null,
                            ExtractorLinkType.M3U8
                        )
                    )
                    Log.d(name, "$playerName: HLS kaynagi eklendi")
                }

                // MP4 kaynaklari
                json.optJSONArray("mp4Sources")?.let { mp4Array ->
                    for (i in 0 until mp4Array.length()) {
                        val mp4 = mp4Array.getJSONObject(i)
                        val mp4Url = mp4.optString("file").takeIf { it.isNotBlank() } ?: continue
                        val qualityLabel = mp4.optString("label")

                        val quality = when {
                            qualityLabel.contains("1080") -> Qualities.P1080.value
                            qualityLabel.contains("720") -> Qualities.P720.value
                            qualityLabel.contains("480") -> Qualities.P480.value
                            else -> Qualities.Unknown.value
                        }

                        callback.invoke(
                            ExtractorLink(
                                name,
                                "$name MP4 ($playerName)",
                                mp4Url.replace("\\/", "/"),
                                actualUrl,
                                quality,
                                mapOf("Referer" to actualUrl),
                                null,
                                ExtractorLinkType.VIDEO
                            )
                        )
                    }
                    Log.d(name, "$playerName: ${mp4Array.length()} MP4 kaynagi eklendi")
                }

                // Altyazilar
                val subtitlesArray = json.optJSONArray("subtitles")
                if (subtitlesArray != null) {
                    var subtitleCount = 0
                    for (i in 0 until subtitlesArray.length()) {
                        val sub = subtitlesArray.getJSONObject(i)
                        val subUrl = sub.optString("file")
                        if (!subUrl.isNullOrBlank()) {
                            val subLabel = sub.optString("label")
                            val lang = when {
                                subLabel.contains("Turkish", true) || subLabel.contains("Türkçe", true) -> "Türkçe"
                                subLabel.contains("English", true) -> "English"
                                else -> subLabel
                            }
                            subtitleCallback(SubtitleFile(lang, subUrl.replace("\\/", "/")))
                            subtitleCount++
                        }
                    }
                    if (subtitleCount > 0) {
                        Log.d(name, "$playerName: $subtitleCount altyazi eklendi")
                    }
                }

                Log.d(name, "$playerName: OPlay player tamamlandi")
                return
            }

            // Fast player (_sd) kontrolu - GUVENLI JSON AYIKLAMA
            val sdStart = response.indexOf("var _sd")
            if (sdStart == -1) {
                Log.e(name, "$playerName: _sd veya VIDEO_DATA bulunamadi")
                return
            }

            val objStart = response.indexOf('{', sdStart)
            if (objStart == -1) {
                Log.e(name, "$playerName: _sd objesi baslangici bulunamadi")
                return
            }

            var braceCount = 0
            var inString = false
            var escaped = false
            var endIndex = -1

            for (i in objStart until response.length) {
                val c = response[i]
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = !inString
                    !inString && c == '{' -> braceCount++
                    !inString && c == '}' -> {
                        braceCount--
                        if (braceCount == 0) {
                            endIndex = i
                            break
                        }
                    }
                }
            }

            if (endIndex == -1) {
                Log.e(name, "$playerName: _sd objesi kapanisi bulunamadi")
                return
            }

            val jsonStr = response.substring(objStart, endIndex + 1)
            Log.d(name, "$playerName: _sd JSON basariyla ayiklandi (uzunluk: ${jsonStr.length})")
            val json = JSONObject(jsonStr)

            val streamUrl = json.optString("stream_url").takeIf { it.isNotBlank() }

            if (streamUrl.isNullOrBlank()) {
                Log.e(name, "$playerName: Stream URL bulunamadi")
                return
            }

            val cleanStream = streamUrl.replace("\\/", "/")

            // Altyazilar
            val subtitles = json.optJSONArray("subtitles")
            if (subtitles != null) {
                val subUrls = mutableSetOf<String>()
                var subtitleCount = 0
                for (i in 0 until subtitles.length()) {
                    val sub = subtitles.getJSONObject(i)
                    val subUrl = sub.optString("file")
                    if (!subUrl.isNullOrBlank() && subUrls.add(subUrl)) {
                        val rawLabel = sub.optString("label")
                        val cleanLabel = when {
                            rawLabel.contains("Turkish", true) || rawLabel.contains("Türkçe", true) -> "Turkish"
                            rawLabel.contains("English", true) || rawLabel.contains("İngilizce", true) -> "English"
                            rawLabel.contains("Forced", true) -> "Forced"
                            else -> rawLabel
                        }
                        subtitleCallback(SubtitleFile(cleanLabel, subUrl.replace("\\/", "/")))
                        subtitleCount++
                    }
                }
                if (subtitleCount > 0) {
                    Log.d(name, "$playerName: $subtitleCount altyazi eklendi")
                }
            }

            callback.invoke(
                ExtractorLink(
                    name,
                    "$name ($playerName)",
                    cleanStream,
                    actualUrl,
                    Qualities.Unknown.value,
                    mapOf(
                        "Referer" to actualUrl,
                        "Origin" to "https://videopark.top"
                    ),
                    null,
                    ExtractorLinkType.M3U8
                )
            )
            Log.d(name, "$playerName: Fast player tamamlandi")

        } catch (e: Exception) {
            Log.e(name, "$playerName: Hata - ${e.message}")
        }
    }
}