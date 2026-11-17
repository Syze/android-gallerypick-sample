package co.getswan.gallerysample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/** Things over or beside the body (bags, umbrellas, bicycles, chairs) and the people in the photo,
 *  from MediaPipe's object detector on the same EfficientDet model iOS uses. Mirrors `BodyObjects.swift`;
 *  the people count stands in for Vision's person detector. */
object BodyObjects {
    private const val TAG = "swan"
    private const val MODEL = "FaceModels/efficientdet_lite0.tflite"
    data class Object(val label: String, val confidence: Float, val box: RectF)

    /** The detector's names for things that hide the body. */
    val labels = setOf("handbag", "backpack", "suitcase", "umbrella", "bicycle", "motorcycle", "chair", "bench", "couch")
    const val confidence = 0.3f
    /** Animals the detector knows, for `GallerySettings.animalBesideDrops`. */
    val animals = setOf("dog", "cat", "horse", "sheep", "cow", "bird", "elephant", "bear", "zebra", "giraffe")

    private lateinit var appContext: Context
    fun init(context: Context) { appContext = context.applicationContext }

    private val detector: ObjectDetector? by lazy {
        runCatching {
            ObjectDetector.createFromOptions(appContext, ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(confidence)
                .setCategoryAllowlist(labels.toList() + animals.toList() + "person")
                .setMaxResults(16)
                .build())
        }.onFailure { Log.e(TAG, "Could not load the object detector: ${it.message}") }.getOrNull()
    }

    /** Everything the detector finds, strongest first, boxes normalised to the image. */
    fun read(bitmap: Bitmap): List<Object> {
        val d = detector ?: return emptyList()
        val result = runCatching { d.detect(BitmapImageBuilder(bitmap).build()) }.getOrNull() ?: return emptyList()
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        return result.detections().mapNotNull { det ->
            val c = det.categories().firstOrNull() ?: return@mapNotNull null
            val b = det.boundingBox()
            Object(c.categoryName(), c.score(), RectF(b.left / w, b.top / h, b.right / w, b.bottom / h))
        }.sortedByDescending { it.confidence }
    }

    /** People in the photo: person boxes at least `bystanderRatio` of the tallest one's height.
     *  The detector can box one person twice, so boxes that mostly overlap count once. */
    fun people(all: List<Object>): Int {
        val persons = ArrayList<Object>()
        for (p in all.filter { it.label == "person" && it.confidence >= GallerySettings.personConfidence }) if (persons.none { overlap(it.box, p.box) > 0.5f }) persons.add(p)
        val tallest = persons.maxOfOrNull { it.box.height() } ?: return 0
        return persons.count { it.box.height() >= tallest * GallerySettings.bystanderRatio }
    }

    /** Intersection over union of two boxes. */
    private fun overlap(a: RectF, b: RectF): Float {
        val i = RectF(); if (!i.setIntersect(a, b)) return 0f
        val inter = i.width() * i.height()
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }

    fun objects(all: List<Object>): List<Object> = all.filter { labels.contains(it.label) }

    /** An animal beside the shopper: at `animalConfidence` or more and at least `animalBesideRatio` of the
     *  tallest person's box height. The strongest, or null. */
    fun animalBeside(all: List<Object>): Object? {
        val tallest = all.filter { it.label == "person" }.maxOfOrNull { it.box.height() } ?: 1f
        return all.filter { animals.contains(it.label) && it.confidence >= GallerySettings.animalConfidence && it.box.height() >= tallest * GallerySettings.animalBesideRatio }
            .maxByOrNull { it.confidence }
    }
}
