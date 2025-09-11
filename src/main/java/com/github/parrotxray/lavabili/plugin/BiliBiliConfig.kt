package com.github.parrotxray.lavabili.plugin

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "plugins.lavabili")
@Component
data class BiliBiliConfig(
    val sources: Sources = Sources(),
    val playlistPageCount: Int = -1,
    val authentication: Authentication = Authentication()
) {
    data class Sources(
        var enable: Boolean = true
    )
    
    data class Authentication(
        var enabled: Boolean = false,
        var sessdata: String = "",
        var biliJct: String = "",
        var dedeUserId: String = "",
        var buvid3: String = "",
        var buvid4: String = ""
    )

    val activeSources: List<String>
        get() = if (sources.enable) listOf("bilibili") else emptyList()

    val isAuthenticated: Boolean
        get() = authentication.enabled && authentication.sessdata.isNotEmpty()
}