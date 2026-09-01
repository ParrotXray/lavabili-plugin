package com.github.parrotxray.lavabili.source

import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextFilter
import com.github.parrotxray.lavabili.plugin.BilibiliConfig
import com.github.parrotxray.lavabili.plugin.LavabiliPlugin
import com.github.parrotxray.lavabili.util.CookieRefreshManager
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpUriRequest
import org.apache.http.client.protocol.HttpClientContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

class BilibiliHttpContextFilter(
    private val config: BilibiliConfig? = null,
    // Resolved once per source manager instance (see BilibiliAudioSourceManager) and
    // reused for every request here, matching how a real device keeps a stable
    // fingerprint instead of a new one per call.
    private val buvid3: String,
    private val buvid4: String,
    private val httpInterface: com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface? = null
) : HttpContextFilter {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(LavabiliPlugin::class.java)
    }

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
        request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
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

        cookieBuilder.append("buvid3=${buvid3}; ")
        cookieBuilder.append("buvid4=${buvid4}; ")
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
}
