package co.getswan.gallerysample

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Development tools: the matching eval (Swan-Eval) and its pinned reference, the eval half of
 *  `DevTools.swift`. Debug builds only, off by default (`GallerySettings.evalMode`). The grid is
 *  unaffected and nothing leaves the phone; the result goes to logcat and `files/scan-reports/eval-*.txt`. */
object DevTools {
    private const val TAG = "swan"

    /** The answer key: the ids of every photo under `evalAlbumFolder` (the shopper alone, head to toe, or a
     *  selfie of them). Null when eval mode is off or the folder is empty, which turns the eval off. */
    fun evalLabels(context: Context): Set<String>? {
        if (!GallerySettings.evalMode) return null
        val ids = HashSet<String>()
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?", arrayOf("${GallerySettings.evalAlbumFolder}%"), null)?.use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0).toString())
        }
        if (ids.isEmpty()) Log.i(TAG, "eval mode is on, but no photo sits under \"${GallerySettings.evalAlbumFolder}\" — nothing will be measured")
        else Log.i(TAG, "eval mode: ${ids.size} labelled photo(s) under \"${GallerySettings.evalAlbumFolder}\"")
        return ids.ifEmpty { null }
    }

    /** In eval mode the reference saved on the first run, so runs are comparable; `evalReplaceReference`
     *  saves this run's instead. Only the host's reference is pinned; the page uploads the new selfie. */
    fun pinnedReference(context: Context, account: String, fresh: FloatArray): FloatArray {
        if (!GallerySettings.evalMode) return fresh
        val file = File(context.noBackupFilesDir, "eval-reference-${account.hashCode()}.bin")
        if (file.exists() && !GallerySettings.evalReplaceReference) {
            val bytes = file.readBytes(); val buf = ByteBuffer.wrap(bytes)
            val pinned = FloatArray(bytes.size / 4) { buf.getFloat() }
            if (pinned.size == fresh.size) { Log.i(TAG, "eval mode: reference pinned from the first run"); return pinned }
        }
        val buf = ByteBuffer.allocate(fresh.size * 4); for (x in fresh) buf.putFloat(x); file.writeBytes(buf.array())
        Log.i(TAG, "eval mode: this run's reference saved as the pin")
        return fresh
    }

    /** What decided one photo, in the order the scan applies them; the names of `EvalGate` in the Xcode copy. */
    private enum class Gate { noImage, noFace, group, faceTooSmall, otherPeople, animal, notFullBody, noMatch, sensitive, tooLarge, noOriginal, grid, gapCut, unusableFace }

    private class Rec(val id: String, val gate: Gate, val distance: Float?, val type: String?, val labelled: Boolean,
                      val taken: String, val facePx: Int, val file: String, val why: String)

    /** The gate a trail outcome names. */
    private fun gateOf(outcome: String, why: String): Gate {
        val o = outcome.lowercase().removePrefix("store: ")
        return when {
            o.startsWith("withdrawn") -> if (why.startsWith("gap cut")) Gate.gapCut else Gate.unusableFace
            o.startsWith("no image") -> Gate.noImage
            o.startsWith("no face") || o.startsWith("no landmarks") -> Gate.noFace
            o.startsWith("group") -> Gate.group
            o.startsWith("face too small") || (o.startsWith("face ") && "under" in o) -> Gate.faceTooSmall
            "other people" in o -> Gate.otherPeople
            "beside the shopper" in o || o == "animal" -> Gate.animal
            "not head to toe" in o -> Gate.notFullBody
            o.startsWith("no match") -> Gate.noMatch
            "unusable" in o -> Gate.unusableFace
            "too large" in o || "over the cap" in o -> Gate.tooLarge
            o.startsWith("match") -> Gate.grid
            else -> Gate.noImage
        }
    }

    /** The MATCHING EVAL block, in the Xcode copy's wording: the matcher's AUC, precision at the top, recall,
     *  coverage, each gate's right and wrong calls, the grid as shown, and every labelled photo that was missed. */
    fun evalReport(labels: Set<String>, trail: Collection<JSONObject>, grid: List<String>): String {
        val distanceOf = Regex("""(?:no )?match ([0-9.]+)"""); val typeOf = Regex("""match [0-9.]+ (selfie|front|side|three-quarter)""")
        val recs = trail.map { e ->
            val o = e.optString("outcome"); val why = e.optString("why")
            Rec(e.optString("id"), gateOf(o, why), distanceOf.find(o)?.groupValues?.get(1)?.toFloatOrNull(), typeOf.find(o)?.groupValues?.get(1),
                e.optString("id") in labels, e.optString("taken"), e.optInt("facePx"), e.optString("file"), why)
        }
        val byId = recs.associateBy { it.id }
        val labelledInWindow = recs.count { it.labelled }; val outside = labels.size - labelledInWindow
        val scored = recs.filter { it.distance != null }
        // The chance a correct photo scores closer than a wrong one, ties counting half.
        fun auc(c: List<Float>, w: List<Float>): Double? {
            if (c.isEmpty() || w.isEmpty()) return null
            var s = 0.0; for (a in c) for (b in w) s += if (a < b) 1.0 else if (a == b) 0.5 else 0.0
            return s / (c.size.toDouble() * w.size)
        }
        fun split(types: Set<String>?): Triple<Double?, Int, Int> {
            val g = scored.filter { types == null || it.type in types }
            val c = g.filter { it.labelled }.map { it.distance!! }; val w = g.filter { !it.labelled }.map { it.distance!! }
            return Triple(auc(c, w), c.size, w.size)
        }
        val all = split(null); val selfies = split(setOf("selfie")); val bodies = split(setOf("front", "side"))
        fun a(v: Double?) = v?.let { "%.3f".format(Locale.US, it) } ?: "n/a"
        val top = grid.take(GallerySettings.evalTopN); val topCorrect = top.count { it in labels }; val gridCorrect = grid.count { it in labels }
        val have = HashMap<String, Int>(); for (id in grid) if (id in labels) byId[id]?.type?.let { have[it] = (have[it] ?: 0) + 1 }
        val need = listOf("selfie" to 1, "front" to 2, "side" to 2)
        val fillable = need.all { (have[it.first] ?: 0) >= it.second }
        val coverage = need.joinToString(", ") { "${it.first} ${have[it.first] ?: 0}/${it.second}" }
        val gates = Gate.values().filter { it != Gate.grid }.joinToString("\n") { g ->
            val here = recs.filter { it.gate == g }; val lost = here.count { it.labelled }
            "  ${g.name.padEnd(14)}${here.size - lost} wrong caught, $lost correct lost"
        }
        fun ratio(p: Int, w: Int) = if (w == 0) "n/a" else "%.2f".format(Locale.US, p.toDouble() / w)
        fun where(r: Rec) = "${r.taken.ifEmpty { "no date" }}   ${r.file}, ${r.facePx}px face"
        val gridLines = top.mapIndexedNotNull { i, id -> byId[id]?.let { r ->
            "  tile ${(i + 1).toString().padEnd(3)} ${if (r.labelled) "YOU" else "no "}  ${(r.type ?: "?").padEnd(13)} d ${r.distance?.let { "%.3f".format(Locale.US, it) } ?: "?"}  ${where(r)}" } }
        val missed = recs.filter { it.labelled && it.gate != Gate.grid }
            .map { "  ${it.gate.name.padEnd(13)} ${where(it)}${if (it.why.isNotEmpty()) "  (${it.why})" else ""}" }
        val S = GallerySettings
        return buildString {
            appendLine(); appendLine("=============================="); appendLine("MATCHING EVAL — \"${S.evalAlbumFolder}\""); appendLine("==============================")
            appendLine("settings    threshold ${"%.2f".format(Locale.US, S.matchThreshold)}, gap ceiling ${"%.2f".format(Locale.US, S.gapCutCeiling)}, face floor ${S.minFacePixels.toInt()}px")
            appendLine("labelled    $labelledInWindow in the window${if (outside > 0) ", $outside outside it (ignored)" else ""}")
            appendLine("AUC         ${a(all.first)}   (matcher only: ${all.second} correct vs ${all.third} wrong, of photos that reached scoring)")
            appendLine("  selfies     ${a(selfies.first)}   (${selfies.second} correct vs ${selfies.third} wrong)")
            appendLine("  full body   ${a(bodies.first)}   (${bodies.second} correct vs ${bodies.third} wrong — front and side)")
            appendLine("top ${S.evalTopN}      precision ${ratio(topCorrect, top.size)}   ($topCorrect of ${top.size} correct; grid has ${grid.size})")
            appendLine("recall      ${ratio(gridCorrect, labelledInWindow)}   ($gridCorrect of $labelledInWindow correct photos reached the grid)")
            appendLine("coverage    ${if (fillable) "fillable" else "NOT fillable"}   (correct photos only: $coverage)")
            appendLine("in grid     $gridCorrect correct, ${grid.size - gridCorrect} wrong")
            appendLine("by gate"); appendLine(gates)
            appendLine("grid        in the order shown"); gridLines.forEach { appendLine(it) }
            if (missed.isEmpty()) appendLine("missed      none") else { appendLine("missed      ${missed.size} of yours never reached the grid"); missed.forEach { appendLine(it) } }
            appendLine("==============================")
        }
    }

    /** Keeps the eval block beside the scan reports, for `adb pull`. */
    fun save(context: Context, text: String) {
        runCatching {
            val dir = File(context.filesDir, "scan-reports").also { it.mkdirs() }
            File(dir, "eval-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt").writeText(text)
        }.onFailure { Log.w(TAG, "eval report not saved: ${it.message}") }
    }
}
