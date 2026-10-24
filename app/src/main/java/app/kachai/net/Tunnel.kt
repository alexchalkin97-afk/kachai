package app.kachai.net

import android.content.Context
import android.util.Log
import app.kachai.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.UUID

class TunnelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Туннель до нашего VPS на ядре Xray (VLESS + Reality).
 *
 * Это не системный VPN: ядро поднимает локальные SOCKS и HTTP прокси на 127.0.0.1,
 * и через них ходит только сам загрузчик. Остальной телефон туннель не трогает.
 * Прокси закрыты логином и паролем, которые меняются при каждом запуске.
 */
object Tunnel {
    private const val TAG = "Tunnel"
    private const val IDLE_STOP_MS = 60_000L
    private const val PROBE_URL = "https://www.youtube.com/generate_204"

    data class Endpoint(
        val socksPort: Int,
        val httpPort: Int,
        val user: String,
        val pass: String,
    ) {
        /** Для yt-dlp: socks5h — DNS тоже через туннель, местный DNS могут подменять. */
        val socksUrl get() = "socks5h://$user:$pass@127.0.0.1:$socksPort"
        /** Для загрузки: aria2c понимает только HTTP-прокси (адрес сайта резолвится уже на сервере). */
        val httpUrl get() = "http://$user:$pass@127.0.0.1:$httpPort"
        val httpProxy get() = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpPort))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var controller: CoreController? = null
    private var envReady = false
    private var users = 0
    private var idleJob: Job? = null

    @Volatile
    var endpoint: Endpoint? = null
        private set

    /** Поднять туннель (если ещё не поднят) и отметить, что он нужен. Парный вызов — [release]. */
    suspend fun acquire(context: Context): Endpoint = mutex.withLock {
        idleJob?.cancel()
        users++
        endpoint?.let { return it }
        try {
            start(context.applicationContext)
        } catch (e: Exception) {
            users--
            throw e
        }
    }

    fun release() {
        scope.launch {
            mutex.withLock {
                users = (users - 1).coerceAtLeast(0)
                if (users == 0) scheduleStop()
            }
        }
    }

    private fun scheduleStop() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_STOP_MS)
            mutex.withLock { if (users == 0) stop() }
        }
    }

    private suspend fun start(context: Context): Endpoint = withContext(Dispatchers.IO) {
        val server = VpsConfig.current() ?: throw TunnelException("Сервер не настроен")
        if (!envReady) {
            Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
            envReady = true
        }
        val ep = Endpoint(
            socksPort = freePort(),
            httpPort = freePort(),
            user = UUID.randomUUID().toString().take(8),
            pass = UUID.randomUUID().toString(),
        )
        val core = Libv2ray.newCoreController(Callback)
        try {
            core.startLoop(buildConfig(server, ep), 0)
        } catch (e: Exception) {
            Diag.log(TAG, "start failed: ${e.message}")
            throw TunnelException("Не удалось запустить туннель", e)
        }
        // Проверяем, что сервер реально отвечает, а не только что ядро стартовало
        val started = System.currentTimeMillis()
        val probe = runCatching { core.measureDelay(PROBE_URL) }
        val delayMs = probe.getOrElse { -1L }
        if (delayMs < 0) {
            Diag.log(TAG, "probe failed after ${System.currentTimeMillis() - started}ms (fp=${server.fingerprint}): ${probe.exceptionOrNull()?.message}")
            runCatching { core.stopLoop() }
            throw TunnelException("Сервер не отвечает")
        }
        Diag.log(TAG, "up, ${delayMs}ms to youtube (fp=${server.fingerprint})")
        controller = core
        endpoint = ep
        ep
    }

    private fun stop() {
        runCatching { controller?.stopLoop() }
        controller = null
        endpoint = null
        Log.i(TAG, "down")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun buildConfig(s: VpsServer, ep: Endpoint): String = buildJsonObject {
        putJsonObject("log") { put("loglevel", "warning") }
        putJsonArray("inbounds") {
            addJsonObject {
                put("tag", "socks-in")
                put("listen", "127.0.0.1")
                put("port", ep.socksPort)
                put("protocol", "socks")
                putJsonObject("settings") {
                    put("auth", "password")
                    put("udp", false)
                    putJsonArray("accounts") {
                        addJsonObject { put("user", ep.user); put("pass", ep.pass) }
                    }
                }
            }
            addJsonObject {
                put("tag", "http-in")
                put("listen", "127.0.0.1")
                put("port", ep.httpPort)
                put("protocol", "http")
                putJsonObject("settings") {
                    putJsonArray("accounts") {
                        addJsonObject { put("user", ep.user); put("pass", ep.pass) }
                    }
                }
            }
        }
        putJsonArray("outbounds") {
            addJsonObject {
                put("tag", "proxy")
                put("protocol", "vless")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        addJsonObject {
                            put("address", s.host)
                            put("port", s.port)
                            putJsonArray("users") {
                                addJsonObject {
                                    put("id", s.uuid)
                                    put("encryption", "none")
                                    put("flow", "xtls-rprx-vision")
                                }
                            }
                        }
                    }
                }
                putJsonObject("streamSettings") {
                    put("network", "tcp")
                    put("security", "reality")
                    putJsonObject("realitySettings") {
                        put("serverName", s.sni)
                        put("fingerprint", s.fingerprint)
                        put("publicKey", s.publicKey)
                        put("shortId", s.shortId)
                        put("spiderX", "/")
                    }
                    if (s.fragment) {
                        // Соединение к серверу идёт через «frag»: он режет TLS-приветствие на куски
                        putJsonObject("sockopt") { put("dialerProxy", "frag") }
                    }
                }
            }
            if (s.fragment) {
                addJsonObject {
                    put("tag", "frag")
                    put("protocol", "freedom")
                    putJsonObject("settings") {
                        putJsonObject("fragment") {
                            put("packets", "tlshello")
                            put("length", s.fragmentLength)
                            put("interval", s.fragmentInterval)
                        }
                    }
                }
            }
        }
    }.toString()

    private object Callback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(p0: Long, p1: String?): Long {
            Log.d(TAG, p1.orEmpty())
            return 0
        }
    }
}
