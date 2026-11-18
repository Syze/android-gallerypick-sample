package co.getswan.gallerysample

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Per-photo scan results kept between scans, so the same photos are not loaded again. Mirrors
 *  `ScanStore.swift`: kept only on this phone (the no-backup folder), one file per account, nothing
 *  that depends on the selfie except a candidate's face vectors. */
class ScanStore private constructor(private val file: File, private val records: HashMap<String, Record>) {
    enum class Verdict { noFace, group, faceTooSmall, otherPeople, animal, notFullBody, unusableFace, tooLarge, sensitive, candidate }

    /** One photo's result: the verdict, the photo's modified stamp, the rules version, face vectors for a candidate. */
    class Record(val verdict: Verdict, val modified: Long, val version: Int, val faces: List<FloatArray>, val note: String?)

    private var unsaved = 0
    var written = 0; private set
    val count: Int get() = synchronized(this) { records.size }

    companion object {
        private const val TAG = "swan"
        const val enabled = true
        /** Bump when a rule changes the stored verdicts, so old records are ignored and rebuilt.
         *  One ahead of iOS's 13: Android's larger-render retry landed after its stores were written. */
        const val rulesVersion = 16

        private fun fileName(account: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(account.lowercase().toByteArray())
            return "scan-store-" + digest.joinToString("") { "%02x".format(it) }.take(16) + ".json"
        }

        /** Opens the store for this account and deletes every other account's store. Null when off. */
        fun open(context: Context, account: String): ScanStore? {
            if (!enabled) return null
            val folder = File(context.noBackupFilesDir, "SwanGalleryPick").also { it.mkdirs() }
            val name = fileName(account)
            folder.listFiles()?.filter { it.name.startsWith("scan-store-") && it.name != name }?.forEach { it.delete() }
            val file = File(folder, name)
            val records = HashMap<String, Record>()
            runCatching {
                val root = JSONObject(file.readText()).getJSONObject("records")
                for (id in root.keys()) {
                    val r = root.getJSONObject(id)
                    val faces = r.optJSONArray("faces")?.let { a -> List(a.length()) { decode(a.getString(it)) } } ?: emptyList()
                    records[id] = Record(Verdict.valueOf(r.getString("verdict")), r.getLong("modified"), r.getInt("version"), faces, r.optString("note").takeIf { it.isNotEmpty() })
                }
            }
            return ScanStore(file, records)
        }

        private fun encode(v: FloatArray): String { val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN); v.forEach { b.putFloat(it) }; return Base64.encodeToString(b.array(), Base64.NO_WRAP) }
        private fun decode(s: String): FloatArray { val b = ByteBuffer.wrap(Base64.decode(s, Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN); return FloatArray(b.remaining() / 4) { b.getFloat() } }
    }

    /** The record for a photo, if it is current: same modified stamp, same rules. */
    fun record(asset: GalleryPhotos.Asset): Record? = synchronized(this) {
        records[asset.id]?.takeIf { it.version == rulesVersion && it.modified == asset.modified }
    }

    /** Stores a verdict; face vectors are kept only for a candidate. */
    fun remember(asset: GalleryPhotos.Asset, verdict: Verdict, faces: List<FloatArray> = emptyList(), note: String? = null) = synchronized(this) {
        records[asset.id] = Record(verdict, asset.modified, rulesVersion, if (verdict == Verdict.candidate) faces else emptyList(), note)
        written++; unsaved++
    }

    fun save() {
        val snapshot: Map<String, Record>
        synchronized(this) { if (unsaved == 0) return; unsaved = 0; snapshot = HashMap(records) }
        runCatching {
            val root = JSONObject()
            for ((id, r) in snapshot) {
                val o = JSONObject().put("verdict", r.verdict.name).put("modified", r.modified).put("version", r.version)
                if (r.faces.isNotEmpty()) o.put("faces", JSONArray(r.faces.map { encode(it) }))
                r.note?.let { o.put("note", it) }
                root.put(id, o)
            }
            val tmp = File(file.path + ".tmp"); tmp.writeText(JSONObject().put("records", root).toString()); tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "Scan store not saved: ${it.message}") }
    }

    /** Saves after every `every` new records, so a scan that is killed keeps most of its work. */
    fun saveIfDue(every: Int = 500) { val due = synchronized(this) { unsaved >= every }; if (due) save() }
}
