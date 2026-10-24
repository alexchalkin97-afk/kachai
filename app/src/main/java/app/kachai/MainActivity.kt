package app.kachai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kachai.ui.AppRoot
import app.kachai.ui.MainViewModel
import app.kachai.ui.theme.KachaiTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme by vm.themeMode.collectAsStateWithLifecycle()
            KachaiTheme(theme) { AppRoot(vm) }
        }
        if (savedInstanceState == null) {
            (application as KachaiApp).rustore.handleIntent(intent)
            handle(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        (application as KachaiApp).rustore.handleIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when {
            intent.action == Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(vm::onShared)
            intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) -> vm.openDownloads.tryEmit(Unit)
        }
    }

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
    }
}
