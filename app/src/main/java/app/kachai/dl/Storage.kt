package app.kachai.dl

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Перенос готового файла в общую папку «Загрузки/Качай». */
object Storage {
    const val FOLDER = "Качай"

    fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "opus", "ogg" -> "audio/ogg"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
    }

    suspend fun publish(context: Context, file: File): Uri = withContext(Dispatchers.IO) {
        val mime = mimeOf(file)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
            dir.mkdirs()
            val target = uniqueFile(dir, file.name)
            file.copyTo(target)
            scan(context, target, mime)
        }
    }

    fun delete(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        while (f.exists()) {
            f = File(dir, "${name.substringBeforeLast('.')} ($i).${name.substringAfterLast('.')}")
            i++
        }
        return f
    }

    private suspend fun scan(context: Context, file: File, mime: String): Uri =
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime)) { _, uri ->
                cont.resume(uri ?: Uri.fromFile(file))
            }
        }
}
