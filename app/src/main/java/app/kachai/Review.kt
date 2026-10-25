package app.kachai

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.kachai.data.Prefs
import kotlinx.coroutines.flow.first
import ru.rustore.sdk.review.RuStoreReviewManagerFactory

/**
 * Просьба оценить приложение — окно RuStore прямо в приложении (оценка влияет на место в поиске RuStore).
 * Просим один раз, когда человек уже успешно скачал [ASK_AFTER] файла: тогда он знает, что приложение работает.
 * Сколько раз окно реально покажется, решает RuStore; без RuStore на телефоне ничего не происходит.
 */
object Review {
    private const val ASK_AFTER = 3

    suspend fun downloadDone(prefs: Prefs) = prefs.edit { it[Prefs.DOWNLOADS_DONE] = (it[Prefs.DOWNLOADS_DONE] ?: 0) + 1 }

    suspend fun maybeAsk(activity: Activity, prefs: Prefs) {
        val p = prefs.data.first()
        if ((p[Prefs.REVIEW_ASKED] ?: 0) == 1 || (p[Prefs.DOWNLOADS_DONE] ?: 0) < ASK_AFTER) return
        prefs.edit { it[Prefs.REVIEW_ASKED] = 1 }
        runCatching {
            val manager = RuStoreReviewManagerFactory.create(activity)
            manager.requestReviewFlow()
                .addOnSuccessListener { info ->
                    manager.launchReviewFlow(info)
                        .addOnFailureListener { Diag.log("Review", "launch failed: ${it.javaClass.simpleName}") }
                }
                .addOnFailureListener { Diag.log("Review", "request failed: ${it.javaClass.simpleName}") }
        }.onFailure { Diag.log("Review", "unavailable: ${it.javaClass.simpleName}") }
    }
}

/** Страница приложения в RuStore — «Оценить» в настройках; без приложения RuStore — в браузере. */
fun openStorePage(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("rustore://apps.rustore.ru/app/${context.packageName}")))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.rustore.ru/catalog/app/${context.packageName}")))
    }
}
