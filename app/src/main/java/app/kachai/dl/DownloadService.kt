package app.kachai.dl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.kachai.Diag
import app.kachai.Stats
import app.kachai.MainActivity
import app.kachai.accounts.Accounts
import app.kachai.R
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.system.Os
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Фоновый сервис: качает задачи из [Downloads] по одной, пока очередь не опустеет. */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private val notifications by lazy { NotificationManagerCompat.from(this) }
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFY_PROGRESS, progressNotification("Подготовка…", -1f),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (worker?.isActive != true) {
            worker = scope.launch {
                while (true) {
                    val task = Downloads.nextQueued() ?: break
                    process(task)
                }
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun process(task: Task) {
        val workDir = File(cacheDir, "dl/${task.id}")
        try {
            Downloads.update(task.id) { it.copy(state = TaskState.RUNNING, progress = -1f, error = null) }
            val started = SystemClock.elapsedRealtime()
            val file = runOnce(task, workDir)
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
            Diag.log(
                "Download", "${task.option.label}: ${file.length() / 1024} KB in ${"%.0f".format(seconds)} s" +
                    " (${"%.2f".format(file.length() / 1048576.0 / seconds.coerceAtLeast(0.1))} MB/s)",
            )
            val mb = file.length() / 1048576.0
            Stats.download(task.info.site, task.option.label, true, mb, mb / seconds.coerceAtLeast(0.1))
            finish(task, file, workDir)
        } catch (e: YoutubeDL.CanceledException) {
            Downloads.fail(task, "Отменено", cancelled = true)
        } catch (e: EngineException) {
            Diag.log("Download", "failed ${task.option.label}: ${e.message} | ${e.cause?.message?.lines()?.lastOrNull { it.isNotBlank() }?.take(300)}")
            Stats.download(task.info.site, task.option.label, false, error = e.message)
            Downloads.fail(task, e.message.orEmpty(), cancelled = false)
            notifyFailed(task, e.message.orEmpty())
        } catch (e: Exception) {
            Diag.log("Download", "crashed ${task.option.label}: $e")
            Stats.download(task.info.site, task.option.label, false, error = e.javaClass.simpleName)
            Downloads.fail(task, "Ошибка: ${e.message}", cancelled = false)
            notifyFailed(task, e.message.orEmpty())
        } finally {
            workDir.deleteRecursively()
        }
    }

    private suspend fun runOnce(task: Task, workDir: File): File {
        val cookies = Accounts.cookiesFor(task.info.url)
        // Последний процент от самого yt-dlp; -1 — он его не печатает (так качает aria2c)
        val enginePercent = AtomicReference(-1f)
        return coroutineScope {
            val poller = launch { pollDiskProgress(task, workDir, enginePercent) }
            try {
                Engine.download(task.id, task.info, task.option, task.section, cookies, workDir) { p ->
                    if (!p.processing && p.percent >= 0) enginePercent.set(p.percent)
                    Downloads.update(task.id) {
                        it.copy(
                            state = if (p.processing) TaskState.PROCESSING else TaskState.RUNNING,
                            // Процент от yt-dlp берём, только если размер заранее неизвестен:
                            // иначе его считает pollDiskProgress (aria2c свой прогресс не выводит)
                            progress = when {
                                p.processing -> -1f
                                // У фрагмента процент yt-dlp идёт по каждой дорожке отдельно (видео 0→100, звук
                                // снова 0→100) — полоса прыгала бы. Для него только байты на диске
                                task.option.approxBytes <= 0 && task.section == null && p.percent >= 0 -> p.percent
                                else -> it.progress
                            },
                        )
                    }
                }
            } finally {
                poller.cancel()
            }
        }
    }

    /**
     * Прогресс по тому, сколько реально записано на диск. aria2c пишет файл кусками в разные места,
     * поэтому длина файла обманчива — считаем занятые блоки (st_blocks), это и есть скачанное.
     */
    private suspend fun pollDiskProgress(task: Task, workDir: File, enginePercent: AtomicReference<Float>) {
        // У фрагмента и размер — доля от всего ролика. Если он вырезается из скачанного целиком (YouTube) —
        // качается весь ролик, и считаем от полного размера
        val total = task.section?.takeUnless { Engine.cutsLocally(task.info) }?.let { s ->
            if (task.info.durationSec > 0) task.option.approxBytes * s.lengthSec / task.info.durationSec else 0L
        } ?: task.option.approxBytes
        var lastBytes = 0L
        var lastAt = SystemClock.elapsedRealtime()
        var speed = 0.0
        while (true) {
            delay(1000)
            val bytes = workDir.listFiles().orEmpty()
                .filter { it.isFile && !it.name.startsWith("thumb.") && !it.name.endsWith(".aria2") }
                .sumOf { f -> runCatching { Os.stat(f.absolutePath).st_blocks * 512 }.getOrDefault(f.length()) }
            val now = SystemClock.elapsedRealtime()
            val instant = (bytes - lastBytes).coerceAtLeast(0) * 1000.0 / (now - lastAt).coerceAtLeast(1)
            speed = if (speed == 0.0) instant else speed * 0.6 + instant * 0.4
            lastBytes = bytes
            lastAt = now
            Downloads.update(task.id) { t ->
                if (t.state != TaskState.RUNNING) return@update t
                t.copy(
                    downloadedBytes = bytes,
                    speedBps = speed.toLong(),
                    progress = when {
                        total <= 0 || bytes <= 0 -> t.progress
                        bytes <= total -> (bytes * 100f / total).coerceAtMost(99f)
                        // Скачано больше оценки: она бывает занижена (размер посчитан по битрейту, или звук
                        // вырезается из целого видео). Тогда верим проценту yt-dlp, а без него (-1)
                        // вместо процента показываем, сколько скачано. У фрагмента — сразу второе
                        else -> if (task.section != null) -1f else enginePercent.get()
                    },
                    etaSec = if (total > bytes && speed > 1) ((total - bytes) / speed).toLong() else 0,
                )
            }
            val t = Downloads.tasks.value.find { it.id == task.id } ?: return
            if (now - lastNotifyAt > 700) {
                lastNotifyAt = now
                val processing = t.state == TaskState.PROCESSING
                notifyProgress(task.info.title, if (processing) -1f else t.progress, processing)
            }
        }
    }

    private suspend fun finish(task: Task, file: File, workDir: File) {
        Downloads.update(task.id) { it.copy(state = TaskState.PROCESSING, progress = -1f) }
        notifyProgress(task.info.title, -1f)
        val uri = Storage.publish(this, file)
        val thumb = File(workDir, "thumb.jpg").takeIf { it.exists() }?.let { src ->
            val dst = File(filesDir, "thumbs/${task.id}.jpg")
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            dst.absolutePath
        }
        val item = HistoryItem(
            id = task.id,
            title = task.info.title,
            uploader = task.info.uploader,
            sourceUrl = task.info.url,
            fileUri = uri.toString(),
            fileName = file.name,
            mime = Storage.mimeOf(file),
            sizeBytes = file.length(),
            thumbPath = thumb,
            createdAt = System.currentTimeMillis(),
            label = task.option.label + task.section?.let { " · ${it.label}" }.orEmpty(),
            durationSec = task.section?.lengthSec ?: task.info.durationSec,
            width = task.option.frameWidth.takeIf { it > 0 } ?: task.info.width,
            height = task.option.frameHeight.takeIf { it > 0 } ?: task.info.height,
        )
        Downloads.complete(task, item)
        notifyDone(item)
    }

    // ---- уведомления ----

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Идёт загрузка", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Загрузка завершена", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** [progress] < 0 — процент неизвестен: идёт постобработка ([processing]) или загрузка без оценки размера. */
    private fun progressNotification(title: String, progress: Float, processing: Boolean = progress < 0) =
        NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(
                when {
                    processing -> "Обработка…"
                    progress < 0 -> "Загрузка…"
                    else -> "${progress.toInt()}%"
                },
            )
            .setProgress(100, progress.toInt().coerceIn(0, 100), progress < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun notifyProgress(title: String, progress: Float, processing: Boolean = progress < 0) {
        if (notifications.areNotificationsEnabled()) {
            runCatching { notifications.notify(NOTIFY_PROGRESS, progressNotification(title, progress, processing)) }
        }
    }

    private fun notifyDone(item: HistoryItem) {
        if (!notifications.areNotificationsEnabled()) return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.fileUri), item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            this, item.id.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Скачано")
            .setContentText(item.title)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { notifications.notify(item.id.hashCode(), n) }
    }

    private fun notifyFailed(task: Task, message: String) {
        if (!notifications.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Не удалось скачать")
            .setContentText(message.ifBlank { task.info.title })
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching { notifications.notify(task.id.hashCode(), n) }
    }

    companion object {
        private const val CH_PROGRESS = "progress"
        private const val CH_DONE = "done"
        private const val NOTIFY_PROGRESS = 1
    }
}
