package app.kachai.billing

import android.content.Intent
import app.kachai.BuildConfig
import app.kachai.Diag
import app.kachai.data.Prefs
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.rustore.sdk.core.exception.RuStoreApplicationBannedException
import ru.rustore.sdk.core.exception.RuStoreNotInstalledException
import ru.rustore.sdk.core.exception.RuStoreOutdatedException
import ru.rustore.sdk.core.exception.RuStoreUserBannedException
import ru.rustore.sdk.core.exception.RuStoreUserUnauthorizedException
import ru.rustore.sdk.core.tasks.Task
import ru.rustore.sdk.pay.RuStorePayClient
import ru.rustore.sdk.pay.model.MainPeriod
import ru.rustore.sdk.pay.model.PreferredPurchaseType
import ru.rustore.sdk.pay.model.ProductId
import ru.rustore.sdk.pay.model.ProductPurchase
import ru.rustore.sdk.pay.model.ProductPurchaseParams
import ru.rustore.sdk.pay.model.ProductPurchaseStatus
import ru.rustore.sdk.pay.model.ProductType
import ru.rustore.sdk.pay.model.SdkTheme
import ru.rustore.sdk.pay.model.SubscriptionPurchase
import ru.rustore.sdk.pay.model.SubscriptionPurchaseStatus
import ru.rustore.sdk.pay.model.TrialPeriod
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Тариф Плюса. [days] — срок разовой покупки (без автопродления); 0 — подписка. */
enum class PlanKind(val days: Int) { MONTH(0), YEAR(0), PASS_MONTH(30), PASS_YEAR(365) }

/**
 * Тариф, как его отдал RuStore: [price] — подпись цены из Консоли («99 ₽»), [trialDays] — пробный период
 * подписки (настраивается в Консоли), 0 — без него.
 */
data class Plan(val id: String, val kind: PlanKind, val price: String, val trialDays: Int)

/**
 * «Качай Плюс» через RuStore Pay SDK: подписка на месяц, на год и разовый Плюс на месяц или год.
 * Какие тарифы есть, задаётся техническими ID товаров из Консоли в secrets.properties; пустой ID — тарифа нет.
 * Подписка хранится в Prefs.PREMIUM_UNTIL, разовые покупки — в Prefs.PASS_UNTIL; сверяем при каждом запуске.
 */
class RuStoreBilling(private val prefs: Prefs) {
    private val tag = "RuStore"

    /** Оплата через RuStore включена, если в сборке указан ID приложения из Консоли. */
    val enabled: Boolean get() = BuildConfig.RUSTORE_APP_ID.isNotBlank()

    private val client get() = RuStorePayClient.instance

    /** Восстановить экран оплаты после возврата из банковского приложения. */
    fun handleIntent(intent: Intent?) {
        if (enabled) runCatching { client.getIntentInteractor().proceedIntent(intent, SdkTheme.LIGHT) }
    }

    /** Технические ID товаров из Консоли по тарифам; тарифы с пустым ID не показываем. */
    private val ids: Map<PlanKind, String> = mapOf(
        PlanKind.MONTH to BuildConfig.RUSTORE_SUBSCRIPTION_ID,
        PlanKind.YEAR to BuildConfig.RUSTORE_YEARLY_ID,
        PlanKind.PASS_MONTH to BuildConfig.RUSTORE_PASS_MONTH_ID,
        PlanKind.PASS_YEAR to BuildConfig.RUSTORE_PASS_YEAR_ID,
    ).filterValues { it.isNotBlank() }

    private fun kindOf(productId: String) = ids.entries.firstOrNull { it.value == productId }?.key

    /**
     * Тарифы с ценами из RuStore, в порядке [PlanKind]. Товар, которого нет в Консоли (или он не опубликован),
     * RuStore не вернёт — его и не показываем. Пустой список — RuStore недоступен: экран покажет одну подписку.
     */
    suspend fun plans(): List<Plan> {
        if (!enabled) return emptyList()
        return runCatching {
            client.getProductInteractor().getProducts(ids.values.map { ProductId(it) }).awaitResult()
                .mapNotNull { p ->
                    val kind = kindOf(p.productId.value) ?: return@mapNotNull null
                    val periods = p.subscriptionInfo?.periods.orEmpty()
                    Diag.log(tag, "product ${p.productId.value}: ${p.amountLabel?.value} / ${p.price?.value} / $periods")
                    // Цена в копейках; подпись RuStore («99.00 руб.») — только если цены нет
                    val price = (periods.filterIsInstance<MainPeriod>().firstOrNull()?.price ?: p.price?.value)
                        ?.takeIf { it > 0 }?.let(::rubles)
                        ?: p.amountLabel?.value?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val trial = periods.filterIsInstance<TrialPeriod>().firstOrNull()?.let { days(it.duration) } ?: 0
                    Plan(p.productId.value, kind, price, trial)
                }
                .sortedBy { it.kind.ordinal }
                .also { Diag.log(tag, "plans: ${it.joinToString { p -> "${p.kind} ${p.price}" }}") }
        }.onFailure { Diag.log(tag, "products failed: ${it.message}") }.getOrDefault(emptyList())
    }

    /** 9900 копеек → «99 ₽», 14950 → «149,50 ₽». */
    private fun rubles(kopecks: Int): String =
        if (kopecks % 100 == 0) "${kopecks / 100} ₽" else "%d,%02d ₽".format(kopecks / 100, kopecks % 100)

    /** Срок в формате ISO 8601 («P7D», «P1W», «P1M») — в днях. */
    private fun days(iso: String): Int = runCatching { java.time.Period.parse(iso) }.getOrNull()
        ?.let { it.days + it.months * 30 + it.years * 365 } ?: 0

    /** Сверить покупки с RuStore. Возвращает true, если Плюс оплачен (подпиской или разово). */
    suspend fun refresh(): Boolean {
        if (!enabled) return false
        val sub = runCatching {
            val active = client.getPurchaseInteractor()
                .getPurchases(ProductType.SUBSCRIPTION, SubscriptionPurchaseStatus.ACTIVE)
                .awaitResult()
                .filterIsInstance<SubscriptionPurchase>()
                .firstOrNull { kindOf(it.productId.value)?.days == 0 }
            if (active != null) {
                // Если RuStore не отдал дату окончания — даём месяц, следующая сверка уточнит
                val until = active.expirationDate?.time ?: (System.currentTimeMillis() + 31L * 24 * 3600_000)
                prefs.edit { it[Prefs.PREMIUM_UNTIL] = until }
            }
            Diag.log(tag, "subscription ${if (active != null) "active" else "not found"}")
            active != null
        }.onFailure { Diag.log(tag, "refresh failed: ${it.message}") }.getOrDefault(false)
        val pass = if (ids.keys.any { it.days > 0 }) refreshPasses() else false
        return sub || pass
    }

    /**
     * Разовый Плюс: срок считаем от дат покупок, а не храним только у себя — так он переживает
     * переустановку и переезд на другой телефон с тем же аккаунтом RuStore. Купленные подряд сроки складываются.
     */
    private suspend fun refreshPasses(): Boolean = runCatching {
        val bought = client.getPurchaseInteractor().getPurchases(ProductType.CONSUMABLE_PRODUCT).awaitResult()
            .filterIsInstance<ProductPurchase>()
            .filter { it.status == ProductPurchaseStatus.CONFIRMED || it.status == ProductPurchaseStatus.PAID }
            .mapNotNull { p ->
                val days = kindOf(p.productId.value)?.days?.takeIf { it > 0 } ?: return@mapNotNull null
                val time = p.purchaseTime?.time ?: return@mapNotNull null
                time to days
            }
            .sortedBy { it.first }
        var until = 0L
        for ((time, days) in bought) until = maxOf(until, time) + days * 24L * 3600_000
        if (until > 0) prefs.edit { it[Prefs.PASS_UNTIL] = until }
        Diag.log(tag, "passes: ${bought.size}")
        until > System.currentTimeMillis()
    }.onFailure { Diag.log(tag, "passes failed: ${it.message}") }.getOrDefault(false)

    /**
     * Купить тариф (по умолчанию — месячную подписку). Открывает платёжный экран RuStore; после оплаты
     * сверяет статус. true — Плюс уже активен; false — оплата прошла, но RuStore ещё не подтвердил её.
     */
    suspend fun purchase(productId: String = BuildConfig.RUSTORE_SUBSCRIPTION_ID): Result<Boolean> {
        if (!enabled) return Result.failure(Exception("Оплата появится в ближайшем обновлении"))
        return runCatching {
            client.getPurchaseInteractor().purchase(
                ProductPurchaseParams(productId = ProductId(productId)),
                PreferredPurchaseType.ONE_STEP,
                SdkTheme.LIGHT,
            ).awaitResult()
            Diag.log(tag, "purchase ok: $productId")
            refresh()
        }.onFailure { Diag.log(tag, "purchase failed: ${it.message}") }
    }

    /** Что сказать пользователю, если оплата не прошла. */
    fun errorText(e: Throwable): String = when (e) {
        is RuStoreNotInstalledException -> "Для оплаты нужно приложение RuStore — установите его"
        is RuStoreOutdatedException -> "Обновите приложение RuStore и повторите"
        is RuStoreUserUnauthorizedException -> "Войдите в аккаунт RuStore и повторите"
        is RuStoreUserBannedException, is RuStoreApplicationBannedException -> "RuStore не разрешает оплату с этого аккаунта"
        else -> "Оплата не завершена"
    }

    /**
     * Ждём без блокировки потока. Не называть await(): у Task есть свой await(), он блокирует поток,
     * а метод класса в Kotlin важнее расширения — на главном потоке это зависание (ANR).
     */
    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }
}
