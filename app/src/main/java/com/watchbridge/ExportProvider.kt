package com.watchbridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands this app's data to WristLink under its new package name (com.wristlink), once, so the
 * watch keeps its health history, settings and pairing across the rename.
 *
 * Read-only, and only for apps signed with the same key.
 * `content://com.watchbridge.export/files` lists the files (relative to the data directory);
 * `content://com.watchbridge.export/file/<path>` opens one.
 */
class ExportProvider : ContentProvider() {

    private companion object {
        val DIRS = listOf("databases", "shared_prefs", "files")
    }

    override fun onCreate() = true

    private val dataDir: File get() = File(context!!.applicationInfo.dataDir)

    private fun checkCaller() {
        val caller = callingPackage ?: throw SecurityException("Unknown caller")
        val pm = context!!.packageManager
        if (pm.checkSignatures(caller, context!!.packageName) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("$caller isn't signed with WristLink's key")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        checkCaller()
        val cursor = MatrixCursor(arrayOf("path", "size"))
        for (dir in DIRS) {
            File(dataDir, dir).walkTopDown().filter { it.isFile }.forEach { file ->
                cursor.addRow(arrayOf(file.relativeTo(dataDir).invariantSeparatorsPath, file.length()))
            }
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        checkCaller()
        val path = uri.path?.removePrefix("/file/") ?: throw FileNotFoundException()
        val file = File(dataDir, path).canonicalFile
        val allowed = DIRS.any { file.path.startsWith(File(dataDir, it).canonicalPath + File.separator) }
        if (!allowed || !file.isFile) throw FileNotFoundException(path)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
