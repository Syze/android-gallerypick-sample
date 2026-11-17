package co.getswan.gallerysample

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlin.math.sqrt

/** What the classifier names in a photo, under the iOS label names the rules read. iOS asks Vision;
 *  Android asks ML Kit's base labeller, whose 430 names cover part of Vision's list. Names it lacks
 *  (hoodie, kimono, costume, lab coat, mask, beanie, wedding dress) never fire here. */
object Labels {
    private const val TAG = "swan"
    /** ML Kit name → the iOS name the rules know. A blazer is read as a suit: it shapes the torso the same way. */
    private val map = mapOf(
        "Sunglasses" to "sunglasses", "Glasses" to "eyeglasses", "Hat" to "hat", "Cap" to "baseball_hat", "Helmet" to "helmet",
        "Scarf" to "scarf", "Tuxedo" to "tuxedo", "Blazer" to "suit", "Jacket" to "jacket", "Tie" to "necktie",
        "Gown" to "gown", "Sari" to "sari", "Dress" to "dress",
        "Passport" to "passport", "Receipt" to "receipt", "Paper" to "paper", "Screenshot" to "screenshot",
    )
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.1f).build()) }

    /** The known labels in `bitmap` with their confidence, strongest first. Empty when labelling fails. */
    fun read(bitmap: Bitmap): Map<String, Float> {
        val found = runCatching { Tasks.await(labeler.process(InputImage.fromBitmap(bitmap, 0))) }
            .onFailure { Log.e(TAG, "image labelling failed: ${it.message}") }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Float>()
        for (l in found.sortedByDescending { it.confidence }) map[l.text]?.let { name -> if (out[name] == null) out[name] = l.confidence }
        return out
    }

    /** How busy a crop is, 0-1: mean gradient magnitude over a 64 px copy. Same as `MuseScore.busyness`. */
    fun busyness(crop: Bitmap): Float {
        val n = 64
        val small = Bitmap.createScaledBitmap(crop, n, n, true)
        val px = IntArray(n * n); small.getPixels(px, 0, n, 0, 0, n, n)
        val grey = FloatArray(n * n) { i -> val c = px[i]; luma(((c shr 16) and 255).toFloat(), ((c shr 8) and 255).toFloat(), (c and 255).toFloat()) }
        var sum = 0f
        for (y in 1 until n - 1) for (x in 1 until n - 1) {
            val gx = grey[y * n + x + 1] - grey[y * n + x - 1]; val gy = grey[(y + 1) * n + x] - grey[(y - 1) * n + x]
            sum += sqrt(gx * gx + gy * gy)
        }
        return sum / ((n - 2) * (n - 2)) / 255
    }

    /** The clothing below the face: three face-widths wide, from 0.2 to 3 face-heights under the box. */
    fun torsoCrop(bitmap: Bitmap, face: RectF): Bitmap? {
        val w = face.width(); val h = face.height()
        val r = RectF(face.centerX() - w * 1.5f, face.bottom + h * 0.2f, face.centerX() + w * 1.5f, face.bottom + h * 3f)
        r.intersect(0f, 0f, 1f, 1f)
        return if (r.width() > 0.01f && r.height() > 0.01f) bitmap.crop(r) else null
    }
}
