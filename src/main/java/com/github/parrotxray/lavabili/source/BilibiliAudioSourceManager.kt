package com.github.parrotxray.lavabili.source

import com.github.topi314.lavalyrics.AudioLyricsManager
import com.github.topi314.lavalyrics.lyrics.AudioLyrics
import com.github.topi314.lavalyrics.lyrics.BasicAudioLyrics
import com.github.topi314.lavasearch.AudioSearchManager
import com.github.topi314.lavasearch.result.AudioSearchResult
import com.github.topi314.lavasearch.result.BasicAudioSearchResult
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
import com.github.parrotxray.lavabili.util.CookieRefreshManager
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpUriRequest
import org.apache.http.util.EntityUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.DataInput
import java.io.DataOutput
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.regex.Pattern

class BilibiliAudioSourceManager(private val config: BilibiliConfig? = null) : AudioSourceManager, AudioSearchManager, AudioLyricsManager {
    val log: Logger = LoggerFactory.getLogger(LavabiliPlugin::class.java)

    val httpInterface: HttpInterface
    private var playlistPageCountConfig: Int = -1

    // Generated once per source manager instance (not per request) and reused for the
    // lifetime of this instance, matching how a real browser/device keeps a stable buvid
    // instead of a new one per call.
    private val resolvedBuvid3: String = config?.auth?.buvid3?.takeIf { it.isNotEmpty() } ?: "${UUID.randomUUID()}infoc"
    private val resolvedBuvid4: String = config?.auth?.buvid4?.takeIf { it.isNotEmpty() } ?: "${UUID.randomUUID()}infoc"

    init {
        val httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager()

        val httpContextFilter = BilibiliHttpContextFilter(config, resolvedBuvid3, resolvedBuvid4, null)
        httpInterfaceManager.setHttpContextFilter(httpContextFilter)
        httpInterface = httpInterfaceManager.`interface`

        val updatedFilter = BilibiliHttpContextFilter(config, resolvedBuvid3, resolvedBuvid4, httpInterface)
        httpInterfaceManager.setHttpContextFilter(updatedFilter)

        // Check and refresh cookie state on startup
        when {
            config?.canRefreshCookies == true -> {
                // Check cookie state on startup
                try {
                    val cookieRefreshManager = CookieRefreshManager(config, httpInterface)
                    if (cookieRefreshManager.shouldRefreshCookies()) {
                        log.info("Detected cookies need refresh on startup, starting automatic refresh...")
                        val result = cookieRefreshManager.refreshCookies()
                        if (result.success) {
                            log.info("Cookie refreshed successfully! Please check the new configuration in the logs and restart the service to use the new cookie.")
                        } else {
                            log.warn("Cookie refresh failed: ${result.message}")
                        }
                    } else {
                        log.info("Cookie check: current cookie state is normal")
                    }
                } catch (e: Exception) {
                    log.warn("Failed to check cookie state: ${e.message}")
                }
            }
            config?.isAuthenticated == true -> {
                log.info("Using fixed cookie authentication mode (ac_time_value not configured, cannot auto-refresh)")
            }
        }
    }

    override fun getSourceName(): String {
        return "bilibili"
    }

    // Checks the HTTP status before parsing so a real non-200 response (risk-control
    // block, CDN error page, empty body) is logged and turned into a null instead of
    // an uncaught JSON-parse exception.
    internal fun fetchJson(request: HttpUriRequest): JsonBrowser? {
        return try {
            val response = httpInterface.execute(request)
            val statusCode = response.statusLine.statusCode
            if (!HttpClientTools.isSuccessWithContent(statusCode)) {
                log.warn("Bilibili request to ${request.uri} failed with HTTP $statusCode")
                return null
            }
            JsonBrowser.parse(response.entity.content)
        } catch (e: Exception) {
            log.warn("Failed to fetch/parse response from ${request.uri}: ${e.message}")
            null
        }
    }

    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {
        log.debug("DEBUG: reference.identifier: ${reference.identifier}")

        // Handle bilisearch: prefix for search functionality
        if (reference.identifier.startsWith(SEARCH_PREFIX)) {
            if (config?.allowSearch != true) {
                log.debug("Bilibili search is disabled in configuration")
                return BasicAudioPlaylist("Bilibili Search Disabled", emptyList(), null, true)
            }

            val searchQuery = reference.identifier.removePrefix(SEARCH_PREFIX).trim()
            log.debug("DEBUG: Bilibili search query: $searchQuery")
            val tracks = doSearch(searchQuery)
            return BasicAudioPlaylist("Bilibili Search: $searchQuery", tracks, null, true)
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

                    // Prefer scraping the video webpage's embedded __INITIAL_STATE__ over the
                    // bare x/web-interface/view API, which bilibili's risk control now flags
                    // more aggressively (-412). Fall back to the API call if scraping fails.
                    val trackData = (if (type == null) extractInitialStateVideoData(bvid) else null) ?: run {
                        val request = if (type != null) {
                            val aid = bvid.removePrefix("av")
                            HttpGet("${BASE_URL}x/web-interface/view?aid=$aid")
                        } else {
                            HttpGet("${BASE_URL}x/web-interface/view?bvid=$bvid")
                        }

                        log.debug("DEBUG: attempt GET with URL: ${BASE_URL}x/web-interface/view?bvid=$bvid")
                        val responseJson = fetchJson(request) ?: return AudioReference.NO_TRACK

                        val statusCode = responseJson.get("code").`as`(Int::class.java)
                        log.debug("DEBUG: statusCode: $statusCode")

                        if (statusCode != 0) {
                            val message = responseJson.get("message").text() ?: "Unknown error"
                            log.debug("Failed to load video: $message (code: $statusCode)")
                            null
                        } else {
                            responseJson.get("data")
                        }
                    } ?: return AudioReference.NO_TRACK

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

                    val responseJson = fetchJson(HttpGet("${BASE_URL}audio/music-service-c/web/$type/info?sid=$sid"))
                        ?: return AudioReference.NO_TRACK

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

    private fun extractInitialStateVideoData(bvid: String): JsonBrowser? {
        return try {
            val response = httpInterface.execute(HttpGet("https://www.bilibili.com/video/$bvid/"))
            val statusCode = response.statusLine.statusCode
            if (!HttpClientTools.isSuccessWithContent(statusCode)) {
                log.debug("Webpage scrape for $bvid got HTTP $statusCode, falling back to the API call")
                return null
            }

            val html = EntityUtils.toString(response.entity, StandardCharsets.UTF_8)

            val marker = "window.__INITIAL_STATE__="
            val markerIndex = html.indexOf(marker)
            if (markerIndex == -1) return null

            val jsonText = extractBalancedJson(html, markerIndex + marker.length) ?: return null
            val videoData = JsonBrowser.parse(jsonText).get("videoData")

            // Sanity-check the shape we actually rely on before trusting it, otherwise
            // fall back to the API call.
            if (videoData.get("bvid").text() == null || videoData.get("owner").get("name").text() == null) {
                return null
            }

            videoData
        } catch (e: Exception) {
            log.debug("Failed to extract __INITIAL_STATE__ for $bvid: ${e.message}")
            null
        }
    }

    private fun extractBalancedJson(text: String, startIndex: Int): String? {
        var depth = 0
        var start = -1
        var inString = false
        var escape = false

        for (i in startIndex until text.length) {
            val c = text[i]

            if (start == -1) {
                if (c == '{') {
                    start = i
                    depth = 1
                }
                continue
            }

            if (escape) {
                escape = false
                continue
            }

            when {
                inString && c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
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

    override fun loadSearch(query: String, types: Set<AudioSearchResult.Type>): AudioSearchResult? {
        if (!query.startsWith(SEARCH_PREFIX)) return null
        if (config?.allowSearch != true) return null

        val searchQuery = query.removePrefix(SEARCH_PREFIX).trim()
        val tracks = doSearch(searchQuery)
        return BasicAudioSearchResult(tracks, emptyList(), emptyList(), emptyList(), emptyList())
    }

    private fun doSearch(query: String): List<AudioTrack> {
        return try {
            val searchParams = mapOf(
                "search_type" to "video",
                "keyword" to query,
                "page" to "1",
                "page_size" to "20",
                "order" to "totalrank",
                "duration" to "0",
                "tids_1" to "0"
            )

            // WBI-signed regardless of authentication state - the plain unsigned search
            // endpoint is blocked by risk control (-412) far more often.
            val searchUrl = "${BASE_URL}x/web-interface/wbi/search/type?${signWbi(searchParams)}"

            log.debug("DEBUG: Bilibili search URL: $searchUrl")

            val responseJson = fetchJson(HttpGet(searchUrl)) ?: return emptyList()

            val statusCode = responseJson.get("code").`as`(Int::class.java)
            if (statusCode != 0) {
                val message = responseJson.get("message").text() ?: "Unknown error"
                log.warn("Bilibili search failed with status code: $statusCode, message: $message")

                when (statusCode) {
                    -412 -> log.error("Search blocked (-412): Need cookies. ${if (config?.isAuthenticated != true) "Configure authentication" else "Cookies may be expired"}")
                    -403 -> log.error("Access forbidden (-403): Rate limited or banned")
                    -400 -> log.error("Bad request (-400): Invalid parameters")
                }

                return emptyList()
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

                        tracks.add(BilibiliAudioTrack(
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
                        ))
                    }
                } catch (e: Exception) {
                    log.warn("Failed to parse search result item", e)
                }
            }

            log.debug("DEBUG: Found ${tracks.size} tracks for query: $query")
            tracks

        } catch (e: Exception) {
            log.error("Error during Bilibili search", e)
            emptyList()
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
                response.getFirstHeader("Content-Location")?.value ?: shortUrl
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
        log.debug("DEBUG: ${playlistData.text()}")

        val playlistName = playlistData.get("title").`as`(String::class.java)
        val sid = playlistData.get("statistic").get("sid").asLong(0).toString()

        val responseJson = fetchJson(HttpGet("${BASE_URL}audio/music-service-c/web/song/of-menu?sid=$sid&pn=1&ps=100"))
            ?: throw IllegalStateException("Empty response loading Bilibili audio playlist $sid page 1")

        val tracksData = responseJson.get("data").get("data").values()
        val tracks = ArrayList<AudioTrack>()

        var curPage = responseJson.get("data").get("curPage").`as`(Int::class.java)
        val pageCount = responseJson.get("data").get("pageCount").`as`(Int::class.java).let {
            if (playlistPageCountConfig == -1) it
            else if (it <= playlistPageCountConfig) it
            else playlistPageCountConfig
        }

        while (curPage <= pageCount) {
            val pageNumber = ++curPage
            val responseJsonPage = fetchJson(HttpGet("${BASE_URL}audio/music-service-c/web/song/of-menu?sid=$sid&pn=$pageNumber&ps=100"))
                ?: throw IllegalStateException("Empty response loading Bilibili audio playlist $sid page $pageNumber")
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

    @Volatile private var wbiKeys: String? = null
    @Volatile private var wbiKeysExpiry: Long = 0L

    private fun getWbiKeys(): String {
        wbiKeys?.takeIf { System.currentTimeMillis() < wbiKeysExpiry }?.let { return it }

        val json = fetchJson(HttpGet("${BASE_URL}x/web-interface/nav"))
            ?: throw IllegalStateException("Empty response fetching WBI keys")

        val wbiImg = json.get("data").get("wbi_img")
        val imgUrl = wbiImg.get("img_url").text()
            ?: throw IllegalStateException("Missing img_url in WBI response")
        val subUrl = wbiImg.get("sub_url").text()
            ?: throw IllegalStateException("Missing sub_url in WBI response")

        val imgKey = imgUrl.substring(imgUrl.lastIndexOf('/') + 1, imgUrl.lastIndexOf('.'))
        val subKey = subUrl.substring(subUrl.lastIndexOf('/') + 1, subUrl.lastIndexOf('.'))
        val rawKey = imgKey + subKey

        val sb = StringBuilder()
        for (index in MIXIN_KEY_ENC_TAB) {
            if (index < rawKey.length) sb.append(rawKey[index])
        }

        wbiKeys = sb.toString().take(32)
        wbiKeysExpiry = System.currentTimeMillis() + 60 * 60 * 1000
        return wbiKeys!!
    }

    // Signs a request with bilibili's WBI scheme (w_rid), required by risk control on
    // endpoints such as x/player/wbi/playurl and x/web-interface/wbi/search/type.
    internal fun signWbi(params: Map<String, String>): String {
        val mixinKey = getWbiKeys()
        val currTime = System.currentTimeMillis() / 1000
        val allParams = params.toMutableMap().apply { put("wts", currTime.toString()) }

        val query = allParams.entries
            .sortedBy { it.key }
            .joinToString("&") { (key, value) ->
                val cleanValue = value.replace(Regex("[!'()*]"), "")
                "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(cleanValue, StandardCharsets.UTF_8)}"
            }

        val md5Bytes = MessageDigest.getInstance("MD5")
            .digest((query + mixinKey).toByteArray(StandardCharsets.UTF_8))
        val wRid = md5Bytes.joinToString("") { "%02x".format(it) }
        return "$query&w_rid=$wRid"
    }

    // Device-fingerprint params bilibili's risk control expects on WBI-signed endpoints
    // such as x/player/wbi/playurl (see bili-user-fingerprint.min.js). Missing these
    // significantly raises the odds of a -412 rejection.
    internal fun dmParams(): Map<String, String> {
        val random = ThreadLocalRandom.current()

        var weightIndex = random.nextInt(SCREEN_WEIGHTS.sum())
        val (width, height) = SCREEN_DIMS.indices.first { i ->
            weightIndex -= SCREEN_WEIGHTS[i]
            weightIndex < 0
        }.let { SCREEN_DIMS[it] }

        val whRnd = Math.floor(114 * random.nextDouble()).toInt()
        val wh = listOf(2 * width + 2 * height + 3 * whRnd, 4 * width - height + whRnd, whRnd)

        val scrollTop = random.nextInt(101)
        val ofRnd = Math.floor(514 * random.nextDouble()).toInt()
        val of = listOf(3 * scrollTop + ofRnd, 4 * scrollTop + 2 * ofRnd, ofRnd)

        fun randomPrintable(length: Int): String =
            (1..length).map { PRINTABLE_CHARS[random.nextInt(PRINTABLE_CHARS.length)] }.joinToString("")

        val dmImgStr = Base64.getEncoder()
            .encodeToString(randomPrintable(16 + random.nextInt(49)).toByteArray(StandardCharsets.UTF_8))
            .dropLast(2)
        val dmCoverImgStr = Base64.getEncoder()
            .encodeToString(randomPrintable(32 + random.nextInt(97)).toByteArray(StandardCharsets.UTF_8))
            .dropLast(2)
        val dmImgInter = """{"ds":[],"wh":[${wh[0]},${wh[1]},${wh[2]}],"of":[${of[0]},${of[1]},${of[2]}]}"""

        return mapOf(
            "dm_img_list" to "[]",
            "dm_img_str" to dmImgStr,
            "dm_cover_img_str" to dmCoverImgStr,
            "dm_img_inter" to dmImgInter
        )
    }

    override fun loadLyrics(audioTrack: AudioTrack): AudioLyrics? {
        if (config?.allowLyrics != true) return null
        val bilibiliTrack = audioTrack as? BilibiliAudioTrack ?: return null
        if (bilibiliTrack.type != BilibiliAudioTrack.TrackType.VIDEO) return null

        return try {
            val bvid = bilibiliTrack.id
            var cid = bilibiliTrack.cid ?: 0L

            if (cid == 0L) {
                val viewJson = fetchJson(HttpGet("${BASE_URL}x/web-interface/view?bvid=$bvid")) ?: return null
                if (viewJson.get("code").asLong(-1) != 0L) return null
                cid = viewJson.get("data").get("cid").asLong(0)
            }
            if (cid == 0L) return null

            val query = signWbi(
                mapOf("bvid" to bvid, "cid" to cid.toString(), "fnval" to "16", "qn" to "120")
            )
            val playerJson = fetchJson(HttpGet("${BASE_URL}x/player/wbi/v2?$query")) ?: return null

            if (playerJson.get("code").asLong(-1) != 0L) return null

            val subtitles = playerJson.get("data").get("subtitle").get("subtitles")
            if (subtitles.values().isEmpty()) return null

            val rawSubUrl = subtitles.index(0).get("subtitle_url").text() ?: return null
            val subUrl = if (rawSubUrl.startsWith("//")) "https:$rawSubUrl" else rawSubUrl

            val subJson = fetchJson(HttpGet(subUrl)) ?: return null

            val lines = ArrayList<AudioLyrics.Line>()
            for (item in subJson.get("body").values()) {
                val from = item.get("from").safeText().toDoubleOrNull() ?: continue
                val to = item.get("to").safeText().toDoubleOrNull() ?: continue
                val content = item.get("content").text() ?: continue
                lines.add(
                    BasicAudioLyrics.BasicLine(
                        Duration.ofMillis((from * 1000).toLong()),
                        Duration.ofMillis(((to - from) * 1000).toLong()),
                        content
                    )
                )
            }

            if (lines.isEmpty()) return null

            BasicAudioLyrics("bilibili", "Bilibili CC", null, lines)
        } catch (e: Exception) {
            log.error("Failed to load Bilibili lyrics: ${e.message}", e)
            null
        }
    }

    override fun shutdown() {
        //
    }

    companion object {
        const val BASE_URL = "https://api.bilibili.com/"
        const val SEARCH_PREFIX = "bilisearch:"

        private val MIXIN_KEY_ENC_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
            26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
            20, 34, 44, 52
        )

        // Simplified pattern - we'll handle page parameter extraction separately
        private val URL_PATTERN = Pattern.compile(
            "^https?://(?:(?:www|m)\\.)?(?:bilibili\\.com|b23\\.tv)/(?<type>video|audio)/(?<id>(?:(?<audioType>am|au|av)?(?<audioId>[0-9]+))|[A-Za-z0-9]+)/?(?:\\?.*)?$"
        )

        // Mirrors yt-dlp's bilibili extractor (_dm_params): common screen resolutions
        // weighted by real-world prevalence, used only to fabricate a plausible dm_img_inter.
        private val SCREEN_DIMS = listOf(
            1920 to 1080, 1366 to 768, 1536 to 864, 1280 to 720,
            2560 to 1440, 1440 to 900, 1600 to 900
        )
        private val SCREEN_WEIGHTS = listOf(18, 18, 17, 8, 7, 5, 5)

        // Equivalent to Python's string.printable, used for the opaque dm_img_str/dm_cover_img_str blobs.
        private const val PRINTABLE_CHARS =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~ \t\n\r"

        private fun getVideoUrl(id: String, page: Int? = null): String {
            return "https://www.bilibili.com/video/$id${if (page != null) "?p=$page" else ""}"
        }

        private fun getAudioUrl(id: String): String {
            return "https://www.bilibili.com/audio/$id"
        }
    }
}
