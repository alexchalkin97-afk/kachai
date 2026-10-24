package app.kachai

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.kachai.net.Router
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал для отчёта об ошибках: маршрут, туннель, ошибки yt-dlp, шаги входа в VK/OK.
 * Хранится только на телефоне; уходит разработчику, только когда пользователь сам нажмёт
 * «Отправить отчёт». Пароли и куки сюда не пишем.
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
            appendLine("VPN: ${if (Router.isVpnActive(context)) "включён" else "выключен"}")
            appendLine("WebView: ${webViewVersion(context)}")
            appendLine("---")
            if (::file.isInitialized && file.exists()) append(file.readText().takeLast(60_000))
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Качай: отчёт об ошибке")
            .putExtra(Intent.EXTRA_TEXT, body)
        context.startActivity(Intent.createChooser(send, "Отправить отчёт").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun webViewVersion(context: Context): String = runCatching {
        val pkg = android.webkit.WebView.getCurrentWebViewPackage() ?: return "?"
        "${pkg.packageName} ${pkg.versionName}"
    }.getOrDefault("?")
}
