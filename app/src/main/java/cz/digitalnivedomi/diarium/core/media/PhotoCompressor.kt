package cz.digitalnivedomi.diarium.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a picked or captured photo into the JPEG that the storage bucket accepts.
 *
 * Two limits in Supabase make the raw bytes unusable as-is:
 *  - `diary-photos` refuses an object above 5 MB (HTTP 413 `EntityTooLarge`). A
 *    full-resolution camera frame is routinely 4–7 MB, so the upload failed and
 *    `entries.photo_path` stayed null — the form fell back to „Zatím žádná fotka.“.
 *  - the bucket only allows jpeg/png/webp/gif. A gallery pick can be HEIC, which
 *    is rejected by mime type on some paths and unreadable in the web app on others.
 *
 * Decoding once and re-encoding at [MAX_DIMENSION] px / quality [QUALITY] fixes
 * both: a normal photo lands at 150–600 kB. The EXIF rotation is applied to the
 * pixels before the re-encode, because a re-encoded JPEG carries no orientation tag.
 */
object PhotoCompressor {

    /** Longest edge kept in the uploaded JPEG. */
    const val MAX_DIMENSION = 1600

    /** JPEG quality of the re-encode. 85 keeps detail without a visible loss. */
    const val QUALITY = 85

    /** Hard ceiling for one upload — the bucket limit is 5 MB. */
    const val MAX_BYTES = 4_500_000

    /**
     * Returns JPEG bytes for [uri], or null when the source cannot be read or
     * decoded (a cloud-only gallery item with no local bytes, a corrupt file, an
     * unsupported container such as HEIC below API 28).
     */
    fun compress(context: Context, uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        withStream(context, uri) { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        }
        val decoded = withStream(context, uri) { BitmapFactory.decodeStream(it, null, options) }
            ?: return null

        val scaled = scaleToFit(decoded)
        val oriented = applyOrientation(scaled, readOrientation(context, uri))

        // A tall photo at 1600 px is still far below the ceiling, but a pathological
        // source (a huge PNG screenshot) can overshoot. Drop the quality instead of
        // failing the upload.
        var quality = QUALITY
        var bytes = encode(oriented, quality)
        while (bytes.size > MAX_BYTES && quality > 40) {
            quality -= 15
            bytes = encode(oriented, quality)
        }
        return bytes.takeIf { it.isNotEmpty() }
    }

    /**
     * Largest power-of-two subsample that still leaves more than [maxDimension]
     * pixels on the long edge, so the decode never allocates the full frame.
     */
    internal fun sampleSize(width: Int, height: Int, maxDimension: Int = MAX_DIMENSION): Int {
        if (maxDimension <= 0) return 1
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxDimension) sample *= 2
        return sample
    }

    private fun scaleToFit(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) return bitmap
        val ratio = MAX_DIMENSION.toFloat() / longest
        val width = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    @Suppress("DEPRECATION")
    private fun readOrientation(context: Context, uri: Uri): Int =
        withStream(context, uri) {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

    @Suppress("DEPRECATION")
    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun <T> withStream(context: Context, uri: Uri, block: (InputStream) -> T): T? =
        try {
            context.contentResolver.openInputStream(uri)?.use(block)
        } catch (_: Exception) {
            null
        }
}
