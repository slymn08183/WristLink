package com.wristlink

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Takes over the data of WristLink's old package (com.watchbridge) on the first start, so the
 * rename keeps the health history, settings, the iPhone's pairing and cached icons.
 *
 * The old app serves its files through a provider that only answers apps signed with the same
 * key. This runs before anything opens a database or a preferences file, and only once: when
 * it's done (or there's nothing to take over) a marker is written and it never runs again.
 */
object LegacyImport {

    private const val TAG = "LegacyImport"
    private const val OLD_PACKAGE = "com.watchbridge"
    private val LIST = Uri.parse("content://com.watchbridge.export/files")
    private const val MARKER = "legacy_import_done"

    fun runOnce(context: Context) {
        val dataDir = File(context.applicationInfo.dataDir)
        val marker = File(dataDir, "files/$MARKER")
        if (marker.exists()) return
        try {
            val copied = copyAll(context, dataDir)
            Log.i(TAG, "Took over $copied files from $OLD_PACKAGE")
        } catch (e: Exception) {
            // No old app (a fresh install) or it can't be read: start empty, don't try again
            Log.i(TAG, "Nothing taken over from $OLD_PACKAGE: ${e.message}")
        }
        marker.parentFile?.mkdirs()
        marker.writeText(System.currentTimeMillis().toString())
    }

    private fun copyAll(context: Context, dataDir: File): Int {
        val resolver = context.contentResolver
        val cursor = resolver.query(LIST, null, null, null, null)
            ?: throw IllegalStateException("$OLD_PACKAGE isn't installed")
        val paths = ArrayList<String>()
        cursor.use { while (it.moveToNext()) paths.add(it.getString(0)) }

        for (path in paths) {
            val target = File(dataDir, newName(path))
            target.parentFile?.mkdirs()
            val source = Uri.parse("content://com.watchbridge.export/file/$path")
            resolver.openInputStream(source)!!.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return paths.size
    }

    /** Preferences files were named after the old package ("watchbridge_settings.xml"). */
    private fun newName(path: String) =
        if (path.startsWith("shared_prefs/watchbridge_")) {
            path.replaceFirst("shared_prefs/watchbridge_", "shared_prefs/wristlink_")
        } else {
            path
        }
}
