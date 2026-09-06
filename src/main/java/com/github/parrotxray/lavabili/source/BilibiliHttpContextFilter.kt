package com.github.parrotxray.lavabili.source

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser
import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextFilter
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools
import com.github.parrotxray.lavabili.plugin.BilibiliConfig
import com.github.parrotxray.lavabili.plugin.LavabiliPlugin
import com.github.parrotxray.lavabili.util.CookieRefreshManager
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpUriRequest
import org.apache.http.client.protocol.HttpClientContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class BilibiliHttpContextFilter(
    private val config: BilibiliConfig? = null,
    initialBuvid3: String,
    initialBuvid4: String,
    // Bilibili's JS-computed browser fingerprint cookie. There's no public endpoint that
    // issues a real one, so this is a stable per-instance placeholder (matching yt-dlp's
    // own workaround) - better than sending no buvid_fp at all.
    private val buvidFp: String,
    // Only auto-refresh buvid3/4 on a 412 when they weren't explicitly pinned via config.
    private val autoManagedBuvid: Boolean = true,
    private val httpInterface: com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface? = null
) : HttpContextFilter {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(LavabiliPlugin::class.java)
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val BUVID_REFRESH_MIN_INTERVAL_MS = 30_000L
    }

    // Resolved once per source manager instance (see BilibiliAudioSourceManager) and
    // reused for every request here, matching how a real device keeps a stable
    // fingerprint instead of a new one per call - refreshed only on a 412 (see below).
    private val buvid3: AtomicReference<String> = AtomicReference(initialBuvid3)
    private val buvid4: AtomicReference<String> = AtomicReference(initialBuvid4)
    private val lastBuvidRefreshAttempt: AtomicLong = AtomicLong(0L)

    private val cookieRefreshManager: AtomicReference<CookieRefreshManager?> = AtomicReference(null)

    private fun getCookieRefreshManager(): CookieRefreshManager? {
        if (config == null || httpInterface == null) return null

        return cookieRefreshManager.updateAndGet { current ->
            current ?: CookieRefreshManager(config, httpInterface)
        }
    }

    override fun onContextOpen(context: HttpClientContext) {
        //
    }

    override fun onContextClose(context: HttpClientContext) {
        //
    }

    override fun onRequest(context: HttpClientContext, request: HttpUriRequest, isRepetition: Boolean) {
        request.setHeader("Referer", "https://www.bilibili.com/")
        request.setHeader("User-Agent", USER_AGENT)
        request.setHeader("Origin", "https://www.bilibili.com")
        request.setHeader("Accept", "application/json, text/plain, */*")
        request.setHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")

        val cookieBuilder = StringBuilder()

        if (config?.isAuthenticated == true) {
            val auth = config.auth

            if (auth.sessdata.isNotEmpty()) {
                cookieBuilder.append("SESSDATA=${auth.sessdata}; ")
            }

            if (auth.biliJct.isNotEmpty()) {
                cookieBuilder.append("bili_jct=${auth.biliJct}; ")
            }

            if (auth.dedeUserId.isNotEmpty()) {
                cookieBuilder.append("DedeUserID=${auth.dedeUserId}; ")
            }

            if (auth.acTimeValue.isNotEmpty()) {
                cookieBuilder.append("ac_time_value=${auth.acTimeValue}; ")
            }
        }

        cookieBuilder.append("buvid3=${buvid3.get()}; ")
        cookieBuilder.append("buvid4=${buvid4.get()}; ")
        cookieBuilder.append("buvid_fp=${buvidFp}; ")
        cookieBuilder.append("CURRENT_FNVAL=4048")

        request.setHeader("Cookie", cookieBuilder.toString())

        if (request.uri.host?.contains("api.bilibili.com") == true) {
            request.setHeader("X-Requested-With", "XMLHttpRequest")

            if (request.uri.path?.contains("/search/") == true) {
                request.setHeader("Sec-Ch-Ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
                request.setHeader("Sec-Ch-Ua-Mobile", "?0")
                request.setHeader("Sec-Ch-Ua-Platform", "\"Windows\"")
                request.setHeader("Sec-Fetch-Dest", "empty")
                request.setHeader("Sec-Fetch-Mode", "cors")
                request.setHeader("Sec-Fetch-Site", "same-site")
                request.setHeader("Referer", "https://search.bilibili.com/")
            }

            if (config?.isAuthenticated == true && config.auth.biliJct.isNotEmpty()) {
                val uri = request.uri.toString()
                if (uri.contains("/web-interface/") || uri.contains("/pgc/player/")) {
                    request.setHeader("X-CSRF-Token", config.auth.biliJct)
                }
            }
        }
    }

    override fun onRequestResponse(
        context: HttpClientContext,
        request: HttpUriRequest,
        response: HttpResponse
    ): Boolean {
        if (response.statusLine.statusCode == 412) {
            log.warn("Received HTTP 412 from bilibili (request blocked by risk control) for ${request.uri}")

            if (autoManagedBuvid) {
                refreshBuvidFromFingerSpi()
            }
        }

        if (response.statusLine.statusCode == 401 ||
            response.statusLine.statusCode == 403) {

            log.warn("Received authentication error response (${response.statusLine.statusCode}), cookies may need to be refreshed")

            if (config?.canRefreshCookies == true) {
                val refreshManager = getCookieRefreshManager()
                if (refreshManager != null) {
                    try {
                        val result = refreshManager.refreshCookies()
                        if (result.success) {
                            log.info("Cookie refresh triggered by authentication error succeeded")
                        } else {
                            log.warn("Cookie refresh triggered by authentication error failed: ${result.message}")
                        }
                    } catch (e: Exception) {
                        log.error("Exception occurred while refreshing cookies", e)
                    }
                }
            } else {
                log.warn("Received authentication error, but ac_time_value is not configured, cannot auto-refresh cookies")
                log.info("Please manually update the cookie configuration or add ac_time_value to enable auto-refresh")
            }
        }

        return false
    }

    override fun onRequestException(context: HttpClientContext?, request: HttpUriRequest, error: Throwable): Boolean {
        return false
    }

    // Bilibili's risk control increasingly rejects requests carrying a buvid3/buvid4
    // that it never actually issued (see yt-dlp issue #14830 / PR #16889), so on a 412
    // we re-fetch a server-issued pair from x/frontend/finger/spi rather than keep
    // retrying with the same (possibly already-flagged) values. Throttled since a burst
    // of 412s from the same underlying cause shouldn't hammer the endpoint repeatedly.
    private fun refreshBuvidFromFingerSpi() {
        if (httpInterface == null) return

        val now = System.currentTimeMillis()
        val last = lastBuvidRefreshAttempt.get()
        if (now - last < BUVID_REFRESH_MIN_INTERVAL_MS) return
        if (!lastBuvidRefreshAttempt.compareAndSet(last, now)) return

        try {
            val request = HttpGet("https://api.bilibili.com/x/frontend/finger/spi")
            request.setHeader("User-Agent", USER_AGENT)
            val response = httpInterface.execute(request)
            if (!HttpClientTools.isSuccessWithContent(response.statusLine.statusCode)) {
                log.debug("finger/spi refresh got HTTP ${response.statusLine.statusCode}")
                return
            }

            val json = JsonBrowser.parse(response.entity.content)
            if (json.get("code").asLong(-1) != 0L) return

            val newBuvid3 = json.get("data").get("b_3").text()
            val newBuvid4 = json.get("data").get("b_4").text()
            if (!newBuvid3.isNullOrEmpty() && !newBuvid4.isNullOrEmpty()) {
                buvid3.set(newBuvid3)
                buvid4.set(newBuvid4)
                log.info("Refreshed buvid3/buvid4 from finger/spi after HTTP 412")
            }
        } catch (e: Exception) {
            log.debug("Failed to refresh buvid3/buvid4 after 412: ${e.message}")
        }
    }
}
