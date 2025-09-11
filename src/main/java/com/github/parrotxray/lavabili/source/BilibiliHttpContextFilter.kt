package com.github.parrotxray.lavabili.source

import com.sedmelluq.discord.lavaplayer.tools.http.HttpContextFilter
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpUriRequest
import org.apache.http.client.protocol.HttpClientContext
import java.util.*

class BilibiliHttpContextFilter : HttpContextFilter {
    private fun generateBuvid3(): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val random = Random()
        val length = 32
        return (1..length)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
    }
    
    private fun generateBuvid4(): String {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val random = Random()
        val length = 36
        return (1..length)
            .map { chars[random.nextInt(chars.length)] }
            .joinToString("")
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
        request.setHeader("Accept-Encoding", "gzip, deflate, br")

        val buvid3 = generateBuvid3()
        val buvid4 = generateBuvid4()
        val cookieString = "buvid3=${buvid3}; buvid4=${buvid4}; CURRENT_FNVAL=4048"
        request.setHeader("Cookie", cookieString)

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
        }
    }

    override fun onRequestResponse(
        context: HttpClientContext,
        request: HttpUriRequest,
        response: HttpResponse
    ): Boolean {
        return false
    }

    override fun onRequestException(context: HttpClientContext?, request: HttpUriRequest, error: Throwable): Boolean {
        return false
    }
}