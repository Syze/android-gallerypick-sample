package co.getswan.gallerysample

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** Signs a matched photo is a document rather than a photo of the person: printed text, and how
 *  much of it sits around the face. Stands in for iOS's classifier labels and page detector
 *  (`MuseScore.DocumentRead`); the gate is `GallerySettings.documentGateEnabled`. */
data class DocumentRead(val lines: Int, val nearFace: Int, val textArea: Float, val faceShare: Float = 0f) {
    /** An ID card or a page: enough printed lines with several around the face, or a few lines
     *  large enough to cover a real share of the frame. */
    val isDocument: Boolean get() = faceShare <= GallerySettings.documentMaxFaceShare &&
        ((lines >= GallerySettings.documentMinLines && nearFace >= GallerySettings.documentNearFaceLines) ||
        (lines >= GallerySettings.documentAreaMinLines && textArea >= GallerySettings.documentMinTextArea))

    companion object {
        private const val TAG = "swan"
        private val recognizer = Reloadable { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
        internal fun client() = recognizer.get()!!
        fun release() = recognizer.release()

        /** Reads the render; `face` is the face box normalised 0-1. Null when recognition fails. */
        fun read(bitmap: Bitmap, face: RectF): DocumentRead? {
            val text = runCatching { Tasks.await(client().process(InputImage.fromBitmap(bitmap, 0))) }
                .onFailure { Log.e(TAG, "text recognition failed: ${it.message}") }.getOrNull() ?: return null
            val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
            // The zone around the face: the box scaled about its centre.
            val s = GallerySettings.documentNearFaceScale
            val zone = RectF(face.centerX() - face.width() * s / 2, face.centerY() - face.height() * s / 2,
                face.centerX() + face.width() * s / 2, face.centerY() + face.height() * s / 2)
            var lines = 0; var near = 0; var area = 0f
            for (block in text.textBlocks) for (line in block.lines) {
                val b = line.boundingBox ?: continue
                // Short fragments (a logo, a date stamp) do not make a document.
                if (line.text.trim().length < 3) continue
                lines++
                val r = RectF(b.left / w, b.top / h, b.right / w, b.bottom / h)
                area += r.width() * r.height()
                if (RectF.intersects(r, zone)) near++
            }
            return DocumentRead(lines, near, area, faceShare = face.width())
        }
    }
}
