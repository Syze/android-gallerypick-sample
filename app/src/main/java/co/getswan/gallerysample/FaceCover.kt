package co.getswan.gallerysample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import kotlin.math.max
import kotlin.math.min

/** What covers a matched selfie's face, from MediaPipe's multiclass selfie segmenter on the head
 *  crop. Mirrors `FaceCover.swift`: the same model, bands and class rules. */
data class FaceCover(val hairline: Float, val jaw: Float, val face: Float, var skinSideRatio: Float? = null) {
    companion object {
        private const val TAG = "swan"
        private const val MODEL = "FaceModels/selfie_multiclass_256x256.tflite"
        private const val INPUT_PIXELS = 256
        // The segmenter's classes, in the model's order.
        private const val HAIR = 1; private const val BODY_SKIN = 2; private const val FACE_SKIN = 3; private const val CLOTHES = 4; private const val ACCESSORIES = 5

        private lateinit var appContext: Context
        fun init(context: Context) { appContext = context.applicationContext }
        fun release() = segmenter.release()
        /** The same segmenter for the body score (`GallerySettings.shareSelfieSegmenter`): one copy of the model, not two. */
        internal fun shared(): ImageSegmenter? = segmenter.get()

        private val segmenter = Reloadable<ImageSegmenter> {
            runCatching {
                ImageSegmenter.createFromOptions(appContext, ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setOutputCategoryMask(true)
                    .setOutputConfidenceMasks(false)
                    .build())
            }.onFailure { Log.e(TAG, "Could not load the selfie segmenter: ${it.message}") }.getOrNull()
        }

        /** Reads the head crop. `face` is the face box in crop pixels; `landmarks` (0-1 on the crop) only for `skinSideRatio`. */
        fun read(headCrop: Bitmap, face: RectF, landmarks: List<PointF>? = null): FaceCover? {
            val s = segmenter.get() ?: return null
            val longest = max(headCrop.width, headCrop.height)
            val scale = if (longest > INPUT_PIXELS) INPUT_PIXELS.toFloat() / longest else 1f
            val small = if (scale < 1f) Bitmap.createScaledBitmap(headCrop, max(1, (headCrop.width * scale).toInt()), max(1, (headCrop.height * scale).toInt()), true) else headCrop
            val result = runCatching { s.segment(BitmapImageBuilder(small).build()) }.getOrNull() ?: return null
            val mask = result.categoryMask().orElse(null) ?: return null
            val mw = mask.width; val mh = mask.height
            if (mw <= 0 || mh <= 0) return null
            val data = ByteBufferExtractor.extract(mask)
            val sx = mw.toFloat() / small.width; val sy = mh.toFloat() / small.height
            fun cls(x: Int, y: Int) = data.get(y * mw + x).toInt() and 255
            val box = RectF(face.left * scale, face.top * scale, face.right * scale, face.bottom * scale)

            fun share(rect: RectF, oval: Boolean = false, counting: (Int) -> Boolean): Float {
                val x0 = max(0, (rect.left * sx).toInt()); val x1 = min(mw, (rect.right * sx).toInt())
                val y0 = max(0, (rect.top * sy).toInt()); val y1 = min(mh, (rect.bottom * sy).toInt())
                if (x1 <= x0 || y1 <= y0) return 0f
                val cx = (x0 + x1) / 2f; val cy = (y0 + y1) / 2f; val rx = max(1f, (x1 - x0) / 2f); val ry = max(1f, (y1 - y0) / 2f)
                var total = 0; var hit = 0
                for (y in y0 until y1) for (x in x0 until x1) {
                    if (oval) { val dx = (x - cx) / rx; val dy = (y - cy) / ry; if (dx * dx + dy * dy > 1) continue }
                    total++; if (counting(cls(x, y))) hit++
                }
                return if (total == 0) 0f else hit.toFloat() / total
            }
            val covered = { c: Int -> c == CLOTHES || c == ACCESSORIES }
            val notFace = { c: Int -> c != FACE_SKIN && c != BODY_SKIN && c != HAIR }
            val h = box.height()
            val hairlineBand = RectF(box.left, box.top - 0.25f * h, box.right, box.top + 0.15f * h)
            val jawBand = RectF(box.left + 0.1f * box.width(), box.bottom - 0.25f * h, box.right - 0.1f * box.width(), box.bottom - 0.02f * h)
            val oval = RectF(box.left + box.width() * 0.1f, box.top + h * 0.05f, box.right - box.width() * 0.1f, box.bottom - h * 0.05f)
            val cover = FaceCover(share(hairlineBand, counting = covered), share(jawBand, counting = covered), share(oval, oval = true, counting = notFace))
            if (landmarks != null && landmarks.size >= 468) cover.readSkin(small, mw, mh, { x, y -> cls(x, y) }, landmarks)
            return cover
        }
    }

    /** Median skin brightness of the brighter half of the face over the darker, split brow (9) to chin (152). */
    private fun readSkin(image: Bitmap, mw: Int, mh: Int, cls: (Int, Int) -> Int, landmarks: List<PointF>) {
        val sx = mw.toFloat() / image.width; val sy = mh.toFloat() / image.height
        val top = PointF(landmarks[9].x * mw, landmarks[9].y * mh); val chin = PointF(landmarks[152].x * mw, landmarks[152].y * mh)
        val dx = chin.x - top.x; val dy = chin.y - top.y
        val pix = IntArray(image.width * image.height); image.getPixels(pix, 0, image.width, 0, 0, image.width, image.height)
        val a = ArrayList<Float>(); val b = ArrayList<Float>()
        for (y in 0 until mh) for (x in 0 until mw) {
            if (cls(x, y) != FACE_SKIN) continue
            val px = min(image.width - 1, (x / sx).toInt()); val py = min(image.height - 1, (y / sy).toInt())
            val c = pix[py * image.width + px]
            val bright = luma(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
            if ((x - top.x) * dy - (y - top.y) * dx > 0) a.add(bright) else b.add(bright)
        }
        if (a.size > 50 && b.size > 50) { val ma = median(a); val mb = median(b); skinSideRatio = max(ma, mb) / max(min(ma, mb), 0.01f) }
    }
}
