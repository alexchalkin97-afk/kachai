package app.kachai.billing

import app.kachai.BuildConfig
import app.kachai.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Подписка «Качай Плюс».
 *
 * Оплата идёт вне приложения (сайт с ЮKassa / CloudPayments и т.п.), пользователь получает код
 * и вводит его здесь. Сервер по [BuildConfig.API_BASE] проверяет код и возвращает срок действия.
 * Пока сервера нет, активация честно отвечает «скоро».
 */
class Premium(private val prefs: Prefs, scope: CoroutineScope) {

    /** Плюс бывает трёх видов: подписка RuStore, разовая покупка в RuStore и промокод. Действует самый долгий. */
    private fun untilOf(p: androidx.datastore.preferences.core.Preferences): Long =
        maxOf(p[Prefs.PREMIUM_UNTIL] ?: 0L, p[Prefs.PASS_UNTIL] ?: 0L, p[Prefs.PROMO_UNTIL] ?: 0L)

    val isPremium: StateFlow<Boolean> = prefs.data.map { p ->
        (p[Prefs.DEBUG_PREMIUM] ?: 0) == 1 || untilOf(p) > System.currentTimeMillis()
    }.stateIn(scope, SharingStarted.Eagerly, false)

    val premiumUntil: StateFlow<Long> = prefs.data.map { untilOf(it) }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    val canActivate: Boolean get() = BuildConfig.API_BASE.isNotBlank()

    @Serializable
    private data class ActivateRequest(val code: String, val deviceId: String)

    @Serializable
    private data class ActivateResponse(val ok: Boolean, val until: Long = 0, val token: String = "", val error: String = "")

    suspend fun activate(code: String, deviceId: String): Result<Long> = withContext(Dispatchers.IO) {
        if (isTesterCode(code)) {
            prefs.edit { it[Prefs.DEBUG_PREMIUM] = 1 }
            return@withContext Result.success(0L)
        }
        if (Promo.looksLikePromo(code)) return@withContext activatePromo(code)
        if (!canActivate) return@withContext Result.failure(Exception("Код не подошёл"))
        runCatching {
            val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
            val body = json.encodeToString(ActivateRequest(code.trim(), deviceId))
                .toRequestBody("application/json".toMediaType())
            val resp = client.newCall(
                Request.Builder().url(BuildConfig.API_BASE.trimEnd('/') + "/activate").post(body).build()
            ).execute().use { r -> json.decodeFromString<ActivateResponse>(r.body!!.string()) }
            if (!resp.ok) error(resp.error.ifBlank { "Код не подошёл" })
            prefs.edit {
                it[Prefs.PREMIUM_UNTIL] = resp.until
                it[Prefs.PREMIUM_TOKEN] = resp.token
            }
            resp.until
        }
    }

    /** Промокод из соцсетей: Плюс на N дней сверх уже имеющегося, один раз на телефоне. */
    private suspend fun activatePromo(code: String): Result<Long> {
        val promo = Promo.parse(code) ?: return Result.failure(Exception("Код не подошёл. Проверьте, что скопировали его целиком"))
        if (LocalDate.now().isAfter(promo.validUntil)) return Result.failure(Exception("Срок действия кода закончился"))
        var until = 0L
        var used = false
        prefs.edit { p ->
            val usedIds = p[Prefs.PROMO_USED].orEmpty()
            if (promo.id.toString() in usedIds) {
                used = true
                return@edit
            }
            val from = maxOf(System.currentTimeMillis(), p[Prefs.PROMO_UNTIL] ?: 0L)
            until = from + promo.days * 24L * 3600_000
            p[Prefs.PROMO_UNTIL] = until
            p[Prefs.PROMO_USED] = usedIds + promo.id.toString()
        }
        if (used) return Result.failure(Exception("Этот код уже активирован на этом телефоне"))
        app.kachai.Stats.promo(promo.id, promo.days)
        return Result.success(until)
    }

    /** Только для отладочной сборки: включить премиум без оплаты. */
    suspend fun setDebug(on: Boolean) = prefs.edit { it[Prefs.DEBUG_PREMIUM] = if (on) 1 else 0 }

    /** Код тестировщика сверяется по хэшу — самого кода в приложении нет. */
    private fun isTesterCode(code: String): Boolean {
        val expected = BuildConfig.TESTER_CODE_HASH
        if (expected.isEmpty()) return false
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(code.trim().uppercase().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return hash == expected
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

data class QuotaState(val used: Int, val limit: Int, val rewardsLeft: Int) {
    val left: Int get() = (limit - used).coerceAtLeast(0)
}

/**
 * Дневной лимит бесплатной версии. Попытка списывается при постановке в очередь
 * и возвращается, если загрузка не удалась. Реклама за вознаграждение даёт +[REWARD_BONUS].
 */
class Quota(private val prefs: Prefs, private val premium: Premium, scope: CoroutineScope) {

    val state: StateFlow<QuotaState> = prefs.data.map { p ->
        val today = today()
        if (p[Prefs.QUOTA_DAY] != today) {
            QuotaState(0, BuildConfig.FREE_DAILY_LIMIT, MAX_REWARDS)
        } else {
            QuotaState(
                used = p[Prefs.QUOTA_USED] ?: 0,
                limit = BuildConfig.FREE_DAILY_LIMIT + (p[Prefs.QUOTA_BONUS] ?: 0),
                rewardsLeft = MAX_REWARDS - (p[Prefs.QUOTA_REWARDS] ?: 0),
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, QuotaState(0, BuildConfig.FREE_DAILY_LIMIT, MAX_REWARDS))

    fun canDownload(): Boolean = premium.isPremium.value || state.value.left > 0

    suspend fun consume() {
        if (premium.isPremium.value) return
        prefs.edit { p ->
            rollDay(p)
            p[Prefs.QUOTA_USED] = (p[Prefs.QUOTA_USED] ?: 0) + 1
        }
    }

    suspend fun refund() {
        if (premium.isPremium.value) return
        prefs.edit { p ->
            rollDay(p)
            p[Prefs.QUOTA_USED] = ((p[Prefs.QUOTA_USED] ?: 0) - 1).coerceAtLeast(0)
        }
    }

    suspend fun addReward() = prefs.edit { p ->
        rollDay(p)
        p[Prefs.QUOTA_BONUS] = (p[Prefs.QUOTA_BONUS] ?: 0) + REWARD_BONUS
        p[Prefs.QUOTA_REWARDS] = (p[Prefs.QUOTA_REWARDS] ?: 0) + 1
    }

    private fun rollDay(p: androidx.datastore.preferences.core.MutablePreferences) {
        val today = today()
        if (p[Prefs.QUOTA_DAY] != today) {
            p[Prefs.QUOTA_DAY] = today
            p[Prefs.QUOTA_USED] = 0
            p[Prefs.QUOTA_BONUS] = 0
            p[Prefs.QUOTA_REWARDS] = 0
        }
    }

    private fun today() = LocalDate.now().toString()

    companion object {
        const val REWARD_BONUS = 2
        const val MAX_REWARDS = 5
    }
}
