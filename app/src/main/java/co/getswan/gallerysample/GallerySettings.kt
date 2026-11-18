package co.getswan.gallerysample

/** The scan's numbers, one place. Mirrors `GallerySettings.swift`: the iOS values are kept
 *  until step 7 of the Android plan re-tunes them on Android readings. */
object GallerySettings {
    /** The photo scheme the page loads grid tiles on; must match `config.photoScheme` when sent. */
    const val photoScheme = "swan-photo"

    /** Grid tiles. Small and fast, dozens are requested at once. */
    const val gridPixels = 256
    /** Grid thumbnails through `ImageDecoder` (EXIF orientation applied) rather than the media provider's
     *  `loadThumbnail`, which handed back HEIC photos with a 90° orientation unrotated (16 Nov). */
    const val gridThumbnailsViaDecoder = true
    const val gridQuality = 70
    /** A large render, for `?size=full` and for photos taken in the flow. */
    const val fullPixels = 1600
    const val fullQuality = 85

    /** How much of one original we will carry: at the cap a file is 13.3 MB as base64. */
    const val originalByteCap = 10 * 1024 * 1024
    /** How many photos from the flow's camera are held in memory. */
    const val captureLimit = 12
    /** The scan's gates run on a camera shot too, as on iOS: a capture cannot pass what a library photo would not. */
    const val captureGatesEnabled = true
    /** Averages the usable selfie frames' embeddings into one reference, as iOS does: one frame's quirk (an expression,
     *  a small turn, a shadow) is cancelled. Off, the sharpest frame alone is the reference. */
    const val blendReferenceFrames = true
    /** A frame joins the blend when its face detail is at least this share of the sharpest frame's. Android has no
     *  capture-quality model (iOS: Vision quality 0.3 and up), so the floor is relative; the log shows each frame's detail. */
    const val blendDetailShare = 0.4f
    /** A camera shot must be the shopper in the selfie, at the scan's cut; the page shows `captureNotShopperWhy`. */
    const val captureIdentityCheck = true
    const val captureNotShopperWhy = "not the person in the selfie"

    // MARK: faces (iOS values; see GallerySettings.swift and FaceVision.swift)

    /** The render the scan reads each photo at, longest side. */
    const val loadPixels = 1536
    /** The copy face boxes are found on: a 120 px face at 1536 is 60 px here, inside ML Kit's reach. */
    const val detectPixels = 768
    /** A face narrower than this at the render is too small to match. Kept at iOS's 120 although ML Kit's boxes read
     *  wider than Vision's: the ratio is 1.5× on close selfies but only 1.1–1.3× on far faces, and 170 dropped every
     *  full-length photo (16 Nov). */
    const val minFacePixels = 120f
    /** Android only: the face must also be this wide in the original's own pixels; 120 is iOS's own floor. A try at
     *  156 (ML Kit's box 1.3× Vision's) to drop one small photo iOS drops (Vision 103 px, ML Kit 134) also dropped
     *  two iOS keeps (Vision 126, ML Kit 137 / 150): ML Kit's boxes do not scale with Vision's on small faces, so
     *  no line separates them. The small photo stays an accepted difference (16 Nov). */
    const val minFaceOriginalPixels = 120f
    /** Android only: the face box shrunk about its centre before the light and sharpness region is cut from it.
     *  ML Kit's box runs about 1.5× Vision's on a close face, so the unshrunk box takes in hair and background on
     *  both sides and reads the light as more even than iOS does (1.3× against 1.6× on the same selfies, 16 Nov). */
    const val faceStatsBoxScale = 0.68f
    /** Android only: when the landmarker finds nothing on a head crop (small, strongly turned faces), align
     *  SFace on ML Kit's own eye, nose and mouth points instead of dropping the face. */
    const val mlKitLandmarkFallback = true
    /** Faces under this share of the widest face's width are bystanders: ignored, not a group. */
    const val bystanderRatio = 0.25f
    /** Several faces of the shopper's size make the photo a group, which is left out. */
    const val requireSingleFace = true
    /** Android only: the zoo's int8 SFace (9 MB) instead of the float one (37 MB). Same model and cutoff; on
     *  the first library it gave the same 16 matches with distances within a few hundredths, so only the
     *  int8 file ships. Off, the float file must be put back in `assets/FaceModels/`. */
    const val sfaceInt8 = true
    /** The distance (1 - cosine) under which a face is the shopper's. One cutoff for every type.
     *  Android 0.63 beside iOS 0.60: on the same photos int8 SFace on MediaPipe points reads 0.02–0.06 farther (mean +0.04, 16 Nov). */
    const val matchThreshold = 0.63f
    /** A selfie: no lower-body joint and the eyes at least this far apart in the original. */
    const val selfieEyeSpacingPixels = 60f
    /** The reference selfie is refused past these head angles, from the landmarker. */
    const val selfieMaxYawDegrees = 20f
    /** Android only: added to the landmarker's head pitch. The head crop is cut with iOS's factors from ML Kit's
     *  larger box, so the face sits lower in it and MediaPipe reads the pitch 2.1° under iOS's on the same photos
     *  (18 shared selfies, range 0.1–3.0°; yaw and roll agree). With it the camera-below and head gates match. */
    const val headPitchOffsetDegrees = 2.1f
    const val selfieMaxPitchDegrees = 15f
    // MARK: documents (Android's reading; iOS uses Vision's labels and page detector)

    /** On, a match is dropped as `document` when its face sits among printed text: an ID card, a
     *  passport, a page. Read by ML Kit text recognition on the scan's render. */
    const val documentGateEnabled = true
    /** Lines of printed text in the photo, and how many of them within `documentNearFaceScale`
     *  face-widths of the face, for the photo to count as a document. */
    const val documentMinLines = 6
    const val documentNearFaceLines = 3
    const val documentNearFaceScale = 3f
    /** Or: a few lines that together cover this share of the frame. An ID card's five printed
     *  lines run the card's width; a sign or a slogan in a selfie's background is a tenth of that. */
    const val documentAreaMinLines = 3
    const val documentMinTextArea = 0.03f
    /** Either rule only when the face is small in the frame, as on a card held up: a selfie in front of a
     *  banner has the text around a face 0.43–0.58 of the width (16 Nov); an ID's portrait is well under 0.3. */
    const val documentMaxFaceShare = 0.3f

    /** On, a match whose face the Muse cannot use (`MuseScore.filter`) is kept out of the grid. */
    const val unusableFaceDrops = true

    // MARK: selfie gates (v2); same values as GallerySettings.swift

    const val selfieGatesV2 = true
    const val gatePitchDegrees = 12f
    /** MediaPipe pitch (positive = camera looking up at the face) at or over this: "camera below". */
    val cameraBelowGate: Float? = 8f
    /** "Camera above": `faceRatio` and |pitch| both at these values. iOS reads Vision's pitch here; Android MediaPipe's. */
    val cameraAboveGate: Pair<Float, Float>? = 1.10f to 12f
    const val gateYawDegrees = 20f
    /** "Head down" / "head up": the head against the floor at or past these degrees rules a selfie out (iOS values).
     *  Android photos carry no phone tilt, so the tilt is taken as `assumedPhoneTiltDegrees`; weaker than iOS's reading. */
    const val headLevelGateEnabled = true
    const val headDownDegrees = 15f
    const val headUpDegrees = 15f
    const val assumedPhoneTiltDegrees = 0f
    const val rollGateEnabled = true
    const val gateRollDegrees = 30f
    const val tooCloseGateEnabled = true
    const val tooCloseCm = 25f
    const val tooFarDemotes = true
    const val tooFarCm = 90f
    const val irisMm = 11.7f
    const val eyeSpacingMm = 63f
    const val assumedFocal35mm = 30f
    /** On, a selfie whose original has no camera make, model or focal length is ruled out. Off (as on iOS): judged
     *  like any other, its distance read with `assumedFocal35mm`. */
    const val noCameraDataGateEnabled = false
    val tooDarkISO: Float? = 2500f
    val lowLightISO: Pair<Float, Float>? = 1600f to 5000f
    const val scarfGateEnabled = true
    const val scarfConfidence = 0.3f
    const val coverReadEnabled = true
    const val coverHairlineLimit = 0.3f
    const val coverJawLimit = 0.4f
    const val coverFaceLimit = 0.15f
    const val lookingAwayGateEnabled = true
    const val lookingAwayDegrees = 12f
    const val lookingUpDegrees = 5f
    const val lookingDownDegrees = 9f
    val lookingDownConfirmed: Pair<Float, Float>? = 5f to 0.35f
    /** Gaze resting point (where the eyes sit looking at the screen) and the head-yaw share taken off. */
    val gazeRest = 0f to -7.5f
    const val gazeHeadShare = 0.37f
    val gazeCurve: Pair<Float, Float>? = 4f to 12f

    // MARK: selfie score (v2)

    const val selfieScoreV2 = true
    const val tierALine = 0.70f
    val scoreWeightsV2 = listOf("pose" to 0.30f, "light" to 0.20f, "gaze" to 0.15f, "expression" to 0.15f, "visibility" to 0.10f, "detail" to 0.10f)
    val posePitch = 3f to 15f
    val poseYaw = 5f to 20f
    val poseRoll = 5f to 20f
    val poseDistance = 40f to 25f
    const val mediaPipePitchEnabled = true
    const val mediaPipeRollEnabled = true
    val mediaPipeRoll = 2f to 30f
    const val cameraBelowCap = 0.6f
    val cameraBelowSlope: Pair<Float, Float>? = 0f to 6f
    val lightEvenness = 1.3f to 2.0f
    val lightBacklight = 0.8f to 0.4f
    val lightClipped = 0.01f to 0.05f
    const val expressionCapped = 0.5f
    const val expressionOpenSmile = 0.4f
    const val puckerLimit = 0.6f
    const val frownLimit = 0.25f
    const val frownCap = 0.6f
    const val wideEyesLine = 0.05f
    val visibilityShare = 0.95f to 0.85f
    const val glassesCap = 0.85f
    const val glassesOvalAllowance = 0.08f
    const val glassesConfidence = 0.5f
    val detailEyeSpacing = 120f to 60f
    const val tierAEyeSpacingMin = 90f
    /** Order: recency in months, 1 up to the first value, 0 from the second. */
    const val orderV2Enabled = true
    val selfieRecency = 12.0 to 36.0
    val fullLengthRecency = 6.0 to 24.0

    /** How far the eyes point from the resting point, split sideways (+ right) and vertical (+ down). */
    fun gazeOffset(e: FaceExpression?, rest: Pair<Float, Float> = gazeRest): Pair<Float, Float>? {
        val y = e?.eyeYaw ?: return null; val p = e.eyePitch ?: return null
        val sideways = y - gazeHeadShare * (e.headYaw ?: 0f)
        return (sideways - rest.first) to (p - rest.second)
    }
    fun gazeOff(e: FaceExpression?): Float? = gazeOffset(e)?.let { kotlin.math.hypot(it.first, it.second) }

    /** The working behind a signal read against a curve, for the score sheet. Same words as iOS. */
    fun rampHow(reading: Float, curve: Pair<Float, Float>, value: Float): String {
        val (good, bad) = curve
        fun s(v: Float) = shortNumber(v)
        return when {
            value >= 1 -> "${s(reading)} is ${if (good > bad) "at or above" else "within"} ${s(good)}: full marks"
            value <= 0 -> "${s(reading)} is ${if (good > bad) "at or below" else "at or past"} ${s(bad)}: zero"
            good > bad -> "(${s(reading)} − ${s(bad)}) ÷ (${s(good)} − ${s(bad)}) = ${"%.2f".format(value)}"
            else -> "(${s(bad)} − ${s(reading)}) ÷ (${s(bad)} − ${s(good)}) = ${"%.2f".format(value)}"
        }
    }

    // MARK: bodies (iOS values; the joint confidences read MediaPipe's visibility here, Vision's confidence there)

    /** Require the lower body: only body pose separates a headshot from a full-length photo. */
    const val requireFullBody = true
    const val bodyJointConfidence = 0.3f
    const val spreadJointConfidence = 0.1f
    /** On, an animal beside the shopper (a dog on the lap, a cat at the shoulder) is read like a second
     *  person and the photo is left out. Animals come from the object detector (`BodyObjects.animals`). */
    const val animalBesideDrops = true
    /** An animal box must reach this share of the tallest person's box height to count; smaller is in the distance. */
    const val animalBesideRatio = 0.25f
    const val animalConfidence = 0.5f
    /** A person box must score this to count as someone in the photo. Real people read 0.88 and up on the
     *  first library; a dog beside a lying shopper read 0.52 and 0.57 as two "people". */
    const val personConfidence = 0.7f
    /** The body whose head joints fall in the face box grown by this margin is the shopper's. */
    const val bodyFaceMargin = 0.5f
    /** Ankles a head-to-toe photo must show; one, since a side photo often hides the far ankle. */
    const val minAnkles = 1
    /** Head to lowest ankle as a share of the image height: with one ankle, with both. */
    const val minBodySpan = 0.55f
    const val minBodySpanBothAnkles = 0.40f
    /** Room below the lowest ankle, as a share of the span. */
    const val minFootRoom = 0.10f
    /** Shoulder width over torso length for a body facing the camera; side and three-quarter from the turn. */
    const val frontShoulderSpread = 0.60f
    const val sideTurnDegrees = 45.0
    const val threeQuarterDegrees = 30.0
    val sideShoulderSpread = (frontShoulderSpread * Math.cos(Math.toRadians(sideTurnDegrees))).toFloat()
    val threeQuarterShoulderSpread = (frontShoulderSpread * Math.cos(Math.toRadians(threeQuarterDegrees))).toFloat()
    /** Head turn (degrees; iOS: 0.25 rad) for a full-length photo to count as a side when no shoulders are found. */
    const val sideYawDegrees = 14.3f

    // MARK: full-length score (values from BodyScore.swift and GallerySettings.swift)

    const val fullLengthScoreV2 = true
    const val fullLengthTierALine = 0.70f
    const val fullLengthUsableFloor = 0.45f
    const val objectReadEnabled = true
    const val looseGarmentGateEnabled = true
    const val looseTorsoFit = 0.35
    const val looseLegsFit = 0.35
    /** Android only: the legs read as one shape (the mask unbroken between the two thigh centres on this share of
     *  the thigh rows) count as loose, as a long coat is. iOS reaches the same verdict another way: Vision loses a
     *  knee under the coat, so its thigh run spans the whole garment. MediaPipe keeps every joint, so the midline
     *  cap halves the coat and the fit reads 0.80 (16 Nov). A suit's thighs touch on about half the rows. */
    const val looseLegsMergedShare = 0.8
    /** Off: on the user's library the merged read hit 14 photos iOS keeps (every side view, and fronts in
     *  trousers with the legs together all read 100%), so it does not separate a coat from legs together.
     *  Kept as a switch; the reading stays in `scoreInputs.legsMerged`. */
    const val looseLegsMergedEnabled = false
    const val torsoUnreadNoTier = true

    // MARK: hands (GallerySettings.swift and MuseScore.swift values)

    /** A hand with this many points inside the padded face box is over the face. */
    const val handPointsOnFace = 10
    const val handsCropScale = 1.8f
    /** Selfies only: a hand in the face box times this (a V sign, a wave, a hand on the chin) rules the selfie out. */
    const val handNearFaceDrops = true
    const val handNearFaceScale = 2.5f
    const val handsNearCropScale = 3.5f

    // MARK: labels (iOS: Vision's classifier; Android: ML Kit image labelling, names mapped in `Labels`)

    /** Android only, on: the sunglasses label counts only when it beats ML Kit's eyeglasses reading. Clear
     *  glasses read eyeglasses 0.93–0.95 with sunglasses 0.57–0.92, so they pass; real sunglasses that also read
     *  as eyeglasses pass too (accepted, see `sunglassesConfidence`). */
    const val sunglassesMustBeatGlasses = true
    /** Android only: the sunglasses label's line, 0.6 beside iOS's 0.4. ML Kit puts 0.38–0.41 on bare faces and
     *  0.57–0.92 on clear glasses (always under its eyeglasses reading), 0.86–0.94 on real sunglasses. With the
     *  must-beat rule, clear glasses pass; a pair of sunglasses that also reads as eyeglasses passes too, which
     *  is the choice taken on 16 Nov: on Android spectacles are never hidden, iOS's Vision reading is not copied. */
    const val sunglassesConfidence = 0.6f
    /** Android only: the sunglasses label counts only when the eyes read darker than the cheeks under them
     *  (`MuseScore.eyeDark`, 1 = as bright) at or under this line. Null: the label alone, while the reading is
     *  logged to set the line. ML Kit names clear glasses "Sunglasses" at up to 0.92 (16 Nov). Clear glasses and
     *  bare eyes read 0.48–0.82 on 14 selfies. Real sunglasses then read 0.23–0.72 on the user's library, inside the
     *  same range, so the darkness does not separate them and the line is off: the label alone, as before.
     *  `MuseScore.eyeContrast` is logged next (sclera-to-iris contrast inside the eye box). */
    val sunglassesEyeDarkLine: Float? = null
    const val garmentCapsEnabled = true
    val looseGarmentLabels = setOf("gown", "sari", "kimono", "wedding_dress", "cloak", "poncho", "bathrobe", "costume", "jacket")
    const val looseGarmentLine = 0.3f
    /** ML Kit reads `jacket` 0.29–0.46 on T-shirts and 0.62+ on real outer layers, so it gets its own line
     *  (iOS: 0.3 on Vision). Untested against the labelled set; tune from scan reports. */
    val looseGarmentLineByLabel = mapOf("jacket" to 0.75f)
    /** Garment caps: labels, confidence line, then the cap on torso, legs and arms. */
    data class GarmentCap(val labels: Set<String>, val line: Float, val torso: Float, val legs: Float, val arms: Float)
    /** Android lines for suit and jacket sit higher than iOS's (0.4 / 0.22 and 0.6): ML Kit names Blazer 0.29–0.46 and
     *  Jacket 0.60–0.71 on plain T-shirts, while real suits read 0.62–0.86 (16 Nov). Vision gives those same suits
     *  0.22–0.24, only the half cap, so the strong cap's line sits above ML Kit's range and the half cap does the work. */
    val garmentCaps = listOf(
        GarmentCap(setOf("suit", "tuxedo"), 0.9f, 0.2f, 1f, 0.2f),
        // The torso-crop read: a blazer over a shirt sits at 0.90–0.93, T-shirts and sides at 0.39 and under (18 Nov).
        GarmentCap(setOf("suit@torso", "tuxedo@torso"), 0.6f, 0.2f, 1f, 0.2f),
        GarmentCap(setOf("lab_coat"), 0.3f, 0.2f, 1f, 0.2f),
        GarmentCap(setOf("suit", "tuxedo"), 0.5f, 0.5f, 1f, 0.5f),
        GarmentCap(setOf("gown", "sari", "kimono", "wedding_dress"), 0.3f, 1f, 0.2f, 1f),
        GarmentCap(setOf("hoodie"), 0.5f, 0.5f, 1f, 1f),
        GarmentCap(setOf("jacket"), 0.75f, 0.5f, 1f, 0.5f),
    )
    /** On, suit and tuxedo are read on the torso crop as well, as `suit@torso`: ML Kit gives a blazer over a shirt 0.33–0.37 on the
     *  whole photo (under the T-shirts' 0.29–0.46) but 0.90–0.93 on the crop, where T-shirts and sides read 0.39 or less (18 Nov).
     *  iOS needs no crop: Vision names the same blazer `suit 0.69–0.71` and the T-shirts nothing. */
    const val garmentReadOnTorso = true
    val torsoGarmentLabels = setOf("suit", "tuxedo")
    const val fullLengthOutfitMultiplier = true
    /** "Busy top" line on the torso crop's gradient. Android 0.13 beside iOS 0.11: the crop hangs off ML Kit's larger
     *  box and reads about 1.2× iOS on the same photos (eight shared bodies, 16 Nov); 0.13 fires on the same ones. */
    const val busyTorso = 0.13f

    /** The garment caps a photo's labels call for, and whether a loose garment was named. Null when nothing hits. */
    fun garmentCaps(garments: List<Pair<String, Float>>): BodyScore.Caps? {
        if (!garmentCapsEnabled && !looseGarmentGateEnabled) return null
        val caps = BodyScore.Caps()
        if (looseGarmentGateEnabled) garments.filter { looseGarmentLabels.contains(it.first) && it.second >= (looseGarmentLineByLabel[it.first] ?: looseGarmentLine) }.maxByOrNull { it.second }
            ?.let { caps.loose = "${it.first} ${"%.2f".format(it.second)}" }
        if (garmentCapsEnabled) for (rule in garmentCaps) {
            val hit = garments.filter { rule.labels.contains(it.first) && it.second >= rule.line }.maxByOrNull { it.second } ?: continue
            caps.torso = minOf(caps.torso, rule.torso); caps.legs = minOf(caps.legs, rule.legs); caps.arms = minOf(caps.arms, rule.arms)
            val why = "${hit.first} ${"%.2f".format(hit.second)}"
            if (!caps.why.contains(why)) caps.why = caps.why + why
        }
        return if (caps.why.isEmpty() && caps.loose == null) null else caps
    }

    // MARK: end-of-scan rules (GallerySettings.swift values)

    /** Gap rule: once every photo is scored, lower the cutoff into the gap between the shopper's photos and a lookalike's. */
    const val gapCutEnabled = true
    /** Every line in distance units sits 0.03 above its iOS value, with `matchThreshold`: ceiling 0.65, floor 0.45,
     *  weak cut 0.50 / reach 0.55, own-photo core 0.40 / keep 0.48. With only the cut shifted, the own-photo keep
     *  rescued 7 matches on iOS and 4 on Android for the same photos (16 Nov). */
    const val gapCutCeiling = 0.68f
    const val gapCutFloor = 0.48f
    const val gapCutMinGap = 0.08f
    const val weakReferenceCutEnabled = true
    const val weakReferenceCut = 0.53f
    const val weakReferenceReach = 0.58f
    const val ownPhotoCore = 0.43f
    const val ownPhotoKeepEnabled = true
    const val ownPhotoKeepLimit = 0.51f
    /** A quick no-match at or past the ceiling skips pose; a small face only at or past the second line. */
    /** 0.78 beside iOS 0.75, the same 0.03 shift. */
    const val earlyNoMatchSmallFace = 0.78f
    const val fullResFaceBelow = 224f
    /** The larger render a small face or a short body is looked at again on, longest side. */
    const val fullResPixels = 4096
    /** Relative gates against the shopper's own selfies, at scan end. */
    const val relativeBlurGateEnabled = true
    const val relativeBlurShare = 0.45f
    const val relativeBlurMin = 5
    const val relativeBlurMaxCm = 60f
    const val relativeEyeGateEnabled = true
    const val relativeOpenness = 0.4f
    const val relativeBaselineMin = 3
    const val frownRelativeEnabled = true
    const val frownOverUsual = 0.15f
    const val frownCeiling = 0.60f
    const val frownBaselineMin = 5
    const val frownBaselineMaxCm = 60f
    const val gazeRestFromLibrary = true
    const val gazeRestLibraryMin = 5
    const val gazeRestMaxShift = 6f
    /** Near-duplicates: a 64-bit difference hash within this many bits of an earlier match is the same shot
     *  (iOS compares Vision feature prints; the page pre-selects one per group). */
    const val duplicateHammingBits = 6

    /** The lowered cutoff for this run's distances, or null to keep `matchThreshold`. */
    fun gapCut(distances: List<Float>): Float? {
        if (!gapCutEnabled) return null
        val sorted = distances.filter { it < gapCutCeiling }.sorted()
        if (sorted.size < 2) return null
        var gap = 0f; var lo = 0f; var hi = 0f
        for (i in 0 until sorted.size - 1) if (sorted[i + 1] - sorted[i] > gap) { gap = sorted[i + 1] - sorted[i]; lo = sorted[i]; hi = sorted[i + 1] }
        if (gap < gapCutMinGap) return null
        val cut = maxOf(gapCutFloor, minOf(matchThreshold, (lo + hi) / 2))
        return if (cut < matchThreshold) cut else null
    }

    /** Android only: photos decoded and detected ahead of the one being read, and the threads doing it. */
    const val lookAheadPhotos = 4
    const val lookAheadWorkers = 3

    /** How often the page's progress bar is told, in ms. */
    const val progressIntervalMs = 250L

    /** The scan window the page may ask for, in months. */
    val monthsBackRange = 1..36

    /** Debug builds log one line per photo scanned (outcome, distance, face size, time). */
    val scanLogEnabled = BuildConfig.DEBUG
    /** Debug builds: each scored body's render with the person mask tinted and the measured rows drawn, saved under
     *  `files/scan-debug/<file>.png` for `adb pull`, to trace a body reading against iOS's. */
    val debugDumpMasks = BuildConfig.DEBUG
    /** Debug builds only: scan one folder of the library instead of the whole window, so both phones judge the
     *  same files (the iPhone's Swan-Eval album). A `relative_path` prefix such as "DCIM/SwanEval"; null scans all. */
    val evalFolder: String? = null   // e.g. "DCIM/SwanEval" for a pushed test set
    /** Debug builds with `evalFolder`: log every photo's clothing labels at scan start, match or not, so a label
     *  line (sari, gown, suit) can be read on stock photos that match no reference. */
    val debugLogLabels = BuildConfig.DEBUG

    // MARK: eval mode (Swan-Eval) — see DevTools.kt; the eval half of the Xcode copy's DevTools.swift

    /** Measures matching against a hand-labelled folder and logs the result after each scan. Debug builds
     *  only; keep it off in any build a brand sees. The grid is unaffected. */
    val evalMode = BuildConfig.DEBUG && false
    /** The folder that holds every photo that should reach the grid: the shopper alone, head to toe, or a
     *  selfie of them. Read as the answer key (the iPhone's "Swan-Eval" album). */
    const val evalAlbumFolder = "DCIM/Swan-Eval"
    /** How much of the grid "precision at the top" looks at. */
    const val evalTopN = 20
    /** Replace the saved eval reference with this run's. Set true for one run, then back. */
    const val evalReplaceReference = false
    /** Debug builds: two more garment reads per matched body in the trail (ML Kit on the torso crop, and MediaPipe's
     *  image classifier on the crop and the whole photo). Logged only, no scoring; for setting a blazer line. */
    val debugGarmentReads = BuildConfig.DEBUG
}
