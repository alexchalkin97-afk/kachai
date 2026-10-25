package app.kachai.dl

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Готовая загрузка — то, что видно в списке «Загрузки». */
@Serializable
data class HistoryItem(
    val id: String,
    val title: String,
    val uploader: String?,
    val sourceUrl: String,
    val fileUri: String,
    val fileName: String,
    val mime: String,
    val sizeBytes: Long,
    val thumbPath: String?,
    val createdAt: Long,
    val label: String,
    /** Для бейджей на превью: длительность и ориентация кадра. 0 — неизвестно (старые записи). */
    val durationSec: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
)

enum class TaskState { QUEUED, RUNNING, PROCESSING, FAILED, CANCELLED }

/** Фрагмент ролика: качаем только его (yt-dlp --download-sections). */
@Serializable
data class ClipSection(val startSec: Int, val endSec: Int) {
    val lengthSec: Int get() = (endSec - startSec).coerceAtLeast(0)
    val label: String get() = "${clock(startSec)}–${clock(endSec)}"

    private fun clock(sec: Int): String =
        if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
}

/** Загрузка в процессе. Завершённые уходят в историю. */
data class Task(
    val id: String,
    val info: MediaInfo,
    val option: FormatOption,
    val state: TaskState = TaskState.QUEUED,
    val progress: Float = -1f,
    val etaSec: Long = 0,
    /** Текущая скорость в байтах/с, -1 — неизвестна. */
    val speedBps: Long = -1,
    /** Сколько уже записано на диск. */
    val downloadedBytes: Long = 0,
    val error: String? = null,
    /** Только фрагмент ролика; null — целиком. */
    val section: ClipSection? = null,
)

/** Очередь загрузок и история. Саму работу выполняет [DownloadService]. */
object Downloads {
    private const val HISTORY_FILE = "history.json"
    private val json = Json { ignoreUnknownKeys = true }

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()

    /** Вызывается, когда загрузка не удалась или отменена — чтобы вернуть попытку в дневной лимит. */
    var onRefund: ((Task) -> Unit)? = null

    private lateinit var historyFile: File

    fun load(context: Context) {
        historyFile = File(context.filesDir, HISTORY_FILE)
        _history.value = runCatching {
            json.decodeFromString<List<HistoryItem>>(historyFile.readText())
        }.getOrDefault(emptyList())
    }

    fun enqueue(context: Context, info: MediaInfo, option: FormatOption, section: ClipSection? = null) {
        val task = Task(id = UUID.randomUUID().toString(), info = info, option = option, section = section)
        _tasks.update { it + task }
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    fun cancel(id: String) {
        val t = _tasks.value.find { it.id == id } ?: return
        when (t.state) {
            TaskState.QUEUED -> {
                update(id) { it.copy(state = TaskState.CANCELLED) }
                onRefund?.invoke(t)
            }
            TaskState.RUNNING, TaskState.PROCESSING -> Engine.cancel(id)
            else -> Unit
        }
    }

    /** Убрать карточку ошибки/отмены с экрана. */
    fun dismiss(id: String) = _tasks.update { list -> list.filterNot { it.id == id } }

    fun retry(context: Context, id: String) {
        val t = _tasks.value.find { it.id == id } ?: return
        dismiss(id)
        enqueue(context, t.info, t.option, t.section)
    }

    fun removeFromHistory(context: Context, item: HistoryItem, deleteFile: Boolean) {
        if (deleteFile) Storage.delete(context, android.net.Uri.parse(item.fileUri))
        item.thumbPath?.let { File(it).delete() }
        _history.update { list -> list.filterNot { it.id == item.id } }
        saveHistory()
    }

    // ---- для DownloadService ----

    internal fun nextQueued(): Task? = _tasks.value.firstOrNull { it.state == TaskState.QUEUED }

    internal fun hasActive(): Boolean =
        _tasks.value.any { it.state == TaskState.QUEUED || it.state == TaskState.RUNNING || it.state == TaskState.PROCESSING }

    internal fun update(id: String, f: (Task) -> Task) =
        _tasks.update { list -> list.map { if (it.id == id) f(it) else it } }

    internal fun fail(task: Task, message: String, cancelled: Boolean) {
        update(task.id) { it.copy(state = if (cancelled) TaskState.CANCELLED else TaskState.FAILED, error = message) }
        onRefund?.invoke(task)
    }

    internal fun complete(task: Task, item: HistoryItem) {
        _tasks.update { list -> list.filterNot { it.id == task.id } }
        addToHistory(item)
    }

    /** Готовый файл не из очереди (обложка, субтитры) — сразу в историю. */
    internal fun addToHistory(item: HistoryItem) {
        _history.update { listOf(item) + it }
        saveHistory()
    }

    private fun saveHistory() {
        runCatching { historyFile.writeText(json.encodeToString(_history.value)) }
    }
}
