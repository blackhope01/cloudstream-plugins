version = 2

cloudstream {
    authors     = listOf("blackhope01")
    language    = "tr"
    description = "Dizi.life Türkçe dizi/film izleme platformu — yabancı diziler, yeni filmler, son bölümler."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://dizi73.life&sz=%size%"
}