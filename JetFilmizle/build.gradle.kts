version = 1

cloudstream {
    authors     = listOf("blackhope01")
    language    = "tr"
    description = "En yeni yerli ve yabancı yapımları Full HD kalitede film izle. Türkçe dublaj ve altyazı seçenekleriyle sunulan ödüllü sinema eserlerini JetFilmizle hızıyla keşfedin."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://jetfilmizle.now&sz=%size%"
}