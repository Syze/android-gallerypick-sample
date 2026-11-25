package co.getswan.gallerysample

import android.content.Context
import android.util.Log
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Tasks

/** ML Kit's text model comes from Google Play services and downloads on first use. `request` starts the download
 *  when the gallery opens; `await` holds the scan until it is on the phone, since a missing model would turn the
 *  document rule off without a sign. Labels ship inside the app. */
object MlKitModels {
    private const val TAG = "swan"
    private fun apis(): Array<OptionalModuleApi> = arrayOf(DocumentRead.client())

    fun request(context: Context) {
        val request = ModuleInstallRequest.newBuilder().apply { apis().forEach { addApi(it) } }.build()
        ModuleInstall.getClient(context).installModules(request)
            .addOnSuccessListener { Log.i(TAG, "ML Kit text model: ${if (it.areModulesAlreadyInstalled()) "on the phone" else "downloading"}") }
            .addOnFailureListener { Log.w(TAG, "ML Kit text model could not be requested: ${it.message}") }
    }

    /** True once the text model is on the phone, waiting up to `GallerySettings.mlKitModelWaitMs`. Off the main thread. */
    fun await(context: Context): Boolean {
        val client = ModuleInstall.getClient(context)
        val deadline = System.currentTimeMillis() + GallerySettings.mlKitModelWaitMs
        var asked = false
        while (true) {
            if (runCatching { Tasks.await(client.areModulesAvailable(*apis())).areModulesAvailable() }.getOrDefault(false)) return true
            if (!asked) { request(context); asked = true }
            if (System.currentTimeMillis() >= deadline) { Log.w(TAG, "ML Kit text model still missing; scanning without it"); return false }
            Thread.sleep(500)
        }
    }
}
