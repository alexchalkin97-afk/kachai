package app.kachai.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.kachai.BuildConfig
import app.kachai.Diag
import app.kachai.KachaiApp
import app.kachai.Stats
import app.kachai.accounts.Accounts
import app.kachai.accounts.Site
import app.kachai.data.Prefs
import app.kachai.data.ThemeMode
import app.kachai.dl.Downloads
import app.kachai.dl.Engine
import app.kachai.dl.EngineException
import app.kachai.dl.FormatOption
import app.kachai.dl.LinkResolver
import app.kachai.dl.MediaInfo
import app.kachai.net.Route
import app.kachai.net.Router
import app.kachai.net.Tunnel
import app.kachai.net.TunnelException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Lookup {
    data object Idle : Lookup
    data object Loading : Lookup
    data class Ready(val info: MediaInfo, val viaVps: Boolean) : Lookup
    /** [loginSite] — видео откроется после входа в этот аккаунт: предлагаем привязать. */
    /** [reloginSite] — аккаунт привязан, но сайт его не пустил: предлагаем войти заново. */
    data class Failed(val message: String, val loginSite: Site? = null, val reloginSite: Site? = null) : Lookup
}

/** Результат нажатия «Скачать». */
enum class StartResult { STARTED, STARTED_SHOW_AD, LIMIT_REACHED, NEEDS_PREMIUM }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val k = app as KachaiApp

    var input by mutableStateOf("")
    var lookup by mutableStateOf<Lookup>(Lookup.Idle)
        private set
    var selected by mutableStateOf<FormatOption?>(null)

    val tasks = Downloads.tasks
    val history = Downloads.history
    val isPremium = k.premium.isPremium
    val premiumUntil = k.premium.premiumUntil
    val quota = k.quota.state
    val themeMode = k.prefs.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val audioByDefault = k.prefs.audioByDefault.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val videoHeight = k.prefs.videoHeight.stateIn(viewModelScope, SharingStarted.Eagerly, 1080)
    val audioMp3 = k.prefs.audioMp3.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val linkedAccounts = Accounts.linked
    val vpnOn = Router.vpnFlow(app).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Открыть вкладку «Загрузки» (из уведомления). */
    val openDownloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var lookupJob: Job? = null

    fun onShared(text: String) {
        val url = extractUrl(text) ?: return
        input = url
        find()
    }

    fun clear() {
        lookupJob?.cancel()
        input = ""
        lookup = Lookup.Idle
        selected = null
    }

    fun find() {
        val raw = extractUrl(input)
        if (raw == null) {
            lookup = Lookup.Failed("Вставьте ссылку на видео")
            return
        }
        lookupJob?.cancel()
        lookup = Lookup.Loading
        selected = null
        lookupJob = viewModelScope.launch {
            // Клипы OK превращаем в ссылку VK (см. LinkResolver)
            val url = LinkResolver.resolve(raw)
            if (url != raw) input = url
            var route = Router.primary(k, url)
            for (attempt in 1..2) {
                val started = System.currentTimeMillis()
                Diag.log("Lookup", "${Diag.short(url)} route=$route vpn=${Router.isVpnActive(k)} attempt=$attempt")
                try {
                    val info = fetch(url, route).copy(lookupViaVps = route == Route.VPS)
                    Diag.log("Lookup", "ok in ${System.currentTimeMillis() - started}ms, ${info.options.size} options")
                    Stats.lookup(info.site, route.name, lastLookupWhere, true, System.currentTimeMillis() - started)
                    lookup = Lookup.Ready(info, route == Route.VPS)
                    selected = pickDefault(
                        info.options, audioByDefault.value, videoHeight.value, audioMp3.value, maxVideoHeight(),
                    )
                    return@launch
                } catch (e: EngineException) {
                    Diag.log("Lookup", "failed in ${System.currentTimeMillis() - started}ms: ${e.message}")
                    Stats.lookup(siteOf(url), route.name, lastLookupWhere, false, System.currentTimeMillis() - started, e.message)
                    if (e.needsLogin) { lookup = loginFailure(url, e); return@launch }
                    // Другого маршрута нет — пробуем тем же ещё раз: сайты вроде Дзена иногда отдают
                    // вместо страницы промежуточную (проверку, перенаправление), со второго раза проходит
                    val next = if (e.network && attempt == 1) Router.fallback(route, url) ?: route else null
                    if (next == null) { lookup = Lookup.Failed(e.message.orEmpty()); return@launch }
                    if (next == route) kotlinx.coroutines.delay(2000)
                    route = next
                } catch (e: TunnelException) {
                    Diag.log("Lookup", "tunnel failed: ${e.message}")
                    val next = if (attempt == 1) Router.fallback(route, url) else null
                    if (next == null) { lookup = Lookup.Failed("${e.message}. Попробуйте включить VPN"); return@launch }
                    route = next
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lookup = Lookup.Failed("Не получилось открыть ссылку")
                    return@launch
                }
            }
        }
    }

    /** Где нашли видео в последний раз — для статистики: server или phone. */
    private var lastLookupWhere = "phone"

    /** Сайт по адресу ссылки (без пути) — для статистики неудачных поисков. */
    private fun siteOf(url: String) = runCatching { java.net.URI(url).host?.removePrefix("www.")?.removePrefix("m.") }
        .getOrNull() ?: "?"

    private suspend fun fetch(url: String, route: Route): MediaInfo {
        lastLookupWhere = "phone"
        val cookies = Accounts.cookiesFor(url)
        if (route == Route.DIRECT) return Engine.fetchInfo(url, null, cookies)
        val ep = Tunnel.acquire(k)
        try {
            // Быстрый поиск на сервере; не ответил — ищем на телефоне, как раньше.
            // С куками аккаунта — только на телефоне: сессию на сервер не отправляем
            if (cookies == null) {
                val started = System.currentTimeMillis()
                lastLookupWhere = "server"
                try {
                    return Engine.fetchInfoRemote(url, k.http).also {
                        Diag.log("Lookup", "server ok in ${System.currentTimeMillis() - started}ms")
                    }
                } catch (e: java.io.IOException) {
                    Diag.log("Lookup", "server failed in ${System.currentTimeMillis() - started}ms: ${e.message}, trying on phone")
                    lastLookupWhere = "phone"
                }
            }
            return Engine.fetchInfo(url, ep.socksUrl, cookies)
        } finally {
            Tunnel.release()
        }
    }

    private fun loginFailure(url: String, e: EngineException): Lookup.Failed {
        val site = Accounts.siteFor(url) ?: return Lookup.Failed(e.message.orEmpty())
        return if (Accounts.isLinked(site)) {
            // VK так же отвечает и на удалённые ролики, но и сессия могла устареть
            Lookup.Failed(
                "${site.title} не отдал это видео с вашим аккаунтом. Если оно открывается у вас в ${site.title}, " +
                    "войдите заново. Иначе видео удалено или закрыто",
                reloginSite = site,
            )
        } else {
            Lookup.Failed("${site.title} показывает это видео только после входа в аккаунт", loginSite = site)
        }
    }

    /** Бесплатная версия качает видео не выше FREE_MAX_HEIGHT. */
    fun maxVideoHeight(): Int = if (isPremium.value) Int.MAX_VALUE else BuildConfig.FREE_MAX_HEIGHT

    fun isLocked(option: FormatOption): Boolean =
        option.kind == FormatOption.Kind.VIDEO && (option.height ?: 0) > maxVideoHeight()

    fun startDownload(): StartResult {
        val ready = lookup as? Lookup.Ready ?: return StartResult.STARTED
        val option = selected ?: return StartResult.STARTED
        if (isLocked(option)) return StartResult.NEEDS_PREMIUM
        if (!k.quota.canDownload()) {
            Stats.limitReached()
            return StartResult.LIMIT_REACHED
        }
        Downloads.enqueue(k, ready.info, option)
        viewModelScope.launch { k.quota.consume() }
        clear()
        if (isPremium.value) return StartResult.STARTED
        // Полноэкранная реклама — после каждой второй загрузки
        downloadsSinceAd++
        val showAd = downloadsSinceAd >= 2
        if (showAd) downloadsSinceAd = 0
        val saved = downloadsSinceAd
        viewModelScope.launch { k.prefs.edit { it[Prefs.DOWNLOADS_SINCE_AD] = saved } }
        return if (showAd) StartResult.STARTED_SHOW_AD else StartResult.STARTED
    }

    private var downloadsSinceAd = 0

    init {
        viewModelScope.launch { downloadsSinceAd = k.prefs.data.first()[Prefs.DOWNLOADS_SINCE_AD] ?: 0 }
    }

    fun addReward() = viewModelScope.launch { k.quota.addReward() }

    fun setThemeMode(m: ThemeMode) = viewModelScope.launch { k.prefs.setThemeMode(m) }
    fun setAudioByDefault(v: Boolean) = viewModelScope.launch { k.prefs.setAudioByDefault(v) }
    fun setVideoHeight(v: Int) = viewModelScope.launch { k.prefs.setVideoHeight(v) }
    fun setAudioMp3(v: Boolean) = viewModelScope.launch { k.prefs.setAudioMp3(v) }
    fun logout(site: Site) = Accounts.logout(site)
    fun setDebugPremium(on: Boolean) = viewModelScope.launch { k.premium.setDebug(on) }

    val canActivatePremium get() = k.premium.canActivate

    suspend fun activatePremium(code: String): Result<Long> = k.premium.activate(code, k.deviceId())

    /** Оплата через RuStore доступна в этой сборке. */
    val canBuyInApp get() = k.rustore.enabled

    suspend fun buyPlus(): Result<Unit> = k.rustore.purchase().also { Stats.purchase(it.isSuccess) }

    var engineVersion by mutableStateOf<String?>(null)
        private set
    var engineUpdating by mutableStateOf(false)
        private set

    fun loadEngineVersion() = viewModelScope.launch {
        runCatching { Engine.awaitReady() }
        engineVersion = Engine.version(k)
    }

    fun updateEngine(onDone: (String) -> Unit) = viewModelScope.launch {
        engineUpdating = true
        val msg = runCatching { if (Engine.update(k)) "Движок обновлён" else "Уже последняя версия" }
            .getOrElse { "Не удалось обновить: ${it.message}" }
        engineVersion = Engine.version(k)
        engineUpdating = false
        onDone(msg)
    }

    companion object {
        private val urlRegex = Regex("""https?://\S+""")
        private val bareRegex = Regex("""(?:www\.|m\.)?[a-z0-9-]+\.[a-z]{2,}/\S+""", RegexOption.IGNORE_CASE)

        fun extractUrl(text: String): String? {
            val t = text.trim()
            urlRegex.find(t)?.let { return it.value.trimEnd('.', ',', ')', '»', '"') }
            bareRegex.find(t)?.let { return "https://" + it.value }
            return null
        }

        /**
         * Вариант по умолчанию: «только звук» в выбранном формате, либо видео
         * не выше выбранного разрешения и не выше разрешённого тарифом.
         */
        fun pickDefault(
            options: List<FormatOption>,
            audio: Boolean,
            height: Int,
            mp3: Boolean,
            maxHeight: Int,
        ): FormatOption? {
            if (audio) {
                val kind = if (mp3) FormatOption.Kind.AUDIO_MP3 else FormatOption.Kind.AUDIO_M4A
                options.firstOrNull { it.kind == kind }?.let { return it }
            }
            val videos = options.filter { it.kind == FormatOption.Kind.VIDEO }
            val limit = minOf(height, maxHeight)
            return videos.filter { (it.height ?: 0) <= limit }.maxByOrNull { it.height ?: 0 }
                ?: videos.minByOrNull { it.height ?: 0 }
                ?: options.firstOrNull()
        }
    }
}
