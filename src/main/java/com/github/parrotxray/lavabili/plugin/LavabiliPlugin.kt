package com.github.parrotxray.lavabili.plugin

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import dev.arbjerg.lavalink.api.AudioPlayerManagerConfiguration
import com.github.parrotxray.lavabili.source.BilibiliAudioSourceManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class LavabiliPlugin(private val config: BilibiliConfig) : AudioPlayerManagerConfiguration {
    init {
        if (config.enabled) {
            log.info("Loading Lavabili plugin...")

            if (config.isAuthenticated) {
                log.info("Bilibili authentication: SESSDATA=${config.auth.sessdata.take(8)}***, UserID=${config.auth.dedeUserId.take(4)}***")
            } else {
                log.info("Bilibili authentication: DISABLED (guest mode)")
            }

            log.debug("DEBUG: Playlist page count limit: ${if (config.playlistPageCount == -1) "unlimited" else config.playlistPageCount}")
        }
    }

    override fun configure(manager: AudioPlayerManager): AudioPlayerManager {
        if (config.activeSources.contains("bilibili")) {
            val sourceManager = BilibiliAudioSourceManager(config)
                .setPlaylistPageCount(config.playlistPageCount)
            
            manager.registerSourceManager(sourceManager)
            log.info("Registered Bilibili audio source manager...")
        }
        return manager
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(LavabiliPlugin::class.java)
    }
}