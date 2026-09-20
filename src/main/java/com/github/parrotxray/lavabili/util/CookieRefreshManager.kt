package com.github.parrotxray.lavabili.util

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import com.github.parrotxray.lavabili.plugin.BilibiliConfig
import com.github.parrotxray.lavabili.plugin.LavabiliPlugin
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.util.EntityUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Bilibili Cookie Refresh Manager
 * 
 * Implements an automatic cookie refresh mechanism based on ac_time_value (refresh_token)
 * Reference: https://github.com/SocialSisterYi/bilibili-API-collect/blob/master/docs/login/cookie_refresh.md
 */
class CookieRefreshManager(
    private val config: BilibiliConfig,
    private val httpInterface: HttpInterface
) {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(LavabiliPlugin::class.java)
        
        // API endpoints related to cookie refresh
        const val COOKIE_INFO_URL = "https://passport.bilibili.com/x/passport-login/web/cookie/info"
        const val COOKIE_REFRESH_URL = "https://passport.bilibili.com/x/passport-login/web/cookie/refresh"
        const val CONFIRM_REFRESH_URL = "https://passport.bilibili.com/x/passport-login/web/confirm/refresh"
        const val CORRESPOND_BASE_URL = "https://www.bilibili.com/correspond/1/"

        // Regex pattern to match refresh_csrf on the correspond page
        private val REFRESH_CSRF_PATTERN = Pattern.compile("<div id=\"1-name\">([^<]+)</div>")

        // Bilibili's fixed 1024-bit RSA public key for encrypting the CorrespondPath message
        // (does not rotate - see https://github.com/SocialSisterYi/bilibili-API-collect/blob/master/docs/login/cookie_refresh.md).
        private const val CORRESPOND_PUBLIC_KEY_PEM = """-----BEGIN PUBLIC KEY-----
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg
Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71
nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40
JNrRuoEUXpabUzGB8QIDAQAB
-----END PUBLIC KEY-----"""

        private val correspondPublicKey: PublicKey by lazy {
            val base64Body = CORRESPOND_PUBLIC_KEY_PEM
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
            val keySpec = X509EncodedKeySpec(Base64.getDecoder().decode(base64Body))
            KeyFactory.getInstance("RSA").generatePublic(keySpec)
        }
    }
    
    fun shouldRefreshCookies(): Boolean {
        if (!config.canRefreshCookies) {
            return false
        }
        
        return try {
            val response = httpInterface.execute(HttpGet(COOKIE_INFO_URL))
            val responseJson = JsonBrowser.parse(response.entity.content)
            
            val code = responseJson.get("code").asLong(0)
            val refresh = responseJson.get("data").get("refresh").asBoolean(false)
            
            log.debug("Cookie check result: code=$code, refresh=$refresh")
            
            // code=0 indicates success, refresh=1 indicates a refresh is needed
            code == 0L && refresh
        } catch (e: Exception) {
            log.warn("Failed to check cookie status", e)
            false
        }
    }
    
    fun refreshCookies(): RefreshResult {
        if (!config.canRefreshCookies) {
            return RefreshResult.error("ac_time_value not configured or authentication info incomplete")
        }
        
        return try {
            log.info("Starting Bilibili cookie refresh...")
            
            // 1. Generate CorrespondPath
            val correspondPath = generateCorrespondPath()
            log.debug("Generated CorrespondPath: $correspondPath")
            
            // 2. Retrieve refresh_csrf
            val refreshCsrf = getRefreshCsrf(correspondPath)
            log.debug("Retrieved refresh_csrf: $refreshCsrf")
            
            // 3. Refresh cookie
            val refreshResult = performCookieRefresh(refreshCsrf)
            if (!refreshResult.success) {
                return refreshResult
            }
            
            // 4. Confirm refresh
            val confirmResult = confirmRefresh(refreshResult.newRefreshToken!!)
            
            if (confirmResult.success) {
                log.info("Cookie refresh succeeded")
            }
            
            confirmResult
            
        } catch (e: Exception) {
            log.error("Error occurred during cookie refresh", e)
            RefreshResult.error("Refresh failed: ${e.message}")
        }
    }
    
    // CorrespondPath is RSA-OAEP(SHA-256)-encrypting "refresh_<timestamp>" with bilibili's
    // public key and hex-encoding the ciphertext - it is NOT an arbitrary local hash. Bilibili
    // decrypts it server-side to validate the timestamp; anything else gets a 404 correspond
    // page (which is exactly the "Unable to extract refresh_csrf" failure this used to hit).
    private fun generateCorrespondPath(): String {
        val message = "refresh_${System.currentTimeMillis()}"

        val oaepParams = OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
        )
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.ENCRYPT_MODE, correspondPublicKey, oaepParams)
        val encrypted = cipher.doFinal(message.toByteArray(StandardCharsets.UTF_8))

        return encrypted.joinToString("") { "%02x".format(it) }
    }

    private fun getRefreshCsrf(correspondPath: String): String {
        val correspondUrl = "$CORRESPOND_BASE_URL$correspondPath"
        val response = httpInterface.execute(HttpGet(correspondUrl))
        
        val htmlContent = EntityUtils.toString(response.entity, StandardCharsets.UTF_8)
        val matcher = REFRESH_CSRF_PATTERN.matcher(htmlContent)
        
        if (matcher.find()) {
            return matcher.group(1)
        } else {
            throw IllegalStateException("Unable to extract refresh_csrf from correspond page")
        }
    }

    private fun performCookieRefresh(refreshCsrf: String): RefreshResult {
        val params = mapOf(
            "csrf" to config.auth.biliJct,
            "refresh_csrf" to refreshCsrf,
            "source" to "main_web",
            "refresh_token" to config.auth.acTimeValue
        )
        
        val postData = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
        }
        
        val request = HttpPost(COOKIE_REFRESH_URL)
        request.setHeader("Content-Type", "application/x-www-form-urlencoded")
        request.entity = StringEntity(postData, StandardCharsets.UTF_8)
        
        val response = httpInterface.execute(request)
        val responseJson = JsonBrowser.parse(response.entity.content)
        
        val code = responseJson.get("code").asLong(-1)
        if (code != 0L) {
            val message = responseJson.get("message").text() ?: "Unknown error"
            return RefreshResult.error("Refresh failed: $message (code: $code)")
        }
        
        val newRefreshToken = responseJson.get("data").get("refresh_token").text()
        
        // Extract new cookies from response headers
        val newCookies = mutableMapOf<String, String>()
        response.allHeaders.filter { it.name.equals("Set-Cookie", ignoreCase = true) }
            .forEach { header ->
                val cookieValue = header.value
                val cookieParts = cookieValue.split(";")[0].split("=", limit = 2)
                if (cookieParts.size == 2) {
                    newCookies[cookieParts[0]] = cookieParts[1]
                }
            }
        
        log.info("=".repeat(80))
        log.info("Cookie refresh succeeded! Please update your application.yml configuration file:")
        
        newCookies["SESSDATA"]?.let { 
            log.info("sessdata: $it")
        }
        newCookies["bili_jct"]?.let { 
            log.info("biliJct: $it")
        }
        newCookies["DedeUserID"]?.let { 
            log.info("dedeUserId: $it")
        }

        log.info("buvid3: ${config.auth.buvid3}")
        log.info("buvid4: ${config.auth.buvid4}")
        log.info("acTimeValue: $newRefreshToken")

        return RefreshResult.success(newRefreshToken, newCookies)
    }

    private fun confirmRefresh(newRefreshToken: String): RefreshResult {
        val params = mapOf(
            "csrf" to config.auth.biliJct,
            "refresh_token" to config.auth.acTimeValue // use the old refresh_token
        )
        
        val postData = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
        }
        
        val request = HttpPost(CONFIRM_REFRESH_URL)
        request.setHeader("Content-Type", "application/x-www-form-urlencoded")
        request.entity = StringEntity(postData, StandardCharsets.UTF_8)
        
        val response = httpInterface.execute(request)
        val responseJson = JsonBrowser.parse(response.entity.content)
        
        val code = responseJson.get("code").asLong(-1)
        if (code != 0L) {
            val message = responseJson.get("message").text() ?: "Unknown error"
            return RefreshResult.error("Confirm refresh failed: $message (code: $code)")
        }
        
        return RefreshResult.success(newRefreshToken, emptyMap())
    }

    data class RefreshResult(
        val success: Boolean,
        val message: String,
        val newRefreshToken: String? = null,
        val newCookies: Map<String, String> = emptyMap()
    ) {
        companion object {
            fun success(newRefreshToken: String, newCookies: Map<String, String>) = 
                RefreshResult(true, "Refresh succeeded", newRefreshToken, newCookies)
            
            fun error(message: String) = RefreshResult(false, message)
        }
    }
}