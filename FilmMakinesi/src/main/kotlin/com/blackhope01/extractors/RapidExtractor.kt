package com.blackhope01.extractors

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class RapidExtractor : ExtractorApi() {
    override val mainUrl = "https://rapid.filmmakinesi.to"
    override val name = "Rapid"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(name, "getUrl çağrıldı, url: $url")

        val response = app.get(url, referer = referer ?: mainUrl)
        val rawHtml = response.text
        Log.d(name, "Raw HTML uzunluğu: ${rawHtml.length}")

        var videoUrl: String? = null

        // 1. Packed JS (Dean Edwards packer) unpack et
        val unpackedJs = unpackPackerJs(rawHtml)
        if (unpackedJs != null) {
            Log.d(name, "JS unpack edildi, uzunluk: ${unpackedJs.length}")

            val varPattern = Regex(
                """(?:var|let|const)\s+(\w+)\s*=\s*(\w+)\s*\(\s*\[(.*?)\]\s*\)""",
                RegexOption.DOT_MATCHES_ALL
            )
            val varMatch = varPattern.find(unpackedJs)

            if (varMatch != null) {
                val varName = varMatch.groupValues[1]
                val funcName = varMatch.groupValues[2]
                val partsStr = varMatch.groupValues[3]

                val parts = Regex(""""([^"]*)"""").findAll(partsStr).map {
                    it.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
                }.toList()

                Log.d(name, "Dinamik bulundu: var=$varName, func=$funcName, parts=${parts.size}")

                val funcBody = extractFuncBody(unpackedJs, funcName)
                if (funcBody != null) {
                    Log.d(name, "Fonksiyon body bulundu, uzunluk: ${funcBody.length}")
                    videoUrl = parseAndExecuteJs(funcBody, parts)
                    Log.d(name, "Dinamik çözülen URL: $videoUrl")
                }
            } else {
                Log.w(name, "Unpack edilmiş JS'te var X = Y([...]) bulunamadı")
            }
        } else {
            Log.w(name, "Packed JS bulunamadı veya unpack edilemedi")
        }

        // 2. Fallback: JSON-LD
        if (videoUrl.isNullOrBlank()) {
            val jsonLdMatch = Regex(""""contentUrl"\s*:\s*"([^"]+)"""").find(rawHtml)
            videoUrl = jsonLdMatch?.groupValues?.get(1)?.replace(".txt", ".m3u8")
            Log.d(name, "Fallback JSON-LD: $videoUrl")
        }

        // 3. Fallback: Direkt m3u8
        if (videoUrl.isNullOrBlank()) {
            val directMatch = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").find(rawHtml)
            videoUrl = directMatch?.groupValues?.get(1)?.replace("\\/", "/")
            Log.d(name, "Fallback direkt m3u8: $videoUrl")
        }

        if (videoUrl.isNullOrBlank()) {
            Log.e(name, "Video URL bulunamadı!")
            return
        }

        // 4. Altyazılar
        parseSubtitles(rawHtml, subtitleCallback)

        // 5. Master fetch
        Log.d(name, "Master fetch ediliyor: $videoUrl")
        val masterResponse = app.get(videoUrl, referer = url, headers = mapOf(
            "Accept" to "*/*",
            "Origin" to mainUrl
        ))
        Log.d(name, "Master status: ${masterResponse.code}")

        if (masterResponse.code != 200) {
            Log.e(name, "Master fetch başarısız: ${masterResponse.code}")
            return
        }

        // 6. ExtractorLink
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
                    "Origin" to mainUrl
                )
            }
        )
        Log.d(name, "ExtractorLink eklendi: $videoUrl")
    }

    // ============================================================
    // DEAN EDWARDS PACKER UNPACKER — String-based parsing (regex-safe)
    // ============================================================
    private fun unpackPackerJs(rawHtml: String): String? {
        return try {
            val startMarker = "eval(function(p,a,c,k,e,d){"
            val endMarker = ",0,{}))"

            val startIdx = rawHtml.indexOf(startMarker)
            if (startIdx == -1) return null

            val endIdx = rawHtml.indexOf(endMarker, startIdx + startMarker.length)
            if (endIdx == -1) return null

            val block = rawHtml.substring(startIdx, endIdx + endMarker.length)

            // packed string: }('...',
            val packedStart = block.indexOf("}('") + 3
            val packedEnd = block.indexOf("',", packedStart)
            if (packedStart == -1 || packedEnd == -1) return null
            val packed = block.substring(packedStart, packedEnd)

            // base: ...',62,...
            val afterPacked = block.substring(packedEnd + 2)
            val baseEnd = afterPacked.indexOf(",")
            if (baseEnd == -1) return null
            val base = afterPacked.substring(0, baseEnd).toInt()

            // count: ...,74,...
            val afterBase = afterPacked.substring(baseEnd + 1)
            val countEnd = afterBase.indexOf(",")
            if (countEnd == -1) return null
            val count = afterBase.substring(0, countEnd).toInt()

            // dictionary: ...'dict'.split
            val dictQuoteStart = afterBase.indexOf("'") + 1
            val dictQuoteEnd = afterBase.indexOf("'.split", dictQuoteStart)
            if (dictQuoteStart == -1 || dictQuoteEnd == -1) return null
            val dictStr = afterBase.substring(dictQuoteStart, dictQuoteEnd)

            Log.d(name, "Packer: base=$base, count=$count, dict=${dictStr.length}, packed=${packed.length}")

            val dictionary = dictStr.split('|')
            val lookup = mutableMapOf<String, String>()

            var c = count - 1
            while (c >= 0) {
                val key = packerEncode(c, base)
                lookup[key] = if (c < dictionary.size && dictionary[c].isNotEmpty()) {
                    dictionary[c]
                } else {
                    key
                }
                c--
            }

            var result = packed
            val sortedKeys = lookup.keys.sortedByDescending { it.length }
            for (key in sortedKeys) {
                val value = lookup[key]!!
                result = result.replace(Regex("\\b${Regex.escape(key)}\\b"), value)
            }

            result
        } catch (e: Exception) {
            Log.e(name, "Unpack hatası: ${e.message}")
            null
        }
    }

    private fun packerEncode(num: Int, base: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.insert(0, digits[n % base])
            n /= base
        }
        return sb.toString()
    }

    // ============================================================
    // POZİSYON-TABANLI JS PARSER
    // ============================================================
    private fun parseAndExecuteJs(funcBody: String, parts: List<String>): String? {
        return try {
            var value = parts.joinToString("")
            val body = funcBody.replace(Regex("""\s+"""), " ")
            val operations = mutableListOf<Operation>()

            Regex("""atob\s*\(\s*result\s*\)""").findAll(body).forEach {
                operations.add(Operation(it.range.first, "atob"))
            }
            Regex("""replace\s*\(\s*/\[a-zA-Z\]/g.*?String\.fromCharCode\(\(o\s*-\s*base\s*\+\s*(\d+)\)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(body).forEach {
                    operations.add(Operation(it.range.first, "caesar:${it.groupValues[1]}"))
                }
            Regex("""for\s*\(\s*let\s+i\s*=\s*0.*?acc\s*=\s*\(\s*acc\s*\+\s*(\d+)\)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(body).forEach {
                    val inc = it.groupValues[1].toInt()
                    val accMatch = Regex("""(?:var|let|const)\s+acc\s*=\s*(\d+)""").find(body)
                    val accStart = accMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    operations.add(Operation(it.range.first, "xor:$accStart:$inc"))
                }
            Regex("""\.reverse\(\)""").findAll(body).forEach {
                operations.add(Operation(it.range.first, "reverse"))
            }
            Regex("""btoa\s*\(\s*result\s*\)""").findAll(body).forEach {
                operations.add(Operation(it.range.first, "btoa"))
            }

            operations.sortBy { it.pos }
            Log.d(name, "JS Parser: ${operations.size} işlem: ${operations.map { it.type }}")

            for (op in operations) {
                when {
                    op.type == "atob" -> value = atob(value)
                    op.type == "btoa" -> value = btoa(value)
                    op.type == "reverse" -> value = value.reversed()
                    op.type.startsWith("caesar:") -> {
                        val shift = op.type.substringAfter(":").toInt()
                        value = caesarShift(value, shift)
                    }
                    op.type.startsWith("xor:") -> {
                        val (_, accStart, inc) = op.type.split(":")
                        value = xorUnmix(value, accStart.toInt(), inc.toInt())
                    }
                }
            }
            value.trim().takeIf { it.contains("http") }
        } catch (e: Exception) {
            Log.e(name, "JS Parser hatası: ${e.message}")
            null
        }
    }

    private data class Operation(val pos: Int, val type: String)

    // ============================================================
    // YARDIMCI FONKSİYONLAR
    // ============================================================
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

    private fun extractFuncBody(jsCode: String, funcName: String): String? {
        val startIdx = jsCode.indexOf("function $funcName")
        if (startIdx == -1) return null
        val braceIdx = jsCode.indexOf('{', startIdx)
        if (braceIdx == -1) return null
        var braceCount = 1
        var i = braceIdx + 1
        while (braceCount > 0 && i < jsCode.length) {
            when (jsCode[i]) {
                '{' -> braceCount++
                '}' -> braceCount--
            }
            i++
        }
        return if (braceCount == 0) jsCode.substring(braceIdx + 1, i - 1) else null
    }

    // ============================================================
    // ALTYAZI PARSER
    // ============================================================
    private suspend fun parseSubtitles(
        rawHtml: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val tracksMatch = Regex("""tracks:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(rawHtml)
        tracksMatch?.groupValues?.get(1)?.let { tracksStr ->
            val subMatches = Regex(
                """"file"\s*:\s*"([^"]+)".*?"label"\s*:\s*"([^"]+)".*?"language"\s*:\s*"([^"]+)"""",
                RegexOption.DOT_MATCHES_ALL
            ).findAll(tracksStr).toList()

            Log.d(name, "Bulunan altyazı sayısı: ${subMatches.size}")

            subMatches.forEachIndexed { index, match ->
                var subUrl = match.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
                val subLabel = match.groupValues[2]
                val langCode = match.groupValues[3]

                // Göreli URL'yi tam URL'ye çevir
                if (!subUrl.startsWith("http")) {
                    subUrl = mainUrl.trimEnd('/') + (if (subUrl.startsWith("/")) "" else "/") + subUrl
                }

                val lang = when {
                    langCode == "forced" || subLabel.contains("Forced", ignoreCase = true) -> "Forced"
                    langCode == "tr" || subLabel.contains("Turkish", ignoreCase = true) -> "Türkçe"
                    langCode == "en" || subLabel.contains("English", ignoreCase = true) -> "İngilizce"
                    else -> return@forEachIndexed
                }

                Log.d(name, "Altyazı #$index - lang: '$lang', url: '$subUrl'")
                subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
            }
        }
    }
}