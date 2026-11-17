package co.getswan.gallerysample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Faces in a photo and what the landmarker reads on one. Mirrors `FaceVision.swift` (detection)
 *  and `FaceExpression.swift` (expression, gaze, head angle). ML Kit finds the boxes, as Vision does
 *  on iOS; MediaPipe's landmarker then reads each head crop, as iOS does for expression. The
 *  landmarker alone misses faces more than a few metres away, which full-length photos are. */
object FaceVision {
    private const val TAG = "swan"
    private const val MODEL = "FaceModels/face_landmarker.task"

    /** One face box, normalised 0-1 with a top-left origin, widest first in `detect`. Points are
     *  read separately (`landmarks`), only for the face that goes on to be matched. */
    data class Face(val box: RectF) {
        val width: Float get() = box.width()
    }

    /** Pupil to pupil in pixels from a face's points: the iris centres (468, 473). */
    fun eyeSpacing(landmarks: List<PointF>, imageWidth: Int, imageHeight: Int): Float? {
        // Five ML Kit points (`keyPoints`): the eye centres stand in for the irises.
        val (a, b) = when { landmarks.size >= 478 -> landmarks[468] to landmarks[473]; landmarks.size == 5 -> landmarks[0] to landmarks[1]; else -> return null }
        return hypot((a.x - b.x) * imageWidth, (a.y - b.y) * imageHeight)
    }

    private lateinit var appContext: Context
    fun init(context: Context) { appContext = context.applicationContext }

    /** Boxes over the whole photo, at every distance. `minFaceSize` is a share of the image width:
     *  below the scan's 120 px floor at 1536 px, so a small face is reported and judged, not missed. */
    // Fast mode: on a 768 px copy it finds the same faces the scan can use in a fraction of the time.
    // One client per calling thread, so the look-ahead workers do not queue behind one detector.
    private val boxes = object : ThreadLocal<com.google.mlkit.vision.face.FaceDetector>() {
        override fun initialValue() = FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.05f)
            .build())
    }
    /** The same in accurate mode, for the three selfie frames only: fast mode misses a face that
     *  fills the frame, and the reference must not be lost to that. */
    private val carefulBoxes by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.05f)
            .build())
    }
    /** ML Kit with its five points, for `keyPoints` only: a head crop, so a large minimum face. */
    private val pointBoxes by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.3f)
            .build())
    }
    /** Points on one head crop: one face, no blendshapes. */
    private val detector: FaceLandmarker? by lazy { build(numFaces = 1, blendshapes = false) }
    /** Expression on a head crop: one face, blendshapes and the head transform. */
    private val reader: FaceLandmarker? by lazy { build(numFaces = 1, blendshapes = true) }

    private fun build(numFaces: Int, blendshapes: Boolean): FaceLandmarker? = runCatching {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(numFaces)
            .setOutputFaceBlendshapes(blendshapes)
            .setOutputFacialTransformationMatrixes(blendshapes)
            .build()
        FaceLandmarker.createFromOptions(appContext, options)
    }.onFailure { Log.e(TAG, "Could not load the face landmarker: ${it.message}") }.getOrNull()

    fun warmUp() { boxes.get(); detector; reader }

    /** Every face box in the photo, widest first, from ML Kit on a copy no larger than `detectPixels`. */
    fun detect(bitmap: Bitmap, careful: Boolean = false): List<Face> {
        val scale = minOf(1f, GallerySettings.detectPixels.toFloat() / maxOf(bitmap.width, bitmap.height))
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        val found = runCatching { Tasks.await((if (careful) carefulBoxes else boxes.get()!!).process(InputImage.fromBitmap(small, 0))) }
            .onFailure { Log.e(TAG, "face detection failed: ${it.message}") }.getOrNull()
        if (small !== bitmap) small.recycle()
        if (found == null) return emptyList()
        val w = small.width.toFloat(); val h = small.height.toFloat()
        return found.map { f -> val b = f.boundingBox; Face(RectF(b.left / w, b.top / h, b.right / w, b.bottom / h)) }
            .sortedByDescending { it.width }
    }

    /** The landmarker's 478 points for `face`, read on its head crop and mapped back to the photo's
     *  normalised coordinates. Empty when the crop yields no face. */
    fun landmarks(bitmap: Bitmap, face: Face): List<PointF> {
        val d = detector ?: return emptyList()
        val head = headBox(face.box)
        val crop = bitmap.crop(head) ?: return emptyList()
        val result = runCatching { d.detect(BitmapImageBuilder(crop).build()) }.getOrNull() ?: return emptyList()
        val points = result.faceLandmarks().firstOrNull() ?: return emptyList()
        return points.map { PointF(head.left + it.x() * head.width(), head.top + it.y() * head.height()) }
    }

    /** Fallback for `landmarks` (`mlKitLandmarkFallback`): ML Kit's eye centres, nose base and mouth corners on the
     *  head crop, five points in the photo's normalised coordinates. Empty when ML Kit finds no face or point there. */
    fun keyPoints(bitmap: Bitmap, face: Face): List<PointF> {
        val head = headBox(face.box)
        val crop = bitmap.crop(head) ?: return emptyList()
        val found = runCatching { Tasks.await(pointBoxes.process(InputImage.fromBitmap(crop, 0))) }.getOrNull()
        val f = found?.maxByOrNull { it.boundingBox.width() } ?: return emptyList()
        val types = listOf(FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE, FaceLandmark.NOSE_BASE, FaceLandmark.MOUTH_LEFT, FaceLandmark.MOUTH_RIGHT)
        val w = crop.width.toFloat(); val h = crop.height.toFloat()
        return types.map { t -> val p = f.getLandmark(t)?.position ?: return emptyList(); PointF(head.left + p.x / w * head.width(), head.top + p.y / h * head.height()) }
    }

    /** The head's yaw in degrees from the landmarker on the head crop, for the photo type when no shoulders are seen. */
    fun headYaw(bitmap: Bitmap, face: Face): Float? = expression(bitmap, face)?.headYaw

    /** The expression on a matched face, read from the head crop as iOS does. Nil when no face is found there. */
    fun expression(bitmap: Bitmap, face: Face): FaceExpression? {
        val r = reader ?: return null
        val crop = bitmap.crop(headBox(face.box)) ?: return null
        val result = runCatching { r.detect(BitmapImageBuilder(crop).build()) }.getOrNull() ?: return null
        return FaceExpression.from(result, crop)
    }
}

/** The expression on a matched face: blendshapes, gaze, iris size, head angle. Field for field `FaceExpression.swift`. */
data class FaceExpression(
    val smile: Float, val jawOpen: Float, val blinkLeft: Float, val blinkRight: Float, val browUp: Float, val squint: Float,
    val eyeWide: Float, val teeth: Float, val gazeUp: Float, val gazeDown: Float, val gazeSide: Float,
    val eyeYaw: Float?, val eyePitch: Float?, val irisPixels: Float?,
    val pucker: Float, val funnel: Float, val press: Float, val browDown: Float,
    val faceRatio: Float?, val faceDetail: Float?,
    val headPitch: Float?, val headYaw: Float?, val headRoll: Float?,
    val lidGap: Float?, val landmarks: List<PointF>,
) {
    val blinkMax: Float get() = max(blinkLeft, blinkRight)
    val blinkBoth: Float get() = min(blinkLeft, blinkRight)

    companion object {
        fun from(result: FaceLandmarkerResult, crop: Bitmap): FaceExpression? {
            val categories = result.faceBlendshapes().orElse(null)?.firstOrNull() ?: return null
            val values = HashMap<String, Float>()
            for (c in categories) values[c.categoryName()] = c.score()
            fun v(name: String) = values[name] ?: 0f
            val points = result.faceLandmarks().firstOrNull()?.map { PointF(it.x(), it.y()) } ?: emptyList()
            val w = crop.width.toFloat(); val h = crop.height.toFloat()
            var irisPixels: Float? = null; var faceRatio: Float? = null; var eyeYaw: Float? = null; var eyePitch: Float? = null
            var faceDetail: Float? = null; var lidGap: Float? = null
            if (points.size >= 478) {
                fun across(a: Int, b: Int) = hypot((points[a].x - points[b].x) * w, (points[a].y - points[b].y) * h)
                irisPixels = (across(469, 471) + across(474, 476)) / 2
                val lower = across(1, 152); if (lower > 0) faceRatio = across(9, 1) / lower
                fun eyeAngles(iris: Int, c0: Int, c1: Int, irisWidth: Float): Pair<Float, Float>? {
                    val a = PointF(points[c0].x * w, points[c0].y * h); val b = PointF(points[c1].x * w, points[c1].y * h)
                    val (left, right) = if (a.x <= b.x) a to b else b to a
                    val length = hypot(right.x - left.x, right.y - left.y)
                    if (length <= 0 || irisWidth <= 0) return null
                    val ux = (right.x - left.x) / length; val uy = (right.y - left.y) / length
                    val dx = points[iris].x * w - (left.x + right.x) / 2; val dy = points[iris].y * h - (left.y + right.y) / 2
                    val mm = 11.7f / irisWidth
                    fun degrees(offset: Float) = asin((offset * mm / 12).coerceIn(-1f, 1f)) * 180 / Math.PI.toFloat()
                    return degrees(dx * ux + dy * uy) to degrees(dy * ux - dx * uy)
                }
                val eyes = listOfNotNull(eyeAngles(468, 33, 133, across(469, 471)), eyeAngles(473, 362, 263, across(474, 476)))
                if (eyes.isNotEmpty()) { eyeYaw = eyes.map { it.first }.average().toFloat(); eyePitch = eyes.map { it.second }.average().toFloat() }
                val gaps = listOf(across(159, 145) to across(469, 471), across(386, 374) to across(474, 476)).mapNotNull { if (it.second > 0) it.first / it.second else null }
                lidGap = gaps.minOrNull()
                val xs = points.map { it.x * w }; val ys = points.map { it.y * h }
                val x0 = xs.min(); val x1 = xs.max(); val y0 = ys.min(); val y1 = ys.max()
                if (x1 > x0 && y1 > y0) faceDetail = laplacianVariance(crop, RectF(x0, y0, x1, y1))
            }
            var headPitch: Float? = null; var headYaw: Float? = null; var headRoll: Float? = null
            // Euler angles from the transform's rotation part, as iOS reads it. The Java API hands the
            // 4x4 over row by row; read column-wise the signs came out opposite to iOS on the same selfies.
            result.facialTransformationMatrixes().orElse(null)?.firstOrNull()?.let { m ->
                fun r(row: Int, col: Int) = m[row * 4 + col]
                fun deg(rad: Float) = rad * 180 / Math.PI.toFloat()
                headPitch = deg(atan2(r(2, 1), r(2, 2))) + GallerySettings.headPitchOffsetDegrees
                headYaw = deg(asin((-r(2, 0)).coerceIn(-1f, 1f)))
                headRoll = deg(atan2(r(1, 0), r(0, 0)))
            }
            return FaceExpression(
                smile = (v("mouthSmileLeft") + v("mouthSmileRight")) / 2, jawOpen = v("jawOpen"),
                blinkLeft = v("eyeBlinkLeft"), blinkRight = v("eyeBlinkRight"), browUp = v("browInnerUp"),
                squint = (v("eyeSquintLeft") + v("eyeSquintRight")) / 2, eyeWide = max(v("eyeWideLeft"), v("eyeWideRight")),
                teeth = max((v("mouthUpperUpLeft") + v("mouthUpperUpRight")) / 2, (v("mouthLowerDownLeft") + v("mouthLowerDownRight")) / 2),
                gazeUp = (v("eyeLookUpLeft") + v("eyeLookUpRight")) / 2, gazeDown = (v("eyeLookDownLeft") + v("eyeLookDownRight")) / 2,
                gazeSide = max((v("eyeLookInLeft") + v("eyeLookOutRight")) / 2, (v("eyeLookOutLeft") + v("eyeLookInRight")) / 2),
                eyeYaw = eyeYaw, eyePitch = eyePitch, irisPixels = irisPixels,
                pucker = v("mouthPucker"), funnel = v("mouthFunnel"), press = (v("mouthPressLeft") + v("mouthPressRight")) / 2,
                browDown = (v("browDownLeft") + v("browDownRight")) / 2,
                faceRatio = faceRatio, faceDetail = faceDetail, headPitch = headPitch, headYaw = headYaw, headRoll = headRoll,
                lidGap = lidGap, landmarks = points,
            )
        }
    }
}
