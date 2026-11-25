package co.getswan.gallerysample

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Small helpers shared by the scan and the scores. Mirrors `Helpers.swift`. */

/** The middle value; with an even count, the mean of the two middle values. */
/** A model held like `lazy`, but one `release()` closes it so the next `get()` builds it again (`Models.release`). */
class Reloadable<T : AutoCloseable>(private val build: () -> T?) {
    private var held: T? = null; private var built = false
    @Synchronized fun get(): T? { if (!built) { held = build(); built = true }; return held }
    @Synchronized fun release() { runCatching { held?.close() }; held = null; built = false }
}

fun median(values: List<Float>): Float {
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

fun clamp01(value: Float): Float = max(0f, min(1f, value))

/** 1 at `good` or better, 0 at `bad` or worse, a straight line between. */
fun ramp(value: Float, good: Float, bad: Float): Float = clamp01((value - bad) / (good - bad))

/** `value` rounded to two decimals, for the page. */
fun twoPlaces(value: Float): Double = (value * 100).roundToInt() / 100.0

fun threePlaces(value: Float): Double = (value * 1000).roundToInt() / 1000.0

/** `value` to two decimals with no trailing zeros ("%g"). */
fun shortNumber(value: Float): String = twoPlaces(value).toString().removeSuffix(".0")

/** `value` rounded to `places` decimals, as a Double for the page. */
fun fixed(value: Float, places: Int): Double { val p = Math.pow(10.0, places.toDouble()); return Math.round(value * p) / p }

/** Luminance and colour statistics of one region from a `side` x `side` copy: mean, clipped, blown,
 *  left and right half means, average colour, Laplacian variance. Same as `MuseScore.stats`. */
data class RegionStats(val mean: Float, val clipped: Float, val blown: Float, val left: Float, val right: Float, val r: Float, val g: Float, val b: Float, val laplacian: Float, val spread: Float = 0f)
fun regionStats(bitmap: Bitmap, region: RectF, side: Int): RegionStats? {
    val px = region.pixels(bitmap)
    val x = px.left.toInt().coerceIn(0, bitmap.width - 1); val y = px.top.toInt().coerceIn(0, bitmap.height - 1)
    val w = px.width().toInt().coerceIn(1, bitmap.width - x); val h = px.height().toInt().coerceIn(1, bitmap.height - y)
    if (w < 2 || h < 2) return null
    // Halved step by step first: one bilinear pass from a large region keeps only a few source pixels per
    // cell and reads far sharper than iOS's Core Graphics draw (Laplacian 3–4× on the same faces, 16 Nov).
    var small = Bitmap.createBitmap(bitmap, x, y, w, h)
    while (small.width > side * 2 && small.height > side * 2) {
        val next = Bitmap.createScaledBitmap(small, small.width / 2, small.height / 2, true); if (small !== bitmap) small.recycle(); small = next
    }
    small = Bitmap.createScaledBitmap(small, side, side, true)
    val pix = IntArray(side * side); small.getPixels(pix, 0, side, 0, 0, side, side)
    val lum = FloatArray(side * side)
    var sumR = 0f; var sumG = 0f; var sumB = 0f; var clipped = 0f; var blown = 0f; var left = 0f; var right = 0f
    for (i in 0 until side * side) {
        val c = pix[i]; val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
        val yv = luma(r, g, b); lum[i] = yv; sumR += r; sumG += g; sumB += b
        if (yv > 0.96f || yv < 0.04f) clipped += 1; if (yv > 0.96f) blown += 1
        if (i % side < side / 2) left += yv else right += yv
    }
    val n = (side * side).toFloat()
    var lapSum = 0f; var lapSq = 0f
    for (yy in 1 until side - 1) for (xx in 1 until side - 1) {
        val i = yy * side + xx
        val l = 4 * lum[i] - lum[i - 1] - lum[i + 1] - lum[i - side] - lum[i + side]
        lapSum += l; lapSq += l * l
    }
    val m = ((side - 2) * (side - 2)).toFloat(); val lapMean = lapSum / m
    // Spread: the brightest tenth against the darkest tenth of the region's pixels.
    val sortedLum = lum.sorted(); val tenth = max(1, lum.size / 10)
    val spread = sortedLum.takeLast(tenth).average().toFloat() - sortedLum.take(tenth).average().toFloat()
    return RegionStats(luma(sumR, sumG, sumB) / n, clipped / n, blown / n, left / (n / 2), right / (n / 2), sumR / n, sumG / n, sumB / n, lapSq / m - lapMean * lapMean, spread)
}

/** Luma of one colour, with the Rec. 601 weights. */
fun luma(r: Float, g: Float, b: Float): Float = 0.299f * r + 0.587f * g + 0.114f * b

/** The Laplacian variance of `region` drawn grey at `side` x `side`, x 1000: fine detail.
 *  Motion blur and missed focus take it down. Same maths as `FaceExpression.laplacianVariance`. */
fun laplacianVariance(bitmap: Bitmap, region: RectF? = null, side: Int = 256): Float? {
    val src = region?.let {
        val x = it.left.toInt().coerceIn(0, bitmap.width - 1); val y = it.top.toInt().coerceIn(0, bitmap.height - 1)
        val w = it.width().toInt().coerceIn(1, bitmap.width - x); val h = it.height().toInt().coerceIn(1, bitmap.height - y)
        Bitmap.createBitmap(bitmap, x, y, w, h)
    } ?: bitmap
    val small = Bitmap.createScaledBitmap(src, side, side, true)
    val px = IntArray(side * side); small.getPixels(px, 0, side, 0, 0, side, side)
    val grey = FloatArray(side * side) { i -> val c = px[i]; luma(((c shr 16) and 255).toFloat(), ((c shr 8) and 255).toFloat(), (c and 255).toFloat()) }
    var sum = 0.0; var sumSq = 0.0; var n = 0.0
    for (y in 1 until side - 1) for (x in 1 until side - 1) {
        val i = y * side + x
        val v = (4 * grey[i] - grey[i - 1] - grey[i + 1] - grey[i - side] - grey[i + side]) / 255.0
        sum += v; sumSq += v * v; n += 1
    }
    val mean = sum / n
    return ((sumSq / n - mean * mean) * 1000).toFloat()
}

/** A normalised box (0-1, top-left origin) in `bitmap`'s pixels. */
fun RectF.pixels(bitmap: Bitmap): RectF = RectF(left * bitmap.width, top * bitmap.height, right * bitmap.width, bottom * bitmap.height)

/** The head crop the landmarker expects: the face box with a generous margin, clipped to the image. Same as `MuseScore.headBox`. */
fun headBox(face: RectF): RectF {
    val w = face.width(); val h = face.height()
    val r = RectF(face.left - w * 0.6f, face.top - h * 0.5f, face.left - w * 0.6f + w * 2.2f, face.top - h * 0.5f + h * 2.4f)
    r.intersect(0f, 0f, 1f, 1f); return r
}

fun Bitmap.crop(normalised: RectF): Bitmap? {
    val r = normalised.pixels(this)
    val x = r.left.toInt().coerceIn(0, width - 1); val y = r.top.toInt().coerceIn(0, height - 1)
    val w = r.width().toInt().coerceIn(1, width - x); val h = r.height().toInt().coerceIn(1, height - y)
    return if (w > 16 && h > 16) Bitmap.createBitmap(this, x, y, w, h) else null
}

/** A 64-bit difference hash of the image (9x8 grey, each bit a left-to-right brightness step). */
fun differenceHash(bitmap: Bitmap): Long {
    val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
    val px = IntArray(72); small.getPixels(px, 0, 9, 0, 0, 9, 8)
    fun grey(i: Int): Float { val c = px[i]; return luma(((c shr 16) and 255).toFloat(), ((c shr 8) and 255).toFloat(), (c and 255).toFloat()) }
    var hash = 0L
    for (y in 0 until 8) for (x in 0 until 8) { hash = hash shl 1; if (grey(y * 9 + x) < grey(y * 9 + x + 1)) hash = hash or 1L }
    return hash
}

fun hammingBits(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
