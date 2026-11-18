package co.getswan.gallerysample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sqrt

/** The photo scan. Mirrors `GalleryScan.swift` and the face half of `LibraryScan.swift` /
 *  `FaceVision.swift`: the reference selfie, every photo in the window through the face gates and
 *  the SFace match, matches streamed to the page. The body gates and the scores come with the
 *  next groups; until then a matched photo with small eyes is streamed as a full-length front. */
class GalleryScan(private val context: Context, private val sendToWeb: (JSONObject) -> Unit) {
    /** The sandbox scan report, set by the host; null in production. */
    var report: ScanReport? = null
    private val worker = Executors.newSingleThreadExecutor()
    /** Decodes and detects the next photos while the current one is read (iOS: `decodeAhead`). */
    private val decoder = Executors.newFixedThreadPool(GallerySettings.lookAheadWorkers)
    private class Ahead(val small: Bitmap?, val faces: List<FaceVision.Face>, val larger: Bitmap? = null)
    private val generation = AtomicInteger(0)
    private val recognizer: FaceRecognizer? by lazy { FaceRecognizer.load(context) }
    /** The last scan's reference face, for the identity check on camera shots. */
    @Volatile private var lastReference: FloatArray? = null
    /** Time spent per reader this scan, in ms, for the summary's `stageMs`. */
    private val stageMs = LinkedHashMap<String, Long>()
    private inline fun <T> timed(stage: String, work: () -> T): T { val t = System.nanoTime(); try { return work() } finally { stageMs[stage] = (stageMs[stage] ?: 0L) + (System.nanoTime() - t) / 1_000_000 } }

    init { FaceVision.init(context); FaceCover.init(context); BodyPose.init(context); BodyObjects.init(context); BodyScore.init(context); Hands.init(context); GarmentClassifier.init(context) }

    fun reset() { generation.incrementAndGet() }

    /** One match: what the page is told, and what the end-of-scan rules need. */
    private class Match(val id: String, val type: String, val distance: Float, val takenAt: Long, val favourite: Boolean,
                        val isFullBody: Boolean, val payload: JSONObject, val selfie: MuseScore?, val vector: FloatArray?,
                        val detail: Float?, val openness: Float?, var dupGroup: String, val held: Boolean)

    /** Runs once the permission is settled. `frames` are the selfie frames, the tapped one last. */
    fun start(monthsBack: Int, limited: Boolean, frames: List<String>, account: String = "") {
        val months = monthsBack.coerceIn(GallerySettings.monthsBackRange)
        if (months != monthsBack) Log.w(TAG, "monthsBack $monthsBack is out of range; using $months")
        val run = generation.incrementAndGet()
        worker.execute {
            val S = GallerySettings
            val startedAt = System.currentTimeMillis()
            stageMs.clear()
            val recognizer = recognizer
            if (recognizer == null) { complete(run, JSONArray(), limited, error = "no-face-model"); return@execute }

            val reference = chooseReference(frames) ?: run { complete(run, JSONArray(), limited, error = "no-face-in-selfie"); return@execute }
            rejection(reference)?.let { complete(run, JSONArray(), limited, error = it); return@execute }
            val freshReference = recognizer.embedding(reference.bitmap, reference.face, FaceVision.landmarks(reference.bitmap, reference.face))
                ?: run { complete(run, JSONArray(), limited, error = "no-face-in-selfie"); return@execute }
            // The other usable frames join the reference (`blendReferenceFrames`), as on iOS.
            val blendedReference = blendReference(recognizer, reference, freshReference)
            // Eval mode pins the first run's reference, so runs are comparable (DevTools.kt).
            val referenceVector = DevTools.pinnedReference(context, account, blendedReference)
            lastReference = referenceVector
            val evalLabels = DevTools.evalLabels(context)

            // What earlier scans learned about this account's photos.
            val store = ScanStore.open(context, account)
            var storeDecided = 0
            val (assets, screenshots) = GalleryPhotos.assetsInWindow(context, months, limited)
            Log.i(TAG, "scan: ${assets.size} photos in $months months ($screenshots screenshots skipped, limited=$limited)")
            if (S.debugLogLabels && S.evalFolder != null) for (a in assets) load(a, S.loadPixels)?.let { b ->
                val labels = Labels.read(b).entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${"%.2f".format(it.value)}" }
                Log.i(TAG, "labels ${a.file}: $labels"); b.recycle()
            }
            sendToWeb(JSONObject().put("type", "onGalleryScanProgress").put("payload", JSONObject().put("done", 0).put("total", assets.size)))
            val tally = linkedMapOf("photos" to assets.size, "noImage" to 0, "noFace" to 0, "group" to 0, "faceTooSmall" to 0, "noMatch" to 0, "earlyNoMatch" to 0,
                "otherPeople" to 0, "animals" to 0, "notFullBody" to 0, "faceRetried" to 0, "faceRescued" to 0, "bodyRetried" to 0, "bodyRescued" to 0, "landmarkFallback" to 0, "unusableFace" to 0, "matches" to 0, "selfies" to 0, "fullLength" to 0, "duplicates" to 0)
            val unusableGates = linkedMapOf<String, Int>()
            var lastProgress = 0L
            val matches = ArrayList<Match>()
            // Every clean distance scored, match or not, for the gap rule; faces under the ceiling with their vectors for the own-photo keep.
            val scoredDistances = ArrayList<Float>()
            val nearFaces = ArrayList<Triple<String, Float, FloatArray>>()
            val hashes = ArrayList<Pair<String, Long>>()
            var closestNoMatch: Float? = null
            // One entry per photo looked at, for the scan report: outcome, the readings behind it, its filename.
            val trail = LinkedHashMap<String, JSONObject>()
            val taken = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }
            // Most photos end at detection (no face, a group, a small face), so the first decode is the small
            // detection render; the scan render is decoded only for a face that passes the cheap gates.
            val ahead = HashMap<Int, java.util.concurrent.Future<Ahead>>()
            fun needsLoad(a: GalleryPhotos.Asset) = store?.record(a)?.let { it.verdict == ScanStore.Verdict.candidate } ?: true
            fun lookAhead(from: Int) { for (k in from until minOf(from + GallerySettings.lookAheadPhotos, assets.size)) if (ahead[k] == null && needsLoad(assets[k])) {
                val a = assets[k]; ahead[k] = decoder.submit<Ahead> {
                    val b = load(a, S.detectPixels); val f = b?.let { FaceVision.detect(it) } ?: emptyList()
                    // A small face the original can clear: its larger render decodes here too, off the main loop.
                    val side = minOf(S.loadPixels, maxOf(a.width, a.height)).toFloat()
                    val scanW = if (a.width >= a.height) side else side * a.width / maxOf(a.height, 1)
                    val big = f.firstOrNull()?.takeIf { it.width * scanW < S.minFacePixels && it.width * a.width >= S.minFacePixels }
                        ?.let { load(a, minOf(S.fullResPixels, maxOf(S.loadPixels * 3 / 2, (S.minFacePixels / it.width * 1.25f).toInt()))) }
                    Ahead(b, f, big) } } }
            for ((i, asset) in assets.withIndex()) {
                if (generation.get() != run) { ahead.values.forEach { it.cancel(true) }; return@execute }
                lookAhead(i)
                if (generation.get() != run) return@execute
                val t0 = System.currentTimeMillis()
                var outcome: String
                var facePx = 0f
                // Garment labels of a matched body, kept whole in the trail (the outcome line is cut at 160).
                var garmentNote: String? = null
                var garmentReads: JSONObject? = null
                // A photo the store already knows is counted without a load.
                val stored = store?.record(asset)
                val decided: String? = stored?.let { r -> when (r.verdict) {
                    ScanStore.Verdict.noFace -> { tally["noFace"] = tally["noFace"]!! + 1; "no face" }
                    ScanStore.Verdict.group -> { tally["group"] = tally["group"]!! + 1; "group" }
                    ScanStore.Verdict.faceTooSmall -> { tally["faceTooSmall"] = tally["faceTooSmall"]!! + 1; "face too small" }
                    ScanStore.Verdict.otherPeople -> { tally["otherPeople"] = tally["otherPeople"]!! + 1; "other people" }
                    ScanStore.Verdict.animal -> { tally["animals"] = tally["animals"]!! + 1; "animal beside" }
                    ScanStore.Verdict.notFullBody -> { tally["notFullBody"] = tally["notFullBody"]!! + 1; "not head to toe" }
                    ScanStore.Verdict.unusableFace -> { tally["unusableFace"] = tally["unusableFace"]!! + 1; "unusable face" }
                    ScanStore.Verdict.tooLarge, ScanStore.Verdict.sensitive -> "skipped"
                    ScanStore.Verdict.candidate -> {
                        val ds = r.faces.map { FaceRecognizer.distance(referenceVector, it) }
                        val b = ds.minOrNull()
                        when {
                            b == null -> null
                            ds.size > 1 && ds.any { it >= S.matchThreshold } -> { tally["group"] = tally["group"]!! + 1; "group" }
                            b >= S.matchThreshold -> {
                                tally["noMatch"] = tally["noMatch"]!! + 1; scoredDistances.add(b)
                                if (S.ownPhotoKeepEnabled && b < S.gapCutCeiling) nearFaces.add(Triple(asset.id, b, r.faces[ds.indexOf(b)]))
                                if (closestNoMatch == null || b < closestNoMatch!!) closestNoMatch = b
                                "no match %.3f".format(b)
                            }
                            else -> null   // a match: loaded and scored as usual
                        }
                    }
                } }
                // Time spent waiting for the look-ahead threads: the pipeline's throughput, not a reader.
                val ready = ahead.remove(i)?.let { f -> timed("waitAhead") { runCatching { f.get() }.getOrNull() } }
                val small: Bitmap? = if (decided != null) { ready?.small?.recycle(); ready?.larger?.recycle(); null } else ready?.small ?: timed("decode") { load(asset, S.detectPixels) }
                val readyFaces: List<FaceVision.Face>? = if (decided == null && ready?.small != null) ready.faces else null
                // The 1536 render's longest side, so the face floor reads as it would on it.
                val scanSide = minOf(S.loadPixels, maxOf(asset.width, asset.height)).toFloat()
                val scanWidth = if (asset.width >= asset.height) scanSide else scanSide * asset.width / maxOf(asset.height, 1)
                val bitmap = small
                // A larger render of the same photo, loaded at most once and only if asked for.
                var larger: Bitmap? = if (decided == null) ready?.larger else null
                // Just large enough for the face floor (iOS's rule for a fetched copy), never over `fullResPixels`.
                var largerNeeded = S.fullResPixels
                fun largerRender(current: Bitmap): Bitmap? { if (larger == null && maxOf(asset.width, asset.height) > maxOf(current.width, current.height)) larger = timed("decodeLarge") { load(asset, largerNeeded) }; return larger }
                if (decided != null) { storeDecided++; outcome = "store: $decided" }
                else if (bitmap == null) { tally["noImage"] = tally["noImage"]!! + 1; outcome = "no image" }
                else {
                    var render: Bitmap = bitmap
                    var all = readyFaces ?: timed("detect") { FaceVision.detect(render) }
                    // Face-size floor: a face under it at the scan's render is looked at again on the larger
                    // render when the original is big enough to clear it.
                    if (all.isNotEmpty() && all.first().width * scanWidth < S.minFacePixels && all.first().width * asset.width >= S.minFacePixels) {
                        tally["faceRetried"] = tally["faceRetried"]!! + 1
                        largerNeeded = minOf(S.fullResPixels, maxOf(S.loadPixels * 3 / 2, (S.minFacePixels / all.first().width * 1.25f).toInt()))
                        largerRender(render)?.let { big ->
                            val again = timed("detect") { FaceVision.detect(big) }
                            if (again.isNotEmpty() && again.first().width * big.width >= S.minFacePixels) { tally["faceRescued"] = tally["faceRescued"]!! + 1; render.recycle(); render = big; larger = null; all = again }
                        }
                    }
                    if (all.isEmpty()) { tally["noFace"] = tally["noFace"]!! + 1; outcome = "no face"; store?.remember(asset, ScanStore.Verdict.noFace) }
                    else {
                        val widestNorm = all.first().width
                        val faces = all.filter { it.width >= widestNorm * S.bystanderRatio }
                        val widestPx = widestNorm * maxOf(render.width.toFloat(), scanWidth)
                        facePx = widestPx
                        // Past the cheap gates: the scan render, unless the larger one is already in hand.
                        var smallCopy: Bitmap? = null
                        if (render.width < scanWidth && faces.size == 1 && widestPx >= S.minFacePixels) timed("decode") { load(asset) }?.let { full -> smallCopy = render; render = full }
                        try {
                        if (S.requireSingleFace && faces.size > 1) { tally["group"] = tally["group"]!! + 1; outcome = "group of ${faces.size}"; store?.remember(asset, ScanStore.Verdict.group, note = "${faces.size} faces") }
                        else if (widestPx < S.minFacePixels) { tally["faceTooSmall"] = tally["faceTooSmall"]!! + 1; outcome = "face ${widestPx.toInt()}px, under ${S.minFacePixels.toInt()}"; store?.remember(asset, ScanStore.Verdict.faceTooSmall) }
                        else if (widestNorm * asset.width < S.minFaceOriginalPixels) { tally["faceTooSmall"] = tally["faceTooSmall"]!! + 1; outcome = "face ${(widestNorm * asset.width).toInt()}px in the original, under ${S.minFaceOriginalPixels.toInt()}"; store?.remember(asset, ScanStore.Verdict.faceTooSmall) }
                        else {
                            // The landmarker misses small, strongly turned heads; ML Kit's five points then align the face.
                            val points = timed("landmarks") { faces.map { f -> FaceVision.landmarks(render, f).ifEmpty {
                                if (S.mlKitLandmarkFallback) FaceVision.keyPoints(render, f).also { if (it.isNotEmpty()) tally["landmarkFallback"] = tally["landmarkFallback"]!! + 1 } else emptyList() } } }
                            val vectors = timed("embed") { faces.indices.map { k -> recognizer.embedding(render, faces[k], points[k]) } }
                            val distances = vectors.map { v -> v?.let { FaceRecognizer.distance(referenceVector, it) } }
                            val bestIndex = distances.indices.filter { distances[it] != null }.minByOrNull { distances[it]!! }
                            val best = bestIndex?.let { distances[it]!! }
                            // A candidate keeps its vectors, so a later scan compares them with its selfie without a load.
                            val complete = vectors.all { it != null }
                            if (best == null) { tally["noFace"] = tally["noFace"]!! + 1; outcome = "no landmarks"; store?.remember(asset, ScanStore.Verdict.noFace) }
                            else if (best >= S.matchThreshold) {
                                if (complete) store?.remember(asset, ScanStore.Verdict.candidate, vectors.map { it!! })
                                tally["noMatch"] = tally["noMatch"]!! + 1
                                if (best >= S.gapCutCeiling && (widestPx >= S.fullResFaceBelow || best >= S.earlyNoMatchSmallFace)) tally["earlyNoMatch"] = tally["earlyNoMatch"]!! + 1
                                if (closestNoMatch == null || best < closestNoMatch!!) closestNoMatch = best
                                scoredDistances.add(best)
                                if (best < S.gapCutCeiling) vectors[bestIndex!!]?.let { nearFaces.add(Triple(asset.id, best, it)) }
                                outcome = "no match %.3f".format(best)
                            } else {
                                // Pose after the face gates, as on iOS: the costliest reader runs only on a matched face.
                                // The detector's input is 320 px, so the small copy serves it as well as the scan render.
                                val detected = timed("objects") { BodyObjects.read(smallCopy ?: render) }
                                val body = timed("pose") { BodyPose.check(render, faces.first().box, BodyObjects.people(detected)) }
                                val originalScale = maxOf(asset.width, asset.height).toFloat() / maxOf(render.width, render.height)
                                val eyes = if (body.lowerJoints == 0) FaceVision.eyeSpacing(points.first(), render.width, render.height)?.let { it * originalScale } else null
                                val faceRatio = faces.first().width * render.width / minOf(render.width, render.height)
                                val headYaw = timed("expression") { FaceVision.headYaw(render, faces.first()) }
                                var bodyAgain: BodyCheck? = null; var bigRender: Bitmap? = null
                                val kind = BodyPose.photoType(faceRatio, eyes, headYaw, body)
                                val selfie = kind == BodyPose.PhotoType.SELFIE
                                val type = kind.wire
                                val persons = detected.filter { it.label == "person" }.joinToString(" ") { "%.2f".format(it.confidence) }
                                val bodyNote = " [joints ${body.lowerJoints} ankles ${body.ankles} span ${"%.2f".format(body.span)} spread ${body.shoulderSpread?.let { "%.2f".format(it) } ?: "-"} people ${body.people} ($persons)]"
                                val animal = if (S.animalBesideDrops) BodyObjects.animalBeside(detected) else null
                                if (body.hasOthers) { tally["otherPeople"] = tally["otherPeople"]!! + 1; outcome = "match ${"%.3f".format(best)}, other people$bodyNote"; store?.remember(asset, ScanStore.Verdict.otherPeople) }
                                // An animal beside the shopper, read like a second person.
                                else if (animal != null) { tally["animals"] = tally["animals"]!! + 1; outcome = "match ${"%.3f".format(best)}, ${animal.label} ${"%.2f".format(animal.confidence)} beside the shopper$bodyNote"; store?.remember(asset, ScanStore.Verdict.animal, note = animal.label) }
                                else if (S.requireFullBody && !selfie && !body.isHeadToToe && !(body.headSeen && body.lowerJoints >= 1 && largerRender(render)?.let { big ->
                                        // Legs seen but not enough of them: one more look at the larger render, since the face already matches.
                                        tally["bodyRetried"] = tally["bodyRetried"]!! + 1
                                        val again = timed("pose") { BodyPose.check(big, faces.first().box, body.people) }
                                        if (again.isHeadToToe && !again.hasOthers) { tally["bodyRescued"] = tally["bodyRescued"]!! + 1; bodyAgain = again; bigRender = big; true } else false
                                    } == true)) {
                                    tally["notFullBody"] = tally["notFullBody"]!! + 1; outcome = "match ${"%.3f".format(best)}, not head to toe$bodyNote"
                                    // Final when nothing could rescue it; else a later selfie might, so the vectors are kept.
                                    if (!body.headSeen || body.lowerJoints < 1) store?.remember(asset, ScanStore.Verdict.notFullBody) else if (complete) store?.remember(asset, ScanStore.Verdict.candidate, vectors.map { it!! })
                                }
                                else {
                                    val document = if (S.documentGateEnabled) timed("text") { DocumentRead.read(render, faces.first().box) } else null
                                    val headLabels = timed("labels") { render.crop(headBox(faces.first().box))?.let { Labels.read(it) } ?: emptyMap() }
                                    val wholeLabels = timed("labels") { Labels.read(render) }
                                    // Suit and tuxedo are also read on the torso crop (`garmentReadOnTorso`), named `suit@torso` for their own cap line.
                                    val torsoCrop = if (!selfie && (S.garmentReadOnTorso || S.debugGarmentReads)) Labels.torsoCrop(render, faces.first().box) else null
                                    val torsoLabels = torsoCrop?.let { timed("labels") { Labels.read(it) } } ?: emptyMap()
                                    val garments = wholeLabels.filter { (S.garmentCaps.any { r -> r.labels.contains(it.key) } || S.looseGarmentLabels.contains(it.key)) && it.value > 0.1f }.map { it.key to it.value } +
                                        (if (S.garmentReadOnTorso) torsoLabels.filter { S.torsoGarmentLabels.contains(it.key) && it.value > 0.1f }.map { "${it.key}@torso" to it.value } else emptyList())
                                    if (!selfie) garmentNote = garments.joinToString(" ") { "${it.first} ${"%.2f".format(it.second)}" }.ifEmpty { "none" }
                                    if (torsoCrop != null && S.debugGarmentReads) {
                                        // Two extra garment reads, logged only: all of ML Kit on the torso crop, and the classifier.
                                        fun fmt(l: List<Pair<String, Float>>) = l.joinToString(" ") { "${it.first} ${"%.2f".format(it.second)}" }.ifEmpty { "none" }
                                        garmentReads = JSONObject().put("mlkitTorso", fmt(torsoLabels.filter { it.value > 0.1f }.map { it.key to it.value }.sortedByDescending { it.second }.take(8)))
                                            .put("classifierTorso", fmt(GarmentClassifier.read(torsoCrop))).put("classifierWhole", fmt(GarmentClassifier.read(render)))
                                    }
                                    val dressy = wholeLabels.filter { MuseScore.DRESSY_LABELS.contains(it.key) }.values.maxOrNull() ?: 0f
                                    val busy = Labels.torsoCrop(render, faces.first().box)?.let { Labels.busyness(it) } ?: 0f
                                    val outfit = MuseScore.Outfit(dressy, busy)
                                    // Hands over or near the face, selfies only, as the iOS gates read them.
                                    val hands = if (selfie) timed("hands") { Hands.read(render, faces.first().box) } else null
                                    val score = if (selfie) timed("selfieScore") { selfieScore(render, faces.first(), points.first(), widestPx, eyes, originalScale, asset, document, headLabels, outfit, hands) } else null
                                    val scoredBody = bodyAgain ?: body
                                    val scoredRender = bigRender ?: render
                                    val scoredScale = maxOf(asset.width, asset.height).toDouble() / maxOf(scoredRender.width, scoredRender.height)
                                    var bodyScore = if (!selfie && S.fullLengthScoreV2) timed("bodyScore") { BodyScore.compute(scoredRender, scoredBody, faces.first().box, scoredScale,
                                        caps = S.garmentCaps(garments), objects = if (S.objectReadEnabled) BodyObjects.objects(detected) else emptyList(), debugName = asset.file) } else null
                                    if (bodyScore != null && S.fullLengthOutfitMultiplier && busy >= S.busyTorso) bodyScore = bodyScore.withOutfit(MuseScore.OUTFIT_MULTIPLIER, "busy top")
                                    bodyScore = bodyScore?.withReading("busy", busy.toDouble())
                                    val gate = score?.filter ?: bodyScore?.filter ?: (if (document?.isDocument == true) "document" else null)
                                    fun note(name: String, m: Map<String, Float>) = if (m.isEmpty()) "" else " $name {" + m.entries.joinToString(", ") { e -> "${e.key} ${"%.2f".format(e.value)}" } + "}"
                                    val labelNote = note("head", headLabels) + note("photo", wholeLabels) + (hands?.let { " hands ${it.hands}${if (it.onFace) " on face" else if (it.nearFace) " near face" else ""}" } ?: "")
                                    // The gap rule sees clean faces only: covered or strained faces land between the shopper's photos and strangers.
                                    if (gate == null && score?.demoted != true) {
                                        scoredDistances.add(best)
                                        if (S.ownPhotoKeepEnabled && best < S.gapCutCeiling) vectors[bestIndex!!]?.let { nearFaces.add(Triple(asset.id, best, it)) }
                                    }
                                    if (complete) store?.remember(asset, ScanStore.Verdict.candidate, vectors.map { it!! })
                                    if (gate != null && S.unusableFaceDrops) {
                                        store?.remember(asset, ScanStore.Verdict.unusableFace, note = gate)
                                        tally["unusableFace"] = tally["unusableFace"]!! + 1
                                        unusableGates[gate] = (unusableGates[gate] ?: 0) + 1
                                        outcome = "match ${"%.3f".format(best)} $type, unusable: $gate${score?.let { angles(it) } ?: ""}${document?.let { " [text ${it.lines} lines, ${it.nearFace} near the face, ${"%.1f".format(it.textArea * 100)}% of the frame, face ${"%.0f".format(it.faceShare * 100)}% wide]" } ?: ""}$labelNote" +
                                            (bodyScore?.let { if (it.reasons.isEmpty()) "" else " (" + it.reasons.joinToString(", ") + ")" } ?: "")
                                    } else {
                                        tally["matches"] = tally["matches"]!! + 1
                                        tally[if (selfie) "selfies" else "fullLength"] = tally[if (selfie) "selfies" else "fullLength"]!! + 1
                                        GalleryPhotos.AnalysisStore.store(asset.id, GalleryPhotos.Analysis(type, scoredBody.isHeadToToe, scoredBody.lowerJoints, scoredBody.hasOthers))
                                        // Near-duplicates: the same shot again joins the earlier match's group.
                                        val hash = differenceHash(render)
                                        var dupGroup = asset.id
                                        hashes.minByOrNull { hammingBits(it.second, hash) }?.let { if (hammingBits(it.second, hash) <= S.duplicateHammingBits) { dupGroup = matches.firstOrNull { m -> m.id == it.first }?.dupGroup ?: it.first; tally["duplicates"] = tally["duplicates"]!! + 1 } }
                                        hashes.add(asset.id to hash)
                                        val payload = JSONObject().put("assetId", asset.id).put("type", type).put("isFullBody", scoredBody.isHeadToToe)
                                            .put("distance", threePlaces(best)).put("takenAt", asset.takenAt).put("dupGroup", dupGroup)
                                        if (score != null) {
                                            payload.put("score", twoPlaces(score.value))
                                            val extra = score.breakdown(); for (k in extra.keys()) payload.put(k, extra.get(k))
                                            val order = MuseScore.orderFields(asset.takenAt, asset.favourite, selfie, score); for (k in order.keys()) payload.put(k, order.get(k))
                                        }
                                        if (bodyScore != null) {
                                            payload.put("score", twoPlaces(bodyScore.value))
                                            val extra = bodyScore.fields(); for (k in extra.keys()) payload.put(k, extra.get(k))
                                            val order = MuseScore.orderFieldsForBody(asset.takenAt, bodyScore); for (k in order.keys()) payload.put(k, order.get(k))
                                        }
                                        // A match the gap rule could still withdraw is held back, so no tile vanishes.
                                        val held = S.gapCutEnabled && best >= S.gapCutFloor
                                        val detail = if (selfie && S.relativeBlurGateEnabled && (score?.cameraCm ?: Float.MAX_VALUE) <= S.relativeBlurMaxCm) score?.expression?.faceDetail else null
                                        val openness = if (selfie && S.relativeEyeGateEnabled) score?.expression?.let { 1 - it.blinkMax } else null
                                        matches.add(Match(asset.id, type, best, asset.takenAt, asset.favourite, body.isHeadToToe, payload, score, vectors[bestIndex!!], detail, openness, dupGroup, held))
                                        if (!held) sendToWeb(JSONObject().put("type", "onGalleryMatchFound").put("payload", JSONObject(payload.toString()).put("done", i + 1).put("total", assets.size)))
                                        outcome = "match ${"%.3f".format(best)} $type (eyes ${eyes?.toInt() ?: "?"}px)${if (held) " held" else ""}${if (dupGroup != asset.id) " dup of $dupGroup" else ""}" +
                                            (score?.let { " score ${"%.2f".format(it.value)} tier ${it.tier ?: "-"}${angles(it)}${if (it.reasons.isEmpty()) "" else ": " + it.reasons.joinToString(", ")}" } ?: "") +
                                            (document?.let { " [text ${it.lines} lines, ${it.nearFace} near the face, ${"%.1f".format(it.textArea * 100)}% of the frame, face ${"%.0f".format(it.faceShare * 100)}% wide]" } ?: "") +
                                            (if (selfie) "" else bodyNote + (if (bodyAgain != null) " rescued on the larger render" else "")) +
                                            (bodyScore?.let { " score ${"%.2f".format(it.value)} tier ${it.tier ?: "-"}${if (it.reasons.isEmpty()) "" else ": " + it.reasons.joinToString(", ")}" +
                                                " {" + listOf("torsoRatio", "torsoFit", "thighL", "thighR", "shinL", "shinR", "armWidth", "torsoLen", "sharpness", "bodyPx").mapNotNull { k -> it.readings[k]?.let { v -> "$k ${"%.3f".format(v)}" } }.joinToString(" ") + "}" } ?: "") +
                                            labelNote + " busy ${"%.3f".format(busy)}"
                                    }
                                }
                            }
                        }
                        } finally { smallCopy?.recycle() }
                    }
                    render.recycle(); larger?.recycle()
                }
                // (the small copy, when kept, is freed with the render below)
                store?.saveIfDue()
                if (report != null || evalLabels != null) trail[asset.id] = JSONObject().put("id", asset.id).put("file", asset.file).put("taken", taken.format(Date(asset.takenAt)))
                    .put("outcome", outcome.substringBefore(" [").substringBefore(" head {").substringBefore(" photo {").take(160)).put("facePx", facePx.toInt()).put("ms", System.currentTimeMillis() - t0).putOpt("garments", garmentNote).putOpt("garmentReads", garmentReads)
                if (GallerySettings.scanLogEnabled) Log.d(TAG, "  #${i + 1}/${assets.size} ${asset.id}: $outcome (${System.currentTimeMillis() - t0}ms)")
                val now = System.currentTimeMillis()
                if (now - lastProgress >= S.progressIntervalMs || i == assets.lastIndex) {
                    lastProgress = now
                    sendToWeb(JSONObject().put("type", "onGalleryScanProgress").put("payload", JSONObject().put("done", i + 1).put("total", assets.size)))
                }
            }
            if (generation.get() != run) return@execute

            // End-of-scan gates: the ones that need every match in.
            val dropped = ArrayList<String>()
            val droppedWhy = LinkedHashMap<String, String>()
            val ends = LinkedHashMap<String, Any?>()
            val checks = JSONObject()
            fun withdraw(ids: List<String>, gate: String?) {
                if (ids.isEmpty()) return
                Log.i(TAG, "  withdrawn at scan end${gate?.let { " ($it)" } ?: " (gap rule)"}: ${ids.joinToString(" ")}")
                for (id in ids) { droppedWhy[id] = gate ?: "gap cut, d ${"%.3f".format(matches.firstOrNull { it.id == id }?.distance ?: 0f)}"; trail[id]?.put("outcome", "withdrawn")?.put("why", droppedWhy[id]) }
                val set = ids.toSet()
                for (m in matches.filter { set.contains(it.id) }) { tally["matches"] = tally["matches"]!! - 1; tally[if (m.type == "selfie") "selfies" else "fullLength"] = tally[if (m.type == "selfie") "selfies" else "fullLength"]!! - 1 }
                matches.removeAll { set.contains(it.id) }
                if (gate != null) { tally["unusableFace"] = tally["unusableFace"]!! + ids.size; unusableGates[gate] = (unusableGates[gate] ?: 0) + ids.size }
                dropped.addAll(ids)
            }
            // Relative eye gate: a selfie whose more closed eye is under `relativeOpenness` of the shopper's median.
            val opennessValues = matches.mapNotNull { it.openness }
            if (S.relativeEyeGateEnabled && opennessValues.size >= S.relativeBaselineMin) {
                val usual = median(opennessValues); ends["eyeBaseline"] = usual
                val eyeDropped = matches.filter { (it.openness ?: 1f) < S.relativeOpenness * usual }.map { it.id }
                ends["eyeDropped"] = eyeDropped.size
                checks.put("eyes", JSONObject().put("usual", twoPlaces(usual)).put("selfies", opennessValues.size).put("line", twoPlaces(S.relativeOpenness)).put("withdrawn", JSONArray(eyeDropped)))
                withdraw(eyeDropped, "eyes under usual")
            }
            // Relative blur gate: a close selfie whose face detail is under `relativeBlurShare` of the median.
            val detailed = matches.filter { it.detail != null }
            if (S.relativeBlurGateEnabled && detailed.size >= S.relativeBlurMin) {
                val usual = median(detailed.map { it.detail!! }); ends["blurBaseline"] = usual
                val blurDropped = detailed.filter { it.detail!! < S.relativeBlurShare * usual }.map { it.id }
                ends["blurDropped"] = blurDropped.size
                val lowest = detailed.sortedBy { it.detail!! }.take(4).map { JSONObject().put("id", it.id).put("ratio", twoPlaces(it.detail!! / maxOf(usual, 0.001f))) }
                checks.put("blur", JSONObject().put("usual", twoPlaces(usual)).put("closeSelfies", detailed.size).put("line", twoPlaces(S.relativeBlurShare)).put("withdrawn", JSONArray(blurDropped)).put("lowest", JSONArray(lowest)))
                withdraw(blurDropped, "blurred against usual")
            }
            // Gap rule, with the weak-reference fallback and the own-photo keep.
            var cut = S.gapCut(scoredDistances)
            if (cut == null && S.weakReferenceCutEnabled && (matches.maxOfOrNull { it.distance } ?: 0f) >= S.weakReferenceReach) { cut = S.weakReferenceCut; ends["weakCut"] = true }
            if (cut != null) {
                ends["gapCut"] = cut
                val sure = nearFaces.filter { f -> f.second < S.ownPhotoCore && matches.any { it.id == f.first } }
                fun nearestOwn(id: String): Float? { val face = nearFaces.firstOrNull { it.first == id } ?: return null; return sure.filter { it.first != id }.map { FaceRecognizer.distance(face.third, it.third) }.minOrNull() }
                var gapDropped = matches.filter { it.distance >= cut }.map { it.id }
                if (S.ownPhotoKeepEnabled) { val kept = gapDropped.filter { (nearestOwn(it) ?: Float.MAX_VALUE) < S.ownPhotoKeepLimit }; ends["ownKept"] = kept.size; gapDropped = gapDropped - kept.toSet() }
                ends["gapDropped"] = gapDropped.size; withdraw(gapDropped, null)
            }
            // The shopper's own gaze resting point, then the frown against their own brows.
            val rescored = LinkedHashSet<String>()
            val shownSelfies = matches.filter { it.selfie != null }
            if (S.gazeRestFromLibrary && S.gazeCurve != null) {
                val offsets = shownSelfies.mapNotNull { S.gazeOffset(it.selfie!!.expression, 0f to 0f) }
                ends["gazeSelfies"] = offsets.size
                if (offsets.size >= S.gazeRestLibraryMin) {
                    val middle = median(offsets.map { it.first }) to median(offsets.map { it.second })
                    if (abs(middle.first - S.gazeRest.first) <= S.gazeRestMaxShift && abs(middle.second - S.gazeRest.second) <= S.gazeRestMaxShift) {
                        ends["libraryRest"] = "%.1f/%.1f".format(middle.first, middle.second)
                        val gazeRescored = JSONArray()
                        for (m in shownSelfies) if (m.selfie!!.regaze(middle)) { refresh(m); rescored.add(m.id); gazeRescored.put(JSONObject().put("id", m.id).put("score", twoPlaces(m.selfie.value)).put("tier", m.selfie.tier ?: "")); Log.i(TAG, "  rescored by gaze rest: ${m.id} -> ${"%.2f".format(m.selfie.value)} tier ${m.selfie.tier}") }
                        checks.put("gazeRest", JSONObject().put("yaw", twoPlaces(middle.first)).put("pitch", twoPlaces(middle.second)).put("selfies", offsets.size)
                            .put("default", JSONArray(listOf(twoPlaces(S.gazeRest.first), twoPlaces(S.gazeRest.second)))).put("rescored", gazeRescored))
                    }
                }
            }
            if (S.frownRelativeEnabled) {
                val close = shownSelfies.filter { (it.selfie!!.cameraCm ?: Float.MAX_VALUE) <= S.frownBaselineMaxCm }.mapNotNull { it.selfie!!.expression?.browDown }
                if (close.size >= S.frownBaselineMin) {
                    val usual = median(close); val line = minOf(maxOf(S.frownLimit, usual + S.frownOverUsual), S.frownCeiling)
                    ends["frownLine"] = line
                    val frownRescored = JSONArray()
                    for (m in shownSelfies) if (m.selfie!!.refrown(line)) { refresh(m); rescored.add(m.id); frownRescored.put(JSONObject().put("id", m.id).put("score", twoPlaces(m.selfie.value)).put("tier", m.selfie.tier ?: "")); Log.i(TAG, "  rescored by frown line: ${m.id} -> ${"%.2f".format(m.selfie.value)} tier ${m.selfie.tier}") }
                    checks.put("frown", JSONObject().put("usual", twoPlaces(usual)).put("line", twoPlaces(line)).put("closeSelfies", close.size).put("rescored", frownRescored))
                }
            }
            // Rescored matches go again with `rescored`; then the held-back ones, now that the cut is known.
            val shown = matches.map { it.id }
            val ordered = matches.filter { !it.held }.map { it.id } + matches.filter { it.held }.map { it.id }
            for (m in matches) if (!m.held && rescored.contains(m.id)) sendToWeb(JSONObject().put("type", "onGalleryMatchFound").put("payload", JSONObject(m.payload.toString()).put("rescored", true).put("done", assets.size).put("total", assets.size)))
            for (m in matches) if (m.held) sendToWeb(JSONObject().put("type", "onGalleryMatchFound").put("payload", JSONObject(m.payload.toString()).put("done", assets.size).put("total", assets.size)))
            ends["rescored"] = rescored.size
            tally["ms"] = (System.currentTimeMillis() - startedAt).toInt()
            tally["screenshots"] = screenshots
            closestNoMatch?.let { ends["closestNoMatch"] = threePlaces(it) }
            store?.let { it.save(); ends["storeDecided"] = storeDecided; ends["storeWritten"] = it.written; ends["storeRecords"] = it.count }
            val stages = stageMs.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value / 1000f}s" }
            Log.i(TAG, "scan done: $tally unusableGates=$unusableGates end=$ends")
            Log.i(TAG, "stages: $stages")
            val summary = JSONObject(tally as Map<*, *>).put("unusableGates", JSONObject(unusableGates as Map<*, *>))
            for ((k, v) in ends) summary.put(k, v)
            summary.put("stageMs", JSONObject(stageMs as Map<*, *>))
            report?.endOfScan(droppedWhy, checks, JSONArray(trail.values.sortedByDescending { it.optString("taken") }))
            if (evalLabels != null) {
                val text = DevTools.evalReport(evalLabels, trail.values, ordered.filter { trail[it]?.optString("outcome") != "withdrawn" })
                Log.i(TAG, text); DevTools.save(context, text)
            }
            complete(run, JSONArray(ordered), limited, summary = summary, dropped = dropped)
        }
    }

    /** A rescored selfie's payload: the new score fields over the old ones, with the order recomputed. */
    private fun refresh(m: Match) {
        val score = m.selfie ?: return
        m.payload.put("score", twoPlaces(score.value))
        val extra = score.breakdown(); for (k in extra.keys()) m.payload.put(k, extra.get(k))
        val order = MuseScore.orderFields(m.takenAt, m.favourite, true, score); for (k in order.keys()) m.payload.put(k, order.get(k))
    }

    /** The v2 selfie score for one matched selfie: expression and cover on the head crop, camera data from the original. */
    private fun selfieScore(bitmap: Bitmap, face: FaceVision.Face, points: List<android.graphics.PointF>, faceWidthPx: Float, eyesOriginal: Float?, originalScale: Float, asset: GalleryPhotos.Asset?, document: DocumentRead?, labels: Map<String, Float>, outfit: MuseScore.Outfit, hands: Hands.Read?): MuseScore {
        val expression = FaceVision.expression(bitmap, face)
        val camera = asset?.let { Original.camera(context, it.uri) }
        val gates = MuseScore.SelfieGates(eyesOriginal, originalScale, (asset?.let { maxOf(it.width, it.height) } ?: maxOf(bitmap.width, bitmap.height)).toFloat(), camera)
        val coverReader: ((FaceExpression?) -> FaceCover?)? = if (GallerySettings.coverReadEnabled) { e ->
            val head = headBox(face.box)
            bitmap.crop(head)?.let { crop ->
                val fb = face.box.pixels(bitmap); val hb = head.pixels(bitmap)
                FaceCover.read(crop, RectF(fb.left - hb.left, fb.top - hb.top, fb.right - hb.left, fb.bottom - hb.top), e?.landmarks)
            }
        } else null
        return MuseScore(face.box, points, bitmap, faceWidthPx, expression, labels = labels, handOnFace = hands?.onFace ?: false, handNearFace = hands?.nearFace ?: false,
            outfit = outfit, selfie = true, gates = gates, coverReader = coverReader, document = document)
    }

    /** The head angles behind a selfie's verdict, for the log: MediaPipe pitch (+ camera below), yaw, roll. */
    private fun angles(score: MuseScore): String {
        val r = score.readings
        val p = r["mpPitch"]; val y = r["mpYaw"]; val ro = r["mpRoll"]
        return if (p == null) " [no head transform]" else " [pitch ${"%+.1f".format(p)} yaw ${"%.1f".format(y ?: 0.0)} roll ${"%.1f".format(ro ?: 0.0)} ratio ${r["faceRatio"] ?: "-"} dark ${r["eyeDark"] ?: "-"} contrast ${r["eyeContrast"] ?: "-"}]"
    }

    private data class Frame(val bitmap: Bitmap, val face: FaceVision.Face, val detail: Float)
    /** The chosen frame, plus every frame with a face (the chosen one included) for the blend. */
    private data class Reference(val bitmap: Bitmap, val face: FaceVision.Face, val note: String, val frames: List<Frame> = emptyList())

    /** The sharpest frame that has a face; the tapped frame (last) wins a tie, as on iOS. */
    private fun chooseReference(frames: List<String>): Reference? {
        var best: Reference? = null; var bestDetail = -1f
        val notes = ArrayList<String>(); val found = ArrayList<Frame>()
        for ((i, uri) in frames.withIndex()) {
            val bytes = GalleryPhotos.bytesOfDataURI(uri) ?: continue
            val bitmap = runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE } }.getOrNull() ?: continue
            val face = FaceVision.detect(bitmap, careful = true).firstOrNull()
            if (face == null) { notes.add("no face"); continue }
            val detail = laplacianVariance(bitmap, face.box.pixels(bitmap)) ?: 0f
            notes.add("%.1f".format(detail)); found.add(Frame(bitmap, face, detail))
            if (detail >= bestDetail) { bestDetail = detail; best = Reference(bitmap, face, "") }
        }
        Log.i(TAG, "reference: ${frames.size} frame(s), detail ${notes.joinToString(" / ")}")
        return best?.copy(frames = found)
    }

    /** The sharpest frame's embedding averaged with the other frames at `blendDetailShare` of its detail, normalised; the
     *  sharpest alone when nothing else qualifies. Logged like iOS: frames' spread and the blend's shift from the sharpest. */
    private fun blendReference(recognizer: FaceRecognizer, reference: Reference, sharpest: FloatArray): FloatArray {
        val S = GallerySettings
        if (!S.blendReferenceFrames || reference.frames.size < 2) return sharpest
        val bestDetail = reference.frames.maxOf { it.detail }
        val sum = sharpest.copyOf(); var used = 1; var spread = 0f
        for (f in reference.frames) {
            if (f.bitmap === reference.bitmap || f.detail < bestDetail * S.blendDetailShare) continue
            val e = recognizer.embedding(f.bitmap, f.face, FaceVision.landmarks(f.bitmap, f.face).ifEmpty { FaceVision.keyPoints(f.bitmap, f.face) }) ?: continue
            spread = maxOf(spread, FaceRecognizer.distance(sharpest, e))
            for (k in sum.indices) sum[k] += e[k]
            used++
        }
        if (used < 2) { Log.i(TAG, "reference: sharpest frame alone (no other frame at ${S.blendDetailShare} of its detail)"); return sharpest }
        val norm = sqrt(sum.sumOf { (it * it).toDouble() }).toFloat()
        val blended = FloatArray(sum.size) { sum[it] / norm }
        Log.i(TAG, "reference blended from $used of ${reference.frames.size} frame(s): frames up to %.3f apart, blend %.3f from the sharpest".format(spread, FaceRecognizer.distance(sharpest, blended)))
        return blended
    }

    /** Why the selfie cannot be the reference, or null. Same rules as `selfieRejection`: other faces, head turned or tilted. */
    private fun rejection(ref: Reference): String? {
        val all = FaceVision.detect(ref.bitmap, careful = true)
        val main = all.firstOrNull() ?: return "no-face-in-selfie"
        if (all.count { it !== main && it.width >= main.width * GallerySettings.bystanderRatio } > 0) return "several-faces-in-selfie"
        val e = FaceVision.expression(ref.bitmap, main) ?: return null
        val yaw = e.headYaw; val pitch = e.headPitch
        if (yaw != null && abs(yaw) > GallerySettings.selfieMaxYawDegrees) { Log.i(TAG, "selfie refused: head turned, yaw %.0f°".format(abs(yaw))); return "selfie-not-frontal" }
        if (pitch != null && abs(pitch) > GallerySettings.selfieMaxPitchDegrees) { Log.i(TAG, "selfie refused: head tilted, pitch %.0f°".format(abs(pitch))); return "selfie-not-frontal" }
        return null
    }

    /** The scan's render of one photo: longest side `pixels`, EXIF orientation applied. */
    private fun load(asset: GalleryPhotos.Asset, pixels: Int = GallerySettings.loadPixels): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, asset.uri)) { decoder, info, _ ->
            val w = info.size.width; val h = info.size.height
            val scale = minOf(1f, pixels.toFloat() / maxOf(w, h))
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
    }.getOrNull()

    private fun complete(run: Int, assetIds: JSONArray, limited: Boolean, error: String? = null, summary: JSONObject? = null, dropped: List<String> = emptyList()) {
        if (generation.get() != run) return
        val payload = JSONObject().put("assetIds", assetIds).put("limited", limited)
        error?.let { payload.put("error", it) }
        summary?.let { payload.put("summary", it) }
        if (dropped.isNotEmpty()) payload.put("dropped", JSONArray(dropped))
        sendToWeb(JSONObject().put("type", "onGalleryScanComplete").put("payload", payload))
    }

    /** Photo access refused: the page shows its guide. */
    fun denied() = sendToWeb(JSONObject().put("type", "onGalleryScanComplete").put("payload", JSONObject().put("assetIds", JSONArray()).put("denied", true)))

    /** `GALLERY_ANALYSE_PHOTO`: what the scan knows about the photo, else `unavailable`. */
    fun analyse(id: String) = sendToWeb(analysisMessage(id, GalleryPhotos.AnalysisStore.get(id), captured = false))

    /** `GALLERY_CAPTURE_PHOTO`: hold the bytes as sent, then judge the shot as the scan would (`analyse` in GalleryPhotos.swift). */
    fun storeCapture(id: String, dataURI: String, shot: String) {
        worker.execute {
            val bytes = GalleryPhotos.bytesOfDataURI(dataURI)
            if (id.isEmpty() || bytes == null) { sendToWeb(analysisError(id, "unreadable", captured = true)); return@execute }
            if (!GalleryPhotos.CaptureStore.store(id, bytes)) { sendToWeb(analysisError(id, "store-full", captured = true)); return@execute }
            val bitmap = decodeCapture(bytes) ?: run { sendToWeb(analysisError(id, "unreadable", captured = true)); return@execute }
            val (analysis, gate) = try { analyseCapture(bitmap) } finally { bitmap.recycle() }
            // Stored first, so the tap that follows takes the same path as a scanned photo.
            GalleryPhotos.AnalysisStore.store(id, analysis)
            if (gate != null) {
                Log.i(TAG, "capture $id (asked $shot) gated: $gate")
                sendToWeb(analysisError(id, "unusable", captured = true).also { it.getJSONObject("payload").put("scoreFilter", gate) })
                return@execute
            }
            Log.i(TAG, "capture $id (asked $shot) read as ${analysis.type}, head to toe ${analysis.isFullBody}")
            sendToWeb(analysisMessage(id, analysis, captured = true))
        }
    }

    /** The capture at the scan's working size: it is its own original, so the scale is 1. */
    private fun decodeCapture(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= GallerySettings.loadPixels) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }.getOrNull()

    /** The scan's face-to-body tie, type and gates on a camera shot: no camera data, so the selfie gates read it as a front camera.
     *  Returns the analysis and the gate that refused it, if any. */
    private fun analyseCapture(bitmap: Bitmap): Pair<GalleryPhotos.Analysis, String?> {
        val S = GallerySettings
        val face = FaceVision.detect(bitmap).firstOrNull()
        val detected = BodyObjects.read(bitmap)
        val body = BodyPose.check(bitmap, face?.box, BodyObjects.people(detected))
        if (face == null) return GalleryPhotos.Analysis(BodyPose.photoType(0f, null, null, body).wire, body.isHeadToToe, body.lowerJoints, body.hasOthers) to null
        val points = FaceVision.landmarks(bitmap, face).ifEmpty { if (S.mlKitLandmarkFallback) FaceVision.keyPoints(bitmap, face) else emptyList() }
        val eyes = if (body.lowerJoints == 0) FaceVision.eyeSpacing(points, bitmap.width, bitmap.height) else null
        val faceRatio = face.width * bitmap.width / minOf(bitmap.width, bitmap.height)
        val kind = BodyPose.photoType(faceRatio, eyes, FaceVision.headYaw(bitmap, face), body)
        val selfie = kind == BodyPose.PhotoType.SELFIE
        val analysis = GalleryPhotos.Analysis(kind.wire, body.isHeadToToe, body.lowerJoints, body.hasOthers)
        // The shot must be the shopper in the selfie, at the scan's cut.
        if (S.captureIdentityCheck && points.isNotEmpty()) {
            val reference = lastReference; val recognizer = recognizer
            if (reference != null && recognizer != null) {
                val d = recognizer.embedding(bitmap, face, points)?.let { FaceRecognizer.distance(reference, it) }
                Log.i(TAG, "capture identity d=${d?.let { "%.3f".format(it) } ?: "none"} cut ${S.matchThreshold}")
                if (d == null || d >= S.matchThreshold) return analysis to S.captureNotShopperWhy
            }
        }
        if (!S.captureGatesEnabled || kind == BodyPose.PhotoType.UNKNOWN) return analysis to null
        val document = if (S.documentGateEnabled) DocumentRead.read(bitmap, face.box) else null
        val headLabels = bitmap.crop(headBox(face.box))?.let { Labels.read(it) } ?: emptyMap()
        val wholeLabels = Labels.read(bitmap)
        val torsoCrop = Labels.torsoCrop(bitmap, face.box)
        val garments = wholeLabels.filter { (S.garmentCaps.any { r -> r.labels.contains(it.key) } || S.looseGarmentLabels.contains(it.key)) && it.value > 0.1f }.map { it.key to it.value } +
            (if (!selfie && S.garmentReadOnTorso && torsoCrop != null) Labels.read(torsoCrop).filter { S.torsoGarmentLabels.contains(it.key) && it.value > 0.1f }.map { "${it.key}@torso" to it.value } else emptyList())
        val dressy = wholeLabels.filter { MuseScore.DRESSY_LABELS.contains(it.key) }.values.maxOrNull() ?: 0f
        val busy = torsoCrop?.let { Labels.busyness(it) } ?: 0f
        val outfit = MuseScore.Outfit(dressy, busy)
        val gate = if (selfie) {
            selfieScore(bitmap, face, points, face.width * bitmap.width, eyes, 1f, null, document, headLabels, outfit, Hands.read(bitmap, face.box)).filter
        } else if (S.fullLengthScoreV2) {
            var bodyScore = BodyScore.compute(bitmap, body, face.box, 1.0, caps = S.garmentCaps(garments), objects = if (S.objectReadEnabled) BodyObjects.objects(detected) else emptyList())
            if (bodyScore != null && S.fullLengthOutfitMultiplier && busy >= S.busyTorso) bodyScore = bodyScore.withOutfit(MuseScore.OUTFIT_MULTIPLIER, "busy top")
            bodyScore?.filter
        } else null
        return analysis to (gate ?: if (document?.isDocument == true) "document" else null)
    }

    private fun analysisMessage(id: String, analysis: GalleryPhotos.Analysis?, captured: Boolean): JSONObject {
        if (analysis == null) return analysisError(id, "unavailable", captured = null)
        return JSONObject().put("type", "onGalleryPhotoAnalysis").put("payload", JSONObject()
            .put("assetId", id).put("captured", captured).put("type", analysis.type)
            .put("isFullBody", analysis.isFullBody).put("lowerBodyJoints", analysis.lowerJoints).put("otherPeople", analysis.otherPeople))
    }

    private fun analysisError(id: String, error: String, captured: Boolean?): JSONObject {
        val payload = JSONObject().put("assetId", id).put("error", error)
        captured?.let { payload.put("captured", it) }
        return JSONObject().put("type", "onGalleryPhotoAnalysis").put("payload", payload)
    }

    fun clearCaptures() = GalleryPhotos.CaptureStore.removeAll()
    fun clearAll() { reset(); GalleryPhotos.CaptureStore.removeAll(); GalleryPhotos.AnalysisStore.removeAll() }

    /** Debug builds: forgets every stored verdict, so the next scan is a full one. */
    fun clearStore(account: String) { ScanStore.open(context, account)?.let { java.io.File(context.noBackupFilesDir, "SwanGalleryPick").deleteRecursively() } }

    companion object { private const val TAG = "swan" }
}
