package app.kachai.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import app.kachai.accounts.Site
import app.kachai.ads.Ads
import app.kachai.billing.Quota
import app.kachai.dl.FormatOption
import app.kachai.dl.MediaInfo
import coil3.compose.AsyncImage

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onDownload: () -> Unit,
    onOpenPremium: () -> Unit,
    onLogin: (Site) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val premium by vm.isPremium.collectAsStateWithLifecycle()
    val quota by vm.quota.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val context = LocalContext.current

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Качай", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (premium) {
                Pill("Плюс", Icons.Rounded.Star, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
            } else {
                Surface(onClick = onOpenPremium, shape = RoundedCornerShape(50), color = Color.Transparent) {
                    Pill("${quota.left} из ${quota.limit}", Icons.Rounded.Bolt)
                }
            }
        }

        Spacer(Modifier.height(32.dp))
        Text("Скачать видео", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Вставьте ссылку или нажмите «Поделиться» в приложении с видео",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        LinkField(
            value = vm.input,
            onValueChange = { vm.input = it },
            onPaste = { clipboardText(context)?.let { vm.input = it; focus.clearFocus(); vm.find() } },
            onClear = vm::clear,
            onGo = { focus.clearFocus(); vm.find() },
        )
        Spacer(Modifier.height(12.dp))

        val loading = vm.lookup is Lookup.Loading
        if (vm.lookup !is Lookup.Ready) {
            Button(
                onClick = { focus.clearFocus(); vm.find() },
                enabled = !loading && vm.input.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        Modifier.size(22.dp), strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Ищем видео…")
                } else {
                    Text("Найти")
                }
            }
        }

        AnimatedContent(
            targetState = vm.lookup,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentKey = { it::class },
            label = "lookup",
        ) { state ->
            when (state) {
                is Lookup.Ready -> ResultCard(
                    info = state.info,
                    selected = vm.selected,
                    onSelect = { if (vm.isLocked(it)) onOpenPremium() else vm.selected = it },
                    isLocked = vm::isLocked,
                    canDownload = premium || quota.left > 0,
                    quotaLeft = if (premium) null else quota.left,
                    rewardsLeft = quota.rewardsLeft,
                    onDownload = onDownload,
                    onWatchAd = {
                        (context as? Activity)?.let { a -> Ads.showRewarded(a) { vm.addReward() } }
                    },
                    onOpenPremium = onOpenPremium,
                )
                is Lookup.Failed -> ErrorCard(
                    state.message,
                    actionLabel = when {
                        state.loginSite != null -> "Войти"
                        state.reloginSite != null -> "Войти заново"
                        else -> "Ещё раз"
                    },
                    onAction = { (state.loginSite ?: state.reloginSite)?.let(onLogin) ?: vm.find() },
                )
                else -> Spacer(Modifier.height(0.dp))
            }
        }

        // Видео только для вошедших — сразу предлагаем привязать аккаунт (один раз на ошибку)
        val failed = vm.lookup as? Lookup.Failed
        var dismissedFor by remember { mutableStateOf<Lookup?>(null) }
        val site = failed?.loginSite
        if (site != null && dismissedFor !== failed) {
            AlertDialog(
                onDismissRequest = { dismissedFor = failed },
                icon = { Icon(Icons.Rounded.AccountCircle, null) },
                title = { Text("Войти в ${site.title}?") },
                text = {
                    Text(
                        "${site.title} показывает это видео только вошедшим. Войдите один раз — " +
                            "и такие видео будут качаться сами.\n\nВход на официальном сайте ${site.hosts.first()}, " +
                            "пароль приложение не видит."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { dismissedFor = failed; onLogin(site) }) { Text("Войти") }
                },
                dismissButton = { TextButton(onClick = { dismissedFor = failed }) { Text("Не сейчас") } },
            )
        }

        if (tasks.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            SectionTitle("Сейчас качается")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tasks.forEach { t ->
                    TaskCard(
                        t,
                        onCancel = { app.kachai.dl.Downloads.cancel(t.id) },
                        onRetry = { app.kachai.dl.Downloads.retry(context, t.id) },
                        onDismiss = { app.kachai.dl.Downloads.dismiss(t.id) },
                    )
                }
            }
        }

        if (vm.lookup is Lookup.Idle && tasks.isEmpty()) {
            Spacer(Modifier.height(32.dp))
            SupportedSites()
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LinkField(
    value: String,
    onValueChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onGo: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("https://youtu.be/…") },
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        trailingIcon = {
            if (value.isEmpty()) {
                TextButton(onClick = onPaste, modifier = Modifier.padding(end = 4.dp)) {
                    Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Вставить")
                }
            } else {
                IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, "Очистить") }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(
    info: MediaInfo,
    selected: FormatOption?,
    onSelect: (FormatOption) -> Unit,
    isLocked: (FormatOption) -> Boolean,
    canDownload: Boolean,
    quotaLeft: Int?,
    rewardsLeft: Int,
    onDownload: () -> Unit,
    onWatchAd: () -> Unit,
    onOpenPremium: () -> Unit,
) {
    val videoOpts = info.options.filter { it.kind == FormatOption.Kind.VIDEO }
    val audioOpts = info.options.filter { it.kind != FormatOption.Kind.VIDEO }
    var audioTab by remember(info.id) { mutableStateOf(selected?.kind != null && selected.kind != FormatOption.Kind.VIDEO) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayCircleOutline, null, Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                if (info.thumbnail != null) {
                    AsyncImage(
                        model = info.thumbnail, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                }
                formatDuration(info.durationSec).takeIf { it.isNotEmpty() }?.let {
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f), contentColor = Color.White,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    ) {
                        Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
            }

            Column(Modifier.padding(16.dp)) {
                Text(info.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(info.uploader, info.site.takeIf { it.isNotBlank() })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(16.dp))
                if (audioOpts.isNotEmpty()) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !audioTab,
                            onClick = { audioTab = false; (videoOpts.firstOrNull { !isLocked(it) } ?: videoOpts.lastOrNull())?.let(onSelect) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("Видео") }
                        SegmentedButton(
                            selected = audioTab,
                            onClick = { audioTab = true; audioOpts.firstOrNull()?.let(onSelect) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("Только звук") }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    (if (audioTab) audioOpts else videoOpts).forEach { opt ->
                        val locked = isLocked(opt)
                        FilterChip(
                            selected = selected == opt,
                            onClick = { onSelect(opt) },
                            label = {
                                Text(buildString {
                                    append(opt.label)
                                    if (locked) append(" · Плюс")
                                    else formatBytes(opt.approxBytes).takeIf { it.isNotEmpty() }?.let { append(" · ≈$it") }
                                })
                            },
                            leadingIcon = if (locked) {
                                { Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp)) }
                            } else null,
                            shape = MaterialTheme.shapes.small,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                if (canDownload) {
                    Button(
                        onClick = onDownload,
                        enabled = selected != null,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(Icons.Rounded.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Скачать")
                    }
                    if (quotaLeft != null && quotaLeft <= 2) {
                        Text(
                            "Сегодня осталось загрузок: $quotaLeft",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                } else {
                    LimitBlock(rewardsLeft, onWatchAd, onOpenPremium)
                }
            }
        }
    }
}

@Composable
private fun LimitBlock(rewardsLeft: Int, onWatchAd: () -> Unit, onOpenPremium: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Бесплатные загрузки на сегодня закончились", style = MaterialTheme.typography.titleSmall)
        Text(
            "Посмотрите короткую рекламу или оформите Плюс — без лимитов.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        if (rewardsLeft > 0) {
            OutlinedButton(
                onClick = onWatchAd,
                enabled = Ads.rewardedReady,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(if (Ads.rewardedReady) "Реклама → +${Quota.REWARD_BONUS} загрузки" else "Реклама загружается…")
            }
        }
        Button(
            onClick = onOpenPremium,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Icon(Icons.Rounded.Star, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Качай Плюс")
        }
    }
}

@Composable
private fun ErrorCard(message: String, actionLabel: String, onAction: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun SupportedSites() {
    Column {
        SectionTitle("Откуда можно качать")
        Text(
            "VK Видео, Rutube, Дзен, Одноклассники, YouTube, TikTok и ещё более 1000 сайтов.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            RIGHTS_NOTICE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

private fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    return text?.let { MainViewModel.extractUrl(it) ?: it.trim() }?.takeIf { it.isNotBlank() }
}
