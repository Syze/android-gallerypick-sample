package co.getswan.gallerysample

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceRecognizerSF
import java.io.File
import kotlin.math.sqrt

/** Face identity embeddings from the SFace model, and the distance between them. Mirrors
 *  `FaceRecognition.swift`: the same OpenCV zoo model (the float ONNX here, Core ML on iOS), the
 *  same five-point alignment to the 112x112 template, the same 1 - cosine distance. */
class FaceRecognizer private constructor(private val model: FaceRecognizerSF) {
    private val lock = Any()

    companion object {
        private const val TAG = "swan"
        private const val ASSET_FLOAT = "FaceModels/face_recognition_sface_2021dec.onnx"
        private const val ASSET_INT8 = "FaceModels/face_recognition_sface_2021dec_int8.onnx"

        /** Loads OpenCV and the model; null, with the reason logged, when either is missing. */
        fun load(context: Context): FaceRecognizer? {
            if (!OpenCVLoader.initLocal()) { Log.e(TAG, "OpenCV did not load; faces cannot be matched"); return null }
            // The int8 model when switched on and OpenCV can run it, else the float one.
            val choices = if (GallerySettings.sfaceInt8) listOf(ASSET_INT8 to "sface-int8.onnx", ASSET_FLOAT to "sface.onnx") else listOf(ASSET_FLOAT to "sface.onnx")
            for ((asset, name) in choices) {
                val loaded = runCatching {
                    // OpenCV reads models by path, so the asset is copied out once.
                    val file = File(context.filesDir, name)
                    if (!file.exists() || file.length() == 0L) context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
                    FaceRecognizer(FaceRecognizerSF.create(file.absolutePath, ""))
                }.onFailure { Log.w(TAG, "Could not load $asset: ${it.message}") }.getOrNull()
                if (loaded != null) { Log.i(TAG, "SFace loaded from $asset"); return loaded }
            }
            Log.e(TAG, "Could not load SFace; faces cannot be matched"); return null
        }

        /** 1 - cosine similarity of two unit vectors: 0 is the same face, about 1 unrelated. */
        fun distance(a: FloatArray, b: FloatArray): Float {
            var dot = 0f; for (i in a.indices) dot += a[i] * b[i]; return 1 - dot
        }
    }

    /** The identity vector of `face` in `bitmap` from its 478 landmarker points, or ML Kit's five (`keyPoints`), unit length;
     *  null when the points are missing. */
    fun embedding(bitmap: Bitmap, face: FaceVision.Face, landmarks: List<android.graphics.PointF>): FloatArray? {
        // OpenCV's row: box, then the eyes (image-left first), nose tip, mouth corners (image-left first).
        val row = row(bitmap, face, landmarks) ?: return null
        val aligned = alignedCrop(bitmap, row) ?: return null
        val feature = Mat()
        return try {
            synchronized(lock) { model.feature(aligned, feature) }
            val vector = FloatArray(feature.cols()); feature.get(0, 0, vector)
            val length = sqrt(vector.fold(0f) { acc, x -> acc + x * x })
            if (length <= 0f) null else FloatArray(vector.size) { vector[it] / length }
        } finally { aligned.release(); feature.release() }
    }

    /** The 112x112 BGR crop the model reads, from OpenCV's five-point alignment; the caller releases it. */
    internal fun alignedCrop(bitmap: Bitmap, row: FloatArray): Mat? {
        val rgba = Mat(); Utils.bitmapToMat(bitmap, rgba)
        val bgr = Mat(); Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR); rgba.release()
        val points = Mat(1, 15, CvType.CV_32F).also { it.put(0, 0, row); it.put(0, 14, floatArrayOf(1f)) }
        val aligned = Mat()
        try { synchronized(lock) { model.alignCrop(bgr, points, aligned) } } finally { bgr.release(); points.release() }
        return aligned
    }

    /** The five-point row OpenCV expects for `face`, or null without landmarks. */
    internal fun row(bitmap: Bitmap, face: FaceVision.Face, landmarks: List<android.graphics.PointF>): FloatArray? {
        val p = when { landmarks.size >= 478 -> listOf(landmarks[468], landmarks[473], landmarks[1], landmarks[61], landmarks[291]); landmarks.size == 5 -> landmarks; else -> return null }
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        val box = face.box.pixels(bitmap)
        val eyes = listOf(p[0], p[1]).sortedBy { it.x }
        val mouth = listOf(p[3], p[4]).sortedBy { it.x }
        return floatArrayOf(box.left, box.top, box.width(), box.height(), eyes[0].x * w, eyes[0].y * h, eyes[1].x * w, eyes[1].y * h,
            p[2].x * w, p[2].y * h, mouth[0].x * w, mouth[0].y * h, mouth[1].x * w, mouth[1].y * h)
    }

    /** The raw (unnormalised) 128 floats OpenCV's engine gives for an aligned crop. */
    internal fun rawFeature(aligned: Mat): FloatArray { val f = Mat(); synchronized(lock) { model.feature(aligned, f) }; return FloatArray(f.cols()).also { f.get(0, 0, it); f.release() } }
}
