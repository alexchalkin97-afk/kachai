package app.kachai

import android.app.Application
import io.appmetrica.analytics.AppMetrica
import io.appmetrica.analytics.AppMetricaConfig

/**
 * Обезличенная статистика (AppMetrica): какие сайты и качество выбирают, как часто и почему
 * не получается, какая скорость. Ссылки, названия видео и данные аккаунтов сюда не попадают.
 */
object Stats {
    private var enabled = false

    fun init(app: Application) {
        val key = BuildConfig.APPMETRICA_KEY
        if (key.isBlank()) return
        runCatching {
            val config = AppMetricaConfig.newConfigBuilder(key)
                .withLocationTracking(false)
                .apply { if (BuildConfig.DEBUG) withLogs() }
                .build()
            AppMetrica.activate(app, config)
            AppMetrica.enableActivityAutoTracking(app)
            enabled = true
        }
    }

    /** Поиск видео. [where] — server (наш сервер), phone (yt-dlp на телефоне). */
    fun lookup(site: String, route: String, where: String, ok: Boolean, ms: Long, error: String? = null) = event(
        "lookup",
        "site" to site, "route" to route, "where" to where, "ok" to ok,
        "seconds" to bucket(ms / 1000.0), "error" to error,
    )

    fun download(site: String, quality: String, route: String, ok: Boolean, mb: Double = 0.0, mbps: Double = 0.0, error: String? = null) =
        event(
            "download",
            "site" to site, "quality" to quality, "route" to route, "ok" to ok,
            "size_mb" to bucket(mb), "speed_mbps" to "%.1f".format(mbps), "error" to error,
        )

    fun login(site: String) = event("account_linked", "site" to site)
    fun premiumOpened() = event("premium_opened")
    fun purchase(ok: Boolean) = event("premium_purchase", "ok" to ok)
    fun limitReached() = event("daily_limit_reached")

    /** Группы вместо точных чисел: в отчётах AppMetrica так удобнее. */
    private fun bucket(v: Double): String = when {
        v < 5 -> "<5"
        v < 10 -> "5-10"
        v < 20 -> "10-20"
        v < 40 -> "20-40"
        v < 80 -> "40-80"
        v < 160 -> "80-160"
        else -> "160+"
    }

    private fun event(name: String, vararg params: Pair<String, Any?>) {
        if (!enabled) return
        runCatching { AppMetrica.reportEvent(name, params.filter { it.second != null }.toMap()) }
    }
}
