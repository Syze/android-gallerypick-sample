package co.getswan.gallerysample

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Bundle
import android.os.Environment
import android.util.Log
import java.io.File

/** Debug builds only: the face-match vector of the first face in every photo of one folder, written as text, so two
 *  builds of the matcher (OpenCV, the model) can be compared bit for bit. `files/faceprobe.txt` in the app's external folder.
 *  `adb shell am start -n co.getswan.gallerysample.sandbox/co.getswan.gallerysample.FaceProbeActivity --es folder DCIM/SwanEval` */
class FaceProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val folder = intent.getStringExtra("folder") ?: "DCIM/SwanEval"
        Thread {
            FaceVision.init(this)
            val sink = File(getExternalFilesDir(null), "faceprobe.txt").also { it.delete() }
            val rec = FaceRecognizer.load(this)
            sink.appendText("opencv ${org.opencv.core.Core.VERSION} ${org.opencv.core.Core.getBuildInformation().lines().firstOrNull { it.contains("Timestamp") }?.trim()}\n")
            val files = File(Environment.getExternalStorageDirectory(), folder).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
            var n = 0
            for (f in files) {
                val render = runCatching { decode(f) }.getOrNull() ?: continue
                val face = FaceVision.detect(render).firstOrNull()
                if (face != null) {
                    val points = FaceVision.landmarks(render, face)
                    val v = rec?.embedding(render, face, points)
                    sink.appendText("${f.name} ${v?.joinToString(" ") { "%.7f".format(it) } ?: "none"}\n"); n++
                }
                render.recycle()
            }
            Log.i(TAG, "faceprobe done: $n faces"); sink.appendText("done: $n faces\n")
            runOnUiThread { finish() }
        }.start()
    }

    private fun decode(f: File): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { decoder, info, _ ->
        val s = minOf(1f, GallerySettings.loadPixels.toFloat() / maxOf(info.size.width, info.size.height))
        if (s < 1f) decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
    }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }

    private companion object { const val TAG = "swan" }
}
