# Swan Gallery Pick — Android sample

A complete Android app that hosts the Swan Gallery Pick flow: the web page in a
`WebView`, and the native controller that scans the photo library, matches the
shopper's face on the phone, ranks the photos and uploads the selected ones. It
is the Android counterpart of `ios-gallery-sample`: same page, same messages,
same rules, file for file.

## Requirements

- Android Studio (Ladybug or later) with the Android SDK, compile SDK 36.
- **JDK 21** for Gradle. Android Studio's bundled JDK 25 breaks Kotlin 2.0.21;
  point Gradle at a JDK 21 (Settings → Build Tools → Gradle → Gradle JDK, or
  `JAVA_HOME` when building from the terminal).
- A physical Android phone, Android 10 (API 29) or later, arm64. The emulator
  has no camera and no photo library worth scanning.
- Swan API keys for the environment the page is built against.

## Run it

1. Open this folder in Android Studio and let Gradle sync. The first sync
   downloads MediaPipe, ML Kit and OpenCV; nothing else is needed.
2. Pick the build variant **`sandboxDebug`** (Build → Select Build Variant).
   `sandbox` is the default flavor and loads Swan's development environment and
   its dev page CDN, so the sample runs as it is. `production` loads the
   production page and needs your own values. To time a scan, use
   **`sandboxRelease`**: it is shrunk, has no debug extras and installs over
   `sandboxDebug` (both are signed with the debug key).
3. `app/src/main/java/co/getswan/gallerysample/SwanEnvironment.kt` holds what
   differs between the two flavors: the page URL, the session URL, the API key,
   whether each scan's report is posted (both flavors, by default), and the shopper's
   `shopDomain`, gender, height and `monthsBack`. In a shipped app the session
   token comes from your own server and the key never ships; the sample mints
   it on the device to keep the setup short.
4. Run on the phone. Grant camera and photo access when asked; the page needs
   https for the camera, which both CDNs provide.

From the terminal:

```sh
JAVA_HOME=/path/to/jdk-21 ./gradlew :app:assembleSandboxDebug
```

## What is in here

| Path | What it is |
|---|---|
| `app/src/main/java/co/getswan/gallerysample/GalleryActivity.kt`, `WebBridge.kt` | Hosts the page and answers its messages; `handleCallback` receives the flow's callbacks. |
| `SwanEnvironment.kt` | The values that differ per flavor: page URL, session URL, key, scan report. |
| `SwanSession.kt`, `SignInActivity.kt` | Sample sign-in: mints a session token from the key. Replace with your own auth. |
| `GallerySettings.kt` | The scan's tuning values, with Android values beside the iOS ones where the readings differ. |
| `GalleryScan.kt` | The library scan: each photo, the end-of-scan checks, the results sent to the page. |
| `FaceVision.kt`, `FaceRecognition.kt`, `BodyPose.kt`, `Hands.kt`, `Labels.kt`, `DocumentRead.kt` | Face boxes and landmarks (ML Kit, MediaPipe), the SFace matcher (OpenCV), pose, hands, labels and text. |
| `MuseScore.kt`, `BodyScore.kt`, `BodyObjects.kt`, `FaceCover.kt` | Scores a matched photo for the Muse; the full-length body score; objects and face cover. |
| `GalleryPhotos.kt`, `Original.kt` | Serves photos to the page: thumbnails, originals, EXIF camera data. |
| `ScanStore.kt` | Keeps what the scan learned about each photo between scans, on the phone only. |
| `ScanReport.kt` | Posts a JSON report of each scan to Swan's scan ingest; `SwanEnvironment` turns it off. |
| `Helpers.kt` | Small shared helpers. |
| `app/src/main/assets/FaceModels/` | The models: SFace (ONNX), the MediaPipe face, pose and hand landmarkers, the selfie segmenter and the object detector. |
| `docs/` | The integration guide (`GALLERY_PICK.md`), the Android notes and plan, the score sheet, the text keys and the changelog. |

## Where it stands

The port follows the iOS controller rule for rule; a change lands in Swift first
and is then ported to the Kotlin file of the same name. On the shared test sets
the picks match iOS in nearly every case. Open items, in `docs/GALLERY_PICK_ANDROID_PLAN.md`:
the size and cost pass (the arm64 release APK is about 128 MB with every model), and
the remaining re-tuning of readings that come from different detectors than
Apple's (label confidences, head-level tilt).

## Developing

- The scan prints its per-photo trail to Logcat under the `swan` tag;
  `adb logcat -s swan` is the equivalent of the Xcode console.
- A debug build with `evalFolder` set in `GallerySettings.kt` (e.g.
  `"DCIM/SwanEval"`) scans only that folder, which is how a fixed photo set is
  compared against iOS: push the photos with `adb push`, scan both phones, and
  compare the two scan reports.
- `local.properties` (the SDK path) is machine-specific and not committed;
  Android Studio writes it on first open.
