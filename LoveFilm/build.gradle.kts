version = 1

cloudstream {
    authors     = listOf("blackhope01")
    language    = "tr"
    description = "Full HD Film izle - Türkçe Dublaj Altyazılı Film izleme sitesi olarak sizlere yabancı yerli filmleri yüksek kalitede sunmakdayız. Kaliteli film izle sitesi"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 0 // will be 3 if unspecified
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://lovefilmizle.net&sz=%size%"
}