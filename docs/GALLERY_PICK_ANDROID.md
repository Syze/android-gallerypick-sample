# Gallery Pick on Android

How an Android app hosts the Gallery Pick page. The page is the same bundle iOS
loads; it tells the platforms apart by the bridge it finds (or `config.platform`)
and adjusts its wording, its permission guides and the `requestSource` it sends
with the build. Everything the page says and does is in GALLERY_PICK.md; this
file covers only what is Android's own. A change to the page itself (a new
message, a config key, a text key, a flow step) is documented there and applies
to both platforms.

**Status.** The port is whole apart from the eval mode of the dev tools and the
step 7 re-tuning: sign-in, the page, the camera flow, the upload and the build;
the photo permission, the library window, thumbnails, originals and captures
are served; the per-photo scan store makes a second scan a fraction of the
first; both flavors post the scan report to Swan's ingest with
`platform: "android"` (iOS sends `platform: "ios"`) and the User-Agent
`photoApp/android/<build>/<env>` (iOS: `photoApp/ios/<build>/<env>`; `sandbox` or
`production`), which the ingest lists as `client` and `env` and filters by, and the signed-in email as `account`, which the
ingest lists scans by (`GET /v1/scans?account=<email>`, or `?q=<part of it>`). Faces are matched against
the selfie with the same SFace model and 0.60 cut-off as iOS (ML Kit finds the
faces, MediaPipe reads each one; ML Kit's model comes from Play services). Bodies are read with the pose landmarker and the same person-mask readings as
iOS, so a front, three-quarter or side arrives with its body score, tier and
`outfit`, and a photo with other people or not head to toe is left out. A
matched selfie goes through the v2 selfie gates and score and arrives with
`score`, `tier`, `order`, `scoreInputs`, `scoreParts`, `scoreReasons` and
`scoreSignals`, as from iOS. A match whose face sits among printed text (an ID
card, a page) is dropped as `document`, read by ML Kit text recognition. Labels
come from ML Kit's image labeller under the iOS names (sunglasses, glasses, hat,
scarf, suit, jacket, gown, sari and a few more), so the sunglasses, hat and scarf
gates, the glasses allowance and the garment caps run, and hands come from
MediaPipe's hand landmarker for the hand-over and hand-near-face gates. The
head-down / head-up gate runs with the phone's tilt taken as level
(`assumedPhoneTiltDegrees`): Android photos carry no tilt in their metadata,
where iOS reads Apple's maker note, so that gate is weaker on Android. The
sunglasses gate on Android keeps spectacles: ML Kit cannot tell clear glasses
from sunglasses the way Vision does, so a face is hidden only when its
sunglasses label beats its eyeglasses label; some sunglasses pass. A few
lines sit beside the iOS values where the readers differ (match cut, garment
label lines, head pitch, sunglasses, loose legs); GALLERY_PICK_ANDROID_PLAN.md
step 7 lists them with the evidence, how sure each one is, and what a test
round should watch for.

---

## Requirements

| Piece | Value |
|---|---|
| `minSdk` | 29 (Android 10) |
| Manifest permissions | `INTERNET`, `CAMERA`, `READ_MEDIA_IMAGES` (13+), `READ_EXTERNAL_STORAGE` with `maxSdkVersion="32"` |
| Page URL | https only: `getUserMedia` needs a secure context |
| Camera permission | Ask the app-level permission **before** the page loads. The page's camera request cannot prompt on its own; it is granted or denied from what the app already holds |

---

## WebView setup

```kotlin
if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)   // chrome://inspect
webView.settings.apply {
    javaScriptEnabled = true
    domStorageEnabled = true
    mediaPlaybackRequiresUserGesture = false
}
webView.addJavascriptInterface(WebBridge(::onWebMessage), "swanBridge")
webView.webChromeClient = object : WebChromeClient() {
    // The page's getUserMedia: granted when the app itself holds CAMERA.
    override fun onPermissionRequest(request: PermissionRequest) {
        runOnUiThread {
            if (ContextCompat.checkSelfPermission(this@GalleryActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                request.grant(request.resources) else request.deny()
        }
    }
}
webView.loadUrl(pageUrl)
```

Keep the activity alive across rotation (`android:configChanges="orientation|screenSize|keyboardHidden"`),
or the page reloads mid-flow.

---

## Bridge

**Page → app.** The page calls `window.swanBridge.postMessage(json)`. The
interface must be registered under the name `swanBridge`; any other name and no
message ever arrives, with no error on either side.

```kotlin
class WebBridge(private val onMessage: (String) -> Unit) {
    @JavascriptInterface fun postMessage(json: String) = onMessage(json)
}
```

Calls land on a WebView worker thread: touch views through `runOnUiThread`.

**App → page.** Post a message into the page on the main thread:

```kotlin
private fun sendToWeb(message: JSONObject) = runOnUiThread {
    webView.evaluateJavascript("window.postMessage($message, window.location.origin);", null)
}
```

---

## Handshake

1. The page sends `WEB_READY` once its bundle has run. Wait for it; a page that
   never sends it (no network, a script error) is the host's to report, so keep
   a watchdog (30 s is generous) and show "try again".
2. Answer with `RENDER_COMPONENT`:

```json
{
  "type": "RENDER_COMPONENT",
  "componentName": "GalleryPick",
  "props": {
    "userDetails": { "email": "…", "shopDomain": "…", "gender": "male", "heightInCm": 181 },
    "token": "<session token>",
    "config": { "appName": "Your App", "platform": "android" },
    "monthsBack": 12
  }
}
```

`config.platform: "android"` is optional (the page finds `window.swanBridge` and
decides the same) but explicit is better. `config.photoScheme` is only needed if
the app serves thumbnails on a scheme other than `swan-photo` (below).

---

## Messages the host answers today

The full table is in GALLERY_PICK.md. On Android without the scan, the minimum:

| Message | Answer |
|---|---|
| `GALLERY_SCAN_START` | Ask `READ_MEDIA_IMAGES` (13+) or `READ_EXTERNAL_STORAGE`, plus `READ_MEDIA_VISUAL_USER_SELECTED` on 14+. Refused: `onGalleryScanComplete { assetIds: [], denied: true }`. Granted: list `MediaStore.Images` in the `monthsBack` window, newest first, the Screenshots folder left out; under 14's selected photos no date window and `limited: true`. The sharpest selfie frame is the reference, blended with the other frames at 40% of its detail as iOS blends by capture quality (`blendReferenceFrames`; `error: "selfie-too-blurry"` when its face detail is under `selfieMinDetail`, `"no-face-in-selfie"`, `"several-faces-in-selfie"` or `"selfie-not-frontal"` when it cannot be). Each photo is read at 1536 px; faces found by the landmarker, bystanders under a quarter of the widest face ignored, a group or a face under 120 px left out, the rest embedded with SFace; a distance under 0.60 is streamed as `onGalleryMatchFound { assetId, type, isFullBody, distance, takenAt, done, total }`, with `onGalleryScanProgress` every 250 ms and a `summary` tally at the end |
| `GALLERY_THUMBNAILS` | `onGalleryThumbnails { thumbs: [{ id, dataURI }] }`, a 256 px JPEG decoded with `ImageDecoder` (EXIF orientation applied; the provider's `loadThumbnail` returned some HEIC photos unrotated) |
| `GALLERY_FULL_IMAGE` | `onGalleryFullImage { id, dataURI, original: true, hasVideo: false }`: the original file's bytes and MIME type, never re-encoded, up to 10 MB, else `{ id, error: "tooLarge" \| "unreadable" }`. There is no Live Photo pairing on Android |
| `GALLERY_CAPTURE_PHOTO` | Hold the JPEG bytes as sent (12 at most), then judge the shot as the scan would, as iOS does: face, pose, type from the body, the scan's gates (`captureGatesEnabled`), and the face must be the shopper in the selfie (`captureIdentityCheck`). Answer `onGalleryPhotoAnalysis { captured: true, type, isFullBody }`, or `error: "unusable"` with `scoreFilter` naming the gate |
| `GALLERY_ANALYSE_PHOTO` | `onGalleryPhotoAnalysis` from what the scan recorded for the id, else `error: "unavailable"` |
| `GALLERY_PICK_MORE` | Android 14+: ask the photo permissions again, which reopens the selected-photos sheet |
| `OPEN_SETTINGS` | `startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))` |
| `GALLERY_TOKEN_EXPIRED` | Mint a new session and send `RENDER_COMPONENT` again, or return to sign-in |
| `WEB_LOG` | `Log.d(tag, payload.text)`; the page's console is also in `chrome://inspect` |
| `COMPONENT_CALLBACK` | `componentName`, `callbackName`, `payload`: the flow's callbacks (`onCaptureComplete`, `onUploadStart`, `onUploadEnd`, `onMuseStart`, `onMuseEnd`, `onError`, …) |
| `GALLERY_RESET`, `GALLERY_CLEAR_CAPTURES` | Drop the run's analyses and held captures; `GALLERY_RESET` also stops a scan still running |

---

## Permissions and the guides

- **Camera blocked.** When the app's `CAMERA` permission is denied the page's
  camera request is denied too, and the page shows the camera guide with
  Android's words ("Open Settings › Apps › {appName} › Permissions", "Tap Camera
  and allow it, then come back"), an Android-style drawing of the permission
  screen, and both *Open Settings* and *Try again*. Android does not restart the
  app after a permission change, so *Try again* re-asks the camera in place.
- **Photo access blocked.** The same shape, for the library: the host answers
  `onGalleryScanComplete` with `denied: true` and the page shows the library
  guide ("Tap Photos and videos", "Choose Allow all").
- `{appName}` is `config.appName`. The words can be overridden per page in
  `config.content.galleryPick.libraryBlockedPage` / `cameraBlockedPage`; the
  Android defaults are listed in GALLERY_PICK_TEXT_CONFIG.md.

---

## Thumbnails and `config.photoScheme`

Library tiles are `<img src="swan-photo://thumb?id=…">`. On Android serve them
from `WebViewClient.shouldInterceptRequest`: read the id from the query, return
a `WebResourceResponse("image/jpeg", null, stream)` with a small JPEG. If the
app intercepts another scheme, name it in `config.photoScheme` and the page
builds its URLs with that scheme instead. The bridge thumbnail fallback (base64
over `GALLERY_THUMBNAILS`) works unchanged on both platforms. Camera shots are
data URIs and never use the scheme.

---

## Cost

Time a scan in the `sandboxRelease` build. Debug builds also run readings that
only feed the report (face widths, extra garment reads, a mask picture per scored
body), and Android runs debuggable code slower, so a debug scan takes about 30%
longer. `sandboxRelease` is signed with the debug key, so it installs over
`sandboxDebug` and keeps its scan store. Running from Android Studio installs
`sandboxDebug` again; install the release build from the terminal to time it.

On a Galaxy S21 FE (692 photos, about 40 matches) `sandboxRelease` takes about
130 s for a first scan and 65 s for a repeat scan; on a matched photo the
readers that need only the render run at the same time (`parallelReaders`). On a repeat scan the store
settles every photo it has already judged without loading it; only the matches
are scored again.

Memory: the readers hold about 360 MB once loaded (the two MediaPipe
segmentation users share one copy of the selfie segmenter), the rest of a
scan's peak is photos in flight; a scan peaks at about 830 MB and the report's
`memoryPeakMB` records it. When the scan ends, and after each camera shot, the
readers only the scan uses are closed (`releaseModelsAfterScan`), so the idle
app holds about 120–160 MB; the face finders and the face match stay loaded for
the camera's same-person check. Debug builds have a memory probe
(`MemProbeActivity`) that logs what each reader adds.

Release builds are shrunk with R8 (`isMinifyEnabled`, `isShrinkResources`).
`app/proguard-rules.pro` keeps what the libraries look up by name: MediaPipe,
its protobufs and its Flogger logging, OpenCV, and the page's
`WebBridge.postMessage`. Without the Flogger rule every MediaPipe task fails to
start and the selfie is refused with `no-face-in-selfie`.

The arm64 release APK is about 110 MB:
- native code, 48 MB: OpenCV 19 MB, MediaPipe 16 MB, ML Kit's label pipeline 11 MB
- the models, 52 MB: selfie segmenter 16 MB, SFace int8 10 MB, pose 9 MB, hands 8 MB, objects 5 MB, face landmarker 4 MB
- ML Kit's label model, 3 MB
- the app's code, 6 MB

OpenCV is a slim build of the official 4.14.0 package: only the modules the face
match uses (core, imgproc, dnn, objdetect, the Java bindings), with the same
compiler, flags and HALs, so the SFace vectors are identical to the official
library's (checked bit for bit with the debug face probe). It lives in
`app/src/main/jniLibs` beside the official package's Java classes
(`app/libs/opencv-4.14.0-classes.jar`); `app/libs/opencv-build/README.md` says
how to rebuild it for an upgrade.

ML Kit's text model comes from Google Play services and downloads on first
use; `MlKitModels.kt` asks for it when the gallery opens and the scan waits up
to `mlKitModelWaitMs` for it (`mlKitReady` in the summary). Labels stay
bundled: the Play services label model reads differently and would move the
glasses, hat and scarf rules. The garment classifier model (5 MB) ships in
debug builds only (`app/src/debug/assets`). A shipping app can move the model
files to Play Feature Delivery or its own CDN so the install stays small and
the models download once; that is the only route under about 100 MB.

## Debugging

Every matched front, side or three-quarter carries ML Kit's garment labels in the
report's trail (`garments`). Debug builds add two more reads under
`GallerySettings.debugGarmentReads`, logged only: ML Kit on the torso crop and
MediaPipe's image classifier (EfficientNet-Lite0, ImageNet names such as `suit`,
`trench coat`, `jersey`) on the crop and the whole photo, as `garmentReads`.
They found the blazer line: suit and tuxedo are now read on the torso crop as
well (`garmentReadOnTorso`, logged as `suit@torso`), where a blazer over a
shirt reads 0.90 and over and a T-shirt under 0.4, so the suit cap holds a
blazer to Tier B as Vision's whole-photo suit label does on iOS.

Debug builds can scan one folder only (`GallerySettings.evalFolder`, a
`relative_path` prefix such as `DCIM/SwanEval`) so that both phones judge the
same pushed files; release builds always scan the whole window. Eval mode
(`GallerySettings.evalMode`, debug builds only) scores each scan against a
hand-labelled folder, `DCIM/Swan-Eval`, the way the Xcode copy scores against
its Swan-Eval album: the matcher's AUC, precision at the top of the grid,
recall, coverage, each gate's right and wrong calls, the grid as shown and the
labelled photos it missed, to logcat and `files/scan-reports/eval-*.txt`. The
first run's reference is pinned so runs compare; `evalReplaceReference`
renews it.

- `adb logcat -s swan` shows the host's own lines and every `WEB_LOG`.
- `adb logcat -s chromium` shows the page's console. `chrome://inspect` on a
  desktop Chrome attaches DevTools to the WebView (debug builds only).
- A first useful check after `WEB_READY`: `[swan/gallery] platform android (config)`
  in the log, and `"requestSource":"android"` in the build request.
- Debug builds log one line per scanned photo: `#12/240 1234: match 0.412 selfie
  (eyes 96px) (310ms)`, `no face`, `group of 2`, `face 80px, under 120`, `no
  match 0.731`; then `scan done: {photos=…, noFace=…, matches=…, ms=…}`. The
  same photos scanned on iOS give the numbers to compare against.

---

## The sample app

`android-gallery-sample` is the host above in client form: a sign-in screen
(email and name make a session, kept encrypted on the phone and offered back as
"Welcome back"), then the gallery screen, which covers the page with a spinner
until `WEB_READY`, renders, and returns to sign-in with the reason when the page
does not open or the token dies. Two flavors, `sandbox` (default) and
`production`, choose the page URL, the session service and the key in
`SwanEnvironment.kt`. Build from the terminal with a JDK 21 (`JAVA_HOME`), not
the JDK 25 bundled with recent Android Studio builds, which this Kotlin version
cannot parse.
