package co.getswan.gallerysample

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/** The original file's camera metadata, for the selfie gates. Mirrors `Original.camera(for:)`.
 *  Android has no maker-note gravity, so `tiltUp` is always null and the head-level gate never fires. */
object Original {
    data class Camera(val present: Boolean, val iso: Float?, val focal35mm: Float?, val focalMm: Float?, val exposure: Float?, val tiltUp: Float?)

    /** Null when the file cannot be read. `present` is false for a photo saved by an app with no camera data. */
    fun camera(context: Context, uri: Uri): Camera? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            val make = exif.getAttribute(ExifInterface.TAG_MAKE); val model = exif.getAttribute(ExifInterface.TAG_MODEL)
            val focalMm = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.toFloat()
            val focal35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 }?.toFloat()
            val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }?.toFloat()
            val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.toFloat()
            Camera(present = !make.isNullOrBlank() || !model.isNullOrBlank() || focalMm != null, iso = iso, focal35mm = focal35, focalMm = focalMm, exposure = exposure, tiltUp = null)
        }
    }.getOrNull()
}
