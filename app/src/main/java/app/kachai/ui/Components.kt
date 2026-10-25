package app.kachai.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import app.kachai.dl.HistoryItem
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
    // До 10 МБ — с десятыми (1,9 МБ, а не «1 МБ»), дальше — округляем до целого
    bytes < 10L * 1024 * 1024 -> String.format(Locale("ru"), "%.1f МБ", bytes / (1024.0 * 1024))
    bytes < 1024L * 1024 * 1024 -> "${(bytes + 512 * 1024) / (1024 * 1024)} МБ"
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

/** Открыть скачанный файл в подходящем приложении (плеер, галерея). */
fun openFile(context: Context, item: HistoryItem) {
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

/** Отправить файл в редактор или мессенджер — главный сценарий после загрузки. */
fun shareFile(context: Context, item: HistoryItem) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(item.mime)
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(item.fileUri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

/** 1 234 → «1,2 тыс.», 3 400 000 → «3,4 млн». */
fun formatCount(n: Long): String = when {
    n <= 0 -> ""
    n < 1_000 -> "$n"
    n < 1_000_000 -> String.format(Locale("ru"), if (n < 10_000) "%.1f тыс." else "%.0f тыс.", n / 1_000.0)
    n < 1_000_000_000 -> String.format(Locale("ru"), if (n < 10_000_000) "%.1f млн" else "%.0f млн", n / 1_000_000.0)
    else -> String.format(Locale("ru"), "%.1f млрд", n / 1_000_000_000.0)
}

/** «20261021» → «3 дня назад» / «2 мес. назад» / «2019». */
fun formatAge(yyyyMMdd: String?): String {
    val date = yyyyMMdd?.let { runCatching { java.time.LocalDate.parse(it, java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() }
        ?: return ""
    val days = java.time.temporal.ChronoUnit.DAYS.between(date, java.time.LocalDate.now())
    return when {
        days < 0 -> ""
        days == 0L -> "сегодня"
        days == 1L -> "вчера"
        days < 7 -> "$days ${plural(days, "день", "дня", "дней")} назад"
        days < 31 -> "${days / 7} нед. назад"
        days < 365 -> "${days / 30} мес. назад"
        else -> "${date.year}"
    }
}

fun plural(n: Long, one: String, few: String, many: String): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1L && m100 != 11L -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
}

/** Ориентация кадра для рилсмейкеров: «9:16» и т.п.; null — размер неизвестен. */
fun aspectLabel(width: Int, height: Int): String? {
    if (width <= 0 || height <= 0) return null
    val r = width.toDouble() / height
    return when {
        r < 0.65 -> "9:16"
        r < 0.9 -> "3:4"
        r < 1.12 -> "1:1"
        r < 1.5 -> "4:3"
        r < 2.0 -> "16:9"
        else -> "21:9"
    }
}

fun isVertical(width: Int, height: Int) = width > 0 && height > width * 1.1

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
fun TaskCard(
    task: Task,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
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
            else -> append("Подготовка…")
        }
        formatSpeed(t.speedBps).takeIf { it.isNotEmpty() }?.let { append(" · $it") }
        formatEta(t.etaSec).takeIf { it.isNotEmpty() && t.progress >= 0 }?.let { append(" · ещё $it") }
        append(" · ${t.option.label}")
        t.section?.let { append(" · ${it.label}") }
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

val ScreenPadding = Arrangement.spacedBy(12.dp)

const val LIMIT_REACHED = "Лимит на сегодня исчерпан"

/** Напоминание об авторских правах: на главном экране и в «О приложении». */
const val RIGHTS_NOTICE = "Используйте только видео, на которые у вас есть права, или в рамках допустимого цитирования."
