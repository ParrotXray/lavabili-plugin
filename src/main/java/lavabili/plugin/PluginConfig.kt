package lavabili.plugin

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "plugins.lavabili")
@Component
data class PluginConfig(
    val sources: Sources = Sources(),
    val playlistPageCount: Int = -1
) {
    data class Sources(
        var enable: Boolean = true
    )
    
    val activeSources: List<String>
        get() = if (sources.enable) listOf("bilibili") else emptyList()
}