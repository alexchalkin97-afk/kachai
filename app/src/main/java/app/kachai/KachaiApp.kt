package app.kachai

import android.app.Application
import android.util.Log
import app.kachai.accounts.Accounts
import app.kachai.ads.Ads
import app.kachai.billing.Premium
import app.kachai.billing.Quota
import app.kachai.billing.RuStoreBilling
import app.kachai.data.Prefs
import app.kachai.dl.Downloads
import app.kachai.dl.Engine
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class KachaiApp : Application(), SingletonImageLoader.Factory {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var prefs: Prefs
        private set
    lateinit var premium: Premium
        private set
    lateinit var quota: Quota
        private set
    lateinit var rustore: RuStoreBilling
        private set

    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        Stats.init(this)
        // Очередь живёт только в памяти: недокачанное после перезапуска процесса уже не продолжится
        java.io.File(cacheDir, "dl").deleteRecursively()
        prefs = Prefs(this)
        premium = Premium(prefs, scope)
        quota = Quota(prefs, premium, scope)
        rustore = RuStoreBilling(prefs)
        // Подписку сверяем с RuStore при каждом запуске: продление, отмена, покупка на другом телефоне
        scope.launch(Dispatchers.Main) { rustore.refresh() }
        Downloads.load(this)
        Accounts.init(this)
        Downloads.onRefund = { scope.launch { quota.refund() } }
        Ads.init(this)

        scope.launch(Dispatchers.IO) {
            Engine.init(this@KachaiApp)
            updateEngineIfDue()
        }
    }

    suspend fun deviceId(): String {
        prefs.data.first()[Prefs.DEVICE_ID]?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit { it[Prefs.DEVICE_ID] = id }
        return id
    }

    /** yt-dlp ломается, когда YouTube что-то меняет, — проверяем обновление раз в сутки. */
    private suspend fun updateEngineIfDue() {
        val last = prefs.data.first()[Prefs.ENGINE_CHECKED_AT] ?: 0L
        if (System.currentTimeMillis() - last < 24 * 3600_000L) return
        runCatching { Engine.update(this) }
            .onSuccess { prefs.edit { p -> p[Prefs.ENGINE_CHECKED_AT] = System.currentTimeMillis() } }
            .onFailure { Log.w("KachaiApp", "engine update: ${it.message}") }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .build()
}
