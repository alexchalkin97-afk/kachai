package app.kachai.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.kachai.accounts.LoginActivity
import app.kachai.ads.AdBanner
import app.kachai.ads.Ads
import app.kachai.dl.TaskState
import kotlinx.coroutines.launch

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Скачать", Icons.Rounded.Download),
    DOWNLOADS("downloads", "Загрузки", Icons.Rounded.FolderOpen),
    SETTINGS("settings", "Настройки", Icons.Rounded.Settings),
}

private const val PREMIUM = "premium"

@Composable
fun AppRoot(vm: MainViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val premium by vm.isPremium.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as Activity

    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun go(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    LaunchedEffect(premium) {
        if (!premium) {
            Ads.preloadInterstitial(context)
            Ads.preloadRewarded(context)
        }
    }
    LaunchedEffect(Unit) { vm.openDownloads.collect { go(Tab.DOWNLOADS.route) } }

    fun start() {
        when (vm.startDownload()) {
            StartResult.LIMIT_REACHED -> message("Лимит на сегодня исчерпан")
            StartResult.NEEDS_PREMIUM -> nav.navigate(PREMIUM)
            StartResult.STARTED_SHOW_AD -> { Ads.showInterstitial(activity); message("Загрузка началась") }
            StartResult.STARTED -> message("Загрузка началась")
        }
    }

    // Вход в VK/OK: после успешной привязки повторяем поиск, если он упирался во вход
    val login = rememberLauncherForActivityResult(LoginActivity.Contract()) { ok ->
        val failed = vm.lookup as? Lookup.Failed
        if (ok && (failed?.loginSite ?: failed?.reloginSite) != null) vm.find()
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val storageDenied = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            granted[Manifest.permission.WRITE_EXTERNAL_STORAGE] == false
        if (storageDenied) message("Без доступа к памяти некуда сохранить файл") else start()
    }

    fun onDownload() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) start() else permissions.launch(needed.toTypedArray())
    }

    val showTabs = route != PREMIUM
    // Бесплатная версия: баннер внизу главного экрана и загрузок. Плюс: только в настройках.
    val showBanner = !premium && (route == Tab.HOME.route || route == Tab.DOWNLOADS.route)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showTabs) Column {
                if (showBanner) AdBanner()
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { go(tab.route) },
                            icon = {
                                val active = tasks.count { it.state == TaskState.RUNNING || it.state == TaskState.QUEUED || it.state == TaskState.PROCESSING }
                                if (tab == Tab.DOWNLOADS && active > 0) {
                                    BadgedBox(badge = { Badge { Text("$active") } }) { Icon(tab.icon, null) }
                                } else {
                                    Icon(tab.icon, null)
                                }
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.HOME.route, modifier = Modifier.padding(padding)) {
            composable(Tab.HOME.route) {
                HomeScreen(
                    vm, onDownload = ::onDownload, onOpenPremium = { nav.navigate(PREMIUM) },
                    onLogin = { login.launch(it) },
                )
            }
            composable(Tab.DOWNLOADS.route) { DownloadsScreen(vm) }
            composable(Tab.SETTINGS.route) {
                SettingsScreen(
                    vm, onOpenPremium = { nav.navigate(PREMIUM) }, onMessage = ::message,
                    onLogin = { login.launch(it) },
                )
            }
            composable(PREMIUM) {
                PremiumScreen(vm, onBack = { nav.popBackStack() }, onMessage = ::message)
            }
        }
    }
}
