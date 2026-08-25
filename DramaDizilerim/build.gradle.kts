version = 2

cloudstream {
    authors     = listOf("blackhope01")
    language    = "tr"
    description = "En popüler NetShort, DramaBox, ReelShort ve FreeReels kısa dizilerini Türkçe dublaj ve altyazı seçenekleriyle Full HD kalitede ücretsiz ve reklamsız izleyin."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://dramadizilerim.com&sz=%size%"
}
