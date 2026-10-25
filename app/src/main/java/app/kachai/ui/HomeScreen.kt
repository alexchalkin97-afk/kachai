package app.kachai.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StayCurrentLandscape
import androidx.compose.material.icons.rounded.StayCurrentPortrait
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kachai.accounts.Site
import app.kachai.ads.Ads
import app.kachai.billing.Quota
import app.kachai.dl.ClipSection
import app.kachai.dl.FormatOption
import app.kachai.dl.HistoryItem
import app.kachai.dl.MediaInfo
import app.kachai.ui.theme.MonoNumbers
import coil3.compose.AsyncImage
import java.io.File
import java.util.Calendar

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onDownload: () -> Unit,
    onOpenPremium: () -> Unit,
    onOpenDownloads: () -> Unit,
    onLogin: (Site) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
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

        AnimatedVisibility(
            visible = vm.clipboardHint != null && vm.input.isEmpty(),
            enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically(),
        ) {
            ClipboardCard(
                host = vm.clipboardHint?.host,
                onFind = { focus.clearFocus(); vm.useClipboard(context) },
                onDismiss = vm::dismissClipboard,
            )
        }
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
                    vm = vm,
                    info = state.info,
                    canDownload = premium || quota.left > 0,
                    quotaLeft = if (premium) null else quota.left,
                    rewardsLeft = quota.rewardsLeft,
                    onSelect = { if (vm.isLocked(it)) onOpenPremium() else vm.selected = it },
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
                        onRetry = { if (!vm.retry(t.id)) Toast.makeText(context, LIMIT_REACHED, Toast.LENGTH_SHORT).show() },
                        onDismiss = { app.kachai.dl.Downloads.dismiss(t.id) },
                    )
                }
            }
        }

        if (vm.lookup is Lookup.Idle) {
            if (history.isNotEmpty()) {
                Spacer(Modifier.height(28.dp))
                RecentDownloads(history, onOpenAll = onOpenDownloads)
            }
            Spacer(Modifier.height(24.dp))
            TipCard()
            Spacer(Modifier.height(28.dp))
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
        placeholder = { Text("https://vkvideo.ru/…") },
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

/** «В буфере обмена ссылка — найти?» */
@Composable
private fun ClipboardCard(host: String?, onFind: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Link, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Ссылка в буфере обмена", style = MaterialTheme.typography.titleSmall)
                host?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onFind) { Text("Найти") }
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Скрыть", Modifier.size(20.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultCard(
    vm: MainViewModel,
    info: MediaInfo,
    canDownload: Boolean,
    quotaLeft: Int?,
    rewardsLeft: Int,
    onSelect: (FormatOption) -> Unit,
    onDownload: () -> Unit,
    onWatchAd: () -> Unit,
    onOpenPremium: () -> Unit,
) {
    val selected = vm.selected
    val videoOpts = info.options.filter { it.kind == FormatOption.Kind.VIDEO }
    val audioOpts = info.options.filter { it.kind != FormatOption.Kind.VIDEO }
    var audioTab by remember(info.id) { mutableStateOf(selected?.kind != null && selected.kind != FormatOption.Kind.VIDEO) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).animateContentSize(),
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
                if (info.thumbnail != null || info.coverUrls.isNotEmpty()) {
                    // Вертикальное видео не обрезаем: показываем целиком, по бокам — тёмные поля
                    AsyncImage(
                        model = info.thumbnail ?: info.coverUrls.lastOrNull(), contentDescription = null,
                        contentScale = if (isVertical(info.width, info.height)) ContentScale.Fit else ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(Color.Black),
                    )
                }
                aspectLabel(info.width, info.height)?.let {
                    OverlayLabel(it, Modifier.align(Alignment.TopStart))
                }
                formatDuration(info.durationSec).takeIf { it.isNotEmpty() }?.let {
                    OverlayLabel(it, Modifier.align(Alignment.BottomEnd))
                }
            }

            Column(Modifier.padding(16.dp)) {
                Text(info.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    listOfNotNull(info.uploader, info.site.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                InfoBadges(info)

                if (info.durationSec > 0 || info.thumbnail != null || info.subtitles.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    ExtrasRow(vm, info)
                }
                AnimatedVisibility(visible = vm.section != null) {
                    vm.section?.let { s -> FragmentPicker(s, info.durationSec, onChange = { vm.section = it }) }
                }

                Spacer(Modifier.height(16.dp))
                if (audioOpts.isNotEmpty()) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !audioTab,
                            onClick = { audioTab = false; (videoOpts.firstOrNull { !vm.isLocked(it) } ?: videoOpts.lastOrNull())?.let(onSelect) },
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

                val section = vm.section
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    (if (audioTab) audioOpts else videoOpts).forEach { opt ->
                        val locked = vm.isLocked(opt)
                        // У фрагмента и размер — доля от всего ролика
                        val bytes = if (section != null && info.durationSec > 0) opt.approxBytes * section.lengthSec / info.durationSec else opt.approxBytes
                        FilterChip(
                            selected = selected == opt,
                            onClick = { onSelect(opt) },
                            label = {
                                Text(buildString {
                                    append(opt.label)
                                    if (locked) append(" · Плюс")
                                    else formatBytes(bytes).takeIf { it.isNotEmpty() }?.let { append(" · ≈$it") }
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
                selected?.let(::optionDetails)?.let { (text, warn) ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))
                if (canDownload) {
                    Button(
                        onClick = onDownload,
                        enabled = selected != null && (section == null || section.lengthSec > 0),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Icon(if (section != null) Icons.Rounded.ContentCut else Icons.Rounded.Download, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (section != null) "Скачать фрагмент · ${formatDuration(section.lengthSec)}" else "Скачать")
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

/** Надпись поверх превью: длительность, «9:16». */
@Composable
private fun OverlayLabel(text: String, modifier: Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.72f), contentColor = Color.White,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.padding(10.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium.merge(MonoNumbers), modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

/** Что важно рилсмейкеру до загрузки: ориентация, плавность, популярность, свежесть. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoBadges(info: MediaInfo) {
    val badges = buildList<Pair<ImageVector, String>> {
        when {
            info.width <= 0 || info.height <= 0 -> Unit
            isVertical(info.width, info.height) -> add(Icons.Rounded.StayCurrentPortrait to "Вертикальное")
            info.width > info.height * 1.1 -> add(Icons.Rounded.StayCurrentLandscape to "Горизонтальное")
            else -> add(Icons.Rounded.CropSquare to "Квадрат")
        }
        if (info.fps > 0) add(Icons.Rounded.Speed to "${info.fps} fps")
        formatCount(info.viewCount).takeIf { it.isNotEmpty() }?.let { add(Icons.Rounded.Visibility to it) }
        formatCount(info.likeCount).takeIf { it.isNotEmpty() }?.let { add(Icons.Rounded.FavoriteBorder to it) }
        formatAge(info.uploadDate).takeIf { it.isNotEmpty() }?.let { add(Icons.Rounded.Schedule to it) }
    }
    if (badges.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 10.dp),
    ) {
        badges.forEach { (icon, text) -> SmallBadge(icon, text) }
    }
}

@Composable
private fun SmallBadge(icon: ImageVector, text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelMedium.merge(MonoNumbers))
        }
    }
}

/** «Фрагмент», «Обложка», «Субтитры». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtrasRow(vm: MainViewModel, info: MediaInfo) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (info.durationSec > 1) {
            FilterChip(
                selected = vm.section != null,
                onClick = {
                    vm.section = if (vm.section == null) ClipSection(0, minOf(info.durationSec, 60)) else null
                },
                label = { Text("Фрагмент") },
                leadingIcon = { Icon(Icons.Rounded.ContentCut, null, Modifier.size(18.dp)) },
                shape = MaterialTheme.shapes.small,
            )
        }
        if (info.thumbnail != null || info.coverUrls.isNotEmpty()) {
            AssistChip(
                onClick = vm::saveCover,
                enabled = !vm.savingExtra,
                label = { Text("Обложка") },
                leadingIcon = { Icon(Icons.Rounded.Image, null, Modifier.size(AssistChipDefaults.IconSize)) },
                shape = MaterialTheme.shapes.small,
            )
        }
        if (info.subtitles.isNotEmpty()) {
            var menu by remember { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { if (info.subtitles.size == 1) vm.saveSubtitles(info.subtitles.first()) else menu = true },
                    enabled = !vm.savingExtra,
                    label = { Text(if (info.subtitles.size == 1) "Субтитры ${info.subtitles.first().lang.uppercase()}" else "Субтитры") },
                    leadingIcon = { Icon(Icons.Rounded.Subtitles, null, Modifier.size(AssistChipDefaults.IconSize)) },
                    shape = MaterialTheme.shapes.small,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    info.subtitles.take(12).forEach { s ->
                        DropdownMenuItem(
                            text = { Text("${s.lang.uppercase()} · ${s.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { menu = false; vm.saveSubtitles(s) },
                        )
                    }
                }
            }
        }
    }
}

/** Выбор отрезка: два бегунка по длительности ролика. */
@Composable
private fun FragmentPicker(section: ClipSection, durationSec: Int, onChange: (ClipSection) -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        RangeSlider(
            value = section.startSec.toFloat()..section.endSec.toFloat(),
            onValueChange = { r ->
                val start = r.start.toInt()
                val end = r.endInclusive.toInt().coerceAtLeast(start + 1).coerceAtMost(durationSec)
                onChange(ClipSection(start, end))
            },
            valueRange = 0f..durationSec.toFloat(),
        )
        Row {
            Text(
                "${formatDuration(section.startSec).ifEmpty { "0:00" }} — ${formatDuration(section.endSec)}",
                style = MaterialTheme.typography.bodyMedium.merge(MonoNumbers),
                modifier = Modifier.weight(1f),
            )
            Text(
                "длина ${formatDuration(section.lengthSec)}",
                style = MaterialTheme.typography.bodyMedium.merge(MonoNumbers),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Начало может сдвинуться на 1–2 секунды — к ближайшему ключевому кадру",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Подсказка под вариантами: точный кадр и подойдёт ли файл для монтажа. Второе — предупреждение ли это. */
private fun optionDetails(opt: FormatOption): Pair<String, Boolean>? = when (opt.kind) {
    FormatOption.Kind.VIDEO -> {
        val base = listOfNotNull(
            "${opt.frameWidth}×${opt.frameHeight}".takeIf { opt.frameWidth > 0 && opt.frameHeight > 0 },
            opt.fps.takeIf { it > 0 }?.let { "$it fps" },
            opt.codec,
        ).joinToString(" · ")
        when {
            base.isEmpty() -> null
            opt.codec == "H.264" -> "$base — откроется в любом редакторе" to false
            opt.codec != null -> "$base — на старых телефонах редактор может не открыть" to true
            else -> base to false
        }
    }
    FormatOption.Kind.AUDIO_M4A -> "AAC без перекодирования — лучшее качество звука" to false
    FormatOption.Kind.AUDIO_MP3 -> "MP3 ≈190 кбит/с — открывается везде" to false
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

/** Лента последних загрузок и итог за месяц. */
@Composable
private fun RecentDownloads(history: List<HistoryItem>, onOpenAll: () -> Unit) {
    val context = LocalContext.current
    val month = remember(history) {
        val now = Calendar.getInstance()
        history.filter {
            val c = Calendar.getInstance().apply { timeInMillis = it.createdAt }
            c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.MONTH) == now.get(Calendar.MONTH)
        }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Недавние", Modifier.weight(1f))
            TextButton(onClick = onOpenAll, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Все") }
        }
        if (month.isNotEmpty()) {
            Text(
                "В этом месяце: ${month.size} ${plural(month.size.toLong(), "файл", "файла", "файлов")} · " +
                    formatBytes(month.sumOf { it.sizeBytes }),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(history.take(8), key = { it.id }) { item ->
                Column(
                    Modifier
                        .width(150.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { openFile(context, item) },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(kindIcon(item), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        item.thumbPath?.let { AsyncImage(File(it), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(item.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(item.label.substringBefore(" · "), formatBytes(item.sizeBytes)).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val TIPS = listOf(
    "Нажмите «Поделиться» в VK, YouTube или TikTok и выберите «Качай» — ссылка найдётся сама.",
    "Для рилсов берите вертикальные ролики 9:16 в 1080p — при монтаже они не потеряют качество.",
    "Нужен только кусок длинного ролика? Нажмите «Фрагмент» и выберите начало и конец.",
    "Звук для озвучки — вкладка «Только звук»: M4A лучше по качеству, MP3 открывается везде.",
    "Видео из закрытой группы VK? Войдите в аккаунт в «Настройках» — пароль остаётся на сайте VK.",
    "Файлы лежат в папке «Загрузки/Качай» — их сразу видят CapCut, VN и галерея.",
)

/** Один совет за раз; закрытый не возвращается до следующего запуска. */
@Composable
private fun TipCard() {
    var index by rememberSaveable { mutableStateOf(TIPS.indices.random()) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    AnimatedVisibility(visible = !hidden, exit = fadeOut() + shrinkVertically()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
            onClick = { index = (index + 1) % TIPS.size },
        ) {
            Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
                Icon(Icons.Rounded.Lightbulb, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Совет", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    AnimatedContent(targetState = index, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tip") { i ->
                        Text(TIPS[i], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { hidden = true }) { Icon(Icons.Rounded.Close, "Скрыть совет", Modifier.size(20.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SupportedSites() {
    Column {
        SectionTitle("Откуда можно качать")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            listOf("VK Видео", "Rutube", "Дзен", "Одноклассники", "YouTube", "TikTok", "+1000 сайтов").forEach {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
                    Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
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
