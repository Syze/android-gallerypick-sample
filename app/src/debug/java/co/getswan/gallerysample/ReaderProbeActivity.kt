package co.getswan.gallerysample

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Bundle
import android.os.Environment
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Debug builds only: reads every photo in one folder with ML Kit's labeller and text reader, raw and as the scan's
 *  readers see them, and logs one `probe` JSON line per photo, so a library or model swap can be compared before and
 *  after (also written to `files/probe.txt` in the app's external folder). `adb shell am start -n co.getswan.gallerysample.sandbox/co.getswan.gallerysample.ReaderProbeActivity --es folder DCIM/SwanEval` */
class ReaderProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val folder = intent.getStringExtra("folder") ?: "DCIM/SwanEval"
        Thread {
            FaceVision.init(this)
            val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.1f).build())
            val reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val files = File(Environment.getExternalStorageDirectory(), folder).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
            Log.i(TAG, "probe start: ${files.size} files in $folder")
            // Also written whole to the app's external files folder (`adb pull`), since logcat cuts long lines.
            val sink = File(getExternalFilesDir(null), "probe.txt").also { it.delete() }
            for (f in files) {
                val render = runCatching { decode(f) }.onFailure { Log.w(TAG, "probe ${f.name}: ${it.message}") }.getOrNull() ?: continue
                val image = InputImage.fromBitmap(render, 0)
                val raw = runCatching { Tasks.await(labeler.process(image)) }.getOrNull()
                val text = runCatching { Tasks.await(reader.process(image)) }.getOrNull()
                val face = FaceVision.detect(render).firstOrNull()
                val out = JSONObject().put("file", f.name).put("w", render.width).put("h", render.height)
                    .put("labelsRaw", raw?.let { l -> JSONObject().also { o -> l.forEach { o.put(it.text, Math.round(it.confidence * 1000) / 1000.0) } } } ?: "failed")
                    .put("textRaw", text?.let { t -> JSONArray(t.textBlocks.flatMap { b -> b.lines.map { "${it.text} @${it.boundingBox?.toShortString()}" } }) } ?: "failed")
                    .put("labels", JSONObject(Labels.read(render).mapValues { Math.round(it.value * 1000) / 1000.0 } as Map<*, *>))
                face?.let { fc ->
                    out.put("headLabels", render.crop(headBox(fc.box))?.let { JSONObject(Labels.read(it).mapValues { e -> Math.round(e.value * 1000) / 1000.0 } as Map<*, *>) })
                    DocumentRead.read(render, fc.box)?.let { d -> out.put("document", "${d.lines} lines, ${d.nearFace} near, area ${"%.4f".format(d.textArea)}") }
                }
                Log.i(TAG, "probe ${f.name}"); sink.appendText("$out\n")
                render.recycle()
            }
            labeler.close(); reader.close()
            Log.i(TAG, "probe done")
            runOnUiThread { finish() }
        }.start()
    }

    /** The scan's render: the longest side at `loadPixels`, sRGB, software pixels (as `GalleryScan.load`). */
    private fun decode(f: File): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, info, _ ->
        val scale = minOf(1f, GallerySettings.loadPixels.toFloat() / maxOf(info.size.width, info.size.height))
        if (scale < 1f) decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
    }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }

    private companion object { const val TAG = "swan" }
}
