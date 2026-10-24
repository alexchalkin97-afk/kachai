package app.kachai.net

import android.content.Context
import android.util.Log
import app.kachai.BuildConfig
import app.kachai.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Параметры VLESS + Reality для подключения к нашему VPS. */
@Serializable
data class VpsServer(
    val host: String,
    val port: Int = 443,
    val uuid: String,
    val sni: String,
    val publicKey: String,
    val shortId: String = "",
    /** Под какой браузер маскируется TLS-приветствие. Reality принимает только свежие — «chrome». */
    val fingerprint: String = BuildConfig.VPS_FINGERPRINT,
    /**
     * Дробить TLS-приветствие на мелкие TCP-пакеты. Приветствие «chrome» с постквантовым ключом
     * не влезает в один пакет, и ТСПУ выбрасывают его хвост; мелкие куски они не собирают.
     */
    val fragment: Boolean = true,
    val fragmentLength: String = "100-200",
    val fragmentInterval: String = "10-20",
)

@Serializable
private data class RemoteConfig(val servers: List<VpsServer> = emptyList())

/**
 * Откуда берём адрес сервера: сначала свежий список с REMOTE_CONFIG_URL
 * (чтобы сменить заблокированный IP без обновления приложения), иначе — вшитый при сборке.
 */
object VpsConfig {
    private const val TAG = "VpsConfig"
    private const val CACHE_FILE = "servers.json"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var servers: List<VpsServer> = emptyList()

    fun current(): VpsServer? = servers.firstOrNull()

    fun isAvailable(): Boolean = current() != null

    fun load(context: Context) {
        val cached = File(context.filesDir, CACHE_FILE)
            .takeIf { it.exists() }
            ?.let { runCatching { json.decodeFromString<RemoteConfig>(it.readText()).servers }.getOrNull() }
        servers = cached?.takeIf { it.isNotEmpty() } ?: listOfNotNull(embedded())
    }

    /** Обновить список серверов с удалённого адреса. Ошибки не страшны — остаётся прежний. */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val url = BuildConfig.REMOTE_CONFIG_URL
        if (url.isBlank()) return@withContext
        runCatching {
            val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
            val body = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                check(r.isSuccessful) { "HTTP ${r.code}" }
                r.body!!.string()
            }
            val parsed = json.decodeFromString<RemoteConfig>(body).servers
            if (parsed.isNotEmpty()) {
                servers = parsed
                File(context.filesDir, CACHE_FILE).writeText(body)
                Diag.log(TAG, "remote config: ${parsed.size} server(s), fp=${parsed[0].fingerprint}, fragment=${parsed[0].fragment}")
            }
        }.onFailure { Diag.log(TAG, "remote config failed: ${it.message}") }
    }

    private fun embedded(): VpsServer? {
        if (BuildConfig.VPS_HOST.isBlank() || BuildConfig.VPS_UUID.isBlank()) return null
        return VpsServer(
            host = BuildConfig.VPS_HOST,
            port = BuildConfig.VPS_PORT,
            uuid = BuildConfig.VPS_UUID,
            sni = BuildConfig.VPS_SNI,
            publicKey = BuildConfig.VPS_PUBLIC_KEY,
            shortId = BuildConfig.VPS_SHORT_ID,
        )
    }
}
