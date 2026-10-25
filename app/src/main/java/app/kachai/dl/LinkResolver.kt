package app.kachai.dl

import app.kachai.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Приводит ссылку к виду, который понимает yt-dlp.
 *
 * Клипы Одноклассников и VK — одна платформа: клип OK — это клип VK с теми же номерами
 * (ok.ru/clip/-239067385/456239017 = vk.com/clip-239067385_456239017), а yt-dlp знает только ссылку VK.
 * Страницу клипа в OK не разбираем: гостю OK отдаёт её по-разному (то клип, то заглушку «войдите»).
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
        okClipToVk(url)?.let { Diag.log(TAG, "ok clip -> $it"); return it }
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

    /**
     * Клип OK для вошедшего в OK: клипы групп OK, закрытые в VK, OK показывает своим пользователям.
     * Берём со страницы клипа номер видео и качаем его как обычное видео OK (с куками OK).
     * null — OK считает нас гостем или номера на странице нет.
     */
    suspend fun okClipAsVideo(url: String, okCookies: File): String? {
        if (!isOkClip(url)) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                // Чистый адрес клипа: с хвостом ?st.cmd=userMain (так копирует OK) приходит главная страница,
                // а клип на ней подгружается скриптом — номера видео в разметке нет
                val clean = okClip.find(url)?.let { "https://ok.ru/clip/${it.groupValues[1]}/${it.groupValues[2]}" } ?: url
                val request = Request.Builder().url(clean)
                    .header("User-Agent", MOBILE_UA)
                    .apply { cookieHeader(okCookies)?.let { header("Cookie", it) } }
                    .build()
                client.newCall(request).execute().use { response ->
                    val page = response.peekBody(4_000_000).string()
                    if ("isAnonym:true" in page) {
                        Diag.log(TAG, "ok clip: OK sees us as guest")
                        return@use null
                    }
                    // На странице — лента из нескольких клипов. Нужный ищем по номеру клипа из ссылки:
                    // "movie":{"id":"456239017","movieId":"16911993547084",…} — это и есть видео OK
                    val clipId = okClip.find(url)?.groupValues?.get(2) ?: return@use null
                    val json = page.replace("&quot;", "\"").replace("\\u0026quot;", "\"").replace("\\\"", "\"")
                    Regex(""""id"\s*:\s*"$clipId"\s*,\s*"movieId"\s*:\s*"(\d+)"""").find(json)
                        ?.let { "https://ok.ru/video/${it.groupValues[1]}" }
                        ?: null.also {
                            Diag.log(TAG, "ok clip $clipId not on page: ${page.length} chars, " +
                                "mentions=${Regex(clipId).findAll(page).count()}, at ${Diag.short(response.request.url.toString())}")
                        }
                }
            }.onFailure { Diag.log(TAG, "ok clip via OK failed: ${it.message}") }
                .getOrNull()
                ?.also { Diag.log(TAG, "ok clip via OK -> $it") }
        }
    }

    fun isOkClipUrl(url: String) = isOkClip(url)

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

    /** Куки из файла Netscape (как его пишет Accounts) — в заголовок Cookie для ok.ru. */
    private fun cookieHeader(file: File): String? = runCatching {
        file.readLines()
            .filter { !it.startsWith("#") && it.isNotBlank() }
            .map { it.split('\t') }
            .filter { it.size >= 7 && it[0].trimStart('.').let { d -> d == "ok.ru" || d.endsWith(".ok.ru") } }
            .joinToString("; ") { "${it[5]}=${it[6]}" }
            .ifEmpty { null }
    }.getOrNull()

    private fun okClipToVk(text: String): String? =
        okClip.find(text)?.let { "https://vk.com/clip${it.groupValues[1]}_${it.groupValues[2]}" }

    private fun isOkClip(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return (host == "ok.ru" || host.endsWith(".ok.ru")) && uri.path.orEmpty().startsWith("/clip")
    }
}
