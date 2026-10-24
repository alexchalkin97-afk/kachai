package app.kachai.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Каким путём пошла конкретная загрузка. */
enum class Route { DIRECT, VPS }

/**
 * Выбор маршрута — автоматически, без настроек:
 * российский сайт — всегда напрямую; зарубежный — через VPN пользователя, если он включён,
 * иначе через наш VPS. Если первый путь не сработал из-за сети, пробуем второй.
 */
object Router {

    fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /**
     * Российские сайты не заблокированы, а зарубежному серверу могут и не отдать видео
     * (да и белый список VPS их не пропустит) — их всегда качаем напрямую.
     */
    private val domesticHosts = listOf(
        "vk.com", "vk.ru", "vkvideo.ru", "vk.link", "ok.ru", "odnoklassniki.ru",
        "rutube.ru", "dzen.ru", "yandex.ru", "mail.ru", "rambler.ru", "ivi.ru", "1tv.ru",
    )

    fun isDomestic(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        return domesticHosts.any { host == it || host.endsWith(".$it") }
    }

    fun primary(context: Context, url: String): Route = when {
        isDomestic(url) -> Route.DIRECT
        isVpnActive(context) || !VpsConfig.isAvailable() -> Route.DIRECT
        else -> Route.VPS
    }

    fun fallback(failed: Route, url: String): Route? {
        if (isDomestic(url)) return null
        return when (failed) {
            Route.DIRECT -> if (VpsConfig.isAvailable()) Route.VPS else null
            Route.VPS -> Route.DIRECT
        }
    }

    /** Поток «включён ли VPN» — для подписи на главном экране. */
    fun vpnFlow(context: Context): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(isVpnActive(context)) }
            override fun onLost(network: Network) { trySend(isVpnActive(context)) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(isVpnActive(context))
            }
        }
        trySend(isVpnActive(context))
        cm.registerDefaultNetworkCallback(cb)
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.distinctUntilChanged()
}
