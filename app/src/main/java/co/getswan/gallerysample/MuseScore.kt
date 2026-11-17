package co.getswan.gallerysample

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** How well a matched photo serves the Muse. The selfie half of `MuseScore.swift`, ported gate for
 *  gate and signal for signal; the body half comes with group C. Readings iOS takes from Vision
 *  (capture quality, classifier labels, hands, document page) are not available here yet: quality
 *  reads 0.5, labels are empty, so the sunglasses, cap, scarf, glasses and document rules stay quiet. */
class MuseScore(
    face: RectF, landmarks: List<PointF>, bitmap: Bitmap, faceWidth: Float,
    val expression: FaceExpression?, val labels: Map<String, Float> = emptyMap(),
    val handOnFace: Boolean = false, val handNearFace: Boolean = false,
    outfit: Outfit = Outfit(0f, 0f), selfie: Boolean = false, gates: SelfieGates? = null,
    coverReader: ((FaceExpression?) -> FaceCover?)? = null, val document: DocumentRead? = null,
) {
    data class Outfit(val dressy: Float, val busy: Float)
    /** What the v2 selfie gates read beyond the face. Selfies only. */
    data class SelfieGates(val eyeSpacingOriginal: Float?, val originalScale: Float, val originalLongSide: Float, val camera: Original.Camera?)
    enum class Smile { NONE, CLOSED, OPEN }

    var value: Float = 0f; private set
    var parts: List<Pair<String, Float>> = emptyList(); private set
    val filter: String?
    val capped: Boolean; val tilted: Boolean; val smile: Smile; val wideEyed: Boolean; val lowered: List<String>
    val demoted: Boolean
    var tier: String?; private set
    var reasons: List<String>; private set
    var signals: Map<String, List<Pair<String, Float>>>; private set
    val signalHows: HashMap<String, String>
    val wideEyesV2: Boolean; val dressy: Boolean; val busy: Boolean
    val readings = LinkedHashMap<String, Double>()
    /** The eye regions' brightness over the cheeks under them; dark lenses read well under 1. See `sunglassesEyeDarkLine`. */
    val eyeDark: Float? = eyeDarkness(bitmap, landmarks)
    /** Brightest tenth against darkest tenth inside the eye boxes: sclera against iris on a bare or clear-glassed
     *  eye, flat behind a dark or mirrored lens. Logged for the sunglasses line. */
    val eyeContrast: Float? = eyeContrast(bitmap, landmarks)
    val cameraCm: Float?; val focalAssumed: Boolean; val cover: FaceCover?

    init {
        val S = GallerySettings
        val parts = ArrayList<Pair<String, Float>>()
        var filter: String? = null
        var smile = Smile.NONE; var wideEyed = false; val lowered = ArrayList<String>()

        // Filters for every photo. A document first, so it is the named filter. (Sunglasses and hands need readers not ported yet.)
        if (GallerySettings.documentGateEnabled && document?.isDocument == true) filter = "document"
        val sunglasses = (labels["sunglasses"] ?: 0f) >= S.sunglassesConfidence &&
            (!GallerySettings.sunglassesMustBeatGlasses || (labels["sunglasses"] ?: 0f) > (labels["eyeglasses"] ?: 0f)) &&
            (GallerySettings.sunglassesEyeDarkLine?.let { line -> eyeDark != null && eyeDark <= line } ?: true)
        if (sunglasses) filter = filter ?: "sunglasses"
        if (handOnFace) filter = filter ?: "hand over face"
        if (selfie && handNearFace) filter = filter ?: "hand near face"
        val capped = labels.any { HAT_LABELS.contains(it.key) && it.value >= HAT_CONFIDENCE }
        if (face.left < 0.01f || face.right > 0.99f || face.top < 0.01f || face.bottom > 0.99f) filter = "face cut by the frame"

        // Face readings and pre-v2 parts. No capture quality on Android: neutral.
        val quality = 0.5f
        parts.add("quality" to clamp01(quality))
        // Pose from the landmarker's head transform (iOS reads Vision here and MediaPipe for the gates).
        val yaw = abs(expression?.headYaw ?: 0f); val pitch = abs(expression?.headPitch ?: 0f); val roll = abs(expression?.headRoll ?: 0f)
        val pose = clamp01(1 - yaw / POSE_LIMIT_DEGREES) * clamp01(1 - pitch / POSE_LIMIT_DEGREES) * clamp01(1 - roll / (POSE_LIMIT_DEGREES * 0.66f))
        parts.add("pose" to pose)
        val tilted = selfie && (pitch > TILTED_PITCH_DEGREES || yaw > TILTED_YAW_DEGREES)

        // Light, from a 64 px copy of the face with a little margin.
        val sized = RectF(face).also { val k = (1 - S.faceStatsBoxScale) / 2; it.inset(face.width() * k, face.height() * k) }
        val pad = RectF(sized).also { it.inset(-sized.width() * 0.1f, -sized.height() * 0.1f); it.intersect(0f, 0f, 1f, 1f) }
        val faceStats = regionStats(bitmap, pad, 64)
        val imageStats = regionStats(bitmap, RectF(0f, 0f, 1f, 1f), 32)
        var light = 0.5f
        if (faceStats != null) {
            val f = faceStats
            val exposure = clamp01(1 - abs(f.mean - 0.5f) / 0.35f)
            val clip = clamp01(1 - f.clipped / 0.2f)
            val balance = clamp01(1 - abs(f.left - f.right) / 0.25f)
            val backlit = clamp01(1 - ((imageStats?.mean ?: f.mean) - f.mean - 0.05f) / 0.25f)
            val cast = colourCast(f.r, f.g, f.b)
            light = (exposure + clip + balance + backlit + cast) / 5
            if (f.mean < 0.08f || f.mean > 0.92f) { if (gates == null) lowered.add("exposure") else filter = filter ?: "exposure" }
            if (f.laplacian < BLUR_VARIANCE) filter = filter ?: "blurred"
        }
        parts.add("light" to light)
        parts.add("size" to clamp01((faceWidth - 120) / (FULL_SIZE_PIXELS - 120)))

        // Mouth, eyes and brows from the expression reader; neutral without it.
        var mouth = 0.7f; var eyes = 0.7f; var brow = 0.7f
        val e = expression
        if (e != null) {
            mouth = clamp01(1 - e.smile) * clamp01(1 - e.jawOpen)
            eyes = clamp01((CLOSED_BLINK - e.blinkMax) / (CLOSED_BLINK - 0.1f))
            brow = clamp01(1 - e.browUp / 0.5f) * clamp01(e.squint / 0.15f)
            if (e.blinkBoth > CLOSED_BLINK) filter = filter ?: "eyes closed"
            else if (e.blinkMax >= WINK_BLINK) filter = filter ?: "one eye closed"
            else if (UNEVEN_BLINK_DROPS && e.blinkMax >= UNEVEN_BLINK && e.blinkMax - e.blinkBoth >= UNEVEN_BLINK_GAP) filter = filter ?: "one eye closed"
            val lipsParted = e.jawOpen >= LIPS_PARTED_JAW || e.teeth >= TEETH_SHOWING
            if (e.smile >= SMILE_FILTER) {
                smile = if (lipsParted) Smile.OPEN else Smile.CLOSED
                if (e.browUp >= SMILE_BROW_UP || e.eyeWide >= SMILE_EYE_WIDE) wideEyed = true
            } else if (e.jawOpen >= MOUTH_OPEN_FILTER && gates == null) lowered.add("mouth open")
            if ((e.browUp >= STARING_BROW && e.squint < STARING_SQUINT_BELOW) || (e.browUp >= WIDE_EYED_BROW && e.squint < WIDE_EYED_SQUINT_BELOW) || e.eyeWide >= EYE_WIDE_FILTER) wideEyed = true
        }
        parts.add("mouth" to mouth); parts.add("eyes" to eyes); parts.add("brow" to brow)

        // Selfie gates (v2). The first filter found stays the named one.
        var cameraCm: Float? = null; var focalAssumed = false; var cover: FaceCover? = null; var headVsFloor: Float? = null
        val signedPitch: Float? = if (S.mediaPipePitchEnabled) e?.headPitch else null
        val selfiePitch = signedPitch?.let { abs(it) } ?: pitch
        val mediaPipeRoll: Float? = if (S.mediaPipeRollEnabled) e?.headRoll?.let { abs(it) } else null
        val selfieRoll = mediaPipeRoll ?: roll
        if (gates != null) {
            if (selfiePitch > S.gatePitchDegrees) filter = filter ?: "head pitched"
            S.cameraBelowGate?.let { line -> if (signedPitch != null && signedPitch >= line) filter = filter ?: "camera below" }
            S.cameraAboveGate?.let { (ratioLine, pitchLine) -> val ratio = e?.faceRatio; if (ratio != null && ratio >= ratioLine && pitch >= pitchLine) filter = filter ?: "camera above" }
            if (yaw > S.gateYawDegrees) filter = filter ?: "head turned"
            if (S.rollGateEnabled && selfieRoll >= S.gateRollDegrees) filter = filter ?: "head tilted"
            if (e != null) {
                if (e.jawOpen >= MOUTH_OPEN_FILTER) filter = filter ?: "mouth open"
                if (e.smile < SMILE_KEEPS_TEETH && e.teeth >= TEETH_SHOWING) filter = filter ?: "teeth showing"
                if (S.lookingAwayGateEnabled) S.gazeOffset(e)?.let { (side, vert) ->
                    if (hypot(side, vert) >= S.lookingAwayDegrees) filter = filter ?: "looking away"
                    else if (vert <= -S.lookingUpDegrees) filter = filter ?: "looking up"
                    else if (vert >= S.lookingDownDegrees) filter = filter ?: "looking down"
                    else S.lookingDownConfirmed?.let { (deg, mp) -> if (vert >= deg && e.gazeDown >= mp) filter = filter ?: "looking down" }
                }
            }
            // The face contour at the frame's edge (the landmarker's points in the photo). The top is the box test's.
            if (landmarks.any { it.x < 0.01f || it.x > 0.99f || it.y < 0.01f }) filter = filter ?: "face cut by the frame"
            // Camera to face from the focal length in the original's pixels and the iris or the eye spacing.
            val focal35: Float? = when {
                gates.camera?.focal35mm != null -> gates.camera.focal35mm
                gates.camera?.focalMm != null -> null
                else -> { focalAssumed = true; S.assumedFocal35mm }
            }
            if (focal35 != null) {
                val focalPx = focal35 / 36 * gates.originalLongSide
                val iris = e?.irisPixels
                if (iris != null && iris > 0) cameraCm = focalPx * S.irisMm / (iris * gates.originalScale) / 10
                else gates.eyeSpacingOriginal?.let { eyesPx -> if (eyesPx > 0) cameraCm = focalPx * S.eyeSpacingMm / eyesPx / 10 }
            }
            // The head against the floor: phone tilt (assumed on Android, see `assumedPhoneTiltDegrees`), plus the angle
            // up to the face from the lens axis, less MediaPipe's pitch. iOS reads the real tilt from Apple's maker note.
            val mpPitch = e?.headPitch
            if (focal35 != null && mpPitch != null) {
                val tilt = gates.camera?.tiltUp ?: S.assumedPhoneTiltDegrees
                val focalRender = focal35 / 36 * maxOf(bitmap.width, bitmap.height)
                val faceAbove = (0.5f - face.centerY()) * bitmap.height
                val down = tilt + atan(faceAbove / focalRender) * 180f / PI.toFloat() - mpPitch
                headVsFloor = down
                if (S.headLevelGateEnabled) {
                    if (down >= S.headDownDegrees) filter = filter ?: "head down"
                    else if (down <= -S.headUpDegrees) filter = filter ?: "head up"
                }
            }
            if (S.tooCloseGateEnabled && cameraCm != null && cameraCm < S.tooCloseCm) filter = filter ?: "too close"
            if (S.noCameraDataGateEnabled && gates.camera?.present == false) filter = filter ?: "no camera data"
            S.tooDarkISO?.let { line -> gates.camera?.iso?.let { iso -> if (iso >= line) filter = filter ?: "too dark" } }
            if (S.scarfGateEnabled && (labels["scarf"] ?: 0f) >= S.scarfConfidence) filter = filter ?: "scarf or high collar"
            if (capped) filter = filter ?: "cap or hat"
            if (filter == null) cover = coverReader?.invoke(e)
            cover?.let { c ->
                if (c.hairline >= S.coverHairlineLimit) filter = filter ?: "hairline covered"
                if (c.jaw >= S.coverJawLimit) filter = filter ?: "jaw covered"
                if (c.face >= S.coverFaceLimit) filter = filter ?: "face covered"
            }
        }
        this.cameraCm = cameraCm; this.focalAssumed = focalAssumed; this.cover = cover

        // Sharpness on a log scale: variance 1e-3 scores 0, 3e-2 scores 1.
        val sharp = faceStats?.let { clamp01((log10(max(it.laplacian, 1e-8f)) + 3) / 1.5f) } ?: 0.5f
        parts.add("sharp" to sharp)

        // Raw readings for the page.
        readings["yaw"] = fixed(yaw, 1); readings["pitch"] = fixed(pitch, 1); readings["roll"] = fixed(roll, 1)
        readings["faceWidth"] = Math.round(faceWidth).toDouble(); readings["quality"] = fixed(quality, 3)
        readings["sunglasses"] = fixed(labels["sunglasses"] ?: 0f, 2); eyeDark?.let { readings["eyeDark"] = fixed(it, 2) }; eyeContrast?.let { readings["eyeContrast"] = fixed(it, 2) }
        readings["cap"] = fixed(labels.filter { HAT_LABELS.contains(it.key) }.values.maxOrNull() ?: 0f, 2)
        readings["handOnFace"] = if (handOnFace) 1.0 else 0.0; readings["handNearFace"] = if (handNearFace) 1.0 else 0.0
        readings["dressy"] = fixed(outfit.dressy, 2); readings["busy"] = fixed(outfit.busy, 3)
        document?.let { readings["docLines"] = it.lines.toDouble(); readings["docNearFace"] = it.nearFace.toDouble(); readings["docTextArea"] = fixed(it.textArea, 3) }
        faceStats?.let { f ->
            readings["brightness"] = fixed(f.mean, 3); readings["clipped"] = fixed(f.clipped, 3)
            readings["backlight"] = fixed((imageStats?.mean ?: f.mean) - f.mean, 3); readings["blur"] = fixed(f.laplacian, 5)
        }
        if (e != null) {
            readings["smile"] = fixed(e.smile, 2); readings["jawOpen"] = fixed(e.jawOpen, 2); readings["blink"] = fixed(e.blinkMax, 2)
            readings["browUp"] = fixed(e.browUp, 2); readings["squint"] = fixed(e.squint, 2); readings["eyeWide"] = fixed(e.eyeWide, 2)
            readings["teeth"] = fixed(e.teeth, 2); readings["pucker"] = fixed(e.pucker, 2); readings["funnel"] = fixed(e.funnel, 2)
            readings["press"] = fixed(e.press, 2); readings["browDown"] = fixed(e.browDown, 2)
            readings["gazeUp"] = fixed(e.gazeUp, 2); readings["gazeDown"] = fixed(e.gazeDown, 2); readings["gazeSide"] = fixed(e.gazeSide, 2)
            e.eyeYaw?.let { readings["eyeYaw"] = fixed(it, 2) }; e.eyePitch?.let { readings["eyePitch"] = fixed(it, 2) }
            if (e.eyeYaw != null) { readings["gazeRestYaw"] = fixed(S.gazeRest.first, 2); readings["gazeRestPitch"] = fixed(S.gazeRest.second, 2) }
            e.faceRatio?.let { readings["faceRatio"] = fixed(it, 3) }; e.faceDetail?.let { readings["faceDetail"] = fixed(it, 3) }
            e.lidGap?.let { readings["lidGap"] = fixed(it, 3) }
            e.headPitch?.let { readings["mpPitch"] = fixed(it, 2) }; e.headYaw?.let { readings["mpYaw"] = fixed(it, 2) }; e.headRoll?.let { readings["mpRoll"] = fixed(it, 2) }
        }
        cameraCm?.let { readings["cameraCm"] = fixed(it, 1) }
        if (gates != null) {
            e?.irisPixels?.let { readings["irisPx"] = fixed(it * gates.originalScale, 1) }
            gates.eyeSpacingOriginal?.let { readings["eyeSpacingPx"] = fixed(it, 1) }
            gates.camera?.focal35mm?.let { readings["focal35"] = fixed(it, 1) }
            gates.camera?.tiltUp?.let { readings["cameraTilt"] = fixed(it, 1) }
            headVsFloor?.let { readings["headVsFloor"] = fixed(it, 1); readings["tiltAssumed"] = if (gates.camera?.tiltUp == null) 1.0 else 0.0 }
            gates.camera?.iso?.let { readings["iso"] = fixed(it, 0) }
            gates.camera?.exposure?.let { readings["exposureMs"] = fixed(it * 1000, 2) }
            readings["focalAssumed"] = if (focalAssumed) 1.0 else 0.0
            gates.camera?.let { readings["cameraData"] = if (it.present) 1.0 else 0.0 }
        }
        cover?.let { c ->
            c.skinSideRatio?.let { readings["skinSideRatio"] = fixed(it, 3) }
            readings["coverHairline"] = fixed(c.hairline, 2); readings["coverJaw"] = fixed(c.jaw, 2); readings["coverFace"] = fixed(c.face, 2)
        }

        this.capped = capped; this.tilted = tilted; this.smile = smile; this.wideEyed = wideEyed; this.lowered = lowered
        val dressy = outfit.dressy >= DRESSY_CONFIDENCE; val busy = outfit.busy >= S.busyTorso
        this.dressy = dressy; this.busy = busy

        // Pre-v2 score: bands and multipliers.
        val weighted = WEIGHTS.zip(parts).fold(0f) { acc, (w, p) -> acc + w.second * p.second }
        var multiplier = 1f
        if (capped) multiplier *= CAP_MULTIPLIER
        if (tilted) multiplier *= TILT_MULTIPLIER
        if (smile == Smile.CLOSED) multiplier *= CLOSED_SMILE_MULTIPLIER
        if (smile == Smile.OPEN) multiplier *= OPEN_SMILE_MULTIPLIER
        if (wideEyed) multiplier *= WIDE_EYED_MULTIPLIER
        for (unused in lowered) multiplier *= LOWERED_MULTIPLIER
        val demoted = multiplier < 1
        this.demoted = demoted
        if (dressy) multiplier *= OUTFIT_MULTIPLIER
        if (busy) multiplier *= OUTFIT_MULTIPLIER
        val banded = if (demoted) 0.5f * weighted * multiplier else 0.5f + 0.5f * weighted * multiplier

        // Selfie score (v2): a weighted geometric mean of six signal groups.
        var tier: String? = null
        val reasons = ArrayList<String>()
        val groupSignals = LinkedHashMap<String, List<Pair<String, Float>>>()
        val hows = HashMap<String, String>()
        var wideEyesV2 = false
        if (S.selfieScoreV2 && gates != null) {
            fun ramped(why: String, reading: Float, curve: Pair<Float, Float>): Pair<String, Float> {
                val v = ramp(reading, curve.first, curve.second); hows[why] = S.rampHow(reading, curve, v); return why to v
            }
            fun noted(why: String, v: Float, how: String): Pair<String, Float> { hows[why] = how; return why to v }
            fun lowest(group: String, list: List<Pair<String, Float>>): Float {
                groupSignals[group] = list
                val low = list.minByOrNull { it.second } ?: return 1f
                if (low.second < 1) reasons.add(low.first)
                return low.second
            }
            val poseSignals = arrayListOf(
                ramped("pitch %.0f°".format(selfiePitch), selfiePitch, S.posePitch),
                ramped("yaw %.0f°".format(yaw), yaw, S.poseYaw),
                ramped("roll %.0f°".format(selfieRoll), selfieRoll, if (mediaPipeRoll == null) S.poseRoll else S.mediaPipeRoll),
            )
            cameraCm?.let { poseSignals.add(ramped("camera %.0f cm".format(it), it, S.poseDistance)) }
            S.cameraBelowSlope?.let { slope -> if (signedPitch != null && signedPitch > slope.first) {
                val sloped = ramp(signedPitch, slope.first, slope.second)
                poseSignals.add(noted("camera below %+.1f°".format(signedPitch), max(sloped, S.cameraBelowCap),
                    "(${shortNumber(slope.second)} − ${shortNumber(signedPitch)}) ÷ (${shortNumber(slope.second)} − ${shortNumber(slope.first)}) = ${"%.2f".format(sloped)}" + (if (sloped < S.cameraBelowCap) ", never under ${shortNumber(S.cameraBelowCap)}" else "")))
            } }
            val poseGroup = lowest("pose", poseSignals)

            val lightSignals = ArrayList<Pair<String, Float>>()
            faceStats?.let { f ->
                val bright = max(f.left, f.right); val dark = max(minOf(f.left, f.right), 0.01f)
                lightSignals.add(ramped("uneven light %.1f×".format(bright / dark), bright / dark, S.lightEvenness))
                imageStats?.let { whole -> val ratio = f.mean / max(whole.mean, 0.01f); lightSignals.add(ramped("backlit %.2f".format(ratio), ratio, S.lightBacklight)) }
                lightSignals.add(ramped("blown %.0f%%".format(f.blown * 100), f.blown * 100, S.lightClipped.first * 100 to S.lightClipped.second * 100))
                lightSignals.add(noted("colour cast", colourCast(f.r, f.g, f.b), "red ${shortNumber(f.r)}, green ${shortNumber(f.g)}, blue ${shortNumber(f.b)}: red ≥ green ≥ blue is skin-like, 1 (0.7 when red − blue is over 0.5); any other order 0.6"))
            }
            S.lowLightISO?.let { curve -> gates.camera?.iso?.let { iso -> lightSignals.add(ramped("low light, ISO %.0f".format(iso), iso, curve)) } }
            val lightGroup = lowest("light", lightSignals)

            val gazeSignals = ArrayList<Pair<String, Float>>()
            S.gazeCurve?.let { curve -> S.gazeOff(e)?.let { off -> gazeSignals.add(ramped("eyes %.0f° off the screen".format(off), off, curve)) } }
            val gazeGroup = lowest("gaze", gazeSignals)

            val expressionSignals = ArrayList<Pair<String, Float>>()
            val smileReading = e?.let { "smile ${shortNumber(it.smile)}" } ?: "a smile from the lip shape"
            if (smile == Smile.CLOSED) expressionSignals.add(noted("closed smile", S.expressionCapped, "$smileReading, lips closed: capped at ${shortNumber(S.expressionCapped)}"))
            if (smile == Smile.OPEN) expressionSignals.add(noted("open smile", S.expressionOpenSmile, "$smileReading, lips parted or teeth showing: capped at ${shortNumber(S.expressionOpenSmile)}"))
            val wideEyes = wideEyed || (e?.eyeWide ?: 0f) >= S.wideEyesLine
            if (wideEyes) expressionSignals.add(noted("wide-eyed", S.expressionCapped, "eyes wide ${shortNumber(e?.eyeWide ?: 0f)} (counts from ${shortNumber(S.wideEyesLine)}), or brows up with a smile: capped at ${shortNumber(S.expressionCapped)}"))
            if (e != null) {
                if (e.pucker >= S.puckerLimit) expressionSignals.add(noted("pucker %.2f".format(e.pucker), S.expressionCapped, "${shortNumber(e.pucker)} is at or over ${shortNumber(S.puckerLimit)}: capped at ${shortNumber(S.expressionCapped)}"))
                if (e.browDown >= S.frownLimit) expressionSignals.add(noted("frown %.2f".format(e.browDown), S.frownCap, "brows pulled down ${shortNumber(e.browDown)}, at or over ${shortNumber(S.frownLimit)}: capped at ${shortNumber(S.frownCap)}"))
            }
            val expressionGroup = lowest("expression", expressionSignals)

            val visibilitySignals = ArrayList<Pair<String, Float>>()
            val glassesOn = (labels["eyeglasses"] ?: 0f) >= S.glassesConfidence
            cover?.let { c ->
                val visible = 1 - max(0f, c.face - (if (glassesOn) S.glassesOvalAllowance else 0f))
                visibilitySignals.add(ramped("face %.0f%% visible".format(visible * 100), visible * 100, S.visibilityShare.first * 100 to S.visibilityShare.second * 100))
            }
            if (glassesOn) visibilitySignals.add(noted("glasses", S.glassesCap, "glasses label ${shortNumber(labels["eyeglasses"] ?: 0f)}, at or over ${shortNumber(S.glassesConfidence)}: capped at ${shortNumber(S.glassesCap)}"))
            val visibilityGroup = lowest("visibility", visibilitySignals)

            val detailSignals = arrayListOf(noted("sharpness %.2f".format(sharp), sharp, "the face's sharpness, 0–1, used as it is"))
            gates.eyeSpacingOriginal?.let { detailSignals.add(ramped("eyes %.0f px apart".format(it), it, S.detailEyeSpacing)) }
            val detailGroup = lowest("detail", detailSignals)

            val groups = listOf(poseGroup, lightGroup, gazeGroup, expressionGroup, visibilityGroup, detailGroup)
            val t = S.scoreWeightsV2.zip(groups).fold(1f) { acc, (w, g) -> acc * g.pow(w.second) }
            this.parts = S.scoreWeightsV2.zip(groups).map { (w, g) -> w.first to g }
            this.value = if (filter == null) t else 0f
            val eyesTooSmall = (gates.eyeSpacingOriginal ?: Float.POSITIVE_INFINITY) < S.tierAEyeSpacingMin
            val eyesWhy = "eyes %.0f px apart".format(gates.eyeSpacingOriginal ?: 0f)
            if (eyesTooSmall && !reasons.contains(eyesWhy)) reasons.add(eyesWhy)
            val tooFar = S.tooFarDemotes && (cameraCm ?: 0f) > S.tooFarCm
            if (tooFar) reasons.add("camera %.0f cm away".format(cameraCm ?: 0f))
            if (filter == null) tier = if (t >= S.tierALine && !eyesTooSmall && !tooFar) "A" else "B"
            wideEyesV2 = wideEyes
        } else {
            // Pre-v2 score, for a face with neither v2 score.
            this.parts = parts
            this.value = if (filter == null) banded else 0f
        }
        this.filter = filter; this.tier = tier; this.reasons = reasons
        this.signals = groupSignals; this.signalHows = hows; this.wideEyesV2 = wideEyesV2
    }

    private fun recomputeTier(oldTier: String?, oldValue: Float) {
        val S = GallerySettings
        value = S.scoreWeightsV2.zip(parts).fold(1f) { acc, (w, p) -> acc * p.second.pow(w.second) }
        // A Tier B over the line was held there by the eye spacing or the distance, which stand.
        val spacingHeld = oldTier == "B" && oldValue >= S.tierALine
        val farHeld = S.tooFarDemotes && (readings["cameraCm"] ?: 0.0) > S.tooFarCm.toDouble()
        tier = if (value >= S.tierALine && !spacingHeld && !farHeld) "A" else "B"
    }

    /** Gaze measured again from another resting point. False when there is nothing to re-measure or nothing changed. */
    fun regaze(rest: Pair<Float, Float>): Boolean {
        val S = GallerySettings
        if (filter != null || tier == null) return false
        val curve = S.gazeCurve ?: return false
        val off = S.gazeOffset(expression, rest)?.let { hypot(it.first, it.second) } ?: return false
        val index = parts.indexOfFirst { it.first == "gaze" }; if (index < 0) return false
        val oldValue = value; val oldTier = tier
        val gaze = ramp(off, curve.first, curve.second)
        val why = "eyes %.0f° off the screen".format(off)
        val oldWhy = signals["gaze"]?.firstOrNull()?.first
        parts = parts.mapIndexed { i, p -> if (i == index) "gaze" to gaze else p }
        signals = signals + ("gaze" to listOf(why to gaze))
        signalHows[why] = S.rampHow(off, curve, gaze)
        reasons = reasons.filter { it != oldWhy } + (if (gaze < 1) listOf(why) else emptyList())
        recomputeTier(oldTier, oldValue)
        readings["gazeRestYaw"] = twoPlaces(rest.first); readings["gazeRestPitch"] = twoPlaces(rest.second)
        return abs(value - oldValue) > 0.0005f || tier != oldTier
    }

    /** The frown judged against the shopper's own line: under it, it no longer caps expression. False when not capped by a frown. */
    fun refrown(line: Float): Boolean {
        if (filter != null || tier == null) return false
        val brow = expression?.browDown ?: return false
        if (brow < GallerySettings.frownLimit || brow >= line) return false
        val index = parts.indexOfFirst { it.first == "expression" }; if (index < 0) return false
        val oldValue = value; val oldTier = tier
        val frownWhy = "frown %.2f".format(brow)
        val kept = (signals["expression"] ?: emptyList()).filter { it.first != frownWhy }
        signals = signals + ("expression" to kept)
        signalHows.remove(frownWhy)
        reasons = reasons.filter { it != frownWhy }
        parts = parts.mapIndexed { i, p -> if (i == index) "expression" to (kept.minOfOrNull { it.second } ?: 1f) else p }
        recomputeTier(oldTier, oldValue)
        readings["frownLine"] = twoPlaces(line)
        return abs(value - oldValue) > 0.0005f || tier != oldTier
    }

    /** Sent to the page with the match, next to `score`. Same fields as `MuseScore.breakdown`. */
    fun breakdown(): JSONObject {
        val fields = JSONObject()
        fields.put("scoreParts", JSONObject().also { o -> parts.forEach { (n, v) -> o.put(n, twoPlaces(v)) } })
        fields.put("scoreInputs", JSONObject(readings as Map<*, *>))
        if (tier != null) fields.put("tier", tier) else {
            val penalties = ArrayList<String>()
            if (capped) penalties.add("cap"); if (tilted) penalties.add("tilted")
            if (smile == Smile.CLOSED) penalties.add("closed smile"); if (smile == Smile.OPEN) penalties.add("open smile")
            if (wideEyed) penalties.add("wide-eyed"); penalties.addAll(lowered)
            if (dressy) penalties.add("dressy"); if (busy) penalties.add("busy")
            fields.put("scorePenalties", JSONArray(penalties))
        }
        if (wideEyesV2) fields.put("wideEyes", true)
        filter?.let { fields.put("scoreFilter", it) }
        if (tier != null) fields.put("scoreReasons", JSONArray(reasons))
        if (signals.isNotEmpty()) fields.put("scoreSignals", JSONObject().also { o ->
            signals.forEach { (group, list) -> o.put(group, JSONArray().also { a -> list.forEach { (why, v) -> a.put(JSONObject().put("why", why).put("value", twoPlaces(v)).put("how", signalHows[why] ?: "")) } }) }
        })
        return fields
    }

    companion object {
        // Values from MuseScore.swift.
        val WEIGHTS = listOf("quality" to 0.25f, "pose" to 0.15f, "light" to 0.10f, "size" to 0.10f, "mouth" to 0.15f, "eyes" to 0.10f, "brow" to 0.10f, "sharp" to 0.05f)
        const val SUNGLASSES_CONFIDENCE = 0.4f; const val HAT_CONFIDENCE = 0.5f
        val HAT_LABELS = setOf("hat", "baseball_hat", "sunhat", "cowboy_hat", "beanie", "helmet", "hardhat")
        const val CAP_MULTIPLIER = 0.6f; const val CLOSED_BLINK = 0.5f; const val SMILE_FILTER = 0.5f; const val SMILE_KEEPS_TEETH = 0.3f
        const val LIPS_PARTED_JAW = 0.15f; const val TEETH_SHOWING = 0.2f; const val CLOSED_SMILE_MULTIPLIER = 0.6f; const val OPEN_SMILE_MULTIPLIER = 0.4f
        const val SMILE_BROW_UP = 0.25f; const val SMILE_EYE_WIDE = 0.05f; const val DRESSY_CONFIDENCE = 0.3f; const val BUSY_TORSO = 0.11f; const val OUTFIT_MULTIPLIER = 0.8f
        val DRESSY_LABELS = setOf("suit", "tuxedo", "necktie", "bowtie", "jacket", "sari", "gown", "wedding_dress", "kimono", "costume")
        const val MOUTH_OPEN_FILTER = 0.3f; const val WINK_BLINK = 0.5f; const val UNEVEN_BLINK_DROPS = true; const val UNEVEN_BLINK = 0.35f; const val UNEVEN_BLINK_GAP = 0.2f
        const val STARING_BROW = 0.6f; const val STARING_SQUINT_BELOW = 0.25f; const val WIDE_EYED_BROW = 0.25f; const val WIDE_EYED_SQUINT_BELOW = 0.10f
        const val EYE_WIDE_FILTER = 0.08f; const val WIDE_EYED_MULTIPLIER = 0.4f; const val LOWERED_MULTIPLIER = 0.4f
        const val POSE_LIMIT_DEGREES = 30f; const val TILTED_PITCH_DEGREES = 15f; const val TILTED_YAW_DEGREES = 20f; const val TILT_MULTIPLIER = 0.6f
        const val FULL_SIZE_PIXELS = 400f; const val BLUR_VARIANCE = 5e-4f

        /** 1 for skin-like red ≥ green ≥ blue, 0.7 when red − blue is over 0.5, 0.6 for any other order. */
        fun colourCast(r: Float, g: Float, b: Float): Float = if (r >= g && g >= b) (if (r - b > 0.5f) 0.7f else 1f) else 0.6f

        /** The same for a body-scored front or side: Tier A sends 1 + R, else T. */
        fun orderFieldsForBody(takenAt: Long, body: BodyScore): JSONObject {
            val fields = JSONObject()
            if (!GallerySettings.orderV2Enabled) return fields
            fields.put("takenAt", takenAt)
            val months = (System.currentTimeMillis() - takenAt) / (30.44 * 24 * 3600 * 1000)
            val curve = GallerySettings.fullLengthRecency
            val recency = ((curve.second - months) / (curve.second - curve.first)).coerceIn(0.0, 1.0)
            val t = body.value.toDouble()
            if (body.tier != "A") { fields.put("order", t); return fields }
            fields.put("orderParts", JSONObject().put("recency", recency))
            fields.put("order", 1 + 0.8 * t + 0.2 * recency)
            return fields
        }

        /** `order` and `takenAt` for one match. Tier A sends 1 + R so it always ranks above Tier B, which sends T. */
        fun orderFields(takenAt: Long, favourite: Boolean, selfie: Boolean, score: MuseScore): JSONObject {
            val fields = JSONObject()
            if (!GallerySettings.orderV2Enabled) return fields
            fields.put("takenAt", takenAt)
            val months = (System.currentTimeMillis() - takenAt) / (30.44 * 24 * 3600 * 1000)
            val curve = if (selfie) GallerySettings.selfieRecency else GallerySettings.fullLengthRecency
            val recency = ((curve.second - months) / (curve.second - curve.first)).coerceIn(0.0, 1.0)
            val t = score.value.toDouble()
            val tierA = score.tier?.let { it == "A" } ?: !score.demoted
            if (!tierA) { fields.put("order", t); return fields }
            val r: Double
            if (selfie) {
                // Only the favourite flag exists on Android; iOS also counts edits and burst picks.
                val choices = if (favourite) 0.5 else 0.0
                val p = 0.7 * 0.5 + 0.3 * minOf(choices, 1.0)
                r = 0.45 * t + 0.40 * p + 0.15 * recency
                fields.put("orderParts", JSONObject().put("choices", choices).put("personal", p).put("recency", recency))
            } else {
                r = 0.8 * t + 0.2 * recency
                fields.put("orderParts", JSONObject().put("recency", recency))
            }
            fields.put("order", 1 + r)
            return fields
        }
    }
}

/** Mean luma of the skin just above and below each eye (inside a lens, outside the eyeball) over the cheeks
 *  under them, from the landmarks (irises and eye corners, or ML Kit's eye centres). Dark lenses darken that
 *  skin; a bare eye or clear glasses leave it as bright as the cheek. Null without points. */
private fun eyeDarkness(bitmap: Bitmap, p: List<PointF>): Float? {
    val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
    val eyes: List<Pair<PointF, Float>>; val cheeks: List<PointF>
    when {
        p.size >= 478 -> {
            fun width(a: Int, b: Int) = hypot((p[a].x - p[b].x) * w, (p[a].y - p[b].y) * h)
            eyes = listOf(p[468] to width(33, 133), p[473] to width(362, 263)); cheeks = listOf(p[50], p[280])
        }
        p.size == 5 -> {
            val span = hypot((p[0].x - p[1].x) * w, (p[0].y - p[1].y) * h)
            eyes = listOf(p[0] to span * 0.4f, p[1] to span * 0.4f)
            cheeks = eyes.map { PointF(it.first.x, it.first.y + span * 0.45f / h) }
        }
        else -> return null
    }
    fun mean(c: PointF, px: Float, dy: Float = 0f): Float? {
        val hw = px * 0.4f / w; val hh = px * 0.12f / h; val cy = c.y + dy * px / h
        return regionStats(bitmap, RectF(c.x - hw, cy - hh, c.x + hw, cy + hh), 12)?.mean
    }
    // The bands 0.45 eye-widths above and below the eye centre: lid and socket skin, clear of the eyeball.
    val eye = eyes.flatMap { listOfNotNull(mean(it.first, it.second, -0.45f), mean(it.first, it.second, 0.45f)) }
    val cheek = eyes.indices.mapNotNull { mean(cheeks[it], eyes[it].second) }
    if (eye.size < 4 || cheek.size < 2) return null
    val under = cheek.average().toFloat(); if (under <= 0.02f) return null
    return (eye.average().toFloat() / under).coerceIn(0f, 2f)
}

/** Luma spread (brightest tenth less darkest tenth) inside each eye box, averaged: the sclera against the iris. */
private fun eyeContrast(bitmap: Bitmap, p: List<PointF>): Float? {
    val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
    val eyes: List<Pair<PointF, Float>> = when {
        p.size >= 478 -> listOf(p[468] to hypot((p[33].x - p[133].x) * w, (p[33].y - p[133].y) * h), p[473] to hypot((p[362].x - p[263].x) * w, (p[362].y - p[263].y) * h))
        p.size == 5 -> { val span = hypot((p[0].x - p[1].x) * w, (p[0].y - p[1].y) * h); listOf(p[0] to span * 0.4f, p[1] to span * 0.4f) }
        else -> return null
    }
    val spreads = eyes.mapNotNull { (c, px) -> val hw = px * 0.5f / w; val hh = px * 0.25f / h; regionStats(bitmap, RectF(c.x - hw, c.y - hh, c.x + hw, c.y + hh), 16)?.spread }
    return if (spreads.size < 2) null else spreads.average().toFloat()
}
