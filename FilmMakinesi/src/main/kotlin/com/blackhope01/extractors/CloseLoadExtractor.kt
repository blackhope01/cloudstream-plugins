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

        val varPattern = Regex("""var\s+(s_\w+)\s*=\s*(dc_\w+)\s*\(\s*\[(.*?)\]\s*\)""", RegexOption.DOT_MATCHES_ALL)
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
            var value = parts.joinToString("")
            Log.d(name, "JS Parser: value = '${value.take(50)}...'")

            val body = funcBody.replace(Regex("""\s+"""), " ")

            val operations = mutableListOf<Operation>()

            // 1. atob(result) pozisyonlarını bul
            Regex("""atob\s*\(\s*result\s*\)""").findAll(body).forEach {
                operations.add(Operation(it.range.first, "atob"))
            }

            // 2. Caesar shift pozisyonunu bul
            Regex("""replace\s*\(\s*/\[a-zA-Z\]/g.*?String\.fromCharCode\(\(o\s*-\s*base\s*\+\s*(\d+)\)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(body).forEach {
                    operations.add(Operation(it.range.first, "caesar:${it.groupValues[1]}"))
                }

            // 3. XOR unmix pozisyonunu bul
            Regex("""for\s*\(\s*let\s+i\s*=\s*0.*?acc\s*=\s*\(\s*acc\s*\+\s*(\d+)\)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(body).forEach {
                    val inc = it.groupValues[1].toInt()
                    val accMatch = Regex("""(?:var|let|const)\s+acc\s*=\s*(\d+)""").find(body)
                    val accStart = accMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    operations.add(Operation(it.range.first, "xor:$accStart:$inc"))
                }

            // 4. reverse() pozisyonunu bul (eğer varsa)
            Regex("""\.reverse\(\)""").findAll(body).forEach {
                operations.add(Operation(it.range.first, "reverse"))
            }

            // 5. btoa(result) pozisyonunu bul (eğer varsa)
            Regex("""btoa\s*\(\s*result\s*\)""").findAll(body).forEach {
                operations.add(Operation(it.range.first, "btoa"))
            }

            // Pozisyona göre sırala ve uygula
            operations.sortBy { it.pos }

            Log.d(name, "JS Parser: ${operations.size} işlem bulundu: ${operations.map { it.type }}")

            for (op in operations) {
                when {
                    op.type == "atob" -> {
                        value = atob(value)
                        Log.d(name, "JS Parser: atob -> '${value.take(50)}...'")
                    }
                    op.type == "btoa" -> {
                        value = btoa(value)
                        Log.d(name, "JS Parser: btoa -> '${value.take(50)}...'")
                    }
                    op.type == "reverse" -> {
                        value = value.reversed()
                        Log.d(name, "JS Parser: reverse -> '${value.take(50)}...'")
                    }
                    op.type.startsWith("caesar:") -> {
                        val shift = op.type.substringAfter(":").toInt()
                        value = caesarShift(value, shift)
                        Log.d(name, "JS Parser: caesar +$shift -> '${value.take(50)}...'")
                    }
                    op.type.startsWith("xor:") -> {
                        val (_, accStart, inc) = op.type.split(":")
                        value = xorUnmix(value, accStart.toInt(), inc.toInt())
                        Log.d(name, "JS Parser: XOR unmix acc=$accStart, +$inc -> '${value.take(50)}...'")
                    }
                }
            }

            if (value.contains("http")) value.trim() else null
        } catch (e: Exception) {
            Log.e(name, "JS Parser hatası: ${e.message}")
            null
        }
    }

    private data class Operation(val pos: Int, val type: String)

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