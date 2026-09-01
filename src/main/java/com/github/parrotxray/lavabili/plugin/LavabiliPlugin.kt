package com.github.parrotxray.lavabili.plugin

import com.github.topi314.lavalyrics.LyricsManager
import com.github.topi314.lavalyrics.api.LyricsManagerConfiguration
import com.github.topi314.lavasearch.SearchManager
import com.github.topi314.lavasearch.api.SearchManagerConfiguration
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import dev.arbjerg.lavalink.api.AudioPlayerManagerConfiguration
import com.github.parrotxray.lavabili.source.BilibiliAudioSourceManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class LavabiliPlugin(private val config: BilibiliConfig) :
    AudioPlayerManagerConfiguration, SearchManagerConfiguration, LyricsManagerConfiguration {

    private val sourceManager: BilibiliAudioSourceManager? by lazy {
        if (config.enabled) {
            BilibiliAudioSourceManager(config).setPlaylistPageCount(config.playlistPageCount)
        } else {
            null
        }
    }

    init {
        if (config.enabled) {
            log.info("Loading Lavabili plugin...")

            if (config.isAuthenticated) {
                log.info("Bilibili authentication: SESSDATA=${config.auth.sessdata.take(8)}***, UserID=${config.auth.dedeUserId.take(4)}***")
            } else {
                log.info("Bilibili authentication: DISABLED (guest mode)")
            }

            log.debug("DEBUG: Playlist page count limit: ${if (config.playlistPageCount == -1) "unlimited" else config.playlistPageCount}")
            log.debug("DEBUG: Search functionality: ${if (config.allowSearch) "enabled" else "disabled"}")
            log.debug("DEBUG: Lyrics functionality: ${if (config.allowLyrics) "enabled" else "disabled"}")
        }
    }

    override fun configure(manager: AudioPlayerManager): AudioPlayerManager {
        sourceManager?.let {
            manager.registerSourceManager(it)
            log.info("Registering Bilibili audio source manager...")
        }
        return manager
    }

    override fun configure(manager: SearchManager): SearchManager {
        if (config.allowSearch) {
            sourceManager?.let {
                manager.registerSearchManager(it)
                log.info("Registering Bilibili search manager...")
            }
        }
        return manager
    }

    override fun configure(manager: LyricsManager): LyricsManager {
        if (config.allowLyrics) {
            sourceManager?.let {
                manager.registerLyricsManager(it)
                log.info("Registering Bilibili lyrics manager...")
            }
        }
        return manager
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(LavabiliPlugin::class.java)
    }
}
