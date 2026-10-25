package app.kachai.ui

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassifier
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
import app.kachai.dl.ClipSection
import app.kachai.dl.Downloads
import app.kachai.dl.Engine
import app.kachai.dl.Extras
import app.kachai.dl.Subtitle
import app.kachai.dl.EngineException
import app.kachai.dl.FormatOption
import app.kachai.dl.LinkResolver
import app.kachai.dl.MediaInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Lookup {
    data object Idle : Lookup
    data object Loading : Lookup
    data class Ready(val info: MediaInfo) : Lookup
    /** [loginSite] — видео откроется после входа в этот аккаунт: предлагаем привязать. */
    /** [reloginSite] — аккаунт привязан, но сайт его не пустил: предлагаем войти заново. */
    data class Failed(val message: String, val loginSite: Site? = null, val reloginSite: Site? = null) : Lookup
}

/** Подсказка «в буфере обмена ссылка»: [stamp] — время копирования, [host] — сайт, если текст прочитан. */
data class ClipboardHint(val stamp: Long, val host: String?)

/** Результат нажатия «Скачать». */
enum class StartResult { STARTED, STARTED_SHOW_AD, LIMIT_REACHED, NEEDS_PREMIUM }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val k = app as KachaiApp

    var input by mutableStateOf("")
    var lookup by mutableStateOf<Lookup>(Lookup.Idle)
        private set
    var selected by mutableStateOf<FormatOption?>(null)
    /** Качать только фрагмент; null — ролик целиком. */
    var section by mutableStateOf<ClipSection?>(null)
    /** Сохраняется обложка или субтитры — кнопки на время блокируем. */
    var savingExtra by mutableStateOf(false)
        private set
    /** В буфере обмена ссылка, которую ещё не предлагали; host — сайт, если его удалось прочитать. */
    var clipboardHint by mutableStateOf<ClipboardHint?>(null)
        private set

    /** Короткие сообщения внизу экрана. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val tasks = Downloads.tasks
    val history = Downloads.history
    val isPremium = k.premium.isPremium
    val premiumUntil = k.premium.premiumUntil
    val quota = k.quota.state
    val themeMode = k.prefs.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.DARK)
    val audioByDefault = k.prefs.audioByDefault.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val videoHeight = k.prefs.videoHeight.stateIn(viewModelScope, SharingStarted.Eagerly, 1080)
    val audioMp3 = k.prefs.audioMp3.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val linkedAccounts = Accounts.linked

    /** Открыть вкладку «Загрузки» (из уведомления). */
    val openDownloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Открыть главный экран: туда пришла ссылка через «Поделиться». */
    val openHome = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var lookupJob: Job? = null

    fun onShared(text: String) {
        val url = extractUrl(text) ?: return
        input = url
        openHome.tryEmit(Unit)
        find()
    }

    fun clear() {
        lookupJob?.cancel()
        input = ""
        lookup = Lookup.Idle
        selected = null
        section = null
    }

    /** Время метки последнего предложенного из буфера текста — чтобы не предлагать одно и то же. */
    private var offeredClipAt = -1L

    /**
     * Проверить буфер обмена, когда окно получило фокус (раньше Android его не отдаёт).
     * На Android 12+ чтение буфера показывает системное «Вставлено из…», поэтому там текст не читаем,
     * а смотрим, распознала ли система в нём ссылку. Сам текст читаем, только когда человек нажмёт «Найти».
     */
    fun checkClipboard(context: Context) {
        if (input.isNotBlank() || lookup !is Lookup.Idle) return
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val desc = cm.primaryClipDescription ?: return
        val stamp = desc.timestamp
        if (stamp == offeredClipAt || !desc.hasMimeType("text/*")) return
        clipboardHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val isUrl = desc.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE &&
                desc.getConfidenceScore(TextClassifier.TYPE_URL) >= 0.5f
            if (!isUrl) return
            ClipboardHint(stamp, host = null)
        } else {
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            val url = text?.let(::extractUrl) ?: return
            ClipboardHint(stamp, host = siteOf(url))
        }
    }

    fun useClipboard(context: Context) {
        val hint = clipboardHint ?: return
        offeredClipAt = hint.stamp
        clipboardHint = null
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        val url = text?.let(::extractUrl) ?: return
        input = url
        find()
    }

    fun dismissClipboard() {
        clipboardHint?.let { offeredClipAt = it.stamp }
        clipboardHint = null
    }

    /** Обложка текущего ролика — в «Загрузки», без лимита. */
    fun saveCover() = saveExtra { info -> Extras.saveCover(k, info); "Обложка сохранена в «Загрузки/Качай»" }

    fun saveSubtitles(sub: Subtitle) = saveExtra { info -> Extras.saveSubtitles(k, info, sub); "Субтитры (${sub.lang}) сохранены в «Загрузки/Качай»" }

    private fun saveExtra(block: suspend (MediaInfo) -> String) {
        val info = (lookup as? Lookup.Ready)?.info ?: return
        savingExtra = true
        viewModelScope.launch {
            val text = runCatching { block(info) }.getOrElse { "Не получилось: ${it.message}" }
            savingExtra = false
            messages.tryEmit(text)
        }
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
        section = null
        clipboardHint = null
        lookupJob = viewModelScope.launch {
            // Клипы OK — это клипы VK с теми же номерами (см. LinkResolver). Но клипы групп OK бывают
            // закрыты в VK — тогда при входе в OK берём клип из самого OK. Ссылку в поле не подменяем
            val okClip = LinkResolver.isOkClipUrl(raw)
            val url = Accounts.cookiesFor(raw)?.takeIf { okClip }?.let { LinkResolver.okClipAsVideo(raw, it) }
                ?: LinkResolver.resolve(raw)
            for (attempt in 1..2) {
                val started = System.currentTimeMillis()
                Diag.log("Lookup", "${Diag.short(url)} attempt=$attempt")
                try {
                    val info = Engine.fetchInfo(url, Accounts.cookiesFor(url))
                    Diag.log("Lookup", "ok in ${System.currentTimeMillis() - started}ms, ${info.options.size} options")
                    Stats.lookup(info.site, true, System.currentTimeMillis() - started)
                    lookup = Lookup.Ready(info)
                    selected = pickDefault(
                        info.options, audioByDefault.value, videoHeight.value, audioMp3.value, maxVideoHeight(),
                    )
                    return@launch
                } catch (e: EngineException) {
                    Diag.log("Lookup", "failed in ${System.currentTimeMillis() - started}ms: ${e.message}")
                    Stats.lookup(siteOf(url), false, System.currentTimeMillis() - started, e.message)
                    // Ссылка из OK — и вход предлагаем в OK, даже если искали копию в VK
                    if (e.needsLogin) { lookup = loginFailure(url, e, site = if (okClip) Site.OK else null); return@launch }
                    // Пробуем ещё раз: сайты вроде Дзена иногда отдают вместо страницы
                    // промежуточную (проверку, перенаправление), со второго раза проходит
                    if (!e.network || attempt == 2) { lookup = Lookup.Failed(e.message.orEmpty()); return@launch }
                    kotlinx.coroutines.delay(2000)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    lookup = Lookup.Failed("Не получилось открыть ссылку")
                    return@launch
                }
            }
        }
    }

    /** Сайт по адресу ссылки (без пути) — для статистики неудачных поисков. */
    private fun siteOf(url: String) = runCatching { java.net.URI(url).host?.removePrefix("www.")?.removePrefix("m.") }
        .getOrNull() ?: "?"

    private fun loginFailure(url: String, e: EngineException, site: Site? = null): Lookup.Failed {
        val site = site ?: Accounts.siteFor(url) ?: return Lookup.Failed(e.message.orEmpty())
        if (e.message == Engine.COMMUNITY_CLOSED) {
            return if (Accounts.isLinked(site)) {
                Lookup.Failed("${e.message}. С вашим аккаунтом ${site.title} доступа нет — вы не участник сообщества", reloginSite = site)
            } else {
                Lookup.Failed("${e.message}. Если вы участник — войдите во ${site.title}", loginSite = site)
            }
        }
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
        Downloads.enqueue(k, ready.info, option, section?.takeIf { it.lengthSec > 0 })
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

    /**
     * Повторить неудавшуюся загрузку. Неудача вернула попытку в лимит, поэтому повтор — новая загрузка:
     * проверяем лимит и снова списываем. false — лимит исчерпан.
     */
    fun retry(id: String): Boolean {
        if (!k.quota.canDownload()) {
            Stats.limitReached()
            return false
        }
        Downloads.retry(k, id)
        viewModelScope.launch { k.quota.consume() }
        return true
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

    suspend fun buyPlus(): Result<Boolean> = k.rustore.purchase().also { Stats.purchase(it.isSuccess) }

    fun purchaseError(e: Throwable): String = k.rustore.errorText(e)

    /** Сверить подписку с RuStore ещё раз: после оплаты подтверждение иногда приходит не сразу. */
    fun refreshPlus() = viewModelScope.launch { k.rustore.refresh() }

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
        private val invisibleRegex = Regex("""[\p{Cc}\p{Cf}  ]""")
        private val bareRegex = Regex("""(?:www\.|m\.)?[a-z0-9-]+\.[a-z]{2,}/\S+""", RegexOption.IGNORE_CASE)

        fun extractUrl(text: String): String? {
            // Невидимые символы из буфера (нулевой, нулевой ширины, метки направления) ломают запуск
            // yt-dlp («invalid null character») — заменяем их пробелом, он и так граница ссылки
            val t = text.replace(invisibleRegex, " ").trim()
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
