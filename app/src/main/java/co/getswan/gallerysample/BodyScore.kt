package co.getswan.gallerysample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** How clearly the body can be seen under the clothes in a matched full-length photo, from the person
 *  mask and the pose. Line for line `BodyScore.swift`: T = visibility^0.7 × openness^0.2 × quality^0.1,
 *  visibility = 0.4 torso + 0.4 legs + 0.2 arms. The mask comes from MediaPipe's selfie segmenter on a
 *  crop around the body (Vision's person segmentation on iOS). */
class BodyScore private constructor(
    val value: Float, val tier: String?, val parts: List<Pair<String, Float>>, val reasons: List<String>,
    val filter: String?, val readings: Map<String, Double?>, val outfit: Map<String, List<Double>>?,
) {
    data class Caps(var torso: Float = 1f, var legs: Float = 1f, var arms: Float = 1f, var why: List<String> = emptyList(), var loose: String? = null)

    /** The same score with the outfit multiplier applied; the tier follows the new value, as `MuseScore`'s body half does. */
    fun withOutfit(multiplier: Float, why: String): BodyScore {
        val v = value * multiplier
        val tier = if (filter != null || this.tier == null) null else if (v >= GallerySettings.fullLengthTierALine) "A" else if (v >= GallerySettings.fullLengthUsableFloor) "B" else null
        return BodyScore(v, tier, parts, reasons + "$why ×${"%.2f".format(multiplier)}", filter, readings, outfit)
    }

    /** The same score with one more reading in the report. */
    fun withReading(key: String, v: Double): BodyScore = BodyScore(value, tier, parts, reasons, filter, readings + (key to v), outfit)

    /** The fields the page receives beside `score`. */
    fun fields(): JSONObject {
        val f = JSONObject()
        f.put("scoreParts", JSONObject().also { o -> parts.forEach { (n, v) -> o.put(n, twoPlaces(v)) } })
        f.put("scoreInputs", JSONObject().also { o -> readings.forEach { (k, v) -> if (v != null) o.put(k, Math.round(v * 1000) / 1000.0) } })
        tier?.let { f.put("tier", it) }
        f.put("usable", tier != null)
        filter?.let { f.put("scoreFilter", it) }
        f.put("scoreReasons", JSONArray(reasons))
        outfit?.let { o -> f.put("outfit", JSONObject().also { j -> o.forEach { (k, v) -> j.put(k, JSONArray(v)) } }) }
        return f
    }

    companion object {
        private const val TAG = "swan"
        // Thresholds, as in BodyScore.swift.
        val torsoFit = 1.05 to 1.35; val thighFit = 0.45 to 0.70; val shinFit = 0.30 to 0.50; val armFit = 0.25 to 0.45
        val outline = 0.14 to 0.04; val light = 0.05 to 0.015 // light halved 19 Nov 2026 (was 0.10 to 0.03)
        const val skinBonus = 0.1
        const val sittingKneeDrop = 0.5; const val sittingThighTilt = 50.0; const val sittingKneeAngle = 120.0
        const val leanLight = 12.0; const val leanHeavy = 25.0; const val lyingTilt = 60.0
        const val opennessLightLean = 0.7f; const val opennessSitting = 0.6f; const val opennessClosed = 0.5f; const val opennessLying = 0.3f
        const val sittingLegs = 0.8f; const val sittingTorso = 0.9f
        const val crossedAnkles = 0.15
        val sharpness = 0.003 to 0.0005; const val sharpnessFloor = 0.3f
        const val minBodyPixels = 400.0
        const val jointConfidence = 0.3f
        val weights = listOf("visibility" to 0.7f, "openness" to 0.2f, "quality" to 0.1f)
        const val objectBodyGate = 0.25
        val furniture = setOf("chair", "bench", "couch")
        const val objectAround = 0.9
        // MediaPipe pose indices.
        private const val NOSE = 0; private val HEAD = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8)
        private const val LS = 11; private const val RS = 12; private const val LE = 13; private const val RE = 14; private const val LW = 15; private const val RW = 16
        private const val LH = 23; private const val RH = 24; private const val LK = 25; private const val RK = 26; private const val LA = 27; private const val RA = 28

        private const val MODEL = "FaceModels/selfie_multiclass_256x256.tflite"
        private lateinit var appContext: Context
        fun init(context: Context) { appContext = context.applicationContext }
        private val segmenter: ImageSegmenter? by lazy {
            runCatching {
                ImageSegmenter.createFromOptions(appContext, ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                    .setRunningMode(RunningMode.IMAGE).setOutputCategoryMask(true).setOutputConfidenceMasks(false).build())
            }.onFailure { Log.e(TAG, "Could not load the segmenter for the body score: ${it.message}") }.getOrNull()
        }

        /** sRGB (0-1) to CIELAB, D65. */
        fun lab(r: Float, g: Float, b: Float): List<Double> {
            fun lin(c: Float): Double { val d = c.toDouble(); return if (d <= 0.04045) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4) }
            val R = lin(r); val G = lin(g); val B = lin(b)
            val X = (0.4124 * R + 0.3576 * G + 0.1805 * B) / 0.95047
            val Y = 0.2126 * R + 0.7152 * G + 0.0722 * B
            val Z = (0.0193 * R + 0.1192 * G + 0.9505 * B) / 1.08883
            fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116
            return listOf(116 * f(Y) - 16, 500 * (f(X) - f(Y)), 200 * (f(Y) - f(Z))).map { Math.round(it * 10) / 10.0 }
        }

        /** `bitmap`: the render the pose was read on; `body`: the kept pose; `face`: the matched face box (normalised,
         *  top-left origin); `originalScale`: original pixels per render pixel; `objects`: from `BodyObjects`. */
        fun compute(bitmap: Bitmap, body: BodyCheck, face: RectF?, originalScale: Double, caps: Caps? = null, objects: List<BodyObjects.Object> = emptyList(),
                    debugName: String? = null): BodyScore? {
            val w = bitmap.width; val h = bitmap.height
            val px = IntArray(w * h); bitmap.getPixels(px, 0, w, 0, 0, w, h)
            fun lum(x: Int, y: Int): Float {
                if (x < 0 || y < 0 || x >= w || y >= h) return 0f
                val c = px[y * w + x]; return luma(((c shr 16) and 255).toFloat(), ((c shr 8) and 255).toFloat(), (c and 255).toFloat()) / 255
            }
            fun rgb(x: Int, y: Int): Triple<Float, Float, Float> { val c = px[y * w + x]; return Triple(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f) }

            // The person mask: the segmenter on a crop around the pose, so the 256 px model sees the body large.
            val s = segmenter ?: return null
            if (body.landmarks.isEmpty()) return null
            val xs = body.landmarks.map { it.x }; val ys = body.landmarks.map { it.y }
            val cropN = RectF((xs.min() - 0.1f).coerceIn(0f, 1f), (ys.min() - 0.1f).coerceIn(0f, 1f), (xs.max() + 0.1f).coerceIn(0f, 1f), (ys.max() + 0.1f).coerceIn(0f, 1f))
            val cx0 = (cropN.left * w).toInt(); val cy0 = (cropN.top * h).toInt()
            val cw = ((cropN.right * w).toInt() - cx0).coerceAtLeast(16); val ch = ((cropN.bottom * h).toInt() - cy0).coerceAtLeast(16)
            val crop = Bitmap.createBitmap(bitmap, cx0, cy0, min(cw, w - cx0), min(ch, h - cy0))
            val longest = max(crop.width, crop.height)
            val small = if (longest > 512) Bitmap.createScaledBitmap(crop, max(1, crop.width * 512 / longest), max(1, crop.height * 512 / longest), true) else crop
            val result = runCatching { s.segment(BitmapImageBuilder(small).build()) }.getOrNull() ?: return null
            val mask = result.categoryMask().orElse(null) ?: return null
            val mw = mask.width; val mh = mask.height
            val data = ByteBufferExtractor.extract(mask)
            fun on(x: Int, y: Int): Boolean {
                if (x < cx0 || y < cy0 || x >= cx0 + crop.width || y >= cy0 + crop.height) return false
                val mx = (x - cx0) * mw / crop.width; val my = (y - cy0) * mh / crop.height
                return (data.get(my * mw + mx).toInt() and 255) != 0
            }

            // Joints in pixels, y down.
            fun joint(i: Int, conf: Float = jointConfidence): PointF? {
                if (body.visibility.getOrNull(i) ?: 0f < conf) return null
                val p = body.landmarks[i]; return PointF(p.x * w, p.y * h)
            }
            fun mid(a: PointF?, b: PointF?): PointF? = if (a != null && b != null) PointF((a.x + b.x) / 2, (a.y + b.y) / 2) else null
            val lS = joint(LS, 0.1f); val rS = joint(RS, 0.1f); val lH = joint(LH, 0.1f); val rH = joint(RH, 0.1f)
            val neck = mid(lS, rS) ?: return null; val root = mid(lH, rH) ?: return null
            val lE = joint(LE); val rE = joint(RE); val lW = joint(LW); val rW = joint(RW)
            val lK = joint(LK); val rK = joint(RK); val lA = joint(LA); val rA = joint(RA)
            val torsoLen = hypot(neck.x - root.x, neck.y - root.y).toDouble()
            if (torsoLen <= 1) return null
            val shoulderW = if (lS != null && rS != null) abs(lS.x - rS.x).toDouble() else null
            val hipW = if (lH != null && rH != null) abs(lH.x - rH.x).toDouble() else null
            fun lerp(a: PointF, b: PointF, t: Double) = PointF(a.x + ((b.x - a.x) * t).toFloat(), a.y + ((b.y - a.y) * t).toFloat())
            fun distToSeg(p: PointF, a: PointF, b: PointF): Double {
                val abx = (b.x - a.x).toDouble(); val aby = (b.y - a.y).toDouble(); val apx = (p.x - a.x).toDouble(); val apy = (p.y - a.y).toDouble()
                val len2 = abx * abx + aby * aby
                if (len2 < 1) return hypot(apx, apy)
                val t = max(0.0, min(1.0, (apx * abx + apy * aby) / len2))
                return hypot(p.x - (a.x + t * abx), p.y - (a.y + t * aby))
            }

            // Arm bands, cut out of the torso rows.
            val armR = torsoLen * 0.11
            val armSegments = ArrayList<Pair<PointF, PointF>>()
            for ((sh, el, wr) in listOf(Triple(lS, lE, lW), Triple(rS, rE, rW))) {
                if (sh != null && el != null) armSegments.add(sh to el)
                if (el != null && wr != null) armSegments.add(el to wr)
            }
            fun inArmBand(x: Int, y: Int): Boolean { val p = PointF(x.toFloat(), y.toFloat()); return armSegments.any { distToSeg(p, it.first, it.second) < armR } }

            // The face's skin colour, in rg chromaticity, for the skin share.
            var skinRG: Pair<Float, Float>? = null
            if (face != null) {
                val fx = (face.centerX() * w).toInt(); val fy = (face.centerY() * h).toInt(); val fr = max(1, (face.width() * w * 0.2f).toInt())
                var sr = 0f; var sg = 0f; var n = 0f
                for (y in max(0, fy - fr)..min(h - 1, fy + fr)) for (x in max(0, fx - fr)..min(w - 1, fx + fr)) {
                    val (r, g, b) = rgb(x, y); val sum = r + g + b
                    if (sum > 0.15f) { sr += r / sum; sg += g / sum; n += 1 }
                }
                if (n > 0) skinRG = (sr / n) to (sg / n)
            }
            fun isSkin(x: Int, y: Int): Boolean {
                val k = skinRG ?: return false
                if (x < 0 || y < 0 || x >= w || y >= h) return false
                val (r, g, b) = rgb(x, y); val sum = r + g + b
                if (sum <= 0.15f) return false
                return abs(r / sum - k.first) < 0.04f && abs(g / sum - k.second) < 0.03f
            }

            class Run(var width: Double, var edge: Double, var light: Double, var skin: Double) { var x = 0; var y = 0 }
            fun edgeAt(xi: Int, y: Int, dir: Int): Float {
                var inside = 0f; var outside = 0f
                for (k in 0 until 3) { inside += lum(xi - dir * k, y); outside += lum(xi + dir * (k + 1), y) }
                return abs(inside - outside) / 3
            }
            fun run(y: Int, cx: Int, minX: Int = 0, maxX: Int = w - 1, cutArms: Boolean): Run? {
                if (y < 0 || y >= h || !on(cx, y)) return null
                var l = cx; var r = cx
                while (l - 1 >= minX && on(l - 1, y) && !(cutArms && inArmBand(l - 1, y))) l--
                while (r + 1 <= maxX && on(r + 1, y) && !(cutArms && inArmBand(r + 1, y))) r++
                var lightSum = 0f; var skin = 0; var n = 0
                for (x in l..r) { lightSum += lum(x, y); if (isSkin(x, y)) skin++; n++ }
                val edges = ArrayList<Float>()
                if (!on(l - 1, y)) edges.add(edgeAt(l, y, -1))
                if (!on(r + 1, y)) edges.add(edgeAt(r, y, 1))
                val e = if (edges.isEmpty()) -1f else edges.sum() / edges.size
                return Run((r - l + 1).toDouble(), e.toDouble(), lightSum.toDouble() / max(n, 1), skin.toDouble() / max(n, 1)).also { it.x = l; it.y = y }
            }
            fun median(v: List<Double>): Double? { val s2 = v.sorted(); return if (s2.isEmpty()) null else s2[s2.size / 2] }
            fun mean(v: List<Double>): Double? = if (v.isEmpty()) null else v.sum() / v.size
            fun ramp(v: Double?, c: Pair<Double, Double>): Double? {
                if (v == null) return null
                val (good, bad) = c
                return if (good < bad) max(0.0, min(1.0, (bad - v) / (bad - good))) else max(0.0, min(1.0, (v - bad) / (good - bad)))
            }

            // Torso rows between the shoulders and the hips, the arm bands cut out.
            val torsoRuns = ArrayList<Run>()
            if (lS != null && rS != null && lH != null && rH != null) {
                val top = PointF((lS.x + rS.x) / 2, (lS.y + rS.y) / 2); val bot = PointF((lH.x + rH.x) / 2, (lH.y + rH.y) / 2)
                var t = 0.2
                while (t <= 0.85 + 1e-9) {
                    val c = lerp(top, bot, t)
                    run(c.y.toInt(), c.x.toInt(), cutArms = true)?.let { sRun ->
                        run(c.y.toInt(), c.x.toInt(), cutArms = false)?.let { whole -> sRun.edge = whole.edge }
                        torsoRuns.add(sRun)
                    }
                    t += 0.05
                }
            }
            var torsoW = median(torsoRuns.map { it.width })
            if (torsoW != null && torsoW < 0.15 * torsoLen) torsoW = null
            val impliedTorso = maxOf(shoulderW ?: 0.0, hipW ?: 0.0, 0.45 * torsoLen)
            val torsoRatio = torsoW?.let { it / impliedTorso }

            // Legs: thigh and shin rows per leg, each run capped at the midline to the other leg.
            data class Leg(val thigh: Double?, val shin: Double?, val runs: List<Run>)
            fun legRuns(hip: PointF?, knee: PointF?, ankle: PointF?, oh: PointF?, ok: PointF?, oa: PointF?): Leg {
                val thigh = ArrayList<Run>(); val shin = ArrayList<Run>()
                fun bound(y: Int, cx: Int): Pair<Int, Int> {
                    if (oh == null || ok == null) return 0 to (w - 1)
                    val oc: Float = if (oa != null && y > ok.y) lerp(ok, oa, ((y - ok.y) / max(1f, oa.y - ok.y)).toDouble()).x
                        else lerp(oh, ok, ((y - oh.y) / max(1f, ok.y - oh.y)).toDouble()).x
                    val midX = ((cx + oc) / 2).toInt()
                    return if (cx < oc.toInt()) 0 to midX else midX to (w - 1)
                }
                if (hip != null && knee != null) { var t = 0.25; while (t <= 0.8 + 1e-9) { val c = lerp(hip, knee, t); val (lo, hi) = bound(c.y.toInt(), c.x.toInt()); run(c.y.toInt(), c.x.toInt(), lo, hi, false)?.let { thigh.add(it) }; t += 0.11 } }
                if (knee != null && ankle != null) { var t = 0.2; while (t <= 0.8 + 1e-9) { val c = lerp(knee, ankle, t); val (lo, hi) = bound(c.y.toInt(), c.x.toInt()); run(c.y.toInt(), c.x.toInt(), lo, hi, false)?.let { shin.add(it) }; t += 0.12 } }
                return Leg(median(thigh.map { it.width }), median(shin.map { it.width }), thigh + shin)
            }
            val legL = legRuns(lH, lK, lA, rH, rK, rA); val legR = legRuns(rH, rK, rA, lH, lK, lA)
            val legRunsAll = legL.runs + legR.runs
            // Thigh rows where the mask runs unbroken from one thigh centre to the other: legs hidden in one garment.
            var legsMerged: Double? = null
            if (lH != null && lK != null && rH != null && rK != null) {
                var rows = 0; var merged = 0; var t = 0.25
                while (t <= 0.8 + 1e-9) {
                    val a = lerp(lH, lK, t); val b = lerp(rH, rK, t); val y = ((a.y + b.y) / 2).toInt()
                    val x0 = min(a.x, b.x).toInt(); val x1 = max(a.x, b.x).toInt()
                    if (y in 0 until h && x1 > x0) { rows++; if ((x0..x1).all { on(it, y) }) merged++ }
                    t += 0.05
                }
                if (rows > 0) legsMerged = merged.toDouble() / rows
            }

            // Arms: cross-sections across the upper and lower arm, twice the outer half.
            fun cross(a: PointF, b: PointF, t: Double): Run? {
                val c = lerp(a, b, t)
                val dx = (b.x - a.x).toDouble(); val dy = (b.y - a.y).toDouble(); val len = hypot(dx, dy)
                if (len <= 1 || !on(c.x.toInt(), c.y.toInt())) return null
                var nx = -dy / len; var ny = dx / len
                val axisMid = lerp(neck, root, 0.5)
                if ((c.x - axisMid.x) * nx + (c.y - axisMid.y) * ny < 0) { nx = -nx; ny = -ny }
                var k = 0; val cap = (armR * 4).toInt()
                while (on((c.x + nx * (k + 1)).toInt(), (c.y + ny * (k + 1)).toInt()) && k < cap) k++
                if (k >= cap) return null
                var lightSum = 0f; var skin = 0; var n = 0
                for (m in 0..k) { val x = (c.x + nx * m).toInt(); val y = (c.y + ny * m).toInt(); lightSum += lum(x, y); if (isSkin(x, y)) skin++; n++ }
                var ei = 0f; var eo = 0f
                for (m in 0 until 3) { ei += lum((c.x + nx * (k - m)).toInt(), (c.y + ny * (k - m)).toInt()); eo += lum((c.x + nx * (k + m + 1)).toInt(), (c.y + ny * (k + m + 1)).toInt()) }
                return Run((2 * k + 1).toDouble(), (abs(ei - eo) / 3).toDouble(), lightSum.toDouble() / max(n, 1), skin.toDouble() / max(n, 1))
            }
            val armWidths = ArrayList<Double>(); val armRuns = ArrayList<Run>()
            for ((a, b) in listOf(lS to lE, rS to rE, lE to lW, rE to rW)) {
                if (a == null || b == null) continue
                val runs = listOf(0.3, 0.5, 0.7).mapNotNull { cross(a, b, it) }
                median(runs.map { it.width })?.let { armWidths.add(it / torsoLen) }
                armRuns.addAll(runs)
            }

            // Each region: the lowest of fit, outline and light, plus the skin bonus.
            val reasons = ArrayList<String>()
            val readings = LinkedHashMap<String, Double?>(); readings["torsoLen"] = torsoLen
            fun region(name: String, fit: Double?, runs: List<Run>): Float {
                val edge = ramp(mean(runs.map { it.edge }.filter { it >= 0 }), outline)
                val lightV = ramp(mean(runs.map { it.light }), light)
                val skin = mean(runs.map { it.skin }) ?: 0.0
                readings[name + "Fit"] = fit; readings[name + "Outline"] = edge; readings[name + "Light"] = lightV; readings[name + "Skin"] = skin
                if (fit == null) { reasons.add("$name unread"); return 0f }
                val signals = listOf("$name fit" to fit, "$name outline" to (edge ?: 1.0), "$name light" to (lightV ?: 1.0))
                val low = signals.minByOrNull { it.second }!!
                if (low.second < 1) reasons.add("${low.first} ${"%.2f".format(low.second)}")
                return min(1.0, low.second + skinBonus * skin).toFloat()
            }
            readings["torsoRatio"] = torsoRatio
            var torso = region("torso", ramp(torsoRatio, torsoFit), torsoRuns)
            val legFits = ArrayList<Double>()
            for ((leg, side) in listOf(legL to "L", legR to "R")) {
                readings["thigh$side"] = leg.thigh?.let { it / torsoLen }; readings["shin$side"] = leg.shin?.let { it / torsoLen }
                val f = listOfNotNull(ramp(leg.thigh?.let { it / torsoLen }, thighFit), ramp(leg.shin?.let { it / torsoLen }, shinFit))
                f.minOrNull()?.let { legFits.add(it) }
            }
            var legs = region("legs", mean(legFits), legRunsAll)
            readings["armWidth"] = armWidths.minOrNull()
            var arms = region("arms", ramp(armWidths.minOrNull(), armFit), armRuns)

            // Pose, from the joints.
            var kneeDrop: Double? = null
            listOfNotNull(lK, rK).maxOfOrNull { it.y }?.let { kneeDrop = (it - root.y) / torsoLen }
            val torsoTilt = abs(atan2((neck.x - root.x).toDouble(), (root.y - neck.y).toDouble())) * 180 / Math.PI
            var thighTilt: Double? = null; var kneeAngle: Double? = null
            for ((hip, knee, ankle) in listOf(Triple(lH, lK, lA), Triple(rH, rK, rA))) {
                if (hip == null || knee == null) continue
                thighTilt = max(thighTilt ?: 0.0, abs(atan2((knee.x - hip.x).toDouble(), (knee.y - hip.y).toDouble())) * 180 / Math.PI)
                if (ankle != null) {
                    val ax = hip.x - knee.x; val ay = hip.y - knee.y; val bx = ankle.x - knee.x; val by = ankle.y - knee.y
                    val cosv = (ax * bx + ay * by).toDouble() / max(1.0, (hypot(ax, ay) * hypot(bx, by)).toDouble())
                    kneeAngle = min(kneeAngle ?: 180.0, acos(max(-1.0, min(1.0, cosv))) * 180 / Math.PI)
                }
            }
            val sitting = (kneeDrop ?: 1.0) < sittingKneeDrop || (thighTilt ?: 0.0) > sittingThighTilt || (kneeAngle ?: 180.0) < sittingKneeAngle

            // Objects over the body: the share of each region's mask inside a box. Furniture counts only when sitting.
            var objectShare = 0.0
            val standingBehind = objects.filter { !sitting && furniture.contains(it.label) }
            if (standingBehind.isNotEmpty()) reasons.add(standingBehind.take(2).joinToString(" ") { "${it.label} ${"%.2f".format(it.confidence)}" } + " behind")
            var objs = objects.filter { sitting || !furniture.contains(it.label) }
            if (objs.isNotEmpty()) {
                var maskCount = 0; val inBox = IntArray(objs.size)
                var y = 0
                while (y < h) { var x = 0; while (x < w) { if (on(x, y)) { maskCount++; objs.forEachIndexed { i, o -> if (o.box.contains(x.toFloat() / w, y.toFloat() / h)) inBox[i]++ } }; x += 3 }; y += 3 }
                val around = objs.filterIndexed { i, _ -> maskCount > 0 && inBox[i].toDouble() / maskCount >= objectAround }
                if (around.isNotEmpty()) {
                    reasons.add(around.take(2).joinToString(" ") { "${it.label} ${"%.2f".format(it.confidence)}" } + " around the body")
                    objs = objs.filterIndexed { i, _ -> maskCount > 0 && inBox[i].toDouble() / maskCount < objectAround }
                }
            }
            if (objs.isNotEmpty()) {
                val boxes = objs.map { RectF(it.box.left * w, it.box.top * h, it.box.right * w, it.box.bottom * h) }
                fun covered(x: Int, y: Int) = boxes.any { it.contains(x.toFloat(), y.toFloat()) }
                val footEnd = (listOfNotNull(lA, rA).maxOfOrNull { it.y } ?: (h - 1).toFloat()).toInt()
                val count = IntArray(4); val hit = IntArray(4)
                var y = 0
                while (y < h) {
                    var x = 0
                    while (x < w) {
                        if (on(x, y)) {
                            val c = covered(x, y); count[3]++; if (c) hit[3]++
                            val region = when {
                                inArmBand(x, y) -> 2
                                y >= neck.y.toInt() && y <= root.y.toInt() -> 0
                                y > root.y.toInt() && y <= footEnd -> 1
                                else -> -1
                            }
                            if (region >= 0) { count[region]++; if (c) hit[region]++ }
                        }
                        x += 3
                    }
                    y += 3
                }
                fun share(i: Int) = if (count[i] > 0) hit[i].toDouble() / count[i] else 0.0
                val sT = share(0); val sL = share(1); val sA = share(2); objectShare = share(3)
                readings["objTorso"] = sT; readings["objLegs"] = sL; readings["objArms"] = sA; readings["objBody"] = objectShare
                torso *= (1 - sT).toFloat(); legs *= (1 - sL).toFloat(); arms *= (1 - sA).toFloat()
                val names = objs.take(3).joinToString(" ") { "${it.label} ${"%.2f".format(it.confidence)}" }
                val over = ArrayList<String>()
                if (sT >= 0.05) over.add("torso ${"%.0f".format(sT * 100)}%"); if (sL >= 0.05) over.add("legs ${"%.0f".format(sL * 100)}%"); if (sA >= 0.05) over.add("arms ${"%.0f".format(sA * 100)}%")
                reasons.add(names + (if (over.isEmpty()) " beside the body" else " over " + over.joinToString(", ")))
            }

            // The garment caps, before the pose factors.
            if (caps != null && caps.why.isNotEmpty()) {
                val capped = ArrayList<String>()
                if (torso > caps.torso) { torso = caps.torso; capped.add("torso ${"%.1f".format(caps.torso)}") }
                if (legs > caps.legs) { legs = caps.legs; capped.add("legs ${"%.1f".format(caps.legs)}") }
                if (arms > caps.arms) { arms = caps.arms; capped.add("arms ${"%.1f".format(caps.arms)}") }
                readings["capTorso"] = caps.torso.toDouble(); readings["capLegs"] = caps.legs.toDouble(); readings["capArms"] = caps.arms.toDouble()
                if (capped.isNotEmpty()) reasons.add(caps.why.joinToString(" ") + " caps " + capped.joinToString(", "))
            }

            // Pose, continued.
            val lying = torsoTilt > lyingTilt
            var armsCrossed = false
            if (lS != null && rS != null && lW != null && rW != null) {
                fun midX(y: Float) = lerp(neck, root, ((y - neck.y) / max(1f, root.y - neck.y)).toDouble()).x
                armsCrossed = (Math.signum(lW.x - midX(lW.y)) != Math.signum(lS.x - neck.x)) && (Math.signum(rW.x - midX(rW.y)) != Math.signum(rS.x - neck.x)) &&
                    lW.y > neck.y && lW.y < root.y + 0.2f * (root.y - neck.y)
            }
            val handsHidden = lW == null && rW == null && (lE != null || rE != null)
            var legsCrossed = false
            if (lH != null && rH != null && lA != null && rA != null) legsCrossed = Math.signum(lH.x - rH.x) != Math.signum(lA.x - rA.x) && abs(lA.x - rA.x) > crossedAnkles * torsoLen
            var openness = 1f
            val pose = ArrayList<String>()
            if (lying) { openness = opennessLying; pose.add("lying") }
            else if (sitting) { openness = opennessSitting; pose.add("sitting"); legs *= sittingLegs; torso *= sittingTorso }
            if (!lying && torsoTilt > leanHeavy) { openness = min(openness, opennessClosed); pose.add("leaning ${"%.0f".format(torsoTilt)}°") }
            else if (!lying && torsoTilt > leanLight) { openness = min(openness, opennessLightLean); pose.add("light lean ${"%.0f".format(torsoTilt)}°") }
            if (armsCrossed) { openness = min(openness, opennessClosed); pose.add("arms crossed") }
            if (handsHidden) { openness = min(openness, opennessClosed); pose.add("hands hidden") }
            if (legsCrossed) { openness = min(openness, opennessClosed); pose.add("legs crossed") }
            reasons.addAll(pose)
            readings["kneeDrop"] = kneeDrop; readings["thighTilt"] = thighTilt; readings["kneeAngle"] = kneeAngle; readings["torsoTilt"] = torsoTilt
            readings["sitting"] = if (sitting) 1.0 else 0.0; readings["armsCrossed"] = if (armsCrossed) 1.0 else 0.0; readings["handsHidden"] = if (handsHidden) 1.0 else 0.0; readings["legsCrossed"] = if (legsCrossed) 1.0 else 0.0

            // Image quality: sharpness over the mask, and the body's height in the original.
            var lapSum = 0.0; var lapSq = 0.0; var ln = 0.0
            var yy = 2
            while (yy < h - 2) { var xx = 2; while (xx < w - 2) { if (on(xx, yy)) { val l = 4 * lum(xx, yy) - lum(xx - 1, yy) - lum(xx + 1, yy) - lum(xx, yy - 1) - lum(xx, yy + 1); lapSum += l; lapSq += (l * l).toDouble(); ln += 1 }; xx += 3 }; yy += 3 }
            val sharp = if (ln > 0) lapSq / ln - (lapSum / ln) * (lapSum / ln) else 0.0
            val quality = max(sharpnessFloor, (ramp(sharp, sharpness) ?: 0.0).toFloat())
            val headTop = HEAD.mapNotNull { joint(it) }.minOfOrNull { it.y }
            val footY = listOfNotNull(lA, rA).maxOfOrNull { it.y }
            val bodyPixels = if (headTop != null && footY != null) (footY - headTop) * originalScale else null
            readings["sharpness"] = sharp; readings["bodyPx"] = bodyPixels; legsMerged?.let { readings["legsMerged"] = it }
            var filter: String? = null
            if (bodyPixels != null && bodyPixels < minBodyPixels) filter = "body under ${minBodyPixels.toInt()} px"
            if (sharp < sharpness.second * 0.4) filter = filter ?: "blurred body"
            if (objectShare >= objectBodyGate) filter = filter ?: "objects over ${"%.0f".format(objectShare * 100)}% of the body"
            if (GallerySettings.looseGarmentGateEnabled) {
                val torsoLoose = (readings["torsoFit"] ?: 1.0) <= GallerySettings.looseTorsoFit && (readings["objTorso"] ?: 0.0) < objectBodyGate
                val legsLoose = (readings["legsFit"] ?: 1.0) <= GallerySettings.looseLegsFit && (readings["objLegs"] ?: 0.0) < objectBodyGate && !sitting && !legsCrossed
                val mergedLoose = GallerySettings.looseLegsMergedEnabled && (legsMerged ?: 0.0) >= GallerySettings.looseLegsMergedShare && (readings["objLegs"] ?: 0.0) < objectBodyGate && !sitting && !legsCrossed
                val why = caps?.loose?.let { "loose garment: $it" }
                    ?: if (torsoLoose) "loose garment: torso fit ${"%.2f".format(readings["torsoFit"] ?: 0.0)}" else if (legsLoose) "loose garment: legs fit ${"%.2f".format(readings["legsFit"] ?: 0.0)}"
                    else if (mergedLoose) "loose garment: legs read as one shape on ${"%.0f".format((legsMerged ?: 0.0) * 100)}% of the thigh rows" else null
                if (why != null) { filter = filter ?: "loose garment"; readings["loose"] = 1.0; reasons.add(why) }
            }
            if (quality < 1) reasons.add("sharpness ${"%.2f".format(quality)}")

            // The outfit's colour for the page's session rule.
            fun labMean(a: PointF, b: PointF, halfWidth: Float, from: Double, to: Double): Pair<Int, List<Double>>? {
                var n = 0; var sr = 0f; var sg = 0f; var sb = 0f
                var t = from
                while (t <= to) {
                    val c = lerp(a, b, t); var dx = -halfWidth
                    while (dx <= halfWidth) { val x = (c.x + dx).toInt(); val y = c.y.toInt(); if (on(x, y)) { val p = rgb(x, y); sr += p.first; sg += p.second; sb += p.third; n++ }; dx += 2 }
                    t += 0.02
                }
                return if (n >= 40) n to lab(sr / n, sg / n, sb / n) else null
            }
            val outfit = LinkedHashMap<String, List<Double>>()
            val halfTrunk = ((shoulderW ?: torsoLen * 0.6) * 0.3).toFloat()
            labMean(neck, root, halfTrunk, 0.25, 0.8)?.let { outfit["torso"] = it.second }
            val legSamples = ArrayList<Pair<Int, List<Double>>>()
            for ((hip, knee) in listOf(lH to lK, rH to rK)) { if (hip == null || knee == null) continue; labMean(hip, knee, (torsoLen * 0.08).toFloat(), 0.3, 0.8)?.let { legSamples.add(it) } }
            if (legSamples.isNotEmpty()) { val total = legSamples.sumOf { it.first }.toDouble(); outfit["legs"] = (0 until 3).map { i -> legSamples.sumOf { it.second[i] * it.first } / total } }

            val visibility = 0.4f * torso + 0.4f * legs + 0.2f * arms
            // Debug builds: the render with the mask tinted green, torso rows red, leg rows blue, joints yellow.
            if (GallerySettings.debugDumpMasks && debugName != null) runCatching {
                val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val px = IntArray(w * h); out.getPixels(px, 0, w, 0, 0, w, h)
                for (y in cy0 until cy0 + crop.height) for (x in cx0 until cx0 + crop.width) if (on(x, y)) {
                    val c = px[y * w + x]; px[y * w + x] = Color.rgb((Color.red(c) * 0.6f).toInt(), min(255, (Color.green(c) * 0.6f + 100).toInt()), (Color.blue(c) * 0.6f).toInt())
                }
                out.setPixels(px, 0, w, 0, 0, w, h)
                val canvas = Canvas(out); val paint = Paint().apply { strokeWidth = max(2f, w / 500f) }
                paint.color = Color.RED; for (r in torsoRuns) canvas.drawLine(r.x.toFloat(), r.y.toFloat(), (r.x + r.width).toFloat(), r.y.toFloat(), paint)
                paint.color = Color.BLUE; for (r in legRunsAll) canvas.drawLine(r.x.toFloat(), r.y.toFloat(), (r.x + r.width).toFloat(), r.y.toFloat(), paint)
                paint.color = Color.YELLOW; for (j in listOfNotNull(lS, rS, lH, rH, lK, rK, lA, rA)) canvas.drawCircle(j.x, j.y, max(4f, w / 150f), paint)
                val dir = java.io.File(appContext.filesDir, "scan-debug").also { it.mkdirs() }
                java.io.FileOutputStream(java.io.File(dir, debugName.substringBeforeLast('.') + ".png")).use { out.compress(Bitmap.CompressFormat.PNG, 90, it) }
                out.recycle()
            }.onFailure { Log.w(TAG, "mask dump failed: ${it.message}") }
            val groups = listOf(visibility, openness, quality)
            val t = weights.zip(groups).fold(1f) { acc, (wt, g) -> acc * g.pow(wt.second) }
            val value = if (filter == null) t else 0f
            val parts = listOf("torso" to torso, "legs" to legs, "arms" to arms, "openness" to openness, "quality" to quality)
            val noTier = filter != null || (GallerySettings.torsoUnreadNoTier && readings["torsoFit"] == null) || (GallerySettings.sittingNoTier && sitting)
            val tier = if (noTier) null else if (t >= GallerySettings.fullLengthTierALine) "A" else if (t >= GallerySettings.fullLengthUsableFloor) "B" else null
            return BodyScore(value, tier, parts, reasons, filter, readings, if (outfit.isEmpty()) null else outfit)
        }
    }
}
