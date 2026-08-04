package ru.valanis.oneplus.statusbar.material

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Module-owned storage for channel icons and their map ([FILE_MAP]). Icons are picked in
 * Settings / NotificationCenter and drawn by SystemUI and AOD: all of them reach the module's
 * files through this provider, so neither root nor storage access is needed.
 * Only the settings packages may write.
 */
class IconProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException(uri.toString())
        val name = uri.lastPathSegment?.takeIf { it.matches(NAME_REGEX) }
            ?: throw FileNotFoundException(uri.toString())
        val file = File(ctx.filesDir, name)
        if (mode == "r") {
            // An empty map is a valid state: readers then tell "nothing saved" from "unreachable"
            if (name == FILE_MAP && !file.exists()) file.writeText("{}")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val caller = callingPackage
        if (caller !in WRITERS && caller != ctx.packageName) {
            throw SecurityException("$caller may not write $name")
        }
        // Readers watch the URI: notify once the writer has finished (closed) the file
        return ParcelFileDescriptor.open(
            file, ParcelFileDescriptor.parseMode(mode), Handler(Looper.getMainLooper())
        ) { ctx.contentResolver.notifyChange(uri, null) }
    }

    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val AUTHORITY = "ru.valanis.oneplus.statusbar.material.icons"
        const val FILE_MAP = "channel_icons.json"

        private val WRITERS = setOf("com.android.settings", "com.oplus.notificationmanager")

        // Flat file names only: no separators, no leading dot ("..")
        private val NAME_REGEX = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]*")

        fun uri(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")
    }
}
