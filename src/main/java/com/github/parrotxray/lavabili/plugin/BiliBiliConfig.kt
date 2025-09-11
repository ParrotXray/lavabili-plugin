package com.github.parrotxray.lavabili.plugin

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "plugins.lavabili")
@Component
data class BilibiliConfig(
    var enabled: Boolean = false,
    var playlistPageCount: Int = -1,
    var auth: Authentication = Authentication()
) {
    
    data class Authentication(
        var enabled: Boolean = false,
        var sessdata: String = "",
        var biliJct: String = "",
        var dedeUserId: String = "",
        var buvid3: String = "",
        var buvid4: String = ""
    )

    val activeSources: List<String>
        get() = if (enabled) listOf("bilibili") else emptyList()

    val isAuthenticated: Boolean
        get() = auth.enabled && auth.sessdata.isNotEmpty()
}