version = 1

cloudstream {
    authors     = listOf("blackhope01")
    language    = "tr"
    description = "Trend olan tüm yabancı yerli filmleri Hd ve 720p 1080p kalitesinde kesintisiz indirmeden izleyebilirsiniz, Online film sitemizde Türkçe dublaj olarak izleyebilirsiniz."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://www.filmhane.shop&sz=%size%"
}