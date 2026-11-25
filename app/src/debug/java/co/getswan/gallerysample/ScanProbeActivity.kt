package co.getswan.gallerysample

import android.app.Activity
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Bundle
import android.os.Debug
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Debug builds only: runs the library scan several times in one process, with library photos as the selfie frames,
 *  and logs the memory still held after each run once the collector has run, to tell a leak from memory that is only
 *  waiting to be collected. No page, no report. Each run uses a new account, so each is a full scan (the store keeps
 *  one account). `adb shell am start -n co.getswan.gallerysample.sandbox/co.getswan.gallerysample.ScanProbeActivity --ei runs 4` */
class ScanProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runs = intent.getIntExtra("runs", 4)
        val ids = (intent.getStringExtra("frames") ?: "1000136646,1000136633").split(",").map { it.trim().toLong() }
        val sameAccount = intent.getBooleanExtra("sameAccount", false)
        Thread {
            val frames = ids.mapNotNull { frame(it) }
            val sink = File(getExternalFilesDir(null), "scanprobe.txt").also { it.delete() }
            fun note(line: String) { Log.i(TAG, "scanprobe $line"); sink.appendText("$line\n") }
            note("start: ${frames.size} frames, $runs runs, ${if (sameAccount) "same account" else "new account each run"}; ${held()}")
            var done = CountDownLatch(1); var summary: JSONObject? = null
            val scan = GalleryScan(this) { m -> if (m.optString("type") == "onGalleryScanComplete") { summary = m.optJSONObject("payload"); done.countDown() } }
            for (run in 1..runs) {
                done = CountDownLatch(1); summary = null
                val t = System.currentTimeMillis()
                scan.start(SwanEnvironment.monthsBack, limited = false, frames = frames, account = if (sameAccount) "scanprobe" else "scanprobe-$run")
                if (!done.await(15, TimeUnit.MINUTES)) { note("run $run: no completion"); break }
                val s = summary?.optJSONObject("summary")
                note("run $run: ${(System.currentTimeMillis() - t) / 1000}s, matches ${s?.optInt("matches")}, peak ${s?.optInt("memoryPeakMB")} MB, error ${summary?.optString("error")}; after GC ${held()}")
            }
            note("done")
            runOnUiThread { finish() }
        }.start()
    }

    /** Memory held now, after two collections: native heap in use, Java heap in use, resident. */
    private fun held(): String {
        repeat(2) { Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(500) }
        val rt = Runtime.getRuntime()
        val rss = runCatching { File("/proc/self/statm").readText().trim().split(" ")[1].toLong() * android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) / 1_048_576 }.getOrDefault(-1L)
        return "native ${Debug.getNativeHeapAllocatedSize() / 1_048_576} MB, java ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB, resident $rss MB"
    }

    /** A library photo as the page sends a selfie frame: a 1280 px JPEG data URI. */
    private fun frame(id: Long): String? = runCatching {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
            val s = minOf(1f, 1280f / maxOf(info.size.width, info.size.height))
            d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt()); d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 90, out); b.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.onFailure { Log.w(TAG, "scanprobe frame $id: ${it.message}") }.getOrNull()

    private companion object { const val TAG = "swan" }
}
