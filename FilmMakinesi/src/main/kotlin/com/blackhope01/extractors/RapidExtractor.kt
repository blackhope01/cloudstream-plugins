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
            // 1. Gömülü string'leri yakala: var X="seed";var Y="ops";
            //    (seed = anahtar türetme dizgisi, ops = atob/reverse/caesar dizisi)
            val seedMatch = Regex(
                """var\s+(\w+)\s*=\s*"([^"]+)"\s*;\s*var\s+(\w+)\s*=\s*"([^"]+)""""
            ).find(funcBody) ?: run {
                Log.w(name, "Seed/ops string'leri bulunamadı")
                return null
            }
            val seedStr = seedMatch.groupValues[2]
            val opsStr = seedMatch.groupValues[4]
            Log.d(name, "Seed: '$seedStr', Ops: '$opsStr'")

            var u3e = parts.joinToString("")

            // 2. Anahtar türetme (fonksiyon body'sindeki algoritmanın birebir aynısı)
            var gzx1 = 0
            var mff = 0
            for (i in seedStr.indices) {
                val ngm = seedStr[i].code
                gzx1 = (gzx1 * 31 + ngm) % 251
                mff = (mff xor (ngm + i)) and 255
            }
            val ghihx = (gzx1 + mff) % 256
            val cv1 = (gzx1 % 13) + 3
            var pzvvv = ((gzx1 * 256 + mff) % 65521) + 1
            Log.d(name, "ghihx=$ghihx, cv1=$cv1, pzvvv=$pzvvv")

            // 3. Operasyonlar — opsStr TERSTEN işlenir
            //    'b' = atob, 'v' = reverse, diğeri = Caesar (öteleme koddan türetilir)
            for (i in opsStr.length - 1 downTo 0) {
                val ch = opsStr[i]
                u3e = when (ch) {
                    'b' -> atob(u3e)
                    'v' -> u3e.reversed()
                    else -> {
                        val oufo = (26 - ((ch.code - 64) % 26)) % 26
                        caesarShift(u3e, oufo)
                    }
                }
            }
            Log.d(name, "Operasyonlar sonrası uzunluk: ${u3e.length}")

            // 4. Deterministik shuffle (PRNG: pzvvv)
            val imm = u3e.length
            if (imm > 1) {
                val irdt = IntArray(imm)
                for (sm7 in imm - 1 downTo 1) {
                    pzvvv = (pzvvv * 75 + 74) % 65537
                    irdt[sm7] = pzvvv % (sm7 + 1)
                }
                val arr = u3e.toCharArray()
                for (sm7 in 1 until imm) {
                    val j = irdt[sm7]
                    val tmp = arr[sm7]
                    arr[sm7] = arr[j]
                    arr[j] = tmp
                }
                u3e = String(arr)
            }

            // 5. XOR unmix
            val sb = StringBuilder(imm)
            var to4 = ghihx
            for (c in u3e) {
                val ngm = c.code
                to4 = (to4 + cv1) % 256
                sb.append((ngm xor to4).toChar())
                to4 = (to4 + ngm) % 256
            }

            val result = sb.toString()
            Log.d(name, "Çözülen değer: ${result.take(200)}")
            result.trim().takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            Log.e(name, "JS Parser hatası: ${e.message}")
            null
        }
    }

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