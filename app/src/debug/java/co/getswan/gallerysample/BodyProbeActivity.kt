package co.getswan.gallerysample

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Bundle
import android.os.Environment
import android.util.Log
import java.io.File

/** Debug builds only: scores every body in one folder twice, the whole photo enlarged and only the body area
 *  (`BodyScore.fixedScaleBodyOnly`), at two render sizes, and logs any reading that differs. Written to
 *  `files/bodyprobe.txt` in the app's external folder.
 *  `adb shell am start -n co.getswan.gallerysample.sandbox/co.getswan.gallerysample.BodyProbeActivity --es folder DCIM/SwanEval` */
class BodyProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val folder = intent.getStringExtra("folder") ?: "DCIM/SwanEval"
        Thread {
            FaceVision.init(this); BodyPose.init(this); BodyObjects.init(this); BodyScore.init(this)
            val sink = File(getExternalFilesDir(null), "bodyprobe.txt").also { it.delete() }
            fun note(line: String) { Log.i(TAG, "bodyprobe $line"); sink.appendText("$line\n") }
            val files = File(Environment.getExternalStorageDirectory(), folder).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
            var scored = 0; var same = 0
            for (f in files) for (side in listOf(GallerySettings.loadPixels, 768)) {
                val render = runCatching { decode(f, side) }.getOrNull() ?: continue
                val face = FaceVision.detect(render).firstOrNull()
                if (face == null) { render.recycle(); continue }
                val detected = BodyObjects.read(render)
                val body = BodyPose.check(render, face.box, BodyObjects.people(detected))
                val scale = 1.0   // original pixels per render pixel: only the body height reading uses it
                val whole = BodyScore.compute(render, body, face.box, scale, objects = BodyObjects.objects(detected), bodyOnly = false)
                val part = BodyScore.compute(render, body, face.box, scale, objects = BodyObjects.objects(detected), bodyOnly = true)
                render.recycle()
                if (whole == null && part == null) continue
                scored++
                val a = whole?.fields()?.toString(); val b = part?.fields()?.toString()
                if (a == b) same++ else note("DIFF ${f.name} @$side\n  whole $a\n  part  $b")
            }
            note("done: $scored bodies scored, $same identical")
            runOnUiThread { finish() }
        }.start()
    }

    private fun decode(f: File, side: Int): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, info, _ ->
        val s = minOf(1f, side.toFloat() / maxOf(info.size.width, info.size.height))
        if (s < 1f) decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
    }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }

    private companion object { const val TAG = "swan" }
}
