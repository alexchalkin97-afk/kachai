package app.kachai.accounts

import android.content.Context
import android.webkit.CookieManager
import app.kachai.Diag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Сайты, где можно привязать аккаунт, чтобы качать видео «только для вошедших». */
enum class Site(
    val title: String,
    val loginUrl: String,
    /** Домены, чьи куки сохраняем для yt-dlp. */
    val hosts: List<String>,
    /** Куда зайти после входа, чтобы сайт выдал сессию, которая нужна yt-dlp. */
    val afterLoginUrls: List<String>,
    /** Заходить туда как настольный браузер (см. VK). */
    val desktopAfterLogin: Boolean,
    /** Ещё домены, где сайт хранит вход, — их тоже чистим при выходе. */
    val extraHosts: List<String> = emptyList(),
) {
    /**
     * VK входит на мобильной m.vk.ru, и настоящая сессия живёт там и на login.vk.ru, а remixsid на
     * .vk.ru остаётся гостевой. yt-dlp же ходит за видео на настольный vk.com. Поэтому после входа
     * открываем vk.ru/feed как настольный браузер: VK сам делает автовход через login.vk.ru и выдаёт
     * настольную сессию, а save() копирует её на vk.com и vkvideo.ru.
     */
    VK("ВКонтакте", "https://m.vk.com/login", listOf("vk.com", "vkvideo.ru", "vk.ru"),
        listOf("https://vk.ru/feed"), desktopAfterLogin = true,
        extraHosts = listOf("m.vk.ru", "login.vk.ru", "id.vk.ru", "api.vk.ru", "m.vk.com", "m.vkvideo.ru")),
    OK("Одноклассники", "https://m.ok.ru/", listOf("ok.ru"), emptyList(), desktopAfterLogin = false),
}

private val VK_SESSION = setOf("remixsid", "remixnsid")
/** Кука сессии вошедшего в OK. */
private val OK_SESSION = setOf("AUTHCODE")
/** С ней vk.com перенаправляет на vk.ru, и POST-запрос yt-dlp теряет параметры. */
private const val VK_REDIRECT_COOKIE = "REDIRECT_TO_VK_RU"

/**
 * Привязанные аккаунты. Вход — на официальной странице сайта во встроенном браузере;
 * пароль приложение не видит. После входа сохраняем куки сессии в файл формата Netscape,
 * который понимает yt-dlp (--cookies). Файлы лежат только на телефоне.
 */
object Accounts {
    private lateinit var dir: File

    private val _linked = MutableStateFlow<Set<Site>>(emptySet())
    val linked: StateFlow<Set<Site>> = _linked.asStateFlow()

    fun init(context: Context) {
        dir = File(context.filesDir, "accounts").apply { mkdirs() }
        // Прежние версии могли «привязать» OK гостевыми куками — такую привязку забываем
        file(Site.OK).takeIf { f -> f.exists() && OK_SESSION.none { name -> f.readText().contains("\t$name\t") } }
            ?.let { it.delete(); Diag.log("Accounts", "ok: guest cookies removed") }
        refresh()
    }

    fun isLinked(site: Site) = site in _linked.value

    fun siteFor(url: String): Site? {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return null
        return Site.entries.firstOrNull { s -> s.hosts.any { host == it || host.endsWith(".$it") } }
    }

    /** Файл сессии для ссылки, если её сайт привязан. */
    fun cookiesFor(url: String): File? = siteFor(url)?.let(::file)?.takeIf { it.exists() }

    /** Похоже ли, что вход уже выполнен (чтобы закрыть окно входа самим). */
    fun looksLoggedIn(site: Site, url: String?): Boolean {
        val cm = CookieManager.getInstance()
        return when (site) {
            // VK переехал на vk.ru; после мобильного входа сессия (remixnsid) — на m.vk.ru
            Site.VK -> listOf("https://m.vk.ru", "https://vk.ru", "https://vk.com").any { host ->
                cm.getCookie(host).orEmpty().let { "remixsid=" in it || "remixnsid=" in it }
            }
            // Только если это и правда ok.ru: у ленты VK тоже путь /feed
            Site.OK -> {
                val uri = url?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
                val host = uri?.host.orEmpty()
                (host == "ok.ru" || host.endsWith(".ok.ru")) && "anonym" !in url!! && "login" !in url &&
                    (uri!!.path.orEmpty().startsWith("/feed") || "st.cmd=userMain" in url)
            }
        }
    }

    /** Сохранить куки сайта из встроенного браузера. false — сессии нет (вход не выполнен). */
    fun save(site: Site): Boolean {
        val cm = CookieManager.getInstance()
        cm.flush()
        val byHost = site.hosts.associateWith { host ->
            cm.getCookie("https://$host").orEmpty().split(";").map { it.trim() }.filter { "=" in it }
                .associate { it.substringBefore("=") to it.substringAfter("=") }.toMutableMap()
        }
        if (site == Site.VK) {
            // Настольная сессия — на vk.ru (после автовхода, см. Site.VK). На vk.com и vkvideo.ru
            // лежат гостевые куки — заменяем их сессией с vk.ru
            byHost.values.forEach { it.remove(VK_REDIRECT_COOKIE) }
            val donor = byHost["vk.ru"]?.takeIf { c -> VK_SESSION.any { it in c } }
                ?: byHost.values.firstOrNull { c -> VK_SESSION.any { it in c } }
            if (donor != null) for (c in byHost.values) {
                if (c !== donor) c.putAll(donor.filterKeys { it.startsWith("remix") })
            }
            Diag.log("Accounts", "vk session: " + byHost.mapValues { (_, c) -> VK_SESSION.filter { it in c } })
        }
        val lines = mutableListOf("# Netscape HTTP Cookie File")
        val farFuture = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        for ((host, cookies) in byHost) for ((name, value) in cookies) {
            lines += listOf(".$host", "TRUE", "/", "TRUE", farFuture.toString(), name, value).joinToString("\t")
        }
        val noCookies = lines.size == 1
        val vkWithoutSession = site == Site.VK && !looksLoggedIn(site, null)
        // У гостя OK тоже есть куки (JSESSIONID, счётчики) — привязан, только если есть сессия входа.
        // Вход в OK идёт через VK ID, и если его не закончить, без этой проверки сохранились бы гостевые куки
        val okWithoutSession = site == Site.OK && byHost.values.none { c -> OK_SESSION.any { it in c } }
        if (site == Site.OK) Diag.log("Accounts", "ok session: ${!okWithoutSession}")
        if (noCookies || vkWithoutSession || okWithoutSession) return false
        file(site).writeText(lines.joinToString("\n", postfix = "\n"))
        refresh()
        return true
    }

    fun logout(site: Site) {
        file(site).delete()
        val cm = CookieManager.getInstance()
        for (host in site.hosts + site.extraHosts) {
            val raw = cm.getCookie("https://$host") ?: continue
            raw.split(";").map { it.trim().substringBefore("=") }.filter { it.isNotEmpty() }.forEach { name ->
                cm.setCookie("https://$host", "$name=; Max-Age=0; Path=/")
                cm.setCookie("https://$host", "$name=; Max-Age=0; Path=/; Domain=.$host")
            }
        }
        cm.flush()
        refresh()
    }

    private fun file(site: Site) = File(dir, "${site.name.lowercase()}.txt")

    private fun refresh() {
        _linked.value = Site.entries.filter { file(it).exists() }.toSet()
    }
}
