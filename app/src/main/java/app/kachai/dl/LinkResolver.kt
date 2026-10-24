package app.kachai.dl

import app.kachai.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Приводит ссылку к виду, который понимает yt-dlp.
 *
 * Клип в Одноклассниках — это клип VK с теми же номерами, но yt-dlp знает только ссылку VK.
 * Ссылки «Поделиться» из OK бывают без номеров (ok.ru/clip?…): тогда идём по перенаправлению
 * и берём номера из итогового адреса или из страницы.
 */
object LinkResolver {
    private const val TAG = "Link"
    private val okClip = Regex("""ok\.ru/clip/(-?\d+)/(\d+)""")

    private val client by lazy {
        OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).followRedirects(true).build()
    }

    suspend fun resolve(url: String): String {
        okClipToVk(url)?.let { return it }
        if (!isOkClip(url)) return url
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    okClipToVk(response.request.url.toString())
                        ?: okClipToVk(response.peekBody(1_000_000).string())
                }
            }.onFailure { Diag.log(TAG, "ok clip resolve failed: ${it.message}") }
                .getOrNull()
                ?.also { Diag.log(TAG, "ok clip -> $it") }
                ?: url
        }
    }

    private fun okClipToVk(text: String): String? =
        okClip.find(text)?.let { "https://vk.com/clip${it.groupValues[1]}_${it.groupValues[2]}" }

    private fun isOkClip(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return (host == "ok.ru" || host.endsWith(".ok.ru")) && uri.path.orEmpty().startsWith("/clip")
    }
}
