package com.github.parrotxray.lavabili.source

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import com.github.parrotxray.lavabili.source.BilibiliAudioSourceManager.Companion.BASE_URL
import com.github.parrotxray.lavabili.plugin.LavabiliPlugin
import org.apache.http.client.methods.HttpGet
import java.net.URI
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class BilibiliAudioTrack(
    audioTrackInfo: AudioTrackInfo,
    val type: TrackType,
    val /*bvid or sid*/ id: String,
    val cid: Long?,
    private val sourceManager: BilibiliAudioSourceManager
) : DelegatedAudioTrack(audioTrackInfo) {
    val log: Logger = LoggerFactory.getLogger(LavabiliPlugin::class.java)

    override fun process(executor: LocalAudioTrackExecutor) {
        val stream = PersistentHttpStream(
            sourceManager.httpInterface,
            URI(getPlaybackURL()),
            null
        )
        processDelegate(MpegAudioTrack(trackInfo, stream), executor)
    }

    private fun getPlaybackURL(): String = when (type) {
        TrackType.AUDIO -> {
            val responseJson = sourceManager.fetchJson(
                HttpGet("${BASE_URL}audio/music-service-c/web/url?sid=$id&privilege=2&quality=2")
            ) ?: throw IllegalStateException("Empty response fetching Bilibili audio URL for $id")

            responseJson
                .get("data")
                .get("cdns")
                .values()[0].`as`(String::class.java)
        }
        TrackType.VIDEO -> {
            // WBI-signed with a device fingerprint, matching what a real player sends -
            // the plain unsigned x/player/playurl endpoint is flagged by risk control
            // (-412) much more aggressively.
            val query = sourceManager.signWbi(
                mapOf("bvid" to id, "cid" to cid.toString(), "fnval" to "16", "qn" to "120") + sourceManager.dmParams()
            )
            val responseJson = sourceManager.fetchJson(
                HttpGet("${BASE_URL}x/player/wbi/playurl?$query")
            ) ?: throw IllegalStateException("Empty response fetching Bilibili playurl for $id")

            responseJson.get("data").get("dash").get("audio")
                .values()
                .sortedByDescending {
                    it.get("id").`as`(Int::class.java)
                }[0].get("baseUrl").`as`(String::class.java)
        }
    }

    override fun makeShallowClone(): AudioTrack {
        return BilibiliAudioTrack(trackInfo, type, id, cid, sourceManager)
    }

    override fun getSourceManager(): AudioSourceManager {
        return sourceManager
    }

    enum class TrackType {
        VIDEO, AUDIO
    }
}
