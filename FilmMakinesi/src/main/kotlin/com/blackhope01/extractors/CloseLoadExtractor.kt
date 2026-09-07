package com.blackhope01.extractors

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class CloseLoadExtractor : ExtractorApi() {
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val name = "CloseLoad"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "getUrl çağrıldı, url: $url")

        val response = app.get(url, referer = referer ?: "")
        val rawHtml = response.text
        Log.d(name, "Raw HTML uzunluğu: ${rawHtml.length}")

        var videoUrl: String? = null

        // Teşhis: packed JS var mı?
        val hasPacker = rawHtml.contains("eval(function(p,a,c,k,e,d){")
        Log.d(name, "Packed JS marker var mı: $hasPacker")

        // Teşhis: dc_ fonksiyon adları ve konumları
        val dcMatches = Regex("""(dc_\w+)""").findAll(rawHtml).map { it.value }.distinct().toList()
        Log.d(name, "dc_ fonksiyon adları: $dcMatches")

        // Teşnis: s_ değişken kalıpları
        val sMatches = Regex("""var\s+(s_\w+)""").findAll(rawHtml).map { it.groupValues[1] }.distinct().toList()
        Log.d(name, "s_ değişkenleri: $sMatches")

        // Teşhis: ["...","..."] parts benzeri diziler (ilk 3 eşleşme, kırpık)
        Regex("""\[\s*"[^"]*"\s*,\s*"[^"]*"""").findAll(rawHtml).take(5).forEachIndexed { i, m ->
            Log.d(name, "Parts benzeri dizi #$i @${m.range.first}: ${m.value.take(80)}")
        }

        // Teşhis: eval( ve function yoğunluğu olan bölgeler
        val evalIdx = rawHtml.indexOf("eval(")
        if (evalIdx != -1) {
            Log.d(name, "eval( konumu: $evalIdx, çevresi: ${rawHtml.substring(evalIdx, (evalIdx + 200).coerceAtMost(rawHtml.length))}")
        }

        val varPattern = Regex("""var\s+(\w+)\s*=\s*(\w+)\s*\(\s*\[(.*?)\]\s*\)""", RegexOption.DOT_MATCHES_ALL)
        val varMatch = varPattern.find(rawHtml)

        if (varMatch != null) {
            val varName = varMatch.groupValues[1]
            val funcName = varMatch.groupValues[2]
            val partsStr = varMatch.groupValues[3]

            // \/ escape'lerini temizle
            val parts = Regex(""""([^"]*)"""").findAll(partsStr).map {
                it.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
            }.toList()

            Log.d(name, "Dinamik bulundu: var=$varName, func=$funcName, parts=${parts.size}")

            // funcBody'yi manuel { } sayarak çıkar (regex non-greedy iç içe }'leri kaçırıyor)
            val funcBody = extractFuncBody(rawHtml, funcName)
            if (funcBody != null) {
                Log.d(name, "Fonksiyon body bulundu, uzunluk: ${funcBody.length}")
                Log.d(name, "FUNC_BODY: $funcBody")
                videoUrl = parseAndExecuteJs(funcBody, parts)
                Log.d(name, "Dinamik çözülen URL: $videoUrl")
            } else {
                Log.w(name, "Fonksiyon body bulunamadı, bilinen decryptor'ları deniyorum")
                videoUrl = tryAllDecryptors(parts)
            }
        } else {
            Log.w(name, "var s_XXX = dc_YYY([...]) pattern bulunamadı")
        }

        if (videoUrl.isNullOrBlank()) {
            val jsonLdMatch = Regex(""""contentUrl"\s*:\s*"([^"]+)"""").find(rawHtml)
            videoUrl = jsonLdMatch?.groupValues?.get(1)
                ?.replace(".txt", ".m3u8")
            Log.d(name, "Fallback JSON-LD contentUrl: $videoUrl")
        }

        if (videoUrl.isNullOrBlank()) {
            Log.e(name, "Video URL bulunamadı!")
            return
        }

        Log.d(name, "Master fetch ediliyor: $videoUrl")
        val masterResponse = app.get(videoUrl, referer = url, headers = mapOf(
            "Accept" to "*/*",
            "Origin" to "https://closeload.filmmakinesi.to"
        ))
        Log.d(name, "Master status: ${masterResponse.code}")

        if (masterResponse.code != 200) {
            Log.e(name, "Master 404/500!")
            return
        }

        val masterBody = masterResponse.text
        Log.d(name, "Master body (ilk 500):\n${masterBody.take(500)}")

        val tracksMatch = Regex("""tracks:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(rawHtml)
        val tracksStr = tracksMatch?.groupValues?.get(1)

        tracksStr?.let { str ->
            val matches = Regex(""""file"\s*:\s*"([^"]+)".*?"label"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                .findAll(str).toList()
            Log.d(name, "Bulunan altyazı sayısı: ${matches.size}")

            matches.forEachIndexed { index, match ->
                val subUrl = match.groupValues[1].replace("\\/", "/")
                val subLabel = match.groupValues[2]
                val lang = when {
                    subLabel.contains("Turkish", ignoreCase = true) -> "Türkçe"
                    subLabel.contains("Forced", ignoreCase = true) -> "Forced"
                    subLabel.contains("English", ignoreCase = true) -> "İngilizce"
                    else -> return@forEachIndexed
                }
                Log.d(name, "Altyazı #$index - lang: '$lang', label: '$subLabel'")
                subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
            }
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Accept" to "*/*",
                    "Origin" to "https://closeload.filmmakinesi.to"
                )
            }
        )
        Log.d(name, "ExtractorLink eklendi: $videoUrl")
    }

    // ============================================================
    // MANUEL FONKSİYON BODY ÇIKARMA (iç içe { } destekli)
    // ============================================================
    private fun extractFuncBody(rawHtml: String, funcName: String): String? {
        val startIdx = rawHtml.indexOf("function $funcName")
        if (startIdx == -1) return null

        val braceIdx = rawHtml.indexOf('{', startIdx)
        if (braceIdx == -1) return null

        var braceCount = 1
        var i = braceIdx + 1
        while (braceCount > 0 && i < rawHtml.length) {
            when (rawHtml[i]) {
                '{' -> braceCount++
                '}' -> braceCount--
            }
            i++
        }
        return if (braceCount == 0) rawHtml.substring(braceIdx + 1, i - 1) else null
    }

    // ============================================================
    // POZİSYON-TABANLI JS PARSER (işlemleri sırayla uygular)
    // ============================================================
    private fun parseAndExecuteJs(funcBody: String, parts: List<String>): String? {
        return try {
            // 1. Gömülü string'leri yakala: var X = "seed"; var Y = "ops";
            val seedMatch = Regex(
                """var\s+(\w+)\s*=\s*"([^"]+)"\s*;\s*var\s+(\w+)\s*=\s*"([^"]+)""""
            ).find(funcBody) ?: run {
                Log.w(name, "Seed/ops string'leri bulunamadı")
                return null
            }
            val seedStr = seedMatch.groupValues[2]
            val opsStr = seedMatch.groupValues[4]
            Log.d(name, "Seed: '$seedStr', Ops: '$opsStr'")

            var la8q = parts.joinToString("")

            // 2. Anahtar türetme
            var m1l = 0
            var rfdgf = 0
            for (i in seedStr.indices) {
                val ioz = seedStr[i].code
                m1l = (m1l * 31 + ioz) % 251
                rfdgf = (rfdgf xor (ioz + i)) and 255
            }
            val ucv = (m1l + rfdgf) % 256
            val h52gx = (m1l % 13) + 3
            var ws7g = ((m1l * 256 + rfdgf) % 65521) + 1
            Log.d(name, "ucv=$ucv, h52gx=$h52gx, ws7g=$ws7g")

            // 3. Operasyonlar — opsStr TERSTEN işlenir
            for (i in opsStr.length - 1 downTo 0) {
                val ch = opsStr[i]
                la8q = when (ch) {
                    'b' -> atob(la8q)
                    'v' -> la8q.reversed()
                    else -> {
                        val tcxa = (26 - ((ch.code - 64) % 26)) % 26
                        caesarShift(la8q, tcxa)
                    }
                }
            }
            // Ekstra kontrol: ops dizisi çok uzunsa reverse (JS'teki vtr.length > 4096)
            if (opsStr.length > 4096) la8q = la8q.reversed()

            Log.d(name, "Operasyonlar sonrası uzunluk: ${la8q.length}")

            // 4. Deterministik shuffle
            val tmzq = la8q.length
            if (tmzq > 1) {
                val gtwld = IntArray(tmzq)
                for (xfm8 in tmzq - 1 downTo 1) {
                    ws7g = (ws7g * 75 + 74) % 65537
                    gtwld[xfm8] = ws7g % (xfm8 + 1)
                }
                val onw = la8q.toCharArray()
                for (xfm8 in 1 until tmzq) {
                    val j = gtwld[xfm8]
                    val tmp = onw[xfm8]
                    onw[xfm8] = onw[j]
                    onw[j] = tmp
                }
                la8q = String(onw)
            }

            // 5. XOR unmix
            val sb = StringBuilder(tmzq)
            var ew0 = ucv
            for (c in la8q) {
                val ioz = c.code
                ew0 = (ew0 + h52gx) % 256
                sb.append((ioz xor ew0).toChar())
                ew0 = (ew0 + ioz) % 256
            }

            val result = sb.toString()
            Log.d(name, "Çözülen değer: ${result.take(200)}")
            result.trim().takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            Log.e(name, "JS Parser hatası: ${e.message}")
            null
        }
    }

    private fun atob(s: String): String {
        var str = s.trim()
        val padding = 4 - str.length % 4
        if (padding != 4) str += "=".repeat(padding)
        return Base64.decode(str, Base64.DEFAULT).toString(Charsets.ISO_8859_1)
    }

    private fun btoa(s: String): String {
        return Base64.encodeToString(s.toByteArray(Charsets.ISO_8859_1), Base64.DEFAULT).trim()
    }

    private fun caesarShift(text: String, shift: Int): String {
        return text.map { c ->
            when {
                c in 'A'..'Z' -> ((c.code - 'A'.code + shift) % 26 + 'A'.code).toChar()
                c in 'a'..'z' -> ((c.code - 'a'.code + shift) % 26 + 'a'.code).toChar()
                else -> c
            }
        }.joinToString("")
    }

    private fun xorUnmix(text: String, accStart: Int, increment: Int): String {
        var acc = accStart
        val unmix = StringBuilder()
        for (i in text.indices) {
            val b = text[i].code
            acc = (acc + increment) % 256
            val plain = b xor acc
            acc = (acc + b) % 256
            unmix.append(plain.toChar())
        }
        return unmix.toString()
    }

    // Fallback decryptor'lar
    private fun tryAllDecryptors(parts: List<String>): String? {
        val decryptors = listOf(::decryptV1, ::decryptV2, ::decryptV3, ::decryptV4)
        for ((index, decryptor) in decryptors.withIndex()) {
            try {
                val result = decryptor(parts)
                if (!result.isNullOrBlank() && result.contains("http")) {
                    Log.d(name, "Fallback decryptor v${index + 1} başarılı!")
                    return result
                }
            } catch (e: Exception) {
                Log.d(name, "Fallback decryptor v${index + 1} başarısız: ${e.message}")
            }
        }
        return null
    }

    private fun decryptV1(valueParts: List<String>): String? {
        var value = valueParts.joinToString("")
        value = caesarShift(value, 9); value = caesarShift(value, 16)
        value = value.reversed()
        var decoded = atob(value); decoded = atob(decoded)
        return xorUnmix(decoded, 241, 11)
    }

    private fun decryptV2(valueParts: List<String>): String? {
        var value = valueParts.joinToString("")
        value = value.reversed(); value = caesarShift(value, 15)
        var decoded = atob(value); decoded = decoded.reversed(); decoded = atob(decoded)
        return xorUnmix(decoded, 185, 12)
    }

    private fun decryptV3(valueParts: List<String>): String? {
        var value = valueParts.joinToString("")
        var decoded = atob(value); decoded = atob(decoded)
        decoded = decoded.reversed(); decoded = caesarShift(decoded, 25); decoded = atob(decoded)
        return xorUnmix(decoded, 77, 9)
    }

    private fun decryptV4(valueParts: List<String>): String? {
        var value = valueParts.joinToString("")
        var decoded = atob(value); decoded = decoded.reversed(); decoded = atob(decoded)
        return xorUnmix(decoded, 130, 10)
    }
}