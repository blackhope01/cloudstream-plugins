package com.blackhope01

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziLifePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziLife())
    }
}
