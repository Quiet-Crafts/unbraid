package app.unbraid.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/** A QR code from a photo the camera app takes into [file], or from a picture the user picks; [onPicked] gets its address, or null. */
class QrScan(val photo: () -> Unit, val picture: () -> Unit)

private fun cameraFile(context: Context) = File(context.cacheDir, "scan/qr.jpg")

/** Only one external camera launch is pending. Start it with a fresh inode, even after a process restart. */
fun qrPhoto(context: Context): File = cameraFile(context).apply {
    parentFile?.mkdirs()
    java.nio.file.Files.deleteIfExists(toPath())
    check(createNewFile())
}

@Composable
fun rememberQrScan(onPicked: (Uri?, InputStream?) -> Unit): QrScan {
    val context = LocalContext.current
    var cameraPending by rememberSaveable { mutableStateOf(false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraFile(context)
        // Detach the completed capture before the next launch can reuse its path. The recipient owns the handle.
        val photo = if (ok) runCatching { file.inputStream() }.getOrNull() else null
        file.delete()
        cameraPending = false
        onPicked(if (ok) Uri.fromFile(file) else null, photo)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { onPicked(it, null) }
    return QrScan(
        photo = {
            if (!cameraPending) runCatching {
                val file = qrPhoto(context)
                cameraPending = true
                camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.scan", file))
            }.onFailure {
                cameraFile(context).delete()
                cameraPending = false
                onPicked(Uri.EMPTY, null)
            }
        },
        picture = { runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.onFailure { onPicked(null, null) } },
    )
}

private const val MAX_BYTES = 32 * 1024 * 1024
private const val MAX_SIDE = 2000
// One scan across all forms: waiters suspend before they open the provider or allocate a snapshot.
internal val qrReadLock = kotlinx.coroutines.sync.Mutex()

/** Power-of-two sampling on a bounded source, accounting for codecs that round dimensions up. */
internal fun qrSample(width: Int, height: Int): Int? {
    if (width !in 1..16384 || height !in 1..16384 || width.toLong() * height > 210_000_000) return null
    var sample = 1
    while (maxOf(width, height) > MAX_SIDE * sample) sample *= 2
    return sample
}

internal fun qrPixelCount(width: Int, height: Int): Int? =
    if (width in 1..MAX_SIDE && height in 1..MAX_SIDE) (width.toLong() * height).toInt() else null

/** Both passes read the same private bytes. Unlink before writing so a killed process leaves no picture here. */
internal fun <T> qrSnapshot(cache: File, source: InputStream, limit: Int, read: (RandomAccessFile) -> T?): T? {
    val file = File.createTempFile("qr-snapshot-", null, cache)
    try {
        return RandomAccessFile(file, "rw").use { snapshot ->
            if (!file.delete()) return null
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = limit
            while (true) {
                val count = source.read(buf, 0, minOf(buf.size, remaining + 1))
                if (count < 0) break
                if (count == 0 || count > remaining) return null
                snapshot.write(buf, 0, count)
                remaining -= count
            }
            snapshot.seek(0)
            read(snapshot)
        }
    } finally { file.delete() }
}

/** The caller owns the input and holds [qrReadLock] for the entire read. */
internal fun decodeQr(context: Context, photo: InputStream): String? = try {
    qrSnapshot(context.cacheDir, photo, MAX_BYTES, ::decode)
} catch (_: Exception) { null }

/** A picker result. A camera URI by itself cannot reopen a newer capture at the staging path. */
fun decodeQr(context: Context, uri: Uri): String? = try {
    if (uri.scheme != "content") null else context.contentResolver.openInputStream(uri)?.use { decodeQr(context, it) }
} catch (_: Exception) { null }

private fun decode(snapshot: RandomAccessFile): String? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFileDescriptor(snapshot.fd, null, bounds)
    val sample = qrSample(bounds.outWidth, bounds.outHeight) ?: return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        inScaled = false
    }
    val bitmap = BitmapFactory.decodeFileDescriptor(snapshot.fd, null, options) ?: return null
    val source = try {
        val w = bitmap.width
        val h = bitmap.height
        val grey = ByteArray(qrPixelCount(w, h) ?: return null)
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in row.indices) {
                val rgb = row[x]
                val r = rgb ushr 16 and 255
                val g = rgb ushr 8 and 255
                val b = rgb and 255
                grey[y * w + x] = ((r + 2 * g + b) / 4).toByte()
            }
        }
        PlanarYUVLuminanceSource(grey, w, h, 0, 0, w, h, false)
    } finally { bitmap.recycle() }
    val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
    val reader = MultiFormatReader()
    return try { reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text } catch (_: ReaderException) {
        try { reader.decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text } catch (_: ReaderException) { null }
    }
}
