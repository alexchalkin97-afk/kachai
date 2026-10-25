package app.kachai.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kachai.dl.Downloads
import app.kachai.dl.HistoryItem
import app.kachai.ui.theme.MonoNumbers
import coil3.compose.AsyncImage
import java.io.File

/** Что показывать в списке готовых файлов. */
private enum class Filter(val label: String, val matches: (HistoryItem) -> Boolean) {
    ALL("Все", { true }),
    VIDEO("Видео", { it.mime.startsWith("video") }),
    AUDIO("Аудио", { it.mime.startsWith("audio") }),
    OTHER("Обложки и субтитры", { !it.mime.startsWith("video") && !it.mime.startsWith("audio") }),
}

@Composable
fun DownloadsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var toDelete by remember { mutableStateOf<HistoryItem?>(null) }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }

    if (tasks.isEmpty() && history.isEmpty()) {
        EmptyDownloads(modifier)
        return
    }

    val shown = remember(history, filter, query) {
        history.filter { filter.matches(it) && (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true)) }
    }
    // Группы по дням в том же порядке, что и список (новые сверху)
    val groups = remember(shown) { shown.groupBy { formatDay(it.createdAt).replaceFirstChar(Char::uppercase) } }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Загрузки", style = MaterialTheme.typography.headlineMedium)
                    if (history.isNotEmpty()) {
                        Text(
                            "${history.size} ${plural(history.size.toLong(), "файл", "файла", "файлов")} · " +
                                formatBytes(history.sumOf { it.sizeBytes }),
                            style = MaterialTheme.typography.bodySmall.merge(MonoNumbers),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (history.size >= 4) {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "Поиск")
                    }
                }
            }
        }
        if (searching) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Название ролика") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        if (tasks.isNotEmpty()) {
            item(key = "tasks-title") { SectionTitle("В процессе", Modifier.padding(top = 12.dp)) }
            items(tasks, key = { it.id }) { t ->
                TaskCard(
                    t,
                    onCancel = { Downloads.cancel(t.id) },
                    onRetry = { if (!vm.retry(t.id)) Toast.makeText(context, LIMIT_REACHED, Toast.LENGTH_SHORT).show() },
                    onDismiss = { Downloads.dismiss(t.id) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        if (history.isNotEmpty()) {
            // Фильтры — только если есть из чего выбирать
            val available = Filter.entries.filter { f -> f == Filter.ALL || history.any(f.matches) }
            if (available.size > 2) {
                item(key = "filters") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        items(available) { f ->
                            FilterChip(
                                selected = filter == f,
                                onClick = { filter = f },
                                label = { Text(f.label) },
                                shape = MaterialTheme.shapes.small,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "nothing") {
                    Text(
                        "Ничего не нашлось",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
            groups.forEach { (day, items) ->
                item(key = "day-$day") { SectionTitle(day, Modifier.padding(top = 12.dp).animateItem()) }
                items(items, key = { it.id }) { item ->
                    HistoryRow(
                        item,
                        onOpen = { openFile(context, item) },
                        onShare = { shareFile(context, item) },
                        onDelete = { toDelete = item },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    toDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить?") },
            text = { Text(item.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { Downloads.removeFromHistory(context, item, deleteFile = true); toDelete = null }) {
                    Text("Удалить файл")
                }
            },
            dismissButton = {
                TextButton(onClick = { Downloads.removeFromHistory(context, item, deleteFile = false); toDelete = null }) {
                    Text("Только из списка")
                }
            },
        )
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onOpen: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(112.dp)
                .height(63.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(kindIcon(item), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            item.thumbPath?.let { path ->
                AsyncImage(File(path), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (item.mime.startsWith("video")) {
                aspectLabel(item.width, item.height)?.let { ThumbLabel(it, Modifier.align(Alignment.TopStart)) }
            }
            formatDuration(item.durationSec).takeIf { it.isNotEmpty() && !item.mime.startsWith("image") }
                ?.let { ThumbLabel(it, Modifier.align(Alignment.BottomEnd)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                listOf(item.label, formatBytes(item.sizeBytes)).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, "Поделиться", tint = MaterialTheme.colorScheme.primary) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Удалить") },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

/** Значок на месте превью: видео, звук, обложка, субтитры. Нужен и в «Недавних» на главном. */
fun kindIcon(item: HistoryItem): ImageVector = when {
    item.mime.startsWith("audio") -> Icons.Rounded.MusicNote
    item.mime.startsWith("image") -> Icons.Rounded.Image
    item.mime.startsWith("video") -> Icons.Rounded.PlayArrow
    else -> Icons.Rounded.Subtitles
}

@Composable
private fun ThumbLabel(text: String, modifier: Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.72f), contentColor = Color.White,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier.padding(4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall.merge(MonoNumbers), modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

@Composable
private fun EmptyDownloads(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(88.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.DownloadDone, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(20.dp))
        Text("Здесь будут ваши видео", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Нажмите «Поделиться» в приложении с видео и выберите «Качай» — или вставьте ссылку на главном экране.\n\n" +
                "Файлы сохраняются в папку «Загрузки/Качай».",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
