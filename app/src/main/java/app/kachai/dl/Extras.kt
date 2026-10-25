package app.kachai.dl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.kachai.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Обложка и субтитры ролика. yt-dlp тут не нужен: ссылки на них уже есть в ответе поиска.
 * В дневной лимит не входят. Сохраняются в «Загрузки/Качай» и попадают в историю, как обычные загрузки.
 */
object Extras {
    private const val TAG = "Extras"
    private const val MAX_BYTES = 20L shl 20

    private val client by lazy { OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build() }

    /** Обложка в лучшем качестве, в JPEG — его открывает любой редактор. */
    suspend fun saveCover(context: Context, info: MediaInfo): HistoryItem = withContext(Dispatchers.IO) {
        // Сначала самая крупная; её может не быть на сайте (у старых роликов YouTube) — тогда обычная
        val bitmap = (info.coverUrls + listOfNotNull(info.thumbnail)).distinct().firstNotNullOfOrNull { url ->
            runCatching { get(url).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
        } ?: error("Не получилось скачать обложку")
        val id = UUID.randomUUID().toString()
        val file = File(workDir(context), fileName(info.title, "обложка", "jpg"))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val thumb = File(context.filesDir, "thumbs/$id.jpg").apply { parentFile?.mkdirs() }
        file.copyTo(thumb, overwrite = true)
        publish(context, file, info, id, label = "Обложка · ${bitmap.width}×${bitmap.height}", thumbPath = thumb.absolutePath)
            .also { Diag.log(TAG, "cover ${bitmap.width}x${bitmap.height}") }
    }

    /** Субтитры в .srt: его понимают CapCut, VN и другие редакторы. VTT переводим в SRT сами. */
    suspend fun saveSubtitles(context: Context, info: MediaInfo, sub: Subtitle): HistoryItem = withContext(Dispatchers.IO) {
        val raw = get(sub.url).toString(Charsets.UTF_8)
        val srt = if (sub.ext == "vtt") vttToSrt(raw) else raw
        if (srt.isBlank()) error("Субтитры пустые")
        val file = File(workDir(context), fileName(info.title, sub.lang, "srt"))
        file.writeText(srt)
        publish(context, file, info, UUID.randomUUID().toString(), label = "Субтитры · ${sub.lang.uppercase()}", thumbPath = null)
            .also { Diag.log(TAG, "subtitles ${sub.lang} (${sub.ext})") }
    }

    private suspend fun publish(
        context: Context, file: File, info: MediaInfo, id: String, label: String, thumbPath: String?,
    ): HistoryItem {
        try {
            val uri = Storage.publish(context, file)
            val item = HistoryItem(
                id = id, title = info.title, uploader = info.uploader, sourceUrl = info.url,
                fileUri = uri.toString(), fileName = file.name, mime = Storage.mimeOf(file),
                sizeBytes = file.length(), thumbPath = thumbPath, createdAt = System.currentTimeMillis(), label = label,
            )
            Downloads.addToHistory(item)
            return item
        } finally {
            file.delete()
        }
    }

    private fun get(url: String): ByteArray = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
        check(r.isSuccessful) { "Сайт не отдал файл (HTTP ${r.code})" }
        val body = r.body ?: error("Пустой ответ")
        check(body.contentLength() <= MAX_BYTES) { "Файл слишком большой" }
        body.bytes()
    }

    private fun workDir(context: Context) = File(context.cacheDir, "extras").apply { mkdirs() }

    /** Название ролика без символов, запрещённых в именах файлов. */
    private fun fileName(title: String, suffix: String, ext: String): String {
        val clean = title.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").trim().take(100).ifEmpty { "video" }
        return "$clean ($suffix).$ext"
    }

    /** WebVTT → SubRip: номера реплик, запятая в миллисекундах, без тегов и настроек позиции. */
    internal fun vttToSrt(vtt: String): String {
        val time = Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{3})""")
        val out = StringBuilder()
        var n = 0
        var lastText = ""
        for (block in vtt.replace("\r", "").split(Regex("\n\\s*\n"))) {
            val lines = block.lines()
            val i = lines.indexOfFirst { time.containsMatchIn(it) }
            if (i < 0) continue
            val m = time.find(lines[i])!!
            val text = lines.drop(i + 1)
                .map { it.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
            if (text.isEmpty() || text == lastText) continue
            lastText = text
            n++
            out.append(n).append('\n')
                .append(srtTime(m.groupValues[1])).append(" --> ").append(srtTime(m.groupValues[2])).append('\n')
                .append(text).append("\n\n")
        }
        return out.toString()
    }

    /** «01:02.500» или «1:01:02.500» → «00:01:02,500». */
    private fun srtTime(t: String): String {
        val parts = t.replace(',', '.').split(':')
        val (h, m, s) = if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else Triple("0", parts[0], parts[1])
        return "%02d:%02d:%s".format(h.toInt(), m.toInt(), s.padStart(6, '0').replace('.', ','))
    }
}
