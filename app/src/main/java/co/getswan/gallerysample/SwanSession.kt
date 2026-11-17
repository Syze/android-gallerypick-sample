package co.getswan.gallerysample

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A signed-in session: who, and a token for the page. The sample mints it on the
 *  device from the publishable key; a shipped app asks its own server instead. */
data class SwanSession(val email: String, val name: String, val token: String, val expiresAt: Long) {

    /** A token this close to expiry counts as gone, so a scan does not start on a
     *  token that dies during the upload. */
    val isUsable: Boolean get() = expiresAt - System.currentTimeMillis() > 10 * 60 * 1000L

    companion object {
        private const val TAG = "swan"
        private val json = "application/json; charset=utf-8".toMediaType()
        private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

        /** Mints a session. Logs the status and the service's message only, never the token or the key. */
        fun create(email: String, name: String): Result<SwanSession> = runCatching {
            val body = JSONObject()
                .put("publishableKey", SwanEnvironment.publishableKey)
                .put("userEmail", email)
                .put("userName", name)
            val request = Request.Builder().url(SwanEnvironment.sessionUrl).post(body.toString().toRequestBody(json)).build()
            client.newCall(request).execute().use { response ->
                val reply = runCatching { JSONObject(response.body?.string() ?: "") }.getOrDefault(JSONObject())
                if (BuildConfig.DEBUG) Log.d(TAG, "[swan/session] POST ${SwanEnvironment.sessionUrl} -> HTTP ${response.code} ${reply.optString("message")}")
                val token = reply.optString("sessionToken")
                check(response.isSuccessful && token.isNotEmpty()) { "Sign-in failed (HTTP ${response.code})" }
                // The token's own expiry, else the service's, else an hour.
                val expiresAt = tokenExpiry(token)
                    ?: reply.optLong("expiresAt", 0L).takeIf { it > 0 }
                    ?: (System.currentTimeMillis() + 60 * 60 * 1000L)
                SwanSession(email, name, token, expiresAt)
            }
        }

        /** The `exp` claim of a JWT in milliseconds, when it has one. */
        private fun tokenExpiry(token: String): Long? {
            val parts = token.split(".")
            if (parts.size < 2) return null
            val claims = runCatching { JSONObject(String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING))) }.getOrNull() ?: return null
            return claims.optLong("exp", 0L).takeIf { it > 0 }?.let { it * 1000L }
        }

        private fun store(context: Context) = EncryptedSharedPreferences.create(
            context, "swan_session", MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

        /** The session kept on this phone, if it is still usable. */
        fun load(context: Context): SwanSession? {
            val prefs = store(context)
            val email = prefs.getString("email", null) ?: return null
            val token = prefs.getString("token", null) ?: return null
            val session = SwanSession(email, prefs.getString("name", "") ?: "", token, prefs.getLong("expiresAt", 0L))
            return session.takeIf { it.isUsable }
        }

        fun save(context: Context, session: SwanSession) {
            store(context).edit()
                .putString("email", session.email).putString("name", session.name)
                .putString("token", session.token).putLong("expiresAt", session.expiresAt).apply()
        }

        fun clear(context: Context) { store(context).edit().clear().apply() }
    }
}
