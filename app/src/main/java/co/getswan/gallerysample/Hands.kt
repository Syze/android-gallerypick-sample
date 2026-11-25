package co.getswan.gallerysample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

/** Hands over or near a matched selfie's face, from MediaPipe's hand landmarker (21 points per hand,
 *  the joint set Vision's hand pose gives on iOS). Same rule as `museContext`: a hand with
 *  `handPointsOnFace` points inside the padded face box is over the face; inside the box grown by
 *  `handNearFaceScale`, near it. */
object Hands {
    private const val TAG = "swan"
    private const val MODEL = "FaceModels/hand_landmarker.task"
    data class Read(val onFace: Boolean, val nearFace: Boolean, val hands: Int)

    private lateinit var appContext: Context
    fun init(context: Context) { appContext = context.applicationContext }
    fun release() = landmarker.release()

    private val landmarker = Reloadable<HandLandmarker> {
        runCatching {
            HandLandmarker.createFromOptions(appContext, HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .setRunningMode(RunningMode.IMAGE).setNumHands(2).build())
        }.onFailure { Log.e(TAG, "Could not load the hand landmarker: ${it.message}") }.getOrNull()
    }

    /** `face` is the face box normalised to `bitmap`. The detector runs on a crop around the face. */
    fun read(bitmap: Bitmap, face: RectF): Read? {
        val l = landmarker.get() ?: return null
        val S = GallerySettings
        // The zone: the face box grown by 15%; the crop around it, wide enough to see a whole hand at its edge.
        val zone = RectF(face).also { it.inset(-face.width() * 0.15f, -face.height() * 0.15f) }
        val scale = if (S.handNearFaceDrops) S.handsNearCropScale else S.handsCropScale
        val region = RectF(zone.centerX() - zone.width() * scale / 2, zone.centerY() - zone.height() * scale / 2,
            zone.centerX() + zone.width() * scale / 2, zone.centerY() + zone.height() * scale / 2)
        region.intersect(0f, 0f, 1f, 1f)
        val whole = region.width() >= 0.9f && region.height() >= 0.9f
        val crop = if (whole) bitmap else (bitmap.crop(region) ?: return null)
        val result = runCatching { l.detect(BitmapImageBuilder(crop).build()) }.getOrNull() ?: return null
        // Each hand's points back in the photo's normalised space.
        val hands = result.landmarks().map { pts -> pts.map { p ->
            if (whole) p.x() to p.y() else (region.left + p.x() * region.width()) to (region.top + p.y() * region.height())
        } }
        fun handIn(area: RectF) = hands.any { pts -> pts.count { area.contains(it.first, it.second) } >= S.handPointsOnFace }
        val grow = S.handNearFaceScale - 1
        val near = RectF(zone).also { it.inset(-zone.width() * grow / 2, -zone.height() * grow / 2) }
        return Read(onFace = handIn(zone), nearFace = S.handNearFaceDrops && handIn(near), hands = hands.size)
    }
}
