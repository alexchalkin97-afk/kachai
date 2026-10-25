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
import ru.rustore.sdk.pay.model.PreferredPurchaseType
import ru.rustore.sdk.pay.model.ProductId
import ru.rustore.sdk.pay.model.ProductPurchaseParams
import ru.rustore.sdk.pay.model.ProductType
import ru.rustore.sdk.pay.model.SdkTheme
import ru.rustore.sdk.pay.model.SubscriptionPurchase
import ru.rustore.sdk.pay.model.SubscriptionPurchaseStatus
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Подписка «Качай Плюс» через RuStore Pay SDK.
 * Статус храним в Prefs.PREMIUM_UNTIL (дата окончания оплаченного периода) и сверяем при каждом запуске.
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

    /** Сверить подписку с RuStore. Возвращает true, если она активна. */
    suspend fun refresh(): Boolean {
        if (!enabled) return false
        return runCatching {
            val active = client.getPurchaseInteractor()
                .getPurchases(ProductType.SUBSCRIPTION, SubscriptionPurchaseStatus.ACTIVE)
                .awaitResult()
                .filterIsInstance<SubscriptionPurchase>()
                .firstOrNull { it.productId.value == BuildConfig.RUSTORE_SUBSCRIPTION_ID }
            if (active != null) {
                // Если RuStore не отдал дату окончания — даём месяц, следующая сверка уточнит
                val until = active.expirationDate?.time ?: (System.currentTimeMillis() + 31L * 24 * 3600_000)
                prefs.edit { it[Prefs.PREMIUM_UNTIL] = until }
            }
            Diag.log(tag, "subscription ${if (active != null) "active" else "not found"}")
            active != null
        }.onFailure { Diag.log(tag, "refresh failed: ${it.message}") }.getOrDefault(false)
    }

    /**
     * Оформить подписку. Открывает платёжный экран RuStore; после оплаты сверяет статус.
     * true — подписка уже активна; false — оплата прошла, но RuStore ещё не подтвердил её.
     */
    suspend fun purchase(): Result<Boolean> {
        if (!enabled) return Result.failure(Exception("Оплата появится в ближайшем обновлении"))
        return runCatching {
            client.getPurchaseInteractor().purchase(
                ProductPurchaseParams(productId = ProductId(BuildConfig.RUSTORE_SUBSCRIPTION_ID)),
                PreferredPurchaseType.ONE_STEP,
                SdkTheme.LIGHT,
            ).awaitResult()
            Diag.log(tag, "purchase ok")
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
