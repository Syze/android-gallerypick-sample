# Gallery Pick on Android — plan

How to bring the iOS Gallery Pick flow to Android: what is reused as is, what the
shared code needs changed so one page serves both, and what has to be written
new in Kotlin. Steps are in build order. Each step ends with something that runs.

**Where this stands (13 Nov 2026).** Steps 1 and 2 are done and verified on a
phone: the page detects Android, the sample signs in, loads the page, runs the
camera flow, uploads and builds. Step 3 is split into groups A–D below; group A
(library plumbing) is built and verified on a phone; group B slices 1–3 (faces
matched with SFace) are built, parity check pending. The decisions at the end
are taken.

## 0. What we have, and what carries over

| Piece | Today | On Android |
|---|---|---|
| The web page (`swan-mobile`, v1.3.18 live; the `[Unreleased]` work on `dev-mobilePackage` on top) | A WebView page; talks to the host by `window.postMessage` in and `swanBridge` out | **Reused as is.** `nativeBridge.ts` already finds `window.swanBridge` (the name `addJavascriptInterface` gives) before the WebKit handler |
| Message contract (`GALLERY_PICK.md`) | `RENDER_COMPONENT`, `GALLERY_SCAN_START`, `onGalleryMatchFound`, `onGalleryScanComplete`, thumbnails, full image, callbacks | **Reused as is.** The Kotlin host answers the same messages with the same payloads |
| Backend: register, upload, build, socket, images, profile | Called by the page, not the host | **Nothing to do** |
| Swift controller (19 files, ~7,350 lines) | Scan, matching, scoring, photo serving, store, session, report | **Rewritten in Kotlin**, file for file, same rules |
| Tuning values (`GallerySettings.swift`) | Set on iPhone readings | **Ported**, then re-tuned (step 7) |
| Models in `FaceModels/` | SFace (Core ML), `face_landmarker.task`, `selfie_multiclass_256x256.tflite`, `efficientdet_lite0.tflite` | MediaPipe files **reused**; SFace needs its ONNX source from the OpenCV model zoo — **a prerequisite for step 3**, not a later decision |
| Scan report, ingest, sandbox/production | `ScanReport.swift`, `SwanEnvironment.swift`, two schemes | **Ported**; build flavors instead of schemes |

## 1. Shared page: one build serves both platforms

Page changes, released once, before any Android work needs them. Nothing here
changes iOS behaviour: every current host keeps today's defaults.

1. **Platform detection inside the package** — one helper, `detectPlatform()`,
   read once at mount and exposed through the copy context like `appName`:
   1. `platform` prop in `RENDER_COMPONENT` (`"ios" | "android"`), when sent;
   2. the bridge's shape: `window.webkit.messageHandlers.swanBridge` is iOS,
      a plain `window.swanBridge` is Android (`addJavascriptInterface`);
   3. `Android` in the user agent, for hosts that inject `window.swanBridge`
      on both platforms (React Native);
   4. else `"ios"`.
2. **`requestSource`** — `galleryService.ts` and the build payload in
   `useGalleryPick.ts` hard-code `"ios"`; both read the detected platform.
3. **Permission guides by platform** — `GalleryPermissionGuide` keeps one
   layout; the step words and the drawn Settings figure follow the platform.
   Android: "Open Settings › Apps › {appName} › Permissions", "Tap Camera",
   "Choose Allow only while using the app"; photos: "Photos and videos",
   "Allow all". The copy key names stay the same (`step1Text`, …) with
   per-platform defaults, so a host override wins on both. Android does not
   end the app after a permission change, so there the guide offers Open
   Settings and Try again both. Behind `PLATFORM_GUIDES`. `OPEN_SETTINGS` is
   unchanged; the Kotlin host opens the app's settings page.
4. **Photo scheme** — the page loads thumbnails from `swan-photo://thumb?id=…`.
   Android's WebView can serve a custom scheme through `shouldInterceptRequest`,
   but `https://swan-photo.local/thumb?id=…` is safer (no mixed-content and
   CORS surprises). `PHOTO_SCHEME` becomes a prop with the current value as
   default, so iOS keeps `swan-photo://`. The page's `SCHEME_TEST_RESULT` check
   already reports whether `<img src>` from the scheme works; it is the first
   thing to verify on Android.
5. **`platform` on analytics** — added to every gallery event in
   `galleryAnalytics.ts`, so iOS and Android funnels can be split.
6. **Docs** — `GALLERY_PICK.md`: one "Android host" section beside the iOS one,
   same message table, plus the `platform` and scheme props;
   GALLERY_PICK_TEXT_CONFIG.md: the Android defaults of the guide keys.

### Step 1 build plan

| # | File | Change |
|---|---|---|
| 1 | `galleryConstants.ts` | `PLATFORM_GUIDES = true`; `Platform = "ios" \| "android"` |
| 2 | `galleryUtils.ts` (or a small `platform.ts`) | `detectPlatform(prop?)` in the order above; a `[swan/gallery] platform ios (bridge)` log line saying which source decided |
| 3 | `GalleryCopyContext.tsx` | `platform` beside `appName`; `usePlatform()` |
| 4 | `GalleryPick.tsx` | read `config.platform`, resolve once, pass to the provider and the hook |
| 5 | `galleryCopy.ts` | `libraryBlockedPage` / `cameraBlockedPage` defaults per platform; `resolveGalleryCopy(overrides, appName, platform)` picks the table before merging |
| 6 | `GalleryPermissionGuide.tsx` | `SettingsAppFigure` / `PhotoAccessFigure` draw the Android page when `platform === "android"`; both buttons on Android |
| 7 | `galleryService.ts`, `useGalleryPick.ts` | `requestSource` from the platform |
| 8 | `galleryAnalytics.ts` | `platform` on every event |
| 9 | `PHOTO_SCHEME` | prop `photoScheme`, default `swan-photo` |
| 10 | Docs + CHANGELOG `[Unreleased]` | as above |

Order: 1–4 first (detection, no visible change), then 7–8 (platform in the data),
then 5–6 (guides), then 9, then docs. Each lands type-checked; the iOS sample is
run after 4, 8 and 6.

Verification: iOS sample on the new page behaves as before (same scan, same
picks, iOS guides, `platform: ios` in PostHog and `requestSource: ios` in the
build request); a plain Chrome tab with a fake `window.swanBridge` shows the
Android guides; the Android skeleton from step 2 renders the intro screen.

## 2. Android skeleton: WebView + bridge, no scan yet

Goal: the client can run the page on Android and complete a Muse through the
camera fallback before any scanning exists.

- New repo `android-gallery-sample`, same shape as `ios-gallery-sample`:
  `app/` with one Activity, `docs/` copied from the package.
- `WebView` with JS enabled, `addJavascriptInterface(bridge, "swanBridge")`,
  `evaluateJavascript("window.postMessage(…)")` for the other direction.
  Camera needs `WebChromeClient.onPermissionRequest` granted and the
  `CAMERA` runtime permission; `getUserMedia` needs https, so the page comes
  from the CDN or an https tunnel, never `file://`.
- `SwanEnvironment.kt`: page URL, session URL, key, scan-report settings;
  two product flavors `sandbox` / `production` mirror the iOS schemes.
- `SwanSession.kt`: the same `/api/session` mint, token in `EncryptedSharedPreferences`.
- `handleCallback(name, payload)`: the same case list as the Swift one.
- Answer `GALLERY_SCAN_START` with `onGalleryScanComplete { assetIds: [] }` for now,
  so the page goes to the camera fallback (use a page build with the no-match
  countdown fix, see CHANGELOG `[Unreleased]`).
- Handle `OPEN_SETTINGS`: `ACTION_APPLICATION_DETAILS_SETTINGS` for the app.

Verification: sign in, selfie, camera top-up, upload, build, Muse shown.
PostHog shows `platform: android`.

## 3. Kotlin modules, mirroring the Swift files

One Kotlin file per Swift file, same names, so a change can be ported by
reading the two side by side.

| Swift | Kotlin | Notes |
|---|---|---|
| `ViewController.swift` | `GalleryActivity.kt` + `WebBridge.kt` | bridge in, `sendToWeb` out |
| `GallerySettings.swift` | `GallerySettings.kt` | all `static let`s become `object` vals; **keep the iOS values** to start |
| `GalleryScan.swift`, `LibraryScan.swift` | `GalleryScan.kt`, `LibraryScan.kt` | coroutine on `Dispatchers.Default`; progress to the page every 250 ms as now |
| `FaceVision.swift` | `FaceVision.kt` | detection, landmarks, pose, matching |
| `MuseScore.swift` | `MuseScore.kt` | pure maths, port line by line |
| `BodyScore.swift`, `BodyObjects.swift` | same names | body visibility, objects over the body |
| `FaceExpression.swift`, `FaceCover.swift` | same names | MediaPipe Tasks for Android, same model files |
| `FaceRecognition.swift` | `FaceRecognition.kt` | SFace through TFLite (or OpenCV Android); same 5-point alignment, same distance |
| `GalleryPhotos.swift` | `GalleryPhotos.kt` | thumbnails, originals, captures, `shouldInterceptRequest` |
| `ScanStore.swift` | `ScanStore.kt` | same JSON record, app-private files |
| `ScanReport.swift` | `ScanReport.kt` | same JSON, gzip, `x-api-key`, retries; report gains `platform` |
| `Helpers.swift` | `Helpers.kt` | median, ramp, clamp, rounding |
| `ScanStore.swift`, `SwanSession.swift` | same names | per-photo record store; session mint |

Rules added since 5 Nov that the port must carry (all in `[Unreleased]`): the
scan-report trail, the loose-garment gate, torso-unread no tier, capture gates
(camera photos scored like scanned ones). The iCloud progress counting and the
multi-shot top-up are iOS-only or page-only and need nothing in Kotlin.
`DevTools.swift` (Xcode copy only) becomes `DevTools.kt` in debug builds: logs
and eval.

### Step 3 build plan: four groups, each runs on the phone on its own

**Group A, library plumbing, no scoring** (built and verified 13 Nov)

- Photo permission on `GALLERY_SCAN_START`: `READ_MEDIA_IMAGES` (13+) or
  `READ_EXTERNAL_STORAGE`, plus `READ_MEDIA_VISUAL_USER_SELECTED` on 14+. Refused
  answers `denied: true`, which shows the Android photo-access guide. Android 14's
  selected photos count as `limited`, with no date window.
- `GalleryPhotos.kt`: the window from `MediaStore.Images`, newest first, the
  Screenshots folder left out; grid tiles from `loadThumbnail`, full renders via
  `ImageDecoder` (EXIF orientation applied); originals streamed as their own bytes
  and MIME type, 10 MB cap; capture and analysis stores.
- `GalleryActivity.kt`: `swan-photo://thumb?id=…[&size=full]` served from
  `shouldInterceptRequest`; handlers for `GALLERY_THUMBNAILS`, `GALLERY_FULL_IMAGE`,
  `GALLERY_CAPTURE_PHOTO`, `GALLERY_ANALYSE_PHOTO`, `GALLERY_RESET`,
  `GALLERY_CLEAR_CAPTURES`, `GALLERY_PICK_MORE`, `SCHEME_TEST_RESULT`.
- Debug-only stub (`GallerySettings.scanStubEnabled`): the newest 20 photos in
  the window stream as matches with made-up types (a selfie, then fronts and
  sides) so the grid, the thumbnails and a real library upload can be tried before
  any face work exists. Release answers "no photos".
- Verify: deny once → guide; allow → grid of 20 real thumbnails; build → uploads
  logged `original: true` with `image/jpeg` or `image/heic`.

**Group B, faces** (about two weeks; slices 1–4 built 13 Nov)

1. Dependencies and models: OpenCV Android and MediaPipe Tasks Vision in Gradle;
   `face_landmarker.task` copied from the iOS bundle; the SFace ONNX is already in
   `assets/FaceModels/` (38.7 MB, from the OpenCV zoo's Git LFS media URL; the raw
   GitHub URL returns an HTML page). Build green, APK size noted.
2. `FaceVision.kt`: Face Landmarker on one photo: box, 478 points, yaw, pitch,
   roll, eye spacing. One debug log line per photo in the same words as the Xcode
   copy, so an Android log diffs against an iOS log of the same picture.
3. `FaceRecognition.kt`: OpenCV `FaceRecognizerSF`, 5-point alignment, embedding,
   distance to the selfie, cut-off 0.60 as on iOS. The stub becomes real for
   faces: the reference from `GALLERY_SCAN_START`, every photo in the window
   checked, matches streamed as `selfie` with `distance`. Bodies still stubbed.
   First parity check: match distances on the same photos on both phones.
   Built: `FaceVision.kt` (ML Kit boxes at every distance, `minFaceSize` 0.05;
   the landmarker on each head crop for the points, and once more with
   blendshapes and the head transform for expression, as iOS),
   `FaceRecognition.kt` (OpenCV `FaceRecognizerSF`, eyes from the iris centres,
   nose tip 1, mouth corners 61/291), `GalleryScan.kt` (reference by face detail,
   selfie refused for other faces or yaw over 20° / pitch over 15°, bystander
   0.25, group, face floor 120 px, 0.60 cut, progress every 250 ms, summary
   tally). Not yet ported from iOS: the larger-render retry for small faces, the
   gaze check on the reference, the gap cut and early no-match, the per-photo
   store. APK 101 MB for arm64 (SFace 37 MB, OpenCV 24 MB, MediaPipe 15 MB,
   landmarker 3.6 MB, code 19 MB); every ABI would be 285 MB, so the build is
   arm64 only.
4. `MuseScore.kt`, selfie half: the selfie gates and the v2 selfie score ported
   line by line, with `FaceExpression.kt` and `FaceCover.kt` on the same MediaPipe
   models. Tier A/B, `scoreInputs`, `scoreParts`, `scoreReasons` in the match so
   the page's score sheet and PostHog see the same fields as from iOS. The
   largest slice, about half of the group.
   Built: every gate and signal group, `FaceCover.kt` (selfie segmenter, same
   bands), `Original.kt` (ISO, focal length, exposure from EXIF), `order` and
   `takenAt` (favourite flag only; no edits or burst picks on Android), gated
   selfies withdrawn and counted in `unusableGates`. Readings iOS takes from
   Vision that have no Android source yet, so their rules stay quiet: capture
   quality (reads 0.5), classifier labels (sunglasses, eyeglasses, hats, scarf,
   dressy, documents), hand pose, document page, phone tilt (head-level gate).
   Candidates: ML Kit Image Labeling with a label map, MediaPipe Hand Landmarker.
   Documents are covered (13 Nov): `DocumentRead.kt` runs ML Kit Text Recognition
   on each matched photo and drops it as `document` when it holds 6 or more lines
   of printed text with 3 or more around the face, or 3 or more lines covering 3%
   of the frame (`documentMinLines`, `documentNearFaceLines`,
   `documentNearFaceScale`, `documentAreaMinLines`, `documentMinTextArea`);
   readings `docLines`, `docNearFace`, `docTextArea` go to the page. Pulled
   forward because a voter ID scored Tier A 0.90 on the first library; it reads
   5 Latin lines over 12.5% of the frame, ordinary selfies 0–4 lines under 1.5%.
   The reader knows Latin script only; Devanagari can be added (about 5 MB). Also pending from iOS: the gap cut, early no-match,
   the scan-end relative gates (blur, eye openness, frown, gaze rest from the
   library), the per-photo store, the larger-render retry.

**Group C, bodies** (about one week; slices 1–2 built 13 Nov)

- `BodyScore.kt`, `BodyObjects.kt` on Pose Landmarker, the segmenter and
  EfficientDet; type front / side / three-quarter; head-to-toe; the loose-garment
  and torso-unread rules; the body half of `MuseScore.kt`. The stub goes.
- Built: `BodyPose.kt` (`BodyCheck` from the pose landmarker, up to three poses,
  the one whose head joints sit in the face box; joints count only inside the
  frame and present, since MediaPipe places a cut-off ankle at the edge with a
  confident visibility), `BodyObjects.kt` (EfficientDet from the iOS bundle for
  objects and the people count, person boxes at 0.5 and merged when they overlap,
  since the detector called a dog a person at 0.3), `photoType` by shoulder
  spread with the head yaw fallback, the `other people` and `not head to toe`
  gates, `BodyScore.kt` line for line (the selfie segmenter on a crop around the
  pose stands in for Vision's person mask; garment caps wait for labels), `order`
  for bodies. Models: `pose_landmarker_full.task` 9.4 MB (new on Android; iOS
  uses Vision for joints), `efficientdet_lite0.tflite` 4.4 MB. APK 145 MB.
- First parity numbers (13 Nov, six standing fronts on the first Android
  library against 208 iPhone full-length photos): torsoRatio 0.85–0.89 vs iOS
  median 0.92, shin 0.21–0.27 vs 0.24, armWidth 0.15–0.17 vs 0.17, thigh
  0.28–0.31 vs 0.39 (all inside the fit curves, so the same fit of 1.00). The
  suit photos score 1.00 where iOS gives about 0.50: iOS's "suit" label caps the
  torso at 0.2 and Android has no labels yet. Person boxes: real people read
  0.88 and up, a dog 0.52–0.57, so people count from 0.7.
- Labels (13 Nov): `Labels.kt` runs ML Kit's base image labeller on the head
  crop (glasses, hats, scarf) and the whole photo (garments, formal wear), with
  its names mapped to the iOS ones; a blazer is read as a suit. Covered:
  sunglasses, eyeglasses, hat, baseball_hat, helmet, scarf, tuxedo, suit, jacket,
  necktie, gown, sari, passport, receipt, paper, screenshot. Never fire on
  Android: hoodie, kimono, costume, lab_coat, mask, beanie, wedding_dress,
  bowtie. With them the garment caps, loose-by-label, the busy-top multiplier
  and the selfie label gates run: the suit fronts fell from 1.00 to 0.60–0.63
  (iOS about 0.50), eleven selfies in real sunglasses and a cap were withdrawn
  as `sunglasses`. ML Kit's confidences run higher than Vision's: sunglasses and
  glasses both read 0.9 on sunglasses, a cap reads hat 0.8, a bare head 0.2–0.47
  against the 0.5 hat line, so that margin is thin and is a step 7 item.
- Pending in the group: the larger-render retry on a small face, the body check
  on a bigger render when only the legs are short.

**Group D, the scan loop** (about one week; slice 1 built 13 Nov)

- `LibraryScan.kt`, `GalleryScan.kt`: the pass over the window, progress every
  250 ms, streamed matches, `dropped` and `summary`; `ScanStore.kt`;
  `ScanReport.kt` with `platform: "android"`; `DevTools.kt` logs in the Xcode
  copy's words. Then step 7.
- Slice 1 built: per-match records; matches from `gapCutFloor` up held back
  and sent last; the gap rule with the weak-reference fallback and the own-photo
  keep; the relative eye and blur gates; the gaze resting point from the library
  and the frown line, with changed selfies resent `rescored`; `dropped` in the
  completion; early no-match counted; near-duplicates by a 64-bit difference
  hash within 6 bits (iOS compares Vision feature prints), one `dupGroup` per
  shot. Summary gains `gapCut`, `gapDropped`, `ownKept`, `eyeDropped`,
  `blurDropped`, `libraryRest`, `frownLine`, `rescored`, `closestNoMatch`.
- Slice 2 built: `ScanStore.kt`, the same JSON record per photo (verdict,
  modified stamp, rules version, a candidate's face vectors) in the app's
  no-backup folder, one file per account named by a hash of the email, the
  other accounts' files deleted on open. A known photo is counted without a
  load; a stored candidate is compared with the new selfie from its vectors
  and loaded only when it matches. Saved every 500 records and at the end.
  Summary gains `storeDecided`, `storeWritten`, `storeRecords`.
- First end-of-scan run on the first library (9 shown selfies): gap cut 0.51
  found, nothing above it; one selfie withdrawn as blurred against usual; the
  gaze resting point from the library 0.8/−7.1 against the default 0/−7.5, one
  selfie rescored; frown line at the 0.25 floor.
- Slice 3 built: `ScanReport.kt`, the same report as iOS with `platform:
  "android"`, `device` and `model` from `Build`, gzipped JSON with the ingest
  key, retried on 5xx and network errors, the page's own picks collected from
  `onImageSelect` for 4 s after the scan, `onGalleryScanReportStored` to the
  page. The trail holds every photo looked at with its outcome, filename,
  capture time and time spent; `checks` carries the eye, blur, gaze-rest and
  frown baselines and what they withdrew or rescored. Sandbox builds only.
- Hands (13 Nov): `Hands.kt`, MediaPipe's hand landmarker (7.5 MB, 21 points
  per hand like Vision's hand pose) on a crop around a matched selfie's face;
  the "hand over face" and "hand near face" gates with the iOS numbers.
- Animal rule (13 Nov): an animal beside the shopper counts like a second
  person, `animalBesideDrops`, landed in Swift (sample, `native-samples/`, the
  Xcode copy, all type-checked) and in Kotlin the same hour; both object
  detectors name the animals already, store rules version 13.
- Larger-render retries (13 Nov): a face under 120 px at 1536 whose original
  clears it is looked at again on a 4096 render (`faceRetried` / `faceRescued`);
  a matched body with the head and at least one lower joint seen but not head
  to toe is checked again on that render and scored there when it passes
  (`bodyRetried` / `bodyRescued`). Same rules as `matchPerson`. First run: the
  four short bodies were retried and none rescued (knee-ups); the 75 small faces
  were decided from the store written before the retry existed, so Android's
  store rules version moved to 14, one ahead of iOS's 13, to re-examine them.
- Done 17 Nov: `DevTools.kt`, the eval mode of the Xcode copy (a `DCIM/Swan-Eval` folder
  as the answer key, the trail as the per-photo record, the same block printed and saved,
  the reference pinned per account). Was: `DevTools.kt`, the eval mode of the Xcode copy (Swan-Eval album,
  per-photo CSV), needed for step 7 and ported then.
- Cost pass (13 Nov), full runs on the 521-photo library, same 16 matches each
  time: baseline 197 s (decode ~125 ms and detection ~115 ms per photo for all
  521; readers only for matches). Small render first (768 px for detection, the
  1536 render only past the cheap gates) plus decode-ahead: 146 s, stages detect
  57 s, 4096 decode 24 s, decode 18 s. Detect ahead on two threads, the larger
  render only as large as the face floor needs (iOS's ×1.25 rule), objects on
  the small copy: 120 s, the larger decode 22 s now the top stage. The larger
  render decoded on the look-ahead threads too, three workers: 114 s; the main
  loop's stages add up to about 70 s, so it now waits on the look-ahead
  pipeline, where one ML Kit detector client serves every worker. A detector
  per worker thread: 101 s, `waitAhead` 34 s; the look-ahead threads are now
  CPU-bound at about 70 ms per photo, close to the iPhone's 6 photos a second on
  a full run. The main loop's own decode and detect (11 s each) are the 1536
  render and the retry detection for candidates, both needed. Speed work
  stops here unless a cheaper detection copy is accepted (parity risk).
- Size (16 Nov): the zoo's int8 SFace (9.4 MB) replaces the float one (36.9 MB),
  `sfaceInt8`: a full run gave the same 16 matches, distances within a few
  hundredths of the float runs, embedding 7.2 s instead of 4.7 s over the scan,
  97 s total. APK about 140 MB for arm64. Play Feature Delivery for the models
  is the client's packaging choice and is documented, not built. The cost
  pass is closed: 197 s → 97 s on a full run of the same library, repeat scans
  20–40 s with the store, same picks throughout. Then, once the port
  is whole, one pass on cost and size (decode once, decode ahead, readers only
  past the cheap gates, warm-up, GPU delegate, int8 SFace, Play Feature
  Delivery), each change measured on the same library.

## 4. Platform replacements for Apple frameworks

| Apple API used today | Android choice | Why |
|---|---|---|
| PhotoKit fetch, date window, screenshots skipped, originals | `MediaStore.Images` with `DATE_TAKEN`, `IS_SCREENSHOT`-style filter by path, `ContentResolver.openInputStream` for originals; Scoped Storage needs `READ_MEDIA_IMAGES` (Android 13+) | same data, different API |
| iCloud-only photos, previews, download caps | Google Photos "cloud-only" items are **not** in MediaStore; treat as absent. Drop the iCloud pass entirely on Android | simpler |
| Vision face detection + landmarks + quality | **ML Kit Face Detection for the boxes, MediaPipe Face Landmarker on each head crop for the points** (13 Nov). The landmarker alone was tried first and missed the face in most full-length photos (449 of 521 "no face" on the first library, clean suit photos among them): its detector is short-range. ML Kit plays Vision's role, the landmarker keeps giving the 478 points, pitch and expression iOS reads from the same model. Its model lives in Play services (`com.google.mlkit.vision.DEPENDENCIES`), so the app grows by about 1 MB. No separate R&D app: the scan's own debug logs are the comparison tool | fewer sources of drift |
| Vision `faceCaptureQuality`, Apple gravity in EXIF | none | the blur-against-usual and head-level gates are off on Android; documented |
| Vision body pose (joints) | **MediaPipe Pose Landmarker** | matches the joint set BodyScore needs |
| Vision person segmentation | **MediaPipe Image Segmenter** (selfie multiclass is already bundled) | same model family |
| Vision image classification (labels: sunglasses, cap, suit…) | **ML Kit Image Labeling** or the existing MediaPipe object detector | labels drive caps and hands rules; expect name differences, map them in one table |
| Vision text / document detection | ML Kit Text Recognition (document gate) | |
| Core ML SFace | **OpenCV for Android** running the zoo's float ONNX through `FaceRecognizerSF` (decided 13 Nov): same alignment, same embedding, same 0.60 cutoff. TFLite conversion was the alternative; dropped as an extra fiddly step | same model, no conversion |
| `SensitiveContentAnalysis` | none; skip | Apple-only |
| HEIC decoding, EXIF (ISO, focal, gravity for the head gate) | `ImageDecoder` (HEIF on Android 9+), `ExifInterface`; Apple maker-note gravity is absent → head-level gate off on Android, `cameraTilt` nil | document the gap |
| Live Photo paired video | none on Android (Motion Photos differ); upload the still only | |

## 5. Photo serving and memory

- Thumbnails: `MediaStore.loadThumbnail` (Android 10+) at the page's size; served
  through `shouldInterceptRequest` for `https://swan-photo.local/…`.
- Originals: streamed from `ContentResolver`, never re-encoded, same 10 MB cap.
- Captures from the page (base64 JPEG): keep the same 120 MB decoded cap.
- WebView runs in the app process on Android, so the scan's memory counts
  against the page too: keep peak under ~400 MB on 4 GB phones. The last client
  scan peaked at 595 MB on iPhone at 1536 px, so the render size or the iCloud
  queue (absent on Android anyway) must give; measure on the skeleton first.

## 6. Dev tools and reporting

- Scan report to the same ingest (`devphotoingest…/v1/scans`), gzipped, with
  `platform: "android"` and `model: Build.MODEL`. The visualizer then shows both.
- `onGalleryScanReportStored` → page → PostHog, unchanged.
- Dev flavor: `DevTools.kt` with the per-photo log lines in the same wording as
  the Xcode copy, so an Android log can be diffed against an iOS log of the
  same library. Eval mode (Swan-Eval album) ported last; needed for step 7.
- Logcat is the console; `adb logcat -s swan` replaces the Xcode log file.
- **R&D readings app, before the port:** a small Android app that runs each
  candidate API (step 4) on a pushed copy of a labelled library and writes the
  same `scoreInputs` JSON the iOS scan report has. The 633 body photos and the
  selfies already on file give the iOS side, so comparing is a script. It
  decides the face source and the label map, and grows into `DevTools.kt`.

Eval mode's first run on Android (17 Nov, answer key = the 18 of the 51 shared
photos iOS showed, whole library scanned): recall 1.00 (18 of 18 reached the
grid, no gate lost one), precision at the top 0.80 (the four "wrong" in the
top 20 are the small photo, the head-down selfie, and two of the user's own
photos outside the 51 that the key cannot know about), coverage fillable,
selfie AUC 0.965. The full-body AUC (0.47) is an artefact of that key: the
"wrong" bodies are the user's WhatsApp photos. A real key labels the whole
library, as the iPhone album does.

## 7. Re-tuning: the real work

**Procedure (16 Nov).** Debug Android builds keep every scan report on the
phone (`files/scan-reports/<at>.json`, `adb pull` with `run-as`), beside the
copy posted to the ingest. `scripts/compare-scan-reports.py ios.json android.json`
joins the two trails by filename and prints outcome agreement, one-sided
matches, and type / distance / score / tier / pick for the shared matches. The
iOS report must come from a build with the trail (the Xcode copy since the
`[Unreleased]` work); the saved reports from before 12 Nov have no trail.
The same files must sit on both phones with their original names: export the
iPhone set (the Swan-Eval album, or the whole window) to the Mac and
`adb push` it into `/sdcard/DCIM/SwanEval/` on the Android phone, then scan
with a window wide enough for the capture dates. Files with spaces in their
names need the media scanner run on each one by hand (`MEDIA_SCANNER_SCAN_FILE`
with the space URL-encoded), or they index without a date.

**First round (16 Nov, 51 shared photos).** Both phones scanned the same set;
the trail port landed in the Xcode copy the same day (it only had the sample
copies), and 16 files the export had renamed were joined by capture time.
Result: 42 of 51 agree in outcome. Every gaze-set selfie agrees (looking
away / up / down, head turned, and the Tier A/B split). Differences, with the
readings behind each:

| # | Photos | iOS | Android | Cause | Proposed Android value |
|---|---|---|---|---|---|
| 1 | 2 sides, face 61–64 px, yaw ±65° | match 0.50 / 0.57, Tier A sides | "no landmarks" (no face) | MediaPipe landmarker finds nothing on a strongly turned small head crop; Vision aligned it | when the landmarker returns nothing, align SFace on ML Kit's own eye/nose/mouth landmarks instead of giving up |
| 2 | 3 fronts/side in T-shirts | 0.98 / 0.98 / 1.0 Tier A | 0.62 / 0.51 / 0.78 (caps) | ML Kit names `Blazer 0.29–0.46`, `Jacket 0.60–0.71` on T-shirts; real suits read `0.62–0.76` / `0.79–0.85` | garment lines beside the iOS ones: suit 0.6 (cap 0.2) and 0.5 (cap 0.5) instead of 0.4 / 0.22; jacket 0.75 instead of 0.6 |
| 3 | 11 of 15 bodies | `busy` 0.08–0.12, fires on 1 | "busy top ×0.80" on 11 | same 64 px gradient code, but the torso crop hangs off ML Kit's larger face box; the Android value is not in the report | write `busy` into the Android body `scoreInputs`, then set the line from the two columns |
| 4 | 9 shared matches | distance d | d + 0.017…0.063, mean +0.038 | int8 SFace, and MediaPipe eye/nose points vs Vision's | match cut 0.63 beside 0.60 (and the 0.65 gap ceiling to 0.68); strangers shift up by the same amount, so the margin holds |
| 5 | 1 front, photo 1228×1638 | face too small (97 px) | match, Tier A | ML Kit boxes run wider than Vision's, so the 120 px line is looser on Android | log `facePx` in the Android trail first; one photo is not enough to set a ratio |
| 6 | 2 selfies | unusable "head down" | match, Tier B | the iOS gate reads the phone's tilt from Apple capture metadata, which Android photos never carry | no Android value: the gate stays iOS-only, noted in GALLERY_PICK_ANDROID.md |
| 7 | 3 selfies with a scarf over the mouth | face found, no match (d 0.84–1.14) | no face | ML Kit does not detect a half-covered face; Vision does and SFace rejects it | none: the same photos are hidden on both |
| 8 | 1 selfie at the Tier line | 0.703 Tier A | 0.70 Tier B | rounding | none |

Applied after the round (16 Nov): items 1–5 as proposed (`mlKitLandmarkFallback`,
garment lines suit 0.6 / 0.5 and jacket 0.75, `busy` in the body `scoreInputs`,
cut 0.63 and ceiling 0.68, `facePx` in the trail), plus the head-down / head-up
gate with the tilt taken as `assumedPhoneTiltDegrees` = 0 and `headVsFloor`
reported, so the next round can measure the estimate against iOS's true-tilt
reading on the shared iPhone photos. Store rules bumped to 15.

**Second round (16 Nov, Android rescan against the same iOS report).** 29 of
35 filename-joined photos agree (was 26); the 16 time-joined gaze selfies still
all agree. Both turned sides now match through the ML Kit fallback (4 photos
used it in the library), with the same tiers as iOS. The T-shirt fronts read
0.77 / 0.80 Tier A (iOS 0.98); the rest of that gap was "busy top". Readings
compared on the shared photos, and the lines set from them:

- `busy`: Android reads about 1.2× iOS on every shared body (0.088 vs 0.079 …
  0.150 vs 0.120). Line 0.13 beside iOS 0.11 fires on exactly the photos iOS
  fires on.
- `facePx`: ML Kit's box is 1.51× Vision's as a median of 32, but the ratio
  is 1.5–2.2× on close selfies and only 1.1–1.3× on the far faces the floor
  is about. A floor of 170 was tried and dropped every full-length photo
  (third round), so the floor stays at 120; one small photo Android keeps
  that iOS drops is the cost.
- `headVsFloor` with the tilt assumed level: within ±3° of iOS's true-tilt
  reading on 10 of 11 shared selfies (mean +1.8°), 8.5° off on one held at
  -4° tilt. Two of the three iOS "head down" selfies now drop on Android too;
  the third (true tilt about +7°) still passes. Gate kept as is.

**Fourth round (16 Nov, floor back at 120, busy 0.13).** 29 of 35 agree; the
body scores now sit beside iOS's: 0.97 / 0.96 / 1.00 / 0.78 / 0.94 / 1.00
against 1.00 / 0.98 / 0.98 / 0.79 / 0.96 / 1.00, same tiers, and "busy top"
fires on 4 of 17 bodies. Picks differ by one: iOS pre-selects the T-shirt
front with the suitcase as its second front, Android the other T-shirt front.

**Tester's set (16 Nov, 19 files from `eoin_for_phone`, the same files on
both phones).** 47 of 53 agree, but the set is thin: the files are re-exported
JPEG/PNG with no camera metadata, so every selfie falls to "no camera data" on
iOS (22) and the selfie gates cannot be compared on it; and the iPhone's store
was not cleared, so most iOS verdicts came from earlier scans. Two findings
stand anyway:

- Real suits: ML Kit `suit 0.79–0.86` / `jacket 0.89–0.91`, Vision `suit
  0.22–0.24`. iOS gives only the half cap (fronts 0.49 / 0.51 Tier B); Android's
  strong cap made them 0.39, under the floor. The strong-cap line now sits at
  0.9, above ML Kit's range, so the half cap does the work as on iOS.
- Sunglasses: ML Kit's "Sunglasses" label fires on clear glasses (`eyeglasses
  0.93–0.95` with `sunglasses 0.57–0.92` on seven of the tester's selfies, and
  0.41 on one bare face), and it also fires beside `eyeglasses 0.93` on real
  sunglasses, so neither the line nor "sunglasses must beat glasses" separates
  them. On the user's set the same label named three photos iOS gated for
  other reasons (hand over face, cap, no match). Vision does not make this
  mistake. Open item: an eye-region darkness reading (eye boxes against the
  skin beside them, from the landmarks) to confirm the label; logged first,
  line set from both sets.

**Tester's set, second pass (16 Nov, `noCameraDataGateEnabled` off on both,
iPhone store cleared).** 38 of 53 agree. Where both judged a photo the numbers
sit together: selfies 0.75 / 0.78, 0.78 / 0.78, 0.96 / 0.94, 0.79 / 0.78,
0.80 / 0.81; the two suit fronts 0.51 / 0.52 and 0.49 / 0.51, Tier B on both.
The disagreements are all readers, not lines:

- Sunglasses on clear glasses (7 selfies, above). The biggest item.
- Document gate on banner selfies (2): text behind and around a large face
  (`text 10 lines, 19.4% of frame`, face ratio 0.78; `3 lines, 3.7%`) reads as
  a document under the Android text rules. The voter ID had a small face on
  the card. Proposed: the text rules only when the face is small in the frame
  (face box under a share of the width to be set from the ID photo).
- A mirror selfie with the hips in frame: MediaPipe's pose sees two lower
  joints (hips at the bottom edge), Vision none, so iOS scores it as a selfie
  (0.85 B) and Android drops it as not head to toe. Android counts knees and
  ankles only, as iOS does; the difference is the pose reader's reach.
  Noted, no change.
- A long coat: iOS reads `legs fit 0.00` and drops it as loose; Android reads
  thighs 0.42–0.49 of the torso (fit 0.80) and scores it 0.72 Tier A. The
  masks differ (Vision person mask vs MediaPipe multiclass category mask),
  so the thigh rows under the coat are read on different outlines. Open item;
  needs the mask looked at on this photo.
- Relative blur: iOS withdrew three selfies at 0.22–0.43× of its usual (17
  close selfies); Android had only 5 close selfies left after the sunglasses
  and document gates and withdrew none (lowest 0.61×). Revisit once those two
  gates are right; the detail scale may also differ (iOS 1.7–12.2, Android
  2.9–13.3 on the same faces).

**Tester's set, third pass (16 Nov).** Two readers added on Android:

- `eyeDark` (MuseScore): the skin just above and below each eye over the cheeks
  under them, from the landmarks. Clear glasses and bare eyes read 0.48–0.82 on
  14 selfies; a first version on the iris read bare eyes as dark as glasses
  (0.34–0.65) and was replaced. The sunglasses label now counts only under
  `sunglassesEyeDarkLine` 0.35, provisional until real sunglasses are read on
  the user's set. All seven glasses selfies then came through, scored
  0.78–0.95, Tier A, beside iOS's 0.62–0.88.
- `documentMaxFaceShare` 0.3: the text rules only with a face under 0.3 of the
  frame width. Both banner selfies (0.44–0.49) pass; the ID's share is logged
  on the next user-library scan.

Result: 10 matches on Android against iOS's 20 on the same files (iOS's extra
ten are duplicates of the same photos under other names in its library). The
pre-selected selfie differs by a hair: iOS ranks a bare-faced selfie first
(0.96) and the frowning banner selfie third (0.88, `camera below +2.1°`);
Android reads that photo's pitch at 0.0° and puts it first (0.95). Both read
its eyes at the resting point (eye pitch -7.6 / -7.9), so neither calls it
looking down.

**Head pitch offset (16 Nov).** On the 18 shared selfies Android's MediaPipe
pitch reads 2.1° under iOS's (mean -2.08, median -2.09, range -3.0..-0.1),
while yaw (±0.3), roll (±0.3), eye yaw/pitch and face ratio agree. The head
crop is cut with iOS's factors from ML Kit's larger box, so the face sits
lower in it. `headPitchOffsetDegrees` 2.1 is added to the reading, so the
camera-below gate and penalty, the head-pitched gate, the reference check and
the head-down estimate land where iOS's do; the frowning banner selfie then
takes iOS's "camera below +2°" and the bare-faced one goes first.

**Distance lines shifted together (16 Nov).** With the pitch offset in, the
tester's top three selfies came in iOS's order, but three glasses selfies iOS
shows (d 0.53–0.54 there, 0.58 on Android) were withdrawn by the gap cut:
both phones cut at the 0.45 floor, and iOS's own-photo keep rescued seven
matches where Android's rescued four, because only the cut and ceiling had
been moved by the int8 shift. Now every line in distance units sits 0.03 above
iOS's: floor 0.48, weak cut 0.53 / reach 0.58, own-photo core 0.43 / keep
0.51, early no-match 0.78. One scarf false positive stays: ML Kit names a
lanyard on a jumper `scarf 0.36` over the 0.3 line where Vision names
nothing; one photo, left for more data. After the shift: 45 of 53 agree, every
glasses selfie iOS shows is shown, the three gates left on his set are the same
on both phones (a shearling collar as `scarf or high collar`, one blurred).

**Why the fronts pre-selected differ on the tester's set (16 Nov, replayed
with the page's own `preselectionPicks` on both reports).** iOS: the long coat
is dropped as loose, so its first front is the suit (0.51 B) and, with every
other front under the 0.5 repeat line, the second is the same suit photo under
its other name (`PRESELECT_REPEAT_BELOW`). Android: the coat scores 0.72 Tier
A and goes first; the session rule (`PRESELECT_SESSION_GAP_MS`, same hour and
same outfit) then skips the dark suit (torso ΔE 14.6 from the coat, under 20)
for the grey T-shirt (ΔE 27). Every file carries the push time as its date on
Android (no EXIF), so the whole set is "one session" there. The one scan
difference behind it is the loose-garment read on the coat (legs fit 0.00 on
iOS, 0.80 on Android): next item, with the Android mask dumped for that photo.

**The coat, traced (16 Nov, mask dump).** Android's mask covers the whole
coat and every joint is placed; each thigh run is capped at the midline to the
other leg, so it reads half the coat (thigh 0.42–0.49 of the torso, fit 0.80).
iOS's trail shows `lowerJoints 3` on the same photo: Vision lost one knee under
the coat, the midline cap fell away for that leg, its thigh run spanned the
whole garment, and `legs fit 0.00` made it loose. Android now reads
`legsMerged`, the share of thigh rows on which the mask runs unbroken between
the two thigh centres, and counts the legs loose at or over
`looseLegsMergedShare` 0.8 (the coat: every row; a suit's trousers touch on
about half). Logged in `scoreInputs`. Checked on the next scan: the coat drops
as loose, the suits read 0.17 and 0.25, and the picks match iOS's (the selfie,
the suit, then the T-shirt where iOS repeats the suit's duplicate copy).
46 of 53 agree; the two left are the hips-in-frame mirror selfie and one
blurred selfie iOS withdraws against a larger usual.

**SwanEval only, both phones (16 Nov, `evalFolder`).** On the same 51 files
the picks agree on 4 of 5 slots (selfie, front 1, both sides) and every
shared score is within 0.04. The fifth is the small photo below: it edges the
iOS pick for front 2 by 0.01. A floor in original pixels was tried and reverted
(table above).

Left as they are: one small photo (face 97 px on Vision) that Android still
matches at 126 px, the scarf selfies (no face on ML Kit,
hidden on both), and one selfie on the Tier A line (0.703 vs 0.70).

Fixed on the way: the Android trail cut outcomes at " head " (meant for the
head-label note), so "head turned" and "not head to toe" lost their words.

Every gate and curve was set on iPhone readings (Vision pitch, MediaPipe on
A-series chips, iPhone camera ISO ranges). Android readings differ, so:

1. Scan the **same labelled libraries** (the user's, the two testers') on an
   Android phone; copy them over with `adb push` so the photos are identical.
2. Compare per photo: match distance, type, gates hit, score, tier, picks.
   Use the scan reports from both platforms, side by side in the visualizer.
3. Where a reading differs by source (Vision vs MediaPipe pitch, ISO ranges),
   add an Android value beside the iOS one in `GallerySettings.kt`, never
   change the iOS constant.
4. Target: same picks on the same library, Tier A sets within one or two photos.
5. Only then share with the client.

**Light and sharpness (16 Nov).** The formulas are the same on both phones
and brightness, backlight, blown and colour cast agree on every shared selfie.
Two readings did not: uneven light read 1.3× on Android where iOS read 1.6×
(two selfies docked on iOS, not on Android), and the face's Laplacian read
3–4× iOS's, so "sharpness 0.85" never fired on Android. Causes, both in how
the 64 px face copy is made, not in the readers: the light region hung off ML
Kit's 1.5× box (hair and background on both sides even the light out), and a
single bilinear downscale keeps only a few source pixels per cell where Core
Graphics averages. Fixes: `faceStatsBoxScale` 0.68 and a halving downscale in
`regionStats`. Checked on the next scan.

**User's library re-scanned with the tester-set lines (16 Nov, evening).**
Two thin lines failed, as the table below warned:

- Merged-legs loose: 14 of the user's body photos iOS keeps dropped as loose
  (every side view, and fronts in trousers with the legs together all read
  100% merged). It does not separate a coat from legs together. Off
  (`looseLegsMergedEnabled`); the coat stays an open difference.
- Sunglasses darkness: the user's real sunglasses read 0.23–0.72, inside the
  clear-glasses range (0.48–0.82); dark lenses in bright light and mirrored
  ones are not dark. Line off; the label alone again, which hides clear
  glasses on Android (the tester's seven) until a cue separates them.
  `eyeContrast` (sclera-to-iris spread inside the eye box) is logged next.
  Decision the same evening: on Android spectacles are never hidden, and the
  iOS reading is not copied. The gate is the label at 0.6 (bare faces read
  0.38–0.41) and only when it beats the eyeglasses label
  (`sunglassesMustBeatGlasses` on): clear glasses pass; sunglasses that also
  read as eyeglasses pass too, accepted.
- Agreement on the user's set fell from 29 to 23 of 35 with those two on;
  the pitch offset moved "head pitched" from 4 to 9 (iOS 13), the right way.

**Saree and long coat, label check (16 Nov, eight stock photos in a pushed
folder, labels logged at scan start since no face matches).** Sarees: ML Kit
names them `gown 0.51–0.82` and `sari 0.23–0.93` (one saree only 0.23 on
`sari`, but 0.82 on `gown`), and `dress 0.85–0.93`. The loose rule takes gown
or sari at 0.3, so all four drop on Android as on iOS; `dress` is not in the
list on either platform (a fitted dress measures). Long coats: `jacket
0.81–0.93` and `suit 0.60–0.90` on all four, no coat label of any kind, as
on the tester's coat. So a coat on Android gets the blazer caps and is not
dropped; iOS drops it only when Vision loses a knee under it. The cue left is
garment length from the mask, which needs tester photos at phone resolution.

**Blazer, same file on both phones (18 Nov).** A blazer over a shirt reads
`suit 0.69–0.71` on Vision, and the T-shirt fronts in the same library read no
suit or jacket at all, so iOS caps it to Tier B (0.58–0.63). ML Kit gives the
blazer `jacket 0.63–0.65, suit 0.33–0.37`, under the T-shirts' `jacket
0.60–0.71, suit 0.29–0.46`, so no whole-photo line separates them: the blazer
scores 0.98 Tier A on Android and takes a front tile by the session rule.
The full dark suits from the stock set still read 0.62–0.86, so the suit cap
holds for those. Labels are now written to the trail (`garments`), and debug
builds log two more reads per body (`debugGarmentReads`: ML Kit on the torso
crop, and MediaPipe's EfficientNet-Lite0 classifier on the crop and the whole
photo). That scan settled it: on the torso crop ML Kit reads the four blazers
`suit 0.90–0.93` and every T-shirt front and side `0.39` or under, while the
classifier agrees with a thinner margin (`suit 0.27–0.63` against `jersey
0.70–0.99`). So suit and tuxedo are now read on the torso crop as well
(`garmentReadOnTorso`, named `suit@torso`, strong cap at 0.6), the blazers
fall to Tier B as on iOS, and the classifier stays debug-only. The jacket label
is no use even on the crop: two sides read `jacket 0.78–0.82` in a T-shirt.

### Android values beside the iOS ones, and what testers should check

Every Android-only line lives in `GallerySettings.kt` under its own name with
the evidence in the comment. None is set per photo; each is a general offset
between the two readers, measured on shared photos. The evidence is uneven:

| Value | Android / iOS | Evidence | Confidence |
|---|---|---|---|
| Distance lines (`matchThreshold`, gap ceiling / floor, weak cut / reach, own-photo core / keep, early no-match) | +0.03 on each | int8 SFace reads 0.02–0.06 farther on all 18 shared matches | solid |
| `headPitchOffsetDegrees` | +2.1° | 18 shared selfies, range 0.1–3.0°; yaw and roll agree | solid |
| `busyTorso` | 0.13 / 0.11 | 1.2× on all eight shared bodies; fires on the same ones | solid |
| Suit / jacket lines (`garmentCaps`) | 0.9, 0.5 / 0.4, 0.22; jacket 0.75 / 0.6 | ML Kit 0.29–0.46 on T-shirts, 0.62–0.86 on suits; Vision 0.22–0.24 on the same suits | solid |
| `mlKitLandmarkFallback` | reader | 4 turned faces rescued, same tiers as iOS | solid |
| `sunglassesConfidence` + must-beat-glasses | 0.6, on / 0.4, off | bare faces 0.38–0.41, clear glasses 0.57–0.92 under eyeglasses 0.93–0.95, real sunglasses 0.86–0.94 | decision: spectacles never hidden; some sunglasses pass |
| `looseLegsMergedShare` | off | one coat at 1.0, but every side view and legs-together front too | off |
| Saree by label | same list and line as iOS | four stock sarees: gown 0.51–0.82, sari 0.23–0.93; all drop | solid for the label; the pose and mask gates untested on a matching face |
| Long coat | none | four stock coats and the tester's: jacket 0.81–0.93, suit 0.60–0.90, no coat label | open: needs tester photos for a length cue |
| `garmentReadOnTorso` (suit on the torso crop, line 0.6) | on, beside iOS's whole-photo suit line 0.4 | four blazers 0.90–0.93 on the crop; T-shirt fronts and sides 0.39 and under; whole photo had the blazers under the T-shirts | solid on one library; check a blazer or coat on a tester's phone drops to Tier B |
| `documentMaxFaceShare` | 0.3 | two banner selfies at 0.44–0.49; the ID card not yet re-read | thin |
| Head-down with `assumedPhoneTiltDegrees` 0 | iOS reads the true tilt | within 3° of iOS on 10 of 11 selfies, 8.5° off on one | thin |
| `minFaceOriginalPixels` | 120, same as iOS | 156 was tried to drop one small photo (Vision 103, ML Kit 134) and also dropped two iOS keeps (Vision 126, ML Kit 137 / 150): no line separates them | accepted difference |
| Not tuned | blur usual, scarf label, pose reach, face floor at the render (120 kept) | noted only | — |

For a test round on a new library, scan the same photos on both phones with
the same reference and run `scripts/compare-scan-reports.py`. Watch for:

- sunglasses selfies shown on Android (the darkness line too low) or clear
  glasses hidden (too high); the reading is `scoreInputs.eyeDark`;
- a full skirt, wide trousers or legs pressed together dropped as loose on
  Android only (`scoreInputs.legsMerged` near 0.8);
- an ID card or page shown on Android (face share over 0.3) or a selfie in
  front of text hidden;
- a selfie taken with the phone tilted that iOS hides as head down and
  Android shows (`scoreInputs.headVsFloor`, `tiltAssumed`);
- fronts and sides whose tier differs: look at `busy`, the garment reasons
  and `legsFit` first.

## 8. Keeping the two in step afterwards

- The page stays one build for both; `platform` is the only switch.
- Rule changes land in Swift first (the Xcode dev copy), then are ported to
  Kotlin by file name. A one-line entry in `CHANGELOG.md` says "ported to Android"
  or "iOS only".
- Same `native-samples/` idea: `native-samples-android/` in the package, type-checked
  by `./gradlew :app:compileSandboxKotlin` in `DEPLOY.md` step 3.

## Decisions taken

- **Minimum Android version:** 10 (API 29), for `loadThumbnail` and Scoped Storage.
- **Native Kotlin** sample, `android-gallery-sample`, beside `ios-gallery-sample`.
  A client on React Native or Flutter reuses the Kotlin scan code; only the bridge
  moves.
- **Parity first, then size** (13 Nov). Same models and numbers as iOS so the
  picks match on the same library; trim size only where a reading proves
  interchangeable. The parity mix is about 125 MB per ABI (OpenCV ~20 MB,
  MediaPipe runtime ~25 MB, float SFace 39 MB, the same three MediaPipe models
  24 MB, a pose model ~6 MB, the app ~13 MB). Ways down later, in order of least
  risk: Play Feature Delivery for the models; int8 SFace (10 MB, embeddings
  shift a little, so the 0.60 cutoff would be re-checked); ML Kit unbundled face
  detection in place of MediaPipe (re-tunes every face reading). Android has no
  Vision or Core ML in the OS, which is why every model ships with the app.

## Rough effort

| Step | Size |
|---|---|
| 1 Page changes | 1 day |
| 2 Skeleton | 3–4 days |
| 3–5 Scan port: A done; B ~2 weeks, C ~1 week, D ~1 week | 3–4 weeks |
| 6 Dev tools | 2–3 days, folded into group D |
| 7 Re-tuning | 1–2 weeks, depends on libraries available |
| R&D readings app | dropped: the scan's debug logs do this job |
| 8 Docs and `native-samples-android/` | 1–2 days |
