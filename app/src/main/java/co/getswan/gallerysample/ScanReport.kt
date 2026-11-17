package co.getswan.gallerysample

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.thread

/** One JSON report per scan, posted to Swan's scan ingest for reviewing test scans. Mirrors
 *  `ScanReport.swift` with `platform: "android"`. Off while `url` is null (production). */
class ScanReport(private val url: String?, private val key: String?, private val page: String, private val localFolder: java.io.File? = null) {
    private val matches = HashMap<String, JSONObject>()
    private val picks = ArrayList<String>()
    private var droppedWhy: Map<String, String> = emptyMap()
    private var checks = JSONObject()
    private var trail = JSONArray()
    private val main = Handler(Looper.getMainLooper())
    /** Called on the main thread with the stored report's id and photo count. */
    var onStored: ((String, Int) -> Unit)? = null

    companion object {
        private const val TAG = "swan"
        const val attempts = 3
        /** The page pre-selects after the scan ends; the report waits this long for its picks. */
        const val pickWaitMs = 4000L
        private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    }

    /** Keeps each match sent to the page; on scan complete, builds and posts the report. */
    fun collect(message: JSONObject) {
        val url = url ?: return
        val type = message.optString("type"); val payload = message.optJSONObject("payload") ?: return
        if (type == "onGalleryMatchFound") { payload.optString("assetId").takeIf { it.isNotEmpty() }?.let { matches[it] = payload }; return }
        if (type != "onGalleryScanComplete") return
        val ids = payload.optJSONArray("assetIds")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        val dropped = payload.optJSONArray("dropped")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        val photos = ids.mapIndexed { index, id -> entry(id, JSONObject().put("position", index + 1)) }
        val droppedPhotos = dropped.map { id -> entry(id, JSONObject().also { o -> droppedWhy[id]?.let { o.put("why", it) } }) }
        val report = JSONObject()
            .put("at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).also { it.timeZone = TimeZone.getTimeZone("UTC") }.format(Date()))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}")
            .put("model", Build.MODEL)
            .put("platform", "android")
            .put("page", page)
            .put("shown", photos.size)
            .put("dropped", JSONArray(dropped))
            .put("droppedPhotos", JSONArray(droppedPhotos))
            .put("checks", checks)
            .put("trail", trail)
        for (k in listOf("error", "denied", "limited", "skipped", "summary")) if (payload.has(k)) report.put(k, payload.get(k))
        matches.clear(); droppedWhy = emptyMap(); checks = JSONObject(); trail = JSONArray(); picks.clear()
        main.postDelayed({
            val picked = picks.filter { ids.contains(it) }
            report.put("picked", JSONArray(picked))
            report.put("photos", JSONArray(photos.map { p -> JSONObject(p.toString()).put("picked", picked.contains(p.optString("id"))) }))
            // Debug builds also keep the report on the phone, for `adb pull` and the cross-platform comparison.
            localFolder?.let { folder -> runCatching { folder.mkdirs(); java.io.File(folder, report.getString("at").replace(":", "") + ".json").writeText(report.toString(2)) }.onFailure { Log.w(TAG, "scan report not kept locally: ${it.message}") } }
            thread { post(report, url, key) }
        }, pickWaitMs)
    }

    /** Notes the page's own pre-selection, reported through `onImageSelect` with `auto`. */
    fun noteCallback(name: String, result: JSONObject?) {
        if (url == null || name != "onImageSelect" || result?.optBoolean("auto") != true || !result.optBoolean("selected")) return
        result.optString("assetId").takeIf { it.isNotEmpty() }?.let { picks.add(it) }
    }

    /** Why each withdrawn match went, the end-of-scan checks and the per-photo trail, set just before scan complete. */
    fun endOfScan(droppedWhy: Map<String, String>, checks: JSONObject, trail: JSONArray) { this.droppedWhy = droppedWhy; this.checks = checks; this.trail = trail }

    private fun entry(id: String, extra: JSONObject): JSONObject {
        val photo = matches[id]?.let { JSONObject(it.toString()) } ?: JSONObject()
        for (k in listOf("assetId", "done", "total")) photo.remove(k)
        photo.put("id", id)
        for (k in extra.keys()) photo.put(k, extra.get(k))
        return photo
    }

    /** One gzipped JSON POST, retried after `Retry-After` (or 2 s, 4 s) on 5xx and network errors, never on other 4xx. */
    private fun post(report: JSONObject, url: String, key: String?) {
        val json = report.toString().toByteArray()
        val packed = ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(json) } }.toByteArray()
        for (attempt in 1..attempts) {
            // The product token names the platform and build, as iOS's "photoApp/<build>" does: the ingest lists it as `client`.
            val request = Request.Builder().url(url).post(packed.toRequestBody("application/json".toMediaType()))
                .header("Content-Encoding", "gzip").header("User-Agent", "photoApp-android/${BuildConfig.VERSION_CODE}")
                .also { b -> key?.let { b.header("x-api-key", it) } }.build()
            val result = runCatching { client.newCall(request).execute() }
            val response = result.getOrNull()
            if (response != null && response.isSuccessful) {
                val reply = runCatching { JSONObject(response.body?.string() ?: "") }.getOrDefault(JSONObject()); response.close()
                val id = reply.optString("id"); val photos = reply.optInt("photos")
                Log.i(TAG, "scan report stored at ${request.url.host}: id $id, $photos photos${if (reply.optBoolean("duplicate")) ", already stored" else ""}")
                if (id.isNotEmpty()) main.post { onStored?.invoke(id, photos) }
                return
            }
            val status = response?.code ?: 0
            val retry = response?.header("Retry-After")?.toDoubleOrNull()
            response?.close()
            if ((response != null && status < 500) || attempt == attempts) { Log.w(TAG, "scan report to ${request.url.host} failed: ${result.exceptionOrNull()?.message ?: "HTTP $status"}"); return }
            Thread.sleep(((retry ?: Math.pow(2.0, attempt.toDouble())) * 1000).toLong())
        }
    }
}
