package co.getswan.gallerysample

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/** Hosts the Swan Gallery Pick page: loads it, waits for WEB_READY, renders with the signed-in
 *  session and answers its messages. Opened by `SignInActivity`; comes back to it with an error
 *  when the page never gets ready or the token dies.
 *  Step 3 of the Android plan: the library is listed and served; real matching is not
 *  ported yet (see `GalleryScan`). */
class GalleryActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var loading: View
    private var session: SwanSession? = null
    private var webReady = false
    private val main = Handler(Looper.getMainLooper())
    private val scan by lazy { GalleryScan(this, ::sendToWeb).also { it.report = report } }
    /** The sandbox scan report (see `ScanReport`); null in production. Its stored id goes to the page for PostHog. */
    private val report: ScanReport? by lazy {
        SwanEnvironment.scanReportUrl?.let { ScanReport(it, SwanEnvironment.scanReportKey, SwanEnvironment.pageUrl, if (BuildConfig.DEBUG) java.io.File(filesDir, "scan-reports") else null).also { r ->
            r.onStored = { id, photos -> sendToWeb(JSONObject().put("type", "onGalleryScanReportStored").put("payload", JSONObject().put("id", id).put("photos", photos))) }
        } }
    }
    /** Thumbnails and originals are read off the WebView's threads. */
    private val photos = Executors.newFixedThreadPool(2)
    private var pendingScanMonths: Int? = null
    /** The selfie frames from `GALLERY_SCAN_START`, kept only until the scan starts; never stored. */
    private var pendingFrames: List<String> = emptyList()

    // A script error in the page loads fine but leaves a blank page, so only this catches it.
    private val webReadyWatchdog = Runnable {
        Log.w(TAG, "No WEB_READY within ${WEB_READY_TIMEOUT_MS / 1000}s; the bundle probably threw. Check chrome://inspect.")
        fail(getString(R.string.error_page))
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { loadPage() }
    // Photos: asked when the page starts a scan, the answer decides between the scan and the guide.
    private val askPhotos = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startScanIfAllowed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge to edge at the bottom, as on iOS: the page pads above the navigation bar itself
        // (safe-area inset), so padding it here too would add the space twice. The status bar
        // keeps its own band with dark icons, since the page's header is light.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        setContentView(R.layout.activity_gallery)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
        loading = findViewById(R.id.loading)
        webView = findViewById(R.id.webView)
        session = SwanSession.load(this)
        if (session == null) { fail(getString(R.string.error_session)); return }
        setUpWebView()

        // The page's camera needs the app's permission first; getUserMedia cannot ask on its own.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) loadPage()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        main.removeCallbacks(webReadyWatchdog)
        scan.clearAll()
        photos.shutdownNow()
        super.onDestroy()
    }

    private fun setUpWebView() {
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)   // chrome://inspect shows the page console
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.addJavascriptInterface(WebBridge(::onWebMessage), "swanBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) fail(getString(R.string.error_page))
            }
            // `swan-photo://thumb?id=…[&size=full]`: the grid's <img> tiles, served as JPEG.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.scheme != GallerySettings.photoScheme) return null
                val id = url.getQueryParameter("id") ?: return WebResourceResponse("text/plain", "utf-8", 404, "No id", null, null)
                val full = url.getQueryParameter("size") == "full"
                val data = GalleryPhotos.jpeg(this@GalleryActivity, id,
                    if (full) GallerySettings.fullPixels else GallerySettings.gridPixels,
                    if (full) GallerySettings.fullQuality else GallerySettings.gridQuality)
                    ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null)
                return WebResourceResponse("image/jpeg", null, 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(data))
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            // The page's getUserMedia: granted when the app itself holds the camera permission.
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (ContextCompat.checkSelfPermission(this@GalleryActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) request.grant(request.resources)
                    else request.deny()
                }
            }
        }
    }

    private fun loadPage() {
        webReady = false
        main.removeCallbacks(webReadyWatchdog)
        main.postDelayed(webReadyWatchdog, WEB_READY_TIMEOUT_MS)
        webView.loadUrl(SwanEnvironment.pageUrl)
    }

    /** Renders the gallery and takes the loading cover away. */
    private fun revealGallery() {
        renderGallery()
        loading.visibility = View.GONE
    }

    /** Back to sign-in with the reason; the sign-in screen shows it with Try again. */
    private fun fail(message: String) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            main.removeCallbacks(webReadyWatchdog)
            setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, message))
            finish()
        }
    }

    // MARK: page -> host

    private fun onWebMessage(raw: String) {
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val type = message.optString("type")
        val payload = message.optJSONObject("payload") ?: JSONObject()
        when (type) {
            "WEB_READY" -> runOnUiThread {
                webReady = true
                main.removeCallbacks(webReadyWatchdog)
                revealGallery()
            }
            "GALLERY_SCAN_START" -> {
                // `referenceImages` is the tapped frame plus up to two taken silently, oldest first; older pages send `referenceImage` only.
                val frames = payload.optJSONArray("referenceImages")?.let { a -> List(a.length()) { a.getString(it) } }
                    ?: listOfNotNull(payload.optString("referenceImage").takeIf { it.isNotEmpty() })
                runOnUiThread { requestPhotosThenScan(payload.optInt("monthsBack", SwanEnvironment.monthsBack), frames) }
            }
            "GALLERY_ANALYSE_PHOTO" -> scan.analyse(payload.optString("assetId"))
            "GALLERY_THUMBNAILS" -> sendThumbnail(payload.optString("assetId"))
            "GALLERY_FULL_IMAGE" -> sendFullImage(payload.optString("assetId"))
            "GALLERY_CAPTURE_PHOTO" -> scan.storeCapture(payload.optString("id"), payload.optString("dataURI"), payload.optString("shot"))
            "GALLERY_CLEAR_CAPTURES" -> scan.clearCaptures()
            "GALLERY_RESET" -> scan.clearAll()
            "GALLERY_PICK_MORE" -> runOnUiThread { askPhotos.launch(photoPermissions()) }   // Android 14+: re-opens the "selected photos" sheet
            "SCHEME_TEST_RESULT" -> Log.i(TAG, "photo scheme ${if (payload.optBoolean("ok")) "works" else "blocked, base64 fallback"}")
            "OPEN_SETTINGS" -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            "WEB_LOG" -> Log.d(TAG, payload.optString("text", payload.optString("message", raw)))
            // The page's token was refused: the kept session is dropped and sign-in asks again.
            "GALLERY_TOKEN_EXPIRED" -> { SwanSession.clear(this); fail(getString(R.string.error_expired)) }
            "COMPONENT_CALLBACK" -> {
                report?.noteCallback(message.optString("callbackName"), message.optJSONObject("payload"))
                Log.i(TAG, "callback ${message.optString("componentName")}.${message.optString("callbackName")} ${message.opt("payload") ?: ""}")
            }
            else -> Log.d(TAG, "<- $type (not handled yet)")
        }
    }

    // MARK: photos

    private fun photoPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 34) arrayOf(GalleryPhotos.readPermission, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else arrayOf(GalleryPhotos.readPermission)

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Full access, Android 14's selected photos, or nothing. */
    private fun photoAccess(): String = when {
        granted(GalleryPhotos.readPermission) -> "full"
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "limited"
        else -> "none"
    }

    private fun requestPhotosThenScan(monthsBack: Int, frames: List<String>) {
        pendingScanMonths = monthsBack
        pendingFrames = frames
        if (photoAccess() != "none") startScanIfAllowed() else askPhotos.launch(photoPermissions())
    }

    private fun startScanIfAllowed() {
        val months = pendingScanMonths ?: return
        val frames = pendingFrames
        pendingScanMonths = null; pendingFrames = emptyList()
        when (val access = photoAccess()) {
            "none" -> scan.denied()
            else -> scan.start(months, limited = access == "limited", frames = frames, account = session?.email ?: "")
        }
    }

    /** Fallback for a tile the scheme could not load: one thumbnail as base64. */
    private fun sendThumbnail(id: String) {
        if (id.isEmpty()) return
        photos.execute {
            val data = GalleryPhotos.jpeg(this, id, GallerySettings.gridPixels, GallerySettings.gridQuality)
            val thumb = JSONObject().put("id", id)
            if (data == null) thumb.put("error", "unavailable") else thumb.put("dataURI", GalleryPhotos.dataURI(data, "image/jpeg"))
            sendToWeb(JSONObject().put("type", "onGalleryThumbnails").put("payload", JSONObject().put("thumbs", org.json.JSONArray().put(thumb))))
        }
    }

    /** One photo for upload: a capture as held, a library photo as its original file. */
    private fun sendFullImage(id: String) {
        photos.execute {
            val payload = JSONObject().put("id", id)
            val capture = GalleryPhotos.CaptureStore.data(id)
            if (capture != null) {
                val data = GalleryPhotos.jpeg(this, id, GallerySettings.fullPixels, GallerySettings.fullQuality)
                if (data == null) payload.put("error", "unavailable") else payload.put("dataURI", GalleryPhotos.dataURI(data, "image/jpeg")).put("original", false)
            } else {
                // No Live Photo pairing on Android: a ".mov" request has nothing to send.
                GalleryPhotos.original(this, id.removeSuffix(".mov"))
                    .onSuccess { if (id.endsWith(".mov")) payload.put("error", "unavailable") else payload.put("dataURI", GalleryPhotos.dataURI(it.data, it.mime)).put("original", true).put("hasVideo", false) }
                    .onFailure { payload.put("error", it.message ?: "unreadable") }
            }
            sendToWeb(JSONObject().put("type", "onGalleryFullImage").put("payload", payload))
        }
    }

    /** RENDER_COMPONENT for GalleryPick with the signed-in session. */
    private fun renderGallery() {
        val session = session ?: return
        val userDetails = JSONObject()
            .put("email", session.email)
            .put("shopDomain", SwanEnvironment.shopDomain)
            .put("gender", SwanEnvironment.userGender)
        SwanEnvironment.userHeightCm?.let { userDetails.put("heightInCm", it) }
        val config = JSONObject()
            .put("appName", getString(R.string.app_name))
            .put("platform", "android")
        sendToWeb(JSONObject()
            .put("type", "RENDER_COMPONENT")
            .put("componentName", "GalleryPick")
            .put("props", JSONObject()
                .put("userDetails", userDetails)
                .put("token", session.token)
                .put("config", config)
                .put("monthsBack", SwanEnvironment.monthsBack)))
    }

    // MARK: host -> page

    private fun sendToWeb(message: JSONObject) = runOnUiThread {
        report?.collect(message)
        webView.evaluateJavascript("window.postMessage($message, window.location.origin);", null)
    }

    companion object {
        private const val TAG = "swan"
        const val EXTRA_ERROR = "error"
        /** How long to wait for WEB_READY. Generous: a first load on a slow connection also downloads a model. */
        private const val WEB_READY_TIMEOUT_MS = 30_000L
    }
}
