package co.getswan.gallerysample

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Bundle
import android.os.Debug
import android.os.Environment
import android.util.Log
import java.io.File

/** Debug builds only: loads and runs each reader in turn on one body photo and logs the memory each step adds, so the
 *  scan's peak can be traced to its readers. Written to `files/memprobe.txt` in the app's external folder.
 *  `adb shell am start -n co.getswan.gallerysample.sandbox/co.getswan.gallerysample.MemProbeActivity --es file DCIM/Swan-Eval/IMG_0894.HEIC` */
class MemProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("file") ?: "DCIM/Swan-Eval/IMG_0894.HEIC"
        Thread {
            FaceVision.init(this); FaceCover.init(this); BodyPose.init(this); BodyObjects.init(this); BodyScore.init(this); Hands.init(this)
            val sink = File(getExternalFilesDir(null), "memprobe.txt").also { it.delete() }
            var last = held()
            fun step(name: String, work: () -> Unit) {
                val before = held(); work(); val after = held()
                val line = "%-28s native %+5d MB (now %4d), resident %+5d MB (now %4d)".format(name, after.first - before.first, after.first, after.second - before.second, after.second)
                Log.i(TAG, "memprobe $line"); sink.appendText("$line\n"); last = after
            }
            sink.appendText("start: native ${last.first} MB, resident ${last.second} MB\n")
            val file = File(Environment.getExternalStorageDirectory(), path)
            var render: Bitmap? = null
            step("decode 1536 render") { render = decode(file, GallerySettings.loadPixels) }
            val r = render ?: run { sink.appendText("no image\n"); runOnUiThread { finish() }; return@Thread }
            var faces: List<FaceVision.Face> = emptyList()
            step("face boxes (ML Kit)") { faces = FaceVision.detect(r) }
            val face = faces.firstOrNull() ?: run { sink.appendText("no face\n"); runOnUiThread { finish() }; return@Thread }
            var points: List<android.graphics.PointF> = emptyList()
            step("face points (landmarker)") { points = FaceVision.landmarks(r, face) }
            val rec = FaceRecognizer.load(this)
            step("face match (OpenCV SFace)") { rec?.embedding(r, face, points) }
            step("expression (landmarker+)") { FaceVision.expression(r, face) }
            var detected: List<BodyObjects.Object> = emptyList()
            step("objects (EfficientDet)") { detected = BodyObjects.read(r) }
            var body: BodyCheck? = null
            step("pose (landmarker)") { body = BodyPose.check(r, face.box, BodyObjects.people(detected)) }
            step("body score (segmenter)") { body?.let { BodyScore.compute(r, it, face.box, 1.0, objects = BodyObjects.objects(detected)) } }
            step("hands (landmarker)") { Hands.read(r, face.box) }
            step("labels (ML Kit)") { Labels.read(r) }
            step("text (ML Kit, Play)") { DocumentRead.read(r, face.box) }
            step("face cover (segmenter)") { r.crop(headBox(face.box))?.let { c -> FaceCover.read(c, face.box.pixels(c), points); c.recycle() } }
            step("second pass, all readers") {
                FaceVision.detect(r); FaceVision.expression(r, face); BodyObjects.read(r); body?.let { BodyScore.compute(r, it, face.box, 1.0) }; Hands.read(r, face.box); Labels.read(r); DocumentRead.read(r, face.box)
            }
            step("release scan readers") { BodyPose.release(); BodyScore.release(); Hands.release(); BodyObjects.release(); Labels.release(); DocumentRead.release(); FaceCover.release(); FaceVision.release() }
            r.recycle()
            sink.appendText("done\n"); Log.i(TAG, "memprobe done")
            runOnUiThread { finish() }
        }.start()
    }

    /** Native heap in use and resident memory, in MB, after two collections. */
    private fun held(): Pair<Int, Int> {
        repeat(2) { Runtime.getRuntime().gc(); System.runFinalization(); Thread.sleep(300) }
        val rss = runCatching { File("/proc/self/statm").readText().trim().split(" ")[1].toLong() * android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) / 1_048_576 }.getOrDefault(-1L)
        return (Debug.getNativeHeapAllocatedSize() / 1_048_576).toInt() to rss.toInt()
    }

    private fun decode(f: File, side: Int): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, info, _ ->
        val s = minOf(1f, side.toFloat() / maxOf(info.size.width, info.size.height))
        if (s < 1f) decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
    }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }

    private companion object { const val TAG = "swan" }
}
