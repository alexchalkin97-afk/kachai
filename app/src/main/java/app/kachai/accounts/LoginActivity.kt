package app.kachai.accounts

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.kachai.Diag
import app.kachai.Stats
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kachai.KachaiApp
import app.kachai.ui.theme.KachaiTheme

/**
 * Вход в VK или OK на официальной странице сайта. Пароль вводится на сайте —
 * приложение его не перехватывает, а после входа забирает только куки сессии.
 */
class LoginActivity : ComponentActivity() {

    private lateinit var site: Site
    private var loading by mutableStateOf(true)
    private var openedAfterLogin = false
    private var afterIndex = 0
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = Site.valueOf(intent.getStringExtra(EXTRA_SITE) ?: Site.VK.name)
        enableEdgeToEdge()
        val prefs = (application as KachaiApp).prefs

        setContent {
            val theme by prefs.themeMode.collectAsStateWithLifecycle(app.kachai.data.ThemeMode.SYSTEM)
            KachaiTheme(theme) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.statusBarsPadding()) {
                        TopAppBar(
                            title = { Text("Вход: ${site.title}") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) { Icon(Icons.Rounded.Close, "Закрыть") }
                            },
                            actions = { TextButton(onClick = ::complete) { Text("Готово") } },
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Это официальный сайт ${site.hosts.first()}. Пароль остаётся на сайте — приложение его не видит.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx -> createWebView(ctx) },
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(ctx: Context) = WebView(ctx).apply {
        // Без явного MATCH_PARENT WebView считает свою высоту «по содержимому», и в CSS 100vh = 0:
        // страница входа VK (она вся на vh) схлопывается, поле для кода уезжает за верх экрана
        layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )
        // В отладочной сборке страницу можно смотреть через chrome://inspect
        if (app.kachai.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        // Притворяемся обычным Chrome: по метке «wv» сайты узнают встроенный браузер
        // и пытаются отправить подтверждение входа в своё приложение — у нас оно не откроется
        settings.userAgentString = settings.userAgentString
            .replace("; wv)", ")")
            .replace(Regex("""Version/\d+\.\d+ """), "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        Diag.log(TAG, "open ${site.name}, ${settings.userAgentString.substringAfter("Chrome/").substringBefore(' ')}")
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Diag.log(TAG, "console: ${message.message().take(200)}")
                }
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "http" || url.scheme == "https") return false
                // Ссылки на приложения (vk://, intent://): открываем запасной адрес, если сайт его дал
                Diag.log(TAG, "app link blocked: ${url.scheme}://${url.host}")
                val fallback = url.getQueryParameter("browser_fallback_url")
                    ?: Regex("""S\.browser_fallback_url=([^;]+)""").find(url.toString())?.groupValues?.get(1)
                        ?.let { Uri.decode(it) }
                if (fallback != null) view.loadUrl(fallback)
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) Diag.log(TAG, "error ${error.errorCode} ${error.description} at ${Diag.short(request.url.toString())}")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) Diag.log(TAG, "http ${response.statusCode} at ${Diag.short(request.url.toString())}")
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loading = true
                Diag.log(TAG, "page ${Diag.short(url)}")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loading = false
                if (finished) return
                when {
                    // Дошли до очередного домена — сессия там появилась, идём дальше
                    openedAfterLogin && isAfterTarget(url) -> nextAfterLogin(view)
                    !openedAfterLogin && Accounts.looksLoggedIn(site, url) -> afterLogin(view)
                }
            }
        }
        loadUrl(site.loginUrl)
        // Лента VK после входа догружается бесконечно, и onPageFinished может не прийти —
        // поэтому проверяем сессию ещё и по таймеру
        lifecycleScope.launch {
            while (!finished && !openedAfterLogin) {
                delay(1500)
                if (Accounts.looksLoggedIn(site, url)) afterLogin(this@apply)
            }
        }
    }

    private fun afterLogin(view: WebView) {
        if (finished || openedAfterLogin) return
        openedAfterLogin = true
        nextAfterLogin(view)
    }

    /**
     * Заходим по очереди на страницы из Site.afterLoginUrls, чтобы сайт выдал нужную yt-dlp сессию
     * (для VK — как настольный браузер, см. Site.VK). Если страница долго грузится — через 12 секунд
     * идём дальше.
     */
    private fun nextAfterLogin(view: WebView) {
        if (finished) return
        val after = site.afterLoginUrls.getOrNull(afterIndex++) ?: return complete()
        if (site.desktopAfterLogin) view.settings.userAgentString = desktopUserAgent(view.settings.userAgentString)
        Diag.log(TAG, "logged in, opening ${Diag.short(after)}${if (site.desktopAfterLogin) " (desktop)" else ""}")
        view.loadUrl(after)
        val step = afterIndex
        lifecycleScope.launch {
            delay(12_000)
            if (afterIndex == step) nextAfterLogin(view)
        }
    }

    /** Та же версия Chrome, но настольная Windows — иначе VK уводит на мобильную m.vk.ru. */
    private fun desktopUserAgent(mobile: String): String {
        val chrome = Regex("""Chrome/[\d.]+""").find(mobile)?.value ?: "Chrome/141.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    /**
     * Дошли ли до самой страницы, а не до промежуточной: автовход VK идёт через login.vk.ru
     * и vk.ru/login.php, и сохранять сессию там ещё рано.
     */
    private fun isAfterTarget(url: String?): Boolean {
        val target = site.afterLoginUrls.getOrNull(afterIndex - 1)?.let(Uri::parse) ?: return false
        val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        return uri.host == target.host && !uri.path.orEmpty().startsWith("/login")
    }

    private fun complete() {
        if (finished) return
        if (Accounts.save(site)) {
            Stats.login(site.name)
            finished = true
            Toast.makeText(this, "${site.title}: аккаунт привязан", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_OK)
            finish()
        } else {
            Toast.makeText(this, "Сначала войдите в аккаунт на этой странице", Toast.LENGTH_SHORT).show()
        }
    }

    /** Запуск экрана входа; результат — привязан ли аккаунт. */
    class Contract : ActivityResultContract<Site, Boolean>() {
        override fun createIntent(context: Context, input: Site) =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_SITE, input.name)

        override fun parseResult(resultCode: Int, intent: Intent?) = resultCode == Activity.RESULT_OK
    }

    companion object {
        private const val TAG = "Login"
        private const val EXTRA_SITE = "site"
    }
}
