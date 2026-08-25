version = 1

cloudstream {
    authors     = listOf("blackhope01")
    language    = "tr"
    description = "Yeni film, dizi, anime ve bölümleri keşfet; güncel trendleri, sıralamaları ve yayın takvimini incele."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://www.diziyo.so&sz=%size%"
}