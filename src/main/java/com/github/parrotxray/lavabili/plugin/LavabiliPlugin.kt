package com.github.parrotxray.lavabili.plugin

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import dev.arbjerg.lavalink.api.AudioPlayerManagerConfiguration
import com.github.parrotxray.lavabili.source.BilibiliAudioSourceManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class LavabiliPlugin(private val config: BiliBiliConfig) : AudioPlayerManagerConfiguration {
    init {
        log.info("START: lavabili-plugin.")
    }

    override fun configure(manager: AudioPlayerManager): AudioPlayerManager {
        if (config.activeSources.contains("bilibili")) {
            manager.registerSourceManager(
                BilibiliAudioSourceManager()
                    .setPlaylistPageCount(config.playlistPageCount)
            )
            log.info("Registered Bilibili source manager...")
        }
        return manager
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(LavabiliPlugin::class.java)
    }
}