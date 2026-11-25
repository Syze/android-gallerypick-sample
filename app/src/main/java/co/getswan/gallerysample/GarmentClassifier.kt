package co.getswan.gallerysample

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier

/** MediaPipe's image classifier (EfficientNet-Lite0, ImageNet classes: suit, trench coat, jersey, kimono, gown...)
 *  on a crop. A second garment read beside ML Kit's, logged only while `GallerySettings.debugGarmentReads` is on.
 *  The model ships in debug builds only (`src/debug/assets`). */
object GarmentClassifier {
    private const val TAG = "swan"
    private const val MODEL = "FaceModels/efficientnet_lite0_classifier.tflite"

    private lateinit var appContext: Context
    fun init(context: Context) { appContext = context.applicationContext }
    fun release() = classifier.release()

    private val classifier = Reloadable<ImageClassifier> {
        runCatching {
            ImageClassifier.createFromOptions(appContext, ImageClassifier.ImageClassifierOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(5)
                .build())
        }.onFailure { Log.e(TAG, "Could not load the image classifier: ${it.message}") }.getOrNull()
    }

    /** Top classes on the bitmap, strongest first, as "name score" pairs. */
    fun read(bitmap: Bitmap): List<Pair<String, Float>> {
        val c = classifier.get() ?: return emptyList()
        val result = runCatching { c.classify(BitmapImageBuilder(bitmap).build()) }.getOrNull() ?: return emptyList()
        return result.classificationResult().classifications().firstOrNull()?.categories()
            ?.map { it.categoryName() to it.score() }?.sortedByDescending { it.second } ?: emptyList()
    }
}
