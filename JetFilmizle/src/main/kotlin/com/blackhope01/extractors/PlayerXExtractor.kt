package com.blackhope01.extractors

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI

open class PlayerXExtractor : ExtractorApi() {
    override val name = "PlayerX"
    override val mainUrl = "https://playerx.info"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val parts = url.split("|label=")
        val actualUrl = parts[0]
        val label = if (parts.size > 1) parts[1].substringBefore("|") else "PlayerX"

        Log.d(name, "1. $actualUrl ($label)")

        try {
            val response = app.get(
                actualUrl,
                referer = "https://jetfilmizle.net/",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to "https://jetfilmizle.net/"
                )
            ).text

            Log.d(name, "2. Sayfa yuklendi (${response.length} karakter)")

            val origin = try {
                val uri = URI(actualUrl)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                "https://playerx.info"
            }

            Log.d(name, "3. Origin: $origin")

            val fileRegex = Regex("""file\s*:\s*["']([^"']+)["']""")
            val match = fileRegex.find(response)

            if (match != null) {
                val streamPath = match.groupValues[1]
                Log.d(name, "4. file: $streamPath")

                val streamUrl = when {
                    streamPath.startsWith("http") -> streamPath
                    streamPath.startsWith("/") -> "$origin$streamPath"
                    else -> "$origin/$streamPath"
                }

                Log.d(name, "5. Stream URL: $streamUrl")

                val trackRegex = Regex("""file:\s*"([^"]+)"[^}]*label:\s*"([^"]+)"""")
                val tracks = trackRegex.findAll(response).toList()
                Log.d(name, "6. Altyazi sayisi: ${tracks.size}")

                tracks.forEach { trackMatch ->
                    val subPath = trackMatch.groupValues[1]
                    val rawLabel = trackMatch.groupValues[2].trim()

                    if (!rawLabel.contains("Forced", ignoreCase = true)) {
                        val subUrl = when {
                            subPath.startsWith("http") -> subPath
                            subPath.startsWith("/") -> "$origin$subPath"
                            else -> "$origin/$subPath"
                        }

                        val cleanLabel = when {
                            rawLabel.contains("Türkçe", ignoreCase = true) ||
                                    rawLabel.contains("t_rk_e", ignoreCase = true) ||
                                    rawLabel.contains("turkce", ignoreCase = true) -> "Turkish"

                            rawLabel.contains("İngilizce", ignoreCase = true) ||
                                    rawLabel.contains("_ngilizce", ignoreCase = true) ||
                                    rawLabel.contains("ingilizce", ignoreCase = true) -> "English"

                            else -> rawLabel
                        }

                        subtitleCallback.invoke(SubtitleFile(url = subUrl, lang = cleanLabel))
                        Log.d(name, "Altyazi: $cleanLabel")
                    }
                }

                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name ($label)",
                        url = streamUrl,
                        referer = origin,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8,
                        headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Referer" to origin,
                            "Origin" to origin,
                            "Accept" to "*/*"
                        )
                    )
                )

                Log.d(name, "Tamamlandi!")

            } else {
                Log.e(name, "file degeri bulunamadi")
                Log.d(name, "HTML basi: ${response.take(300)}")
            }

        } catch (e: Exception) {
            Log.e(name, "Hata: ${e.message}", e)
        }
    }
}