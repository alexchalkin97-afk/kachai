package app.kachai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kachai.dl.Task
import app.kachai.dl.TaskState
import app.kachai.ui.theme.MonoNumbers
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024L * 1024 -> "${(bytes / 1024).coerceAtLeast(1)} КБ"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} МБ"
    else -> String.format(Locale("ru"), "%.1f ГБ", bytes / (1024.0 * 1024 * 1024))
}

fun formatDuration(sec: Int): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = sec % 3600 / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec <= 0 -> ""
    bytesPerSec < 1024L * 1024 -> "${bytesPerSec / 1024} КБ/с"
    else -> String.format(Locale("ru"), "%.1f МБ/с", bytesPerSec / (1024.0 * 1024))
}

fun formatEta(sec: Long): String = when {
    sec <= 0 -> ""
    sec < 60 -> "$sec с"
    else -> "${sec / 60} мин"
}

fun formatDay(ts: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    val now = Calendar.getInstance()
    val sameYear = c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> "сегодня"
        sameYear && dayDiff == 1 -> "вчера"
        else -> SimpleDateFormat(if (sameYear) "d MMM" else "d MMM yyyy", Locale("ru")).format(Date(ts))
    }
}

/** Небольшая «таблетка»: лимит, статус Плюса. */
@Composable
fun Pill(
    text: String,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(color = container, contentColor = content, shape = CircleShape) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge.merge(MonoNumbers))
        }
    }
}

/** Карточка активной загрузки: прогресс, отмена, ошибка с повтором. */
@Composable
fun TaskCard(task: Task, onCancel: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 14.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        task.info.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        statusLine(task),
                        style = MaterialTheme.typography.bodySmall.merge(MonoNumbers),
                        color = if (task.state == TaskState.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                when (task.state) {
                    TaskState.FAILED, TaskState.CANCELLED -> {
                        IconButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, "Повторить") }
                        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Убрать") }
                    }
                    else -> IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Отменить") }
                }
            }
            if (task.state == TaskState.RUNNING || task.state == TaskState.PROCESSING || task.state == TaskState.QUEUED) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.padding(end = 12.dp)) {
                    val indeterminate = task.progress < 0 || task.state != TaskState.RUNNING
                    val trackMod = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    if (indeterminate) {
                        LinearProgressIndicator(trackMod, strokeCap = StrokeCap.Round, gapSize = 0.dp)
                    } else {
                        LinearProgressIndicator(
                            progress = { task.progress / 100f }, modifier = trackMod,
                            strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
                        )
                    }
                }
            }
        }
    }
}

private fun statusLine(t: Task): String = when (t.state) {
    TaskState.QUEUED -> "В очереди · ${t.option.label}"
    TaskState.RUNNING -> buildString {
        when {
            t.progress >= 0 -> append("${t.progress.toInt()}%")
            t.downloadedBytes > 0 -> append(formatBytes(t.downloadedBytes))
            else -> append(if (t.viaVps) "Подключаемся к серверу…" else "Подготовка…")
        }
        formatSpeed(t.speedBps).takeIf { it.isNotEmpty() }?.let { append(" · $it") }
        formatEta(t.etaSec).takeIf { it.isNotEmpty() && t.progress >= 0 }?.let { append(" · ещё $it") }
        append(" · ${t.option.label}")
    }
    TaskState.PROCESSING ->
        if (t.option.kind == app.kachai.dl.FormatOption.Kind.VIDEO) "Склеиваем видео и звук…" else "Готовим аудиофайл…"
    TaskState.FAILED -> t.error ?: "Ошибка"
    TaskState.CANCELLED -> "Отменено"
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

val ScreenPadding = Arrangement.spacedBy(12.dp)
