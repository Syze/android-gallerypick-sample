package co.getswan.gallerysample

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import java.io.ByteArrayOutputStream
import java.util.Calendar

/** Photos served to the page: the window's assets, thumbnails, originals and captures.
 *  Mirrors `GalleryPhotos.swift`. The id the page sees is the MediaStore row id as text. */
object GalleryPhotos {

    data class Asset(val id: String, val uri: Uri, val takenAt: Long, val mime: String, val bytes: Long, val width: Int, val height: Int, val favourite: Boolean, val modified: Long, val file: String)
    data class Bytes(val data: ByteArray, val mime: String)

    /** Why an original cannot be sent; the page counts the photo as failed. Same names as iOS. */
    enum class Unavailable(val reason: String) { TOO_LARGE("tooLarge"), UNREADABLE("unreadable") }

    private fun uriFor(id: String): Uri? =
        id.toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }

    /** Every image in the last `months`, newest first, screenshots left out. Under partial access
     *  (Android 14+) the query already sees only the chosen photos, so no date window is applied. */
    fun assetsInWindow(context: Context, months: Int, limited: Boolean): Pair<List<Asset>, Int> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.DISPLAY_NAME,
        ) + (if (Build.VERSION.SDK_INT >= 30) arrayOf(MediaStore.Images.Media.IS_FAVORITE) else emptyArray())
        val from = Calendar.getInstance().apply { add(Calendar.MONTH, -months) }.timeInMillis
        // DATE_TAKEN is missing on some rows; DATE_ADDED (seconds) stands in for those.
        val window = "((${MediaStore.Images.Media.DATE_TAKEN} >= ?) OR (${MediaStore.Images.Media.DATE_TAKEN} IS NULL AND ${MediaStore.Images.Media.DATE_ADDED} >= ?))"
        val folder = GallerySettings.evalFolder?.let { "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" }
        val selection = listOfNotNull(if (limited) null else window, folder).joinToString(" AND ").ifEmpty { null }
        val args = (if (limited) emptyList() else listOf(from.toString(), (from / 1000).toString())).plus(listOfNotNull(GallerySettings.evalFolder?.let { "$it%" })).toTypedArray().ifEmpty { null }
        val assets = ArrayList<Asset>()
        var screenshots = 0
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val favCol = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Images.Media.IS_FAVORITE) else -1
            val modCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                // No screenshot flag in MediaStore: the standard folder name is the tell.
                if (c.getString(pathCol)?.contains("Screenshots", ignoreCase = true) == true) { screenshots++; continue }
                val id = c.getLong(idCol)
                val takenAt = c.getLong(takenCol).takeIf { it > 0 } ?: (c.getLong(addedCol) * 1000L)
                assets.add(Asset(id.toString(), ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    takenAt, c.getString(mimeCol) ?: "image/jpeg", c.getLong(sizeCol), c.getInt(wCol), c.getInt(hCol), favCol >= 0 && c.getInt(favCol) == 1, c.getLong(modCol), c.getString(nameCol) ?: ""))
            }
        }
        return assets to screenshots
    }

    // MARK: thumbnails

    /** JPEG bytes for a library id or a capture id, longest side `maxPixel`, aspect kept. */
    fun jpeg(context: Context, id: String, maxPixel: Int, quality: Int): ByteArray? {
        CaptureStore.data(id)?.let { return render(it, maxPixel, quality) }
        val uri = uriFor(id) ?: return null
        return runCatching {
            val bitmap = if (maxPixel <= GallerySettings.gridPixels && !GallerySettings.gridThumbnailsViaDecoder)
                context.contentResolver.loadThumbnail(uri, Size(maxPixel, maxPixel), null)
            else decodeFit(ImageDecoder.createSource(context.contentResolver, uri), maxPixel)
            encode(bitmap, quality)
        }.getOrNull()
    }

    /** A held capture re-rendered at `maxPixel`; the bytes as sent when already small enough. */
    private fun render(data: ByteArray, maxPixel: Int, quality: Int): ByteArray? = runCatching {
        encode(decodeFit(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(data)), maxPixel), quality)
    }.getOrNull()

    /** Decodes with EXIF orientation applied and the longest side at most `maxPixel`. */
    private fun decodeFit(source: ImageDecoder.Source, maxPixel: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width; val h = info.size.height
            val scale = minOf(1f, maxPixel.toFloat() / maxOf(w, h))
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    // MARK: originals

    /** The original file for upload, never re-encoded, up to `originalByteCap`. */
    fun original(context: Context, id: String): Result<Bytes> {
        val uri = uriFor(id) ?: return Result.failure(IllegalArgumentException(Unavailable.UNREADABLE.reason))
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > GallerySettings.originalByteCap) throw IllegalStateException(Unavailable.TOO_LARGE.reason)
                }
                Bytes(out.toByteArray(), mime)
            } ?: throw IllegalStateException(Unavailable.UNREADABLE.reason)
        }
    }

    fun dataURI(bytes: ByteArray, mime: String) = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** The bytes of a `data:image/...;base64,` URI, or null when it is not one. */
    fun bytesOfDataURI(dataURI: String): ByteArray? {
        val comma = dataURI.indexOf(','); if (comma < 0 || !dataURI.startsWith("data:")) return null
        return runCatching { Base64.decode(dataURI.substring(comma + 1), Base64.DEFAULT) }.getOrNull()
    }

    /** Photos taken inside the flow, held as JPEG bytes by id; never written to the library. */
    object CaptureStore {
        private val held = HashMap<String, ByteArray>()
        /** False when full: refusing beats evicting a photo still on screen. */
        @Synchronized fun store(id: String, data: ByteArray): Boolean {
            if (held[id] == null && held.size >= GallerySettings.captureLimit) return false
            held[id] = data; return true
        }
        @Synchronized fun data(id: String): ByteArray? = held[id]
        @Synchronized fun removeAll() = held.clear()
    }

    /** What the page learned about one photo, kept so a tap answers at once: from the scan, or from
     *  `GalleryScan.analyseCapture` for a camera shot. */
    data class Analysis(val type: String, val isFullBody: Boolean, val lowerJoints: Int = if (isFullBody) 4 else 0, val otherPeople: Boolean = false)
    object AnalysisStore {
        private val held = HashMap<String, Analysis>()
        @Synchronized fun store(id: String, analysis: Analysis) { held[id] = analysis }
        @Synchronized fun get(id: String): Analysis? = held[id]
        @Synchronized fun removeAll() = held.clear()
    }

    /** The permission the photo scan needs on this Android version. */
    val readPermission: String =
        if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
}
