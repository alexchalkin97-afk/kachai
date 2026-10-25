package app.kachai.dl

import android.content.Context
import android.util.Log
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import app.kachai.Diag
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicInteger

/** Что нашли по ссылке. */
@Serializable
data class MediaInfo(
    val url: String,
    val id: String,
    val title: String,
    val uploader: String?,
    val durationSec: Int,
    val thumbnail: String?,
    val site: String,
    val options: List<FormatOption>,
    /**
     * Ответ yt-dlp при поиске. Загрузка берёт его через --load-info-json и не разбирает страницу
     * заново — это лишние секунды «подготовки».
     */
    val infoJsonPath: String? = null,
    /** Кадр лучшего видео: по нему видно ориентацию (вертикальное — для рилсов). 0 — неизвестно. */
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val viewCount: Long = 0,
    val likeCount: Long = 0,
    /** Дата публикации в виде yyyyMMdd, как отдаёт yt-dlp. */
    val uploadDate: String? = null,
    /** Субтитры от автора (не автоматические). */
    val subtitles: List<Subtitle> = emptyList(),
    /** Обложки от лучшей к худшей (thumbnail — обычно средняя, для превью). Крупной на сайте может не оказаться. */
    val coverUrls: List<String> = emptyList(),
)

/** Файл субтитров на сайте: язык и прямая ссылка. */
@Serializable
data class Subtitle(val lang: String, val name: String, val url: String, val ext: String)

/** Вариант загрузки, который видит пользователь. */
@Serializable
data class FormatOption(
    val kind: Kind,
    /** Высота кадра для видео; null — «лучшее, что есть». */
    val height: Int? = null,
    val approxBytes: Long = 0,
    /** Конкретный видеоформат yt-dlp: так разрешение не окажется выше выбранного. */
    val formatId: String? = null,
    /** Точный кадр, частота и кодек выбранного формата — для подсказки под вариантами. */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val fps: Int = 0,
    val codec: String? = null,
) {
    enum class Kind { VIDEO, AUDIO_M4A, AUDIO_MP3 }

    /** Стабильный ключ для «качества по умолчанию» в настройках. */
    val key: String
        get() = when (kind) {
            Kind.VIDEO -> "v${height ?: "best"}"
            Kind.AUDIO_M4A -> "m4a"
            Kind.AUDIO_MP3 -> "mp3"
        }

    val label: String
        get() = when (kind) {
            Kind.VIDEO -> when (height) {
                null -> "Лучшее"
                2160 -> "4K"
                1440 -> "2K"
                else -> "${height}p"
            }
            Kind.AUDIO_M4A -> "M4A"
            Kind.AUDIO_MP3 -> "MP3"
        }
}

/**
 * Ошибка с понятным текстом. [network] — стоит попробовать другой маршрут,
 * [needsLogin] — сайт отдаёт видео только вошедшим в аккаунт.
 */
class EngineException(
    message: String,
    val network: Boolean,
    cause: Throwable? = null,
    val needsLogin: Boolean = false,
) : Exception(message, cause)

/** Обёртка над yt-dlp (youtubedl-android): Python, ffmpeg и QuickJS уже внутри библиотеки. */
object Engine {
    private const val TAG = "Engine"
    private const val LOOKUP_TIMEOUT_MS = 75_000L
    /** Нарезка файлов YouTube: кусок и число потоков (см. chunkYoutube). */
    private const val YT_CHUNK = 1L shl 20
    private const val YT_PARALLEL = 16
    /** Откуда брать звук, если отдельной дорожки нет: лучшее видео не выше 720p, иначе любое лучшее. */
    private const val AUDIO_FROM_VIDEO = "b[height<=720]/b"
    private val ready = CompletableDeferred<Unit>()

    private val heightsLadder = listOf(2160, 1440, 1080, 720, 480, 360)

    private lateinit var infoDir: File
    private lateinit var appContext: Context

    /** Распаковка Python занимает несколько секунд при первом запуске — делаем это в фоне. */
    fun init(context: Context) {
        appContext = context.applicationContext
        infoDir = File(context.cacheDir, "info").apply { mkdirs() }
        try {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            Aria2c.getInstance().init(context)
            ready.complete(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            ready.completeExceptionally(e)
        }
    }

    suspend fun awaitReady() = ready.await()

    fun version(context: Context): String? = YoutubeDL.getInstance().versionName(context)

    /**
     * Обновление заменяет файл yt-dlp, а Python читает его по ходу работы.
     * Поэтому обновление ждёт, пока закончатся текущие запуски, а новые ждут конца обновления.
     */
    private val updateGate = Mutex()
    private val running = AtomicInteger(0)

    private suspend fun <T> useEngine(block: suspend () -> T): T {
        awaitReady()
        updateGate.withLock { running.incrementAndGet() }
        try {
            return block()
        } finally {
            running.decrementAndGet()
        }
    }

    /** Обновить yt-dlp с GitHub. Возвращает true, если поставлена новая версия. */
    suspend fun update(context: Context): Boolean = withContext(Dispatchers.IO) {
        awaitReady()
        updateGate.withLock {
            while (running.get() > 0) delay(500)
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE) ==
                YoutubeDL.UpdateStatus.DONE
        }
    }

    /** [cookies] — файл сессии привязанного аккаунта (VK, OK), если есть. */
    suspend fun fetchInfo(
        url: String,
        cookies: File? = null,
        timeoutMs: Long = LOOKUP_TIMEOUT_MS,
    ): MediaInfo = withContext(Dispatchers.IO) {
        // yt-dlp при плохой сети может ждать бесконечно — сторож убивает процесс по таймауту
        val processId = "info-" + java.util.UUID.randomUUID()
        var finished = false
        val watchdog = launch {
            delay(timeoutMs)
            Diag.log(TAG, "lookup timeout after ${timeoutMs / 1000}s, killing yt-dlp")
            YoutubeDL.getInstance().destroyProcessById(processId)
        }
        try {
            useEngine { fetchInfoNow(url, cookies, processId) }.also { finished = true }
        } finally {
            watchdog.cancel()
            if (!finished) YoutubeDL.getInstance().destroyProcessById(processId)
        }
    }

    private fun fetchInfoNow(url: String, cookies: File?, processId: String): MediaInfo {
        val req = YoutubeDLRequest(url)
            .addOption("--no-playlist")
            .addOption("--dump-json")
            .addOption("--socket-timeout", 15)
            .addOption("--retries", 2)
            .addOption("--extractor-retries", 1)
        cookies?.let { req.addOption("--cookies", it.absolutePath) }
        val response = try {
            YoutubeDL.getInstance().execute(req, processId)
        } catch (e: YoutubeDL.CanceledException) {
            throw EngineException("Сайт слишком долго не отвечает. Проверьте интернет или включите VPN", network = true, e)
        } catch (e: YoutubeDLException) {
            throw translate(e)
        }
        return parseInfo(response.out, url, processId)
    }

    /**
     * YouTube режет скорость каждого запроса, и тем сильнее, чем длиннее запрос: файл целиком не отдаёт,
     * кусок 10 МБ тянется минутами. Поэтому режем его файлы на куски по 1 МБ (&range=, как делает
     * сам yt-dlp с formats=dashy) и качаем их в [YT_PARALLEL] потоков.
     * Возвращает новый файл с разбором или null, если резать нечего.
     */
    private fun chunkYoutube(infoJson: File): File? {
        val mapper = YoutubeDL.objectMapper
        val root = mapper.readTree(infoJson) as? ObjectNode ?: return null
        var changed = false
        fun chunk(f: JsonNode) {
            val fmt = f as? ObjectNode ?: return
            val url = fmt.path("url").asText("")
            val size = fmt.path("filesize").asLong(0)
            if (fmt.path("protocol").asText() != "https" || size <= 0 || "googlevideo.com" !in url) return
            val sep = if ('?' in url) '&' else '?'
            fmt.remove("downloader_options")
            fmt.put("protocol", "http_dash_segments")
            val fragments = fmt.putArray("fragments")
            var start = 0L
            while (start < size) {
                val end = minOf(start + YT_CHUNK, size) - 1
                fragments.addObject().put("url", "$url${sep}range=$start-$end")
                start += YT_CHUNK
            }
            changed = true
        }
        root.path("formats").forEach(::chunk)
        root.path("requested_formats").forEach(::chunk)
        if (!changed) return null
        return File(infoJson.parentFile, infoJson.nameWithoutExtension + ".chunked.json")
            .apply { mapper.writeValue(this, root) }
    }

    private fun parseInfo(json: String, url: String, fileId: String): MediaInfo {
        val info = runCatching { YoutubeDL.objectMapper.readValue(json, VideoInfo::class.java) }
            .getOrElse { throw EngineException("Не получилось разобрать ответ сайта", network = false, it) }
        val infoFile = runCatching { File(infoDir, "$fileId.json").apply { writeText(json) } }.getOrNull()
        val video = info.formats.orEmpty().filter { it.height > 0 && it.vcodec != null && it.vcodec != "none" }
        val best = video.maxWithOrNull(compareBy({ minOf(it.width, it.height).takeIf { w -> w > 0 } ?: it.height }, { it.tbr }))
        // Instagram и некоторые другие сайты длительность не сообщают — без неё нет ни фрагмента, ни оценки
        // размера. Тогда спрашиваем ffmpeg: он читает её из заголовка видео, не скачивая файл
        val duration = info.duration.takeIf { it > 0 } ?: best?.let(::probeDuration) ?: 0
        return MediaInfo(
            width = best?.width?.takeIf { it > 0 } ?: info.width,
            height = best?.height?.takeIf { it > 0 } ?: info.height,
            fps = best?.fps ?: 0,
            viewCount = info.viewCount?.toLongOrNull() ?: 0,
            likeCount = info.likeCount?.toLongOrNull() ?: 0,
            uploadDate = info.uploadDate?.takeIf { it.length == 8 },
            subtitles = runCatching { parseSubtitles(json) }.getOrDefault(emptyList()),
            coverUrls = runCatching { coverCandidates(json) }.getOrDefault(emptyList()),
            infoJsonPath = infoFile?.absolutePath,
            url = info.webpageUrl ?: url,
            id = info.id ?: url.hashCode().toString(),
            title = info.title ?: info.fulltitle ?: "Без названия",
            uploader = info.uploader,
            durationSec = duration,
            thumbnail = info.thumbnail,
            site = info.extractorKey ?: info.extractor ?: "",
            options = buildOptions(info.formats.orEmpty(), duration),
        )
    }

    /** Прогресс загрузки: процент (-1 — неизвестно), секунд до конца, скорость в байтах/с, идёт ли постобработка. */
    data class Progress(val percent: Float, val etaSec: Long, val bytesPerSec: Long, val processing: Boolean)

    /**
     * Скачать в [outDir]. Возвращает итоговый файл.
     * Разбор страницы берётся сохранённый при поиске, если он есть (см. MediaInfo.infoJsonPath).
     */
    suspend fun download(
        taskId: String,
        info: MediaInfo,
        option: FormatOption,
        section: ClipSection?,
        cookies: File?,
        outDir: File,
        onProgress: (Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        useEngine { downloadNow(taskId, info, option, section, cookies, outDir, onProgress) }
    }

    private fun downloadNow(
        taskId: String,
        info: MediaInfo,
        option: FormatOption,
        section: ClipSection?,
        cookies: File?,
        outDir: File,
        onProgress: (Progress) -> Unit,
    ): File {
        outDir.mkdirs()
        // YouTube тормозит одно длинное соединение ffmpeg (~0,1 МБ/с), а кусками отдаёт в десятки раз быстрее.
        // Поэтому его фрагмент качаем целиком и вырезаем отрезок уже на телефоне (см. cutsLocally)
        if (section != null && cutsLocally(info)) {
            val whole = downloadNow(taskId, info, option, null, cookies, outDir, onProgress)
            onProgress(Progress(-1f, 0, -1, processing = true))
            return cutWithFfmpeg(taskId, whole, section)
        }
        val savedInfo = info.infoJsonPath?.let(::File)?.takeIf { it.exists() }
        // Фрагмент по сети вырезает ffmpeg прямо из потока — ему нужны исходные ссылки, без нарезки на куски
        val infoJson = if (section != null) savedInfo else savedInfo?.let { runCatching { chunkYoutube(it) }.getOrNull() ?: it }
        val chunked = infoJson != null && infoJson != savedInfo
        if (chunked) Diag.log(TAG, "youtube: ${YT_CHUNK shr 20} MB chunks x$YT_PARALLEL")
        // С --load-info-json ссылку не передаём: иначе yt-dlp разберёт страницу ещё раз
        val req = (if (infoJson != null) YoutubeDLRequest(emptyList()) else YoutubeDLRequest(info.url))
            .addOption("--no-playlist")
            // Каждое обновление прогресса — отдельной строкой: без этого yt-dlp пишет его через \r,
            // библиотека ждёт перевода строки, и процент в приложение не доходит
            .addOption("--newline")
            .addOption("--no-mtime")
            .addOption("--socket-timeout", 20)
            .addOption("--retries", 5)
            .addOption("--fragment-retries", 10)
            // Пропущенный кусок — битое видео; лучше честная ошибка
            .addOption("--abort-on-unavailable-fragments")
            .addOption("-N", if (chunked) YT_PARALLEL else 4)
            .addOption("-o", File(outDir, "%(title).120B.%(ext)s").absolutePath)
            .addOption("--write-thumbnail")
            .addOption("--convert-thumbnails", "jpg")
            .addOption("-o", "thumbnail:" + File(outDir, "thumb.%(ext)s").absolutePath)
        // Обычные файлы качает aria2c: 8 потоков по 1 МБ, «замёрзший» поток (<50 КБ/с) рвёт
        // и переоткрывает. Под ТСПУ одно соединение то и дело подвисает — так скорость не падает до нуля.
        // HLS-плейлисты и нарезанный YouTube (см. chunkYoutube) — встроенному загрузчику.
        // Фрагмент качает ffmpeg: он сам переходит к нужному месту и берёт только этот кусок
        if (section == null) {
            req.addOption("--downloader", "libaria2c.so")
                .addOption("--downloader", "m3u8:native")
                .addOption("--downloader", "dash:native")
                .addOption(
                    "--downloader-args",
                    "aria2c:-x 8 -s 8 -k 1M --lowest-speed-limit=50K --max-tries=0 --retry-wait=1 " +
                        "--timeout=15 --connect-timeout=10 --file-allocation=none --summary-interval=1",
                )
        } else {
            req.addOption("--download-sections", "*${section.startSec}-${section.endSec}")
            Diag.log(TAG, "section ${section.label}")
        }
        infoJson?.let { req.addOption("--load-info-json", it.absolutePath) }
        cookies?.let { req.addOption("--cookies", it.absolutePath) }
        when (option.kind) {
            FormatOption.Kind.VIDEO -> {
                // Если формат выбран заранее — берём именно его (со звуком отдельно или без),
                // иначе — сортировка: сначала высота, потом H.264 и mp4, их играют даже старые телефоны
                // Запасное «bv*+ba/b» — если страница разобрана заново и такого номера формата уже нет:
                // тогда решает -S
                option.formatId?.let { id -> req.addOption("-f", "$id+ba/$id/bv*+ba/b") }
                val res = option.height?.let { "res:$it," }.orEmpty()
                req.addOption("-S", "${res}vcodec:h264,ext:mp4:m4a")
                req.addOption("--merge-output-format", "mp4")
            }
            // У сайтов вроде Rutube нет отдельной звуковой дорожки — тогда берём видео со звуком, а -x
            // вырезает из него звук. Видео не выше 720p: звук там такой же, как в 1080p (проверено побайтово),
            // а качать меньше. Ниже 720p брать нельзя — у части роликов звук там хуже (70 кбит/с против 195)
            FormatOption.Kind.AUDIO_M4A -> {
                req.addOption("-f", "ba[ext=m4a]/ba/$AUDIO_FROM_VIDEO")
                req.addOption("-x").addOption("--audio-format", "m4a")
            }
            FormatOption.Kind.AUDIO_MP3 -> {
                req.addOption("-f", "ba/$AUDIO_FROM_VIDEO")
                req.addOption("-x").addOption("--audio-format", "mp3").addOption("--audio-quality", 2)
            }
        }
        // Прогресс от aria2c yt-dlp печатает в stderr, а библиотека разбирает только stdout —
        // поэтому сливаем потоки. Тогда и текст ошибки приходит в общий поток: храним хвост сами
        val tail = ArrayDeque<String>()
        try {
            YoutubeDL.getInstance().execute(req, taskId, redirectErrorStream = true) { progress, eta, line ->
                if (line.isNotBlank()) {
                    if (tail.size >= 30) tail.removeFirst()
                    tail.addLast(line)
                }
                val post = line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]") ||
                    line.startsWith("[VideoConvertor]") || line.startsWith("[FixupM")
                onProgress(Progress(progress, eta, parseSpeed(line), post))
            }
        } catch (e: YoutubeDL.CanceledException) {
            throw e
        } catch (e: YoutubeDLException) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: tail.joinToString("\n")
            throw translate(YoutubeDLException(message))
        }
        return outDir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith("thumb.") && !it.name.endsWith(".part") }
            ?.maxByOrNull { it.length() }
            ?: throw EngineException("Файл не найден после загрузки", network = false)
    }

    /**
     * Фрагмент вырезаем из скачанного целиком ролика, а не тянем по сети через ffmpeg: так у YouTube
     * (его файлы режутся на куски, см. chunkYoutube). У остальных сайтов видео и так идёт короткими
     * сегментами HLS, и ffmpeg качает отрезок быстро.
     */
    fun cutsLocally(info: MediaInfo): Boolean =
        info.site.equals("Youtube", ignoreCase = true) && info.infoJsonPath?.let { File(it).exists() } == true

    /**
     * Встроенный ffmpeg — тот же, что вызывает yt-dlp, с теми же библиотеками
     * (LD_LIBRARY_PATH как у youtubedl-android).
     */
    private fun ffmpeg(vararg args: String): ProcessBuilder {
        val bin = File(appContext.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val packages = File(appContext.noBackupFilesDir, "youtubedl-android/packages")
        return ProcessBuilder(listOf(bin.absolutePath) + args).redirectErrorStream(true).apply {
            environment()["LD_LIBRARY_PATH"] =
                listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(packages, "$it/usr/lib").absolutePath }
        }
    }

    /** Длительность видео по ссылке: ffmpeg читает только заголовок файла. null — не вышло за 15 с. */
    private fun probeDuration(format: VideoFormat): Int? = runCatching {
        val url = format.url?.takeIf { it.startsWith("http") } ?: return null
        val headers = format.httpHeaders.orEmpty().entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
        val args = buildList {
            add("-hide_banner")
            if (headers.isNotEmpty()) { add("-headers"); add(headers) }
            add("-i"); add(url)
        }
        val process = ffmpeg(*args.toTypedArray()).start()
        // Вывод «ffmpeg -i» короткий и не забьёт канал, поэтому можно сначала дождаться конца
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroy()
            return null
        }
        val out = process.inputStream.bufferedReader().readText()
        Regex("""Duration: (\d+):(\d{2}):(\d{2}(?:\.\d+)?)""").find(out)?.destructured?.let { (h, m, s) ->
            (h.toInt() * 3600 + m.toInt() * 60 + s.toDouble()).roundToInt()
        }?.takeIf { it > 0 }?.also { Diag.log(TAG, "duration from ffmpeg: ${it}s") }
    }.getOrNull()

    /** Идущие нарезки — чтобы «Отменить» останавливал и их, а не только yt-dlp. */
    private val cutProcesses = ConcurrentHashMap<String, Process>()
    private val cancelledCuts = ConcurrentHashMap.newKeySet<String>()

    /**
     * Вырезать отрезок из готового файла встроенным ffmpeg — тем же, что вызывает yt-dlp, с теми же
     * библиотеками (LD_LIBRARY_PATH как у youtubedl-android). Без перекодирования, поэтому за секунды;
     * начало прилипает к ближайшему ключевому кадру.
     */
    private fun cutWithFfmpeg(taskId: String, source: File, section: ClipSection): File {
        val target = File(source.parentFile, "cut.${source.extension}")
        Diag.log(TAG, "cutting ${section.label} from ${source.length() / 1024} KB")
        val process = ffmpeg(
            "-y", "-hide_banner", "-loglevel", "error",
            "-ss", section.startSec.toString(), "-i", source.absolutePath, "-t", section.lengthSec.toString(),
            "-map", "0", "-c", "copy", "-avoid_negative_ts", "make_zero",
            target.absolutePath,
        ).start()
        cutProcesses[taskId] = process
        val output = try {
            process.inputStream.bufferedReader().readText().also { process.waitFor() }
        } finally {
            cutProcesses.remove(taskId)
        }
        if (cancelledCuts.remove(taskId)) throw YoutubeDL.CanceledException()
        if (process.exitValue() != 0 || !target.exists() || target.length() == 0L) {
            Diag.log(TAG, "ffmpeg cut failed (${process.exitValue()}): ${output.takeLast(500)}")
            throw EngineException("Не получилось вырезать фрагмент", network = false)
        }
        // Итоговый файл — под именем ролика, а не «cut»
        source.delete()
        return File(source.parentFile, source.name).also { target.renameTo(it) }
    }


    // aria2c: «DL:2.1MiB», yt-dlp: «at 2.10MiB/s»
    private val speedRegex = Regex("""(?:DL:|\sat\s+)([\d.]+)([KMG]i)?B""")

    private fun parseSpeed(line: String): Long {
        val m = speedRegex.find(line) ?: return -1
        val value = m.groupValues[1].toDoubleOrNull() ?: return -1
        val mult = when (m.groupValues[2]) { "Ki" -> 1024.0; "Mi" -> 1024.0 * 1024; "Gi" -> 1024.0 * 1024 * 1024; else -> 1.0 }
        return (value * mult).toLong()
    }

    fun cancel(taskId: String) {
        YoutubeDL.getInstance().destroyProcessById(taskId)
        cutProcesses[taskId]?.let { cancelledCuts += taskId; it.destroy() }
    }

    private fun buildOptions(formats: List<VideoFormat>, durationSec: Int): List<FormatOption> {
        val audio = formats.filter { it.vcodec == "none" && it.acodec != null && it.acodec != "none" }
        val bestAudio = audio.filter { it.ext == "m4a" }.maxByOrNull { it.abr }
            ?: audio.maxByOrNull { it.abr }
        val audioBytes = bestAudio?.let { sizeOf(it, durationSec) } ?: 0L

        val video = formats.filter { it.height > 0 && it.vcodec != null && it.vcodec != "none" }
        val maxHeight = video.maxOfOrNull { shortSide(it) } ?: 0

        val videoOptions = if (maxHeight == 0) {
            listOf(FormatOption(FormatOption.Kind.VIDEO, null))
        } else {
            heightsLadder.filter { it <= maxHeight }.ifEmpty { listOf(maxHeight) }.map { h ->
                val candidates = video.filter { shortSide(it) in (h * 3 / 4 + 1)..h }
                // Сначала обычный файл (его aria2c качает в 8 потоков), а не HLS-плейлист; среди них — H.264
                val pick = candidates.maxWithOrNull(
                    compareBy<VideoFormat>(
                        { it.manifestUrl == null },
                        { it.vcodec?.startsWith("avc") == true },
                        { it.tbr },
                    )
                )
                val hasAudio = pick?.acodec != null && pick.acodec != "none"
                val bytes = pick?.let { sizeOf(it, durationSec) + if (hasAudio) 0 else audioBytes } ?: 0
                FormatOption(
                    FormatOption.Kind.VIDEO, h, bytes, pick?.formatId,
                    frameWidth = pick?.width ?: 0, frameHeight = pick?.height ?: 0,
                    fps = pick?.fps ?: 0, codec = pick?.vcodec?.let(::codecName),
                )
            }
        }
        val audioOptions = if (audio.isNotEmpty() || maxHeight > 0) listOf(
            FormatOption(FormatOption.Kind.AUDIO_M4A, approxBytes = audioBytes),
            // --audio-quality 2 даёт VBR около 190 кбит/с
            FormatOption(FormatOption.Kind.AUDIO_MP3, approxBytes = 190_000L / 8 * durationSec),
        ) else emptyList()
        return videoOptions + audioOptions
    }

    /** Понятное имя кодека: H.264 открывается в любом редакторе, VP9/AV1 — не везде. */
    private fun codecName(vcodec: String): String? = when {
        vcodec.startsWith("avc") || vcodec.startsWith("h264") -> "H.264"
        vcodec.startsWith("hev") || vcodec.startsWith("hvc") || vcodec.startsWith("h265") -> "H.265"
        vcodec.startsWith("vp9") || vcodec.startsWith("vp09") -> "VP9"
        vcodec.startsWith("av01") || vcodec.startsWith("av1") -> "AV1"
        else -> null
    }

    /**
     * Обложки от лучшей к худшей. yt-dlp перечисляет их от худшей к лучшей, а размер указывает не у всех
     * (у YouTube у maxresdefault его нет) — поэтому идём с конца списка. JPEG вперёд WebP той же картинки.
     */
    private fun coverCandidates(json: String): List<String> =
        YoutubeDL.objectMapper.readTree(json).path("thumbnails").toList()
            .map { it.path("url").asText() }
            .filter { it.startsWith("http") }
            .reversed()
            .let { all -> all.filterNot { ".webp" in it }.take(4) + all.filter { ".webp" in it }.take(1) }

    /**
     * Субтитры от автора из ответа yt-dlp («subtitles», не «automatic_captions»): по одному файлу
     * на язык, в формате, который умеем сохранить как .srt (srt или vtt).
     */
    private fun parseSubtitles(json: String): List<Subtitle> {
        val subs = YoutubeDL.objectMapper.readTree(json).path("subtitles")
        val result = mutableListOf<Subtitle>()
        subs.fieldNames().forEach { lang ->
            if (lang == "live_chat") return@forEach
            val files = subs.path(lang).toList()
            val file = files.firstOrNull { it.path("ext").asText() == "srt" }
                ?: files.firstOrNull { it.path("ext").asText() == "vtt" }
                ?: return@forEach
            val url = file.path("url").asText().takeIf { it.startsWith("http") } ?: return@forEach
            result += Subtitle(lang, file.path("name").asText(lang), url, file.path("ext").asText())
        }
        // Сначала русский и английский — их чаще всего и ищут
        return result.sortedBy { s -> listOf("ru", "en").indexOfFirst { s.lang.startsWith(it) }.let { if (it < 0) 9 else it } }
    }

    /**
     * «1080p» — это короткая сторона кадра: у вертикального 1080×1920 тоже 1080p.
     * Так же считает yt-dlp в -S res:N, поэтому выбор и подпись совпадают.
     */
    private fun shortSide(f: VideoFormat): Int =
        if (f.width > 0) minOf(f.width, f.height) else f.height

    /** У HLS-потоков YouTube размера нет — тогда считаем по битрейту (кбит/с) и длительности. */
    private fun sizeOf(f: VideoFormat, durationSec: Int): Long = when {
        f.fileSize > 0 -> f.fileSize
        f.fileSizeApproximate > 0 -> f.fileSizeApproximate
        else -> f.tbr.toLong() * 1000 / 8 * durationSec
    }

    /** Перевести ошибку yt-dlp в понятную человеку. */
    private fun translate(e: YoutubeDLException): EngineException {
        val m = e.message.orEmpty()
        Diag.log(TAG, "yt-dlp error: " + m.lines().filter { it.isNotBlank() }.takeLast(6).joinToString(" | ").take(1500))
        fun has(vararg s: String) = s.any { m.contains(it, ignoreCase = true) }
        return when {
            // OK отправляет гостей на страницу входа — yt-dlp видит её как «Unsupported URL»
            has("st.cmd=anonymMain") ->
                EngineException("Это видео доступно только после входа в аккаунт", network = false, e, needsLogin = true)
            has("Unsupported URL") ->
                EngineException("Эта ссылка не поддерживается", network = false, e)
            has("restricted by the community", "access to this clip has been restricted", "access to this video has been restricted") ->
                EngineException("Автор ограничил доступ к этому видео — скачать его нельзя", network = false, e)
            has("Private video", "This video is private") ->
                EngineException("Видео закрыто автором", network = false, e)
            has("Video unavailable", "This video is not available", "has been removed") ->
                EngineException("Видео недоступно или удалено", network = false, e)
            has("confirm your age", "age-restricted", "Sign in to confirm your age") ->
                EngineException("Видео 18+: YouTube не отдаёт его без входа в аккаунт", network = false, e)
            has("Sign in to confirm you", "not a bot") ->
                EngineException("YouTube принял нас за бота. Попробуйте через пару минут", network = true, e)
            // «Доступ ограничен» — так VK отвечает на видео с закрытой страницы, если для него мы гость
            has("login required", "rate-limit reached", "Requested content is not available",
                "only available to signed-in users", "available for registered users",
                "Доступ ограничен", "Access denied", "Access restricted") ->
                EngineException("Это видео доступно только после входа в аккаунт", network = false, e, needsLogin = true)
            has("HTTP Error 403", "Forbidden") ->
                EngineException("Сайт отказал в доступе. Попробуйте ещё раз", network = true, e)
            has("timed out", "Connection refused", "Connection reset", "Network is unreachable",
                "Unable to connect", "Failed to establish", "Temporary failure in name resolution",
                "Name or service not known", "ProxyError", "SOCKS", "Read timed out") ->
                EngineException("Нет соединения с сайтом", network = true, e)
            else -> EngineException("Не получилось скачать. Попробуйте ещё раз", network = true, e)
        }
    }
}
