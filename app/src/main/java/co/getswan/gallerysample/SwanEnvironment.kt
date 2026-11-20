package co.getswan.gallerysample

/** What differs between a sandbox and a production build: the Swan page, the session
 *  service and the key. Picked by the product flavor, like the iOS sample's schemes.
 *  A shipped app mints tokens on its own server and ships no key. */
object SwanEnvironment {
    val sandbox: Boolean = BuildConfig.FLAVOR == "sandbox"

    /** The Swan page. Must be https: the camera needs a secure context. Sandbox loads the dev CDN,
     *  whose versions are numbered separately from production's. */
    val pageUrl: String = if (sandbox) "https://dhua7f26rck7x.cloudfront.net/v1.3.24/index.html"
                          else "https://swan-mobile-web-component.swan.so/v1.3.18/index.html"

    /** Where the sample mints session tokens. Must be the same environment as the page. */
    const val sessionUrl: String = "https://swan-integration.swanvision.dev/api/session"

    /** The sample's key for this build. */
    val publishableKey: String = if (sandbox) "sw_pk_77e0eaa18bdc4520be45bf2a0a2abf71" else "sw_pk_77e0eaa18bdc4520be45bf2a0a2abf71"

    /** The shopper the sample signs in as. */
    /** Where each scan's JSON report is posted, and the ingest key (see `ScanReport`). Sandbox builds only;
     *  the key ships inside the app and keeps out casual traffic, nothing more. */
    val scanReportUrl: String? = if (sandbox) "https://devphotoingest.api.swanvision.ai/v1/scans" else null
    val scanReportKey: String? = if (sandbox) "a1e5074c29a3c3da344212996765c566e30c7b99cd0a6e81" else null

    const val shopDomain: String = "swantestflight"
    const val userGender: String = "male"      // "male" | "female"
    val userHeightCm: Int? = 181
    const val monthsBack: Int = 12
}
