package co.getswan.gallerysample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** How the person stands in a photo: joints seen, head to toe, the body's turn. Mirrors `BodyCheck`
 *  and `bodyCheck(in:)` with MediaPipe's pose landmarker in Vision's place. */
data class BodyCheck(
    /** Knees and ankles seen, 0-4. Sent to the page as `lowerBodyJoints`. */
    val lowerJoints: Int, val ankles: Int,
    /** Any of the nose, eyes or ears. */
    val headSeen: Boolean,
    /** Highest head point to lowest ankle, as a share of the image height. */
    val span: Float,
    /** Lowest ankle to the bottom edge, as a share of the image height; zero with no ankle. */
    val footRoom: Float,
    /** People the detector finds in the whole photo, faces or not. */
    val people: Int,
    /** Shoulder width across the photo over the torso's length; low when side-on. Null without the joints. */
    val shoulderSpread: Float?,
    /** The 33 pose landmarks of the kept body, normalised; empty when none. For the body score. */
    val landmarks: List<PointF> = emptyList(),
    val visibility: List<Float> = emptyList(),
    /** Knees seen, for the head-to-toe check. */
    val knees: Int = 0,
) {
    var shopperFaces = 1
    val hasOthers: Boolean get() = people > max(1, shopperFaces)
    /** Head to toe: head in frame, enough ankles, a body tall enough, room below the ankles for the feet. */
    val isHeadToToe: Boolean get() {
        if (GallerySettings.headToAnkleOnly) return headSeen && ankles >= GallerySettings.minAnkles && knees >= GallerySettings.gateKnees
        val needed = if (ankles >= 2) GallerySettings.minBodySpanBothAnkles else GallerySettings.minBodySpan
        return headSeen && ankles >= GallerySettings.minAnkles && span >= needed && footRoom >= GallerySettings.minFootRoom * span
    }

    companion object {
        fun unseen(people: Int) = BodyCheck(0, 0, false, 0f, 0f, people, null)
    }
}

object BodyPose {
    private const val TAG = "swan"
    private const val MODEL = "FaceModels/pose_landmarker_full.task"
    // MediaPipe's pose indices.
    private val HEAD = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8)
    private const val L_SHOULDER = 11; private const val R_SHOULDER = 12; private const val L_HIP = 23; private const val R_HIP = 24
    private val KNEES = listOf(25, 26); private val ANKLES = listOf(27, 28)

    private lateinit var appContext: Context
    fun init(context: Context) { appContext = context.applicationContext }

    private val landmarker: PoseLandmarker? by lazy {
        runCatching {
            PoseLandmarker.createFromOptions(appContext, PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumPoses(3)
                .build())
        }.onFailure { Log.e(TAG, "Could not load the pose landmarker: ${it.message}") }.getOrNull()
    }

    fun warmUp() { landmarker }

    /** The shopper's body: the pose whose head joints fall in the face box (grown by `bodyFaceMargin`),
     *  else the first pose when no face is given. `people` comes from the object detector. */
    fun check(bitmap: Bitmap, face: RectF?, people: Int): BodyCheck {
        val nobody = BodyCheck.unseen(people)
        val l = landmarker ?: return nobody
        val result = runCatching { l.detect(BitmapImageBuilder(bitmap).build()) }.getOrNull() ?: return nobody
        val poses = result.landmarks(); if (poses.isEmpty()) return nobody
        fun vis(p: NormalizedLandmark) = p.visibility().orElse(1f)
        // MediaPipe places a joint outside the frame with a confident visibility, where Vision has no
        // point at all: a joint counts only inside the frame and present.
        fun inFrame(p: NormalizedLandmark) = p.x() in 0f..1f && p.y() in 0f..1f && p.presence().orElse(1f) >= GallerySettings.bodyJointConfidence
        fun seen(pose: List<NormalizedLandmark>, idx: List<Int>, atLeast: Float = GallerySettings.bodyJointConfidence) = idx.map { pose[it] }.filter { inFrame(it) && vis(it) >= atLeast }

        var pose = poses[0]
        if (face != null) {
            val zone = RectF(face).also { it.inset(-face.width() * GallerySettings.bodyFaceMargin, -face.height() * GallerySettings.bodyFaceMargin) }
            fun onFace(p: List<NormalizedLandmark>) = seen(p, HEAD).count { zone.contains(it.x(), it.y()) }
            val best = poses.maxByOrNull { onFace(it) } ?: return nobody
            if (onFace(best) == 0) return nobody
            pose = best
        }
        val knees = seen(pose, KNEES); val ankles = seen(pose, ANKLES); val head = seen(pose, HEAD)
        // The gate's ankles, at `gateAnkleConfidence`; the lower-joint count keeps the usual line.
        val gateAnkles = seen(pose, ANKLES, GallerySettings.gateAnkleConfidence)
        // y runs top to bottom here: the span is the lowest ankle less the highest head point.
        var span = 0f
        val top = head.minOfOrNull { it.y() }; val bottom = gateAnkles.maxOfOrNull { it.y() }
        if (top != null && bottom != null) span = max(0f, bottom - top)
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        fun pixel(i: Int): PointF? { val p = pose[i]; return if (inFrame(p) && vis(p) >= GallerySettings.spreadJointConfidence) PointF(p.x() * w, p.y() * h) else null }
        fun mid(a: PointF?, b: PointF?): PointF? = if (a != null && b != null) PointF((a.x + b.x) / 2, (a.y + b.y) / 2) else null
        // Vision's neck and root are the shoulder and hip midpoints.
        val neck = mid(pixel(L_SHOULDER), pixel(R_SHOULDER)); val root = mid(pixel(L_HIP), pixel(R_HIP))
        val torso = if (neck != null && root != null) hypot(neck.x - root.x, neck.y - root.y).takeIf { it > 1 } else null
        val spread = if (torso != null) { val a = pixel(L_SHOULDER); val b = pixel(R_SHOULDER); if (a != null && b != null) abs(a.x - b.x) / torso else null } else null
        return BodyCheck(
            lowerJoints = knees.size + ankles.size, ankles = gateAnkles.size, headSeen = head.isNotEmpty(),
            span = span, footRoom = bottom?.let { 1f - it } ?: 0f, people = people, shoulderSpread = spread,
            landmarks = pose.map { PointF(it.x(), it.y()) }, visibility = pose.map { if (inFrame(it)) vis(it) else 0f },
            knees = knees.size,
        )
    }

    enum class PhotoType(val wire: String) { SELFIE("selfie"), FRONT("front"), THREE_QUARTER("three-quarter"), SIDE("side"), UNKNOWN("unknown") }

    /** The photo's type from inputs in hand. Same rules as `photoType` in GallerySettings.swift. */
    fun photoType(faceRatio: Float, eyeSpacing: Float?, yawDegrees: Float?, body: BodyCheck): PhotoType {
        val bigEnough = (eyeSpacing ?: 0f) >= GallerySettings.selfieEyeSpacingPixels
        if (body.lowerJoints == 0 && bigEnough) return PhotoType.SELFIE
        if (!body.isHeadToToe) return PhotoType.UNKNOWN
        body.shoulderSpread?.let { spread ->
            if (spread < GallerySettings.sideShoulderSpread) return PhotoType.SIDE
            return if (spread < GallerySettings.threeQuarterShoulderSpread) PhotoType.THREE_QUARTER else PhotoType.FRONT
        }
        val yaw = yawDegrees ?: return PhotoType.UNKNOWN
        return if (abs(yaw) > GallerySettings.sideYawDegrees) PhotoType.SIDE else PhotoType.FRONT
    }
}
