package com.narik.terus

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Jembatan IPC: proses aplikasi target (via MainHook) membaca state
 * play/stop + koordinat marker melalui panggilan Binder, yang diizinkan
 * SELinux antar aplikasi tanpa syarat chmod.
 * Hanya-baca: satu-satunya method adalah "get".
 */
class StateProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.narik.terus.state"
        const val METHOD_GET = "get"
        const val KEY_PLAY = "play"
        const val KEY_LAT = "lat"
        const val KEY_LNG = "lng"
        const val PREFS_NAME = "narik_state"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET || arg.isNullOrEmpty()) return null
        val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: return null
        return Bundle().apply {
            putBoolean(KEY_PLAY, prefs.getBoolean("${arg}_play", false))
            putString(KEY_LAT, prefs.getString("${arg}_lat", null))
            putString(KEY_LNG, prefs.getString("${arg}_lng", null))
        }
    }

    // Tidak dipakai — provider ini murni call()-based, hanya-baca.
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
