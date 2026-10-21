package app.unbraid.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.io.File

/** A QR code from a photo the camera app takes into [file], or from a picture the user picks; [onPicked] gets its address, or null. */
class QrScan(val photo: () -> Unit, val picture: () -> Unit)

/** Where the camera app writes the photo; deleted once read ([decodeQr]) or cancelled. */
fun qrPhoto(context: Context) = File(context.cacheDir, "scan/qr.jpg")

@Composable
fun rememberQrScan(onPicked: (Uri?) -> Unit): QrScan {
    val context = LocalContext.current
    val file = qrPhoto(context)
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) onPicked(Uri.fromFile(file)) else { file.delete(); onPicked(null) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(), onPicked)
    return QrScan(
        photo = {
            // The camera app writes into the app's cache through a one-off grant; no camera permission is involved.
            runCatching {
                file.parentFile?.mkdirs()
                camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.scan", file))
            }.onFailure { onPicked(null) }
        },
        picture = { runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.onFailure { onPicked(null) } },
    )
}

/** The first QR code in the picture, read at up to about 2000 pixels a side (a phone photo is far larger); blocks. The photo is deleted after. */
fun decodeQr(context: Context, uri: Uri): String? = try { decode(context, uri) } catch (e: Exception) { null } finally { qrPhoto(context).delete() }

private fun decode(context: Context, uri: Uri): String? {
    val open = { if (uri.scheme == "file") File(uri.path!!).inputStream() else context.contentResolver.openInputStream(uri)!! }
    val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { o -> open().use { BitmapFactory.decodeStream(it, null, o) } }
    var sample = 1
    while (maxOf(size.outWidth, size.outHeight) / sample > 2000) sample *= 2
    val bmp = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    val source = RGBLuminanceSource(bmp.width, bmp.height, px)
    val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
    return runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text }
        .recoverCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text }.getOrNull()
}
