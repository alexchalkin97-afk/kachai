package app.kachai

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал для отчёта об ошибках: поиск, загрузки, ошибки yt-dlp, шаги входа в VK/OK.
 * Хранится только на телефоне; уходит разработчику письмом на SUPPORT_EMAIL, только когда пользователь
 * сам нажмёт «Отправить отчёт» и затем «Отправить» в почте. Пароли и куки сюда не пишем.
 */
object Diag {
    private const val MAX_BYTES = 300_000L
    private lateinit var file: File
    private val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "diag.log")
    }

    @Synchronized
    fun log(tag: String, message: String) {
        Log.i(tag, message)
        if (!::file.isInitialized) return
        runCatching {
            if (file.length() > MAX_BYTES) file.writeText(file.readText().takeLast((MAX_BYTES / 2).toInt()))
            file.appendText("${time.format(Date())} [$tag] $message\n")
        }
    }

    /** Ссылку в журнал пишем без параметров — в них бывают токены. */
    fun short(url: String?): String = url?.substringBefore('?')?.take(120).orEmpty()

    fun share(context: Context) {
        val body = buildString {
            appendLine("Качай ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("WebView: ${webViewVersion(context)}")
            appendLine("---")
            if (::file.isInitialized && file.exists()) append(file.readText().takeLast(60_000))
        }
        val subject = "Качай ${BuildConfig.VERSION_NAME}: отчёт об ошибке"
        // Письмо разработчику: SENDTO с mailto открывают только почтовые приложения. Тема — ещё и в самой
        // ссылке (её понимают все клиенты), журнал — в EXTRA_TEXT: в ссылку он не влезет
        val email = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("mailto:${BuildConfig.SUPPORT_EMAIL}?subject=${Uri.encode(subject)}"),
        )
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.SUPPORT_EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(email)
        } catch (e: ActivityNotFoundException) {
            // Почты на телефоне нет — отдаём текст любому приложению, адрес пишем в начале
            val any = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, "Для: ${BuildConfig.SUPPORT_EMAIL}\n\n$body")
            val chooser = Intent.createChooser(any, "Отправить отчёт")
                // Сам «Качай» тоже принимает текст — в списке он не нужен
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    }

    private fun webViewVersion(context: Context): String = runCatching {
        val pkg = android.webkit.WebView.getCurrentWebViewPackage() ?: return "?"
        "${pkg.packageName} ${pkg.versionName}"
    }.getOrDefault("?")
}
