package com.blackhope01.extractors

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

open class HdPlayerExtractor : ExtractorApi() {
    override val name            = "HdPlayer"
    override val mainUrl         = "https://hdplayersystem.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "getUrl başladı. Gelen URL: $url, Referer: $referer")

        // Dil bilgisini al (url # işaretiyle ayrılabilir)
        val lang = url.substringAfterLast("#", "tr")
        val cleanUrl = url.substringBefore("#")
        Log.d(name, "Dil: $lang, Temiz URL: $cleanUrl")

        // Embed linkten data parametresini çıkar
        val dataParam = cleanUrl.substringAfterLast("/")
        if (dataParam.isBlank()) {
            Log.e(name, "Data parametresi çıkarılamadı")
            throw ErrorLoadingException("data parametresi bulunamadı")
        }
        Log.d(name, "Data parametresi: $dataParam")

        // Embed sayfasını çekip altyazıları al
        try {
            val embedHtml = app.get(cleanUrl, referer = referer).text
            Log.d(name, "Embed sayfası alındı, uzunluk: ${embedHtml.length}")

            // playerjsSubtitle değişkenini yakala: [Turkish]URL
            val subtitleRegex = Regex("""var\s+playerjsSubtitle\s*=\s*"([^"]+)"""")
            val subtitleMatch = subtitleRegex.find(embedHtml)
            if (subtitleMatch != null) {
                val subtitleValue = subtitleMatch.groupValues[1]
                Log.d(name, "playerjsSubtitle değeri: $subtitleValue")

                // Format: [Dil]URL
                val regex = Regex("""^\[([^\]]+)\](.*)$""")
                val parsed = regex.find(subtitleValue)
                if (parsed != null) {
                    val subLang = parsed.groupValues[1].trim()
                    val subUrl = parsed.groupValues[2].trim()
                    if (subUrl.isNotEmpty()) {
                        Log.d(name, "Altyazı eklendi: $subLang -> $subUrl")
                        subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
                    }
                } else {
                    Log.w(name, "Subtitle formatı beklenmeyen yapıda: $subtitleValue")
                }
            } else {
                Log.d(name, "playerjsSubtitle bulunamadı")
            }
        } catch (e: Exception) {
            Log.e(name, "Embed sayfası alınırken hata: ${e.message}")
        }

        // POST URL'sini oluştur
        val postUrl = "$mainUrl/player/index.php?data=$dataParam&do=getVideo"
        Log.d(name, "POST URL: $postUrl")

        // Referer değerini belirle
        val extRef = referer ?: ""
        Log.d(name, "Kullanılan referer: $extRef")

        // POST isteği gövdesini oluştur
        val postBody = mapOf(
            "hash" to dataParam,
            "r" to extRef
        )
        Log.d(name, "POST gövdesi: $postBody")

        // POST isteğini gönder
        val response = app.post(
            url = postUrl,
            data = postBody,
            referer = extRef,
            headers = mapOf(
                "x-requested-with" to "XMLHttpRequest",
                "accept" to "*/*"
            )
        )
        Log.d(name, "POST yanıt kodu: ${response.code}, başarılı: ${response.isSuccessful}")

        val responseText = response.text
        Log.d(name, "Yanıt içeriği (ilk 500 karakter): ${responseText.take(500)}")

        // JSON parse et
        val json = try {
            JSONObject(responseText)
        } catch (e: Exception) {
            Log.e(name, "JSON parse hatası: ${e.message}")
            throw ErrorLoadingException("JSON parse hatası")
        }

        // Önce securedLink'i al (gerçek .m3u8), boşsa videoSource'a düş
        val securedLink = json.optString("securedLink", "")
        val videoSource = json.optString("videoSource", "")
        val videoUrl = if (securedLink.isNotBlank()) {
            Log.d(name, "securedLink kullanılacak: $securedLink")
            securedLink
        } else {
            Log.d(name, "videoSource kullanılacak: $videoSource")
            videoSource
        }

        if (videoUrl.isBlank()) {
            Log.e(name, "Video URL bulunamadı")
            throw ErrorLoadingException("Video linki bulunamadı")
        }

        // Uzantıya göre link tipini belirle (query parametrelerini yok say)
        val videoPath = videoUrl.substringBefore("?")
        val isHls = videoPath.endsWith(".m3u8")

        if (isHls) {
            Log.d(name, "Video HLS (.m3u8) olarak algılandı, ExtractorLinkType.M3U8 kullanılıyor")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = videoUrl,
                ) {
                    this.referer = cleanUrl
                    this.type    = ExtractorLinkType.M3U8
                    this.quality = Qualities.Unknown.value
                }
            )
        } else {
            Log.d(name, "Video HLS değil, ExtractorLinkType.VIDEO kullanılıyor")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = this.name,
                    url    = videoUrl,
                ) {
                    this.referer = cleanUrl
                    this.type    = ExtractorLinkType.VIDEO
                }
            )
        }
    }
}