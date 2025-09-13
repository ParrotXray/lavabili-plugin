package com.github.parrotxray.lavabili.source

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist
import com.github.parrotxray.lavabili.plugin.LavabiliPlugin
import com.github.parrotxray.lavabili.plugin.BilibiliConfig
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpGet
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.DataInput
import java.io.DataOutput
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

class BilibiliAudioSourceManager(private val config: BilibiliConfig? = null) : AudioSourceManager {
    val log: Logger = LoggerFactory.getLogger(LavabiliPlugin::class.java)

    val httpInterface: HttpInterface
    private var playlistPageCountConfig: Int = -1

    init {
        val httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager()
        httpInterfaceManager.setHttpContextFilter(BilibiliHttpContextFilter(config))
        httpInterface = httpInterfaceManager.`interface`
    }

    override fun getSourceName(): String? {
        return "bilibili"
    }

    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {
        log.debug("DEBUG: reference.identifier: ${reference.identifier}")
        
        // Handle bilisearch: prefix for search functionality
        if (reference.identifier.startsWith("bilisearch:")) {
            val searchQuery = reference.identifier.substring("bilisearch:".length).trim()
            log.debug("DEBUG: Bilibili search query: $searchQuery")
            return searchBilibili(searchQuery)
        }
        
        // Handle b23.tv short URLs by resolving them first
        val resolvedUrl = if (reference.identifier.contains("b23.tv")) {
            resolveShortUrl(reference.identifier)
        } else {
            reference.identifier
        }
        
        log.debug("DEBUG: resolved URL: $resolvedUrl")
        
        val matcher = URL_PATTERN.matcher(resolvedUrl)
        if (matcher.find()) {
            when (matcher.group("type")) {
                "video" -> {
                    log.debug("DEBUG: type: video")
                    val bvid = matcher.group("id")
                    
                    // Enhanced page parameter extraction
                    val page = extractPageParameter(resolvedUrl)
                    log.debug("DEBUG: extracted page parameter: $page")
                    
                    val type: String? = when (matcher.group("audioType")) {
                        "av" -> "av"
                        else -> null
                    }

                    var response: CloseableHttpResponse
                    if (type != null) {
                        val aid = bvid.removePrefix("av")
                        response = httpInterface.execute(HttpGet("${BASE_URL}x/web-interface/view?aid=$aid"))
                    } else {
                        response = httpInterface.execute(HttpGet("${BASE_URL}x/web-interface/view?bvid=$bvid"))
                    }

                    log.debug("DEBUG: attempt GET with URL: ${BASE_URL}x/web-interface/view?bvid=$bvid")
                    val responseJson = JsonBrowser.parse(response.entity.content)

                    val statusCode = responseJson.get("code").`as`(Int::class.java)
                    log.debug("DEBUG: statusCode: $statusCode")
                    
                    if (statusCode != 0) {
                        val message = responseJson.get("message").text() ?: "Unknown error"
                        log.warn("Failed to load video: $message (code: $statusCode)")

                        if (statusCode == -403 || statusCode == -404) {
                            if (!config?.isAuthenticated!!) {
                                log.warn("Video may require login. Try configuring authentication.")
                            }
                        }
                        return AudioReference.NO_TRACK
                    }

                    val trackData = responseJson.get("data")
                    val pagesCount = trackData.get("pages").values().size
                    val hasPageParameter = page > 0
                    
                    return if (pagesCount > 1) {
                        if (hasPageParameter) {
                            loadVideoFromAnthology(trackData, page - 1) // Convert to 0-based index
                        } else {
                            loadVideoAnthology(trackData, 0)
                        }
                    } else {
                        loadVideo(trackData)
                    }
                }
                "audio" -> {
                    val type = when (matcher.group("audioType")) {
                        "am" -> "menu"
                        "au" -> "song"
                        else -> return AudioReference.NO_TRACK
                    }
                    val sid = matcher.group("audioId")

                    val response = httpInterface.execute(HttpGet("${BASE_URL}audio/music-service-c/web/$type/info?sid=$sid"))
                    val responseJson = JsonBrowser.parse(response.entity.content)

                    val statusCode = responseJson.get("code").`as`(Int::class.java)
                    if (statusCode != 0) {
                        val message = responseJson.get("message").text() ?: "Unknown error"
                        log.warn("Failed to load audio: $message (code: $statusCode)")
                        return AudioReference.NO_TRACK
                    }

                    return when (type) {
                        "song" -> loadAudio(responseJson.get("data"))
                        "menu" -> loadAudioPlaylist(responseJson.get("data"))
                        else -> AudioReference.NO_TRACK
                    }
                }
            }
        }
        return null
    }

    private fun extractPageParameter(url: String): Int {
        return try {
            // Look for p= parameter in query string
            val pageRegex = Regex("[?&]p=(\\d+)")
            val matchResult = pageRegex.find(url)
            matchResult?.groupValues?.get(1)?.toInt() ?: 0
        } catch (e: Exception) {
            log.debug("Failed to extract page parameter from URL: $url", e)
            0
        }
    }

    private fun searchBilibili(query: String): AudioPlaylist? {
        return try {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())

            val searchUrl = if (config?.isAuthenticated == true) {
                "https://api.bilibili.com/x/web-interface/wbi/search/type?search_type=video&keyword=$encodedQuery&page=1&page_size=20&order=totalrank&duration=0&tids_1=0"
            } else {
                "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=$encodedQuery&page=1&page_size=20&order=totalrank&duration=0&tids_1=0"
            }

            log.debug("DEBUG: Bilibili search URL: $searchUrl")
            
            val response = httpInterface.execute(HttpGet(searchUrl))
            
            val responseJson = JsonBrowser.parse(response.entity.content)
            
            val statusCode = responseJson.get("code").`as`(Int::class.java)
            if (statusCode != 0) {
                val message = responseJson.get("message").text() ?: "Unknown error"
                log.warn("Bilibili search failed with status code: $statusCode, message: $message")
                
                when (statusCode) {
                    -412 -> {
                        log.error("Search blocked (-412): Need cookies. ${if (!config?.isAuthenticated!!) "Configure authentication" else "Cookies may be expired"}")
                    }
                    -403 -> log.error("Access forbidden (-403): Rate limited or banned")
                    -400 -> log.error("Bad request (-400): Invalid parameters")
                }
                
                return BasicAudioPlaylist("Bilibili Search Results", emptyList(), null, true)
            }
            
            val searchResults = responseJson.get("data").get("result")
            val tracks = ArrayList<AudioTrack>()
            
            for (item in searchResults.values()) {
                try {
                    val bvid = item.get("bvid")?.text()
                    val title = item.get("title")?.text()
                    val author = item.get("author")?.text()
                    val duration = item.get("duration")?.text()
                    val pic = item.get("pic")?.text()
                    
                    if (bvid != null && title != null && author != null) {
                        // Parse duration from "mm:ss" format to milliseconds
                        val durationMs = parseDuration(duration)
                        
                        // Clean HTML tags from title and author
                        val cleanTitle = cleanHtmlTags(title)
                        val cleanAuthor = cleanHtmlTags(author)
                        
                        val track = BilibiliAudioTrack(
                            AudioTrackInfo(
                                cleanTitle,
                                cleanAuthor,
                                durationMs,
                                bvid,
                                false,
                                getVideoUrl(bvid),
                                pic,
                                if (pic != null) "" else null
                            ),
                            BilibiliAudioTrack.TrackType.VIDEO,
                            bvid,
                            item.get("cid")?.asLong(0) ?: 0L,
                            this
                        )
                        tracks.add(track)
                    }
                } catch (e: Exception) {
                    log.warn("Failed to parse search result item", e)
                }
            }
            
            log.debug("DEBUG: Found ${tracks.size} tracks for query: $query")
            BasicAudioPlaylist("Bilibili Search: $query", tracks, null, true)
            
        } catch (e: Exception) {
            log.error("Error during Bilibili search", e)
            BasicAudioPlaylist("Bilibili Search Results", emptyList(), null, true)
        }
    }
    
    private fun parseDuration(duration: String?): Long {
        if (duration == null) return 0L
        
        return try {
            val parts = duration.split(":")
            when (parts.size) {
                2 -> {
                    val minutes = parts[0].toLong()
                    val seconds = parts[1].toLong()
                    (minutes * 60 + seconds) * 1000
                }
                3 -> {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val seconds = parts[2].toLong()
                    (hours * 3600 + minutes * 60 + seconds) * 1000
                }
                else -> 0L
            }
        } catch (e: Exception) {
            0L
        }
    }
    
    private fun cleanHtmlTags(text: String): String {
        return text.replace(Regex("<[^>]*>"), "").trim()
    }

    private fun resolveShortUrl(shortUrl: String): String {
        return try {
            // Follow redirects to get the actual Bilibili URL
            val response = httpInterface.execute(HttpGet(shortUrl))
            val location = response.getFirstHeader("Location")?.value
            if (location != null && location.contains("bilibili.com")) {
                location
            } else {
                // If no redirect header, try to get the final URL from the response
                val finalUrl = response.getFirstHeader("Content-Location")?.value ?: shortUrl
                finalUrl
            }
        } catch (e: Exception) {
            log.warn("Failed to resolve short URL: $shortUrl", e)
            shortUrl
        }
    }

    fun setPlaylistPageCount(count: Int): BilibiliAudioSourceManager {
        playlistPageCountConfig = count
        return this
    }

    private fun loadVideo(trackData: JsonBrowser): AudioTrack {
        val bvid = trackData.get("bvid").`as`(String::class.java)

        val log: Logger = LoggerFactory.getLogger(LavabiliPlugin::class.java)
        log.debug("DEBUG: ${trackData.text()}")

        val artworkUrl: String? = if (trackData.get("pic").text() != null) {
            trackData.get("pic").text()
        } else {
            trackData.get("first_frame").text()
        }

        return BilibiliAudioTrack(
            AudioTrackInfo(
                trackData.get("title").`as`(String::class.java),
                trackData.get("owner").get("name").`as`(String::class.java),
                trackData.get("duration").asLong(0) * 1000,
                bvid,
                false,
                getVideoUrl(bvid),
                artworkUrl,
                if (artworkUrl != null) "" else null
            ),
            BilibiliAudioTrack.TrackType.VIDEO,
            bvid,
            trackData.get("cid").asLong(0),
            this
        )
    }

    private fun loadVideoFromAnthology(trackData: JsonBrowser, pageIndex: Int): AudioTrack {
        val log: Logger = LoggerFactory.getLogger(LavabiliPlugin::class.java)
        log.debug("DEBUG: Loading single track from anthology, page: $pageIndex")
        log.debug("DEBUG: ${trackData.text()}")

        val author = trackData.get("owner").get("name").`as`(String::class.java)
        val bvid = trackData.get("bvid").`as`(String::class.java)
        val pages = trackData.get("pages").values()

        if (pageIndex < 0 || pageIndex >= pages.size) {
            log.warn("Invalid page index: $pageIndex, total pages: ${pages.size}")
            return loadVideo(trackData)
        }

        val pageData = pages[pageIndex]
        
        val artworkUrl: String? = if (trackData.get("pic").text() != null) {
            trackData.get("pic").text()
        } else {
            trackData.get("first_frame").text()
        }

        return BilibiliAudioTrack(
            AudioTrackInfo(
                pageData.get("part").`as`(String::class.java),
                author,
                pageData.get("duration").asLong(0) * 1000,
                bvid,
                false,
                getVideoUrl(bvid, pageData.get("page").`as`(Int::class.java)),
                artworkUrl,
                if (artworkUrl != null) "" else null
            ),
            BilibiliAudioTrack.TrackType.VIDEO,
            bvid,
            pageData.get("cid").asLong(0),
            this
        )
    }

    private fun loadVideoAnthology(trackData: JsonBrowser, selectedPage: Int): AudioPlaylist {
        val log: Logger = LoggerFactory.getLogger(LavabiliPlugin::class.java)
        log.debug("DEBUG: ${trackData.text()}")

        val playlistName = trackData.get("title").`as`(String::class.java)
        val author = trackData.get("owner").get("name").`as`(String::class.java)
        val bvid = trackData.get("bvid").`as`(String::class.java)

        val tracks = ArrayList<AudioTrack>()

        for (item in trackData.get("pages").values()) {
            log.debug("DEBUG: ${item.text()}")
            val artworkUrl: String? = if (trackData.get("pic").text() != null) {
                trackData.get("pic").text()
            } else {
                trackData.get("first_frame").text()
            }

            tracks.add(BilibiliAudioTrack(
                AudioTrackInfo(
                    item.get("part").`as`(String::class.java),
                    author,
                    item.get("duration").asLong(0) * 1000,
                    bvid,
                    false,
                    getVideoUrl(bvid, item.get("page").`as`(Int::class.java)),
                    artworkUrl,
                    if (artworkUrl != null) "" else null
                ),
                BilibiliAudioTrack.TrackType.VIDEO,
                bvid,
                item.get("cid").asLong(0),
                this
            ))
        }

        val selectedTrack = if (selectedPage in 0 until tracks.size) tracks[selectedPage] else null
        
        return BasicAudioPlaylist(playlistName, tracks, selectedTrack, false)
    }

    private fun loadAudio(trackData: JsonBrowser): AudioTrack {
        val sid = trackData.get("statistic").get("sid").asLong(0).toString()

        val log: Logger = LoggerFactory.getLogger(LavabiliPlugin::class.java)
        log.debug("DEBUG: ${trackData.text()}")

        return BilibiliAudioTrack(
            AudioTrackInfo(
                trackData.get("title").`as`(String::class.java),
                trackData.get("uname").`as`(String::class.java),
                trackData.get("duration").asLong(0) * 1000,
                "au$sid",
                false,
                getAudioUrl("au$sid")
            ),
            BilibiliAudioTrack.TrackType.AUDIO,
            sid,
            null,
            this
        )
    }

    private fun loadAudioPlaylist(playlistData: JsonBrowser): AudioPlaylist {
        val log: Logger = LoggerFactory.getLogger(LavabiliPlugin::class.java)
        log.debug("DEBUG: ${playlistData.text()}")

        val playlistName = playlistData.get("title").`as`(String::class.java)
        val sid = playlistData.get("statistic").get("sid").asLong(0).toString()

        val response = httpInterface.execute(HttpGet("${BASE_URL}audio/music-service-c/web/song/of-menu?sid=$sid&pn=1&ps=100"))
        val responseJson = JsonBrowser.parse(response.entity.content)

        val tracksData = responseJson.get("data").get("data").values()
        val tracks = ArrayList<AudioTrack>()

        var curPage = responseJson.get("data").get("curPage").`as`(Int::class.java)
        val pageCount = responseJson.get("data").get("pageCount").`as`(Int::class.java).let {
            if (playlistPageCountConfig == -1) it
            else if (it <= playlistPageCountConfig) it
            else playlistPageCountConfig
        }

        while (curPage <= pageCount) {
            val responsePage = httpInterface.execute(HttpGet("${BASE_URL}audio/music-service-c/web/song/of-menu?sid=$sid&pn=${++curPage}&ps=100"))
            val responseJsonPage = JsonBrowser.parse(responsePage.entity.content)
            tracksData.addAll(responseJsonPage.get("data").get("data").values())
        }

        for (track in tracksData) {
            tracks.add(loadAudio(track))
        }

        return BasicAudioPlaylist(playlistName, tracks, null, false)
    }

    override fun isTrackEncodable(track: AudioTrack): Boolean {
        return true
    }

    override fun encodeTrack(track: AudioTrack, output: DataOutput) {
        track as BilibiliAudioTrack
        DataFormatTools.writeNullableText(output, track.type.toString())
        DataFormatTools.writeNullableText(output, track.id)
        DataFormatTools.writeNullableText(output, track.cid.toString())
    }

    override fun decodeTrack(trackInfo: AudioTrackInfo, input: DataInput): AudioTrack {
        val inputString = DataFormatTools.readNullableText(input)
        log.debug("DEBUG: $inputString")
        val trackType: BilibiliAudioTrack.TrackType = when (inputString) {
            "VIDEO" -> {
                BilibiliAudioTrack.TrackType.VIDEO
            }
            "AUDIO" -> {
                BilibiliAudioTrack.TrackType.AUDIO
            }
            else -> {
                throw IllegalArgumentException("ERROR: Must be VIDEO or AUDIO")
            }
        }
        return BilibiliAudioTrack(trackInfo, trackType, DataFormatTools.readNullableText(input), DataFormatTools.readNullableText(input).toLong(), this)
    }

    override fun shutdown() {
        //
    }

    companion object {
        const val BASE_URL = "https://api.bilibili.com/"
        
        // Simplified pattern - we'll handle page parameter extraction separately
        private val URL_PATTERN = Pattern.compile(
            "^https?://(?:(?:www|m)\\.)?(?:bilibili\\.com|b23\\.tv)/(?<type>video|audio)/(?<id>(?:(?<audioType>am|au|av)?(?<audioId>[0-9]+))|[A-Za-z0-9]+)/?(?:\\?.*)?$"
        )

        private fun getVideoUrl(id: String, page: Int? = null): String {
            return "https://www.bilibili.com/video/$id${if (page != null) "?p=$page" else ""}"
        }

        private fun getAudioUrl(id: String): String {
            return "https://www.bilibili.com/audio/$id"
        }
    }
}