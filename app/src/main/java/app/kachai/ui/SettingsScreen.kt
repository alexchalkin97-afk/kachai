package app.kachai.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kachai.BuildConfig
import app.kachai.accounts.Site
import app.kachai.ads.AdBanner
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import app.kachai.data.ThemeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onOpenPremium: () -> Unit,
    onMessage: (String) -> Unit,
    onLogin: (Site) -> Unit,
    modifier: Modifier = Modifier,
) {
    val premium by vm.isPremium.collectAsStateWithLifecycle()
    val until by vm.premiumUntil.collectAsStateWithLifecycle()
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val audioDefault by vm.audioByDefault.collectAsStateWithLifecycle()
    val videoHeight by vm.videoHeight.collectAsStateWithLifecycle()
    val mp3 by vm.audioMp3.collectAsStateWithLifecycle()
    val linked by vm.linkedAccounts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadEngineVersion() }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp, bottom = 20.dp))

        PlusCard(premium, until, onOpenPremium)
        Spacer(Modifier.height(28.dp))

        SectionTitle("Качество по умолчанию")
        Group {
            Column(Modifier.padding(16.dp)) {
                Text("Что качать", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !audioDefault, onClick = { vm.setAudioByDefault(false) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Видео") }
                    SegmentedButton(
                        selected = audioDefault, onClick = { vm.setAudioByDefault(true) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Только звук") }
                }

                Spacer(Modifier.height(20.dp))
                Text("Разрешение видео", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                val maxHeight = vm.maxVideoHeight()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1080, 720, 480, 360).forEach { h ->
                        val locked = h > maxHeight
                        FilterChip(
                            selected = !locked && minOf(videoHeight, maxHeight) == h,
                            onClick = { if (locked) onOpenPremium() else vm.setVideoHeight(h) },
                            label = { Text(if (locked) "${h}p · Плюс" else "${h}p") },
                            leadingIcon = if (locked) {
                                { Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp)) }
                            } else null,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("Формат звука", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !mp3, onClick = { vm.setAudioMp3(false) }, label = { Text("M4A — качество лучше") })
                    FilterChip(selected = mp3, onClick = { vm.setAudioMp3(true) }, label = { Text("MP3 — играет везде") })
                }
            }
        }
        Text(
            "Это выбирается сразу после вставки ссылки. Перед скачиванием всегда можно поменять.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
        Spacer(Modifier.height(28.dp))

        SectionTitle("Аккаунты")
        Group {
            Site.entries.forEach { site ->
                AccountRow(
                    site = site,
                    linked = site in linked,
                    onLogin = { onLogin(site) },
                    onLogout = { vm.logout(site); onMessage("${site.title}: аккаунт отвязан") },
                )
            }
        }
        Text(
            "Нужны для видео, которые ВКонтакте и Одноклассники показывают только вошедшим. " +
                "Вход на официальной странице сайта, пароль приложение не видит. Данные входа хранятся только на этом телефоне.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
        Spacer(Modifier.height(28.dp))

        SectionTitle("Тема")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ThemeMode.SYSTEM to "Как в системе", ThemeMode.LIGHT to "Светлая", ThemeMode.DARK to "Тёмная")
                .forEach { (mode, label) ->
                    FilterChip(selected = theme == mode, onClick = { vm.setThemeMode(mode) }, label = { Text(label) })
                }
        }
        Spacer(Modifier.height(28.dp))

        SectionTitle("Движок загрузки")
        Group {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("yt-dlp", style = MaterialTheme.typography.titleSmall)
                    Text(
                        vm.engineVersion ?: "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { vm.updateEngine(onMessage) }, enabled = !vm.engineUpdating) {
                    Text(if (vm.engineUpdating) "Обновляем…" else "Обновить")
                }
            }
        }
        Text(
            "Обновляется само раз в сутки. Если видео перестали качаться — нажмите «Обновить».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
        Spacer(Modifier.height(28.dp))

        SectionTitle("О приложении")
        Group {
            Column(Modifier.padding(16.dp)) {
                Text("Качай ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Свободная программа под лицензией GPL-3.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { app.kachai.Diag.share(context) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) { Text("Что-то не работает? Отправить отчёт") }
                if (BuildConfig.SOURCE_URL.isNotBlank()) {
                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.SOURCE_URL))) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) { Text("Исходный код") }
                }
            }
        }

        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(28.dp))
            SectionTitle("Отладка")
            Group {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Плюс без оплаты", Modifier.weight(1f))
                    Switch(checked = premium, onCheckedChange = vm::setDebugPremium)
                }
            }
        }

        // В Плюсе рекламы нет совсем
        if (!premium) {
            Spacer(Modifier.height(28.dp))
            AdBanner()
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AccountRow(site: Site, linked: Boolean, onLogin: () -> Unit, onLogout: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.AccountCircle, null, Modifier.size(28.dp),
            tint = if (linked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(site.title, style = MaterialTheme.typography.titleSmall)
            Text(
                if (linked) "Привязан" else "Не привязан",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (linked) {
            TextButton(onClick = onLogout) { Text("Выйти") }
        } else {
            OutlinedButton(onClick = onLogin) { Text("Войти") }
        }
    }
}

@Composable
private fun PlusCard(premium: Boolean, until: Long, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        color = if (premium) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(if (premium) "Плюс активен" else "Качай Плюс", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        premium && until > 0 -> "до " + SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date(until))
                        premium -> "Спасибо за поддержку!"
                        else -> "Без лимита, без рекламы, до 4K"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) { Column { content() } }
}
