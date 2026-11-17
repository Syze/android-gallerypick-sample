package co.getswan.gallerysample

import android.webkit.JavascriptInterface

/** What the page calls. Registered as `swanBridge`, the name the page looks for
 *  (`window.swanBridge.postMessage(json)`). Runs on a WebView thread, not the UI thread. */
class WebBridge(private val onMessage: (String) -> Unit) {
    @JavascriptInterface
    fun postMessage(json: String) = onMessage(json)
}
