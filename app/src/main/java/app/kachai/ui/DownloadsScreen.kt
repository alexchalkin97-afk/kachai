package app.kachai.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kachai.dl.Downloads
import app.kachai.dl.HistoryItem
import coil3.compose.AsyncImage
import java.io.File

@Composable
fun DownloadsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var toDelete by remember { mutableStateOf<HistoryItem?>(null) }

    if (tasks.isEmpty() && history.isEmpty()) {
        EmptyDownloads(modifier)
        return
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Загрузки", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 12.dp)) }
        if (tasks.isNotEmpty()) {
            item { SectionTitle("В процессе") }
            items(tasks, key = { it.id }) { t ->
                TaskCard(
                    t,
                    onCancel = { Downloads.cancel(t.id) },
                    onRetry = { Downloads.retry(context, t.id) },
                    onDismiss = { Downloads.dismiss(t.id) },
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        if (history.isNotEmpty()) {
            item { SectionTitle("Готово") }
            items(history, key = { it.id }) { item ->
                HistoryRow(
                    item,
                    onOpen = { open(context, item) },
                    onShare = { share(context, item) },
                    onDelete = { toDelete = item },
                )
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
private fun HistoryRow(item: HistoryItem, onOpen: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
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
            val audio = item.mime.startsWith("audio")
            Icon(
                if (audio) Icons.Rounded.MusicNote else Icons.Rounded.PlayArrow, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.thumbPath?.let { path ->
                AsyncImage(File(path), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                listOf(item.label, formatBytes(item.sizeBytes), formatDay(item.createdAt)).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Поделиться") },
                    leadingIcon = { Icon(Icons.Rounded.Share, null) },
                    onClick = { menu = false; onShare() },
                )
                DropdownMenuItem(
                    text = { Text("Удалить") },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
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
            Modifier.size(72.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.DownloadDone, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Text("Здесь будут ваши видео", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Файлы сохраняются в папку «Загрузки/Качай»",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun open(context: Context, item: HistoryItem) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.fileUri), item.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Нет приложения, чтобы открыть файл", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, "Файл удалён или перемещён", Toast.LENGTH_SHORT).show()
    }
}

private fun share(context: Context, item: HistoryItem) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(item.mime)
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(item.fileUri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}
