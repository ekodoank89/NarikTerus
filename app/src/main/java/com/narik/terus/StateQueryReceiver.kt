package com.narik.terus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Menerima permintaan state (ACTION_QUERY) dari hook di proses target,
 * membaca prefs milik sendiri (selalu diizinkan), lalu membalas dengan
 * broadcast ACTION_STATE ke paket peminta.
 * Didaftarkan di manifest sehingga membangunkan proses modul bila mati.
 */
class StateQueryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MainHook.ACTION_QUERY) return
        val channel = intent.getStringExtra("channel") ?: return
        val senderPkg = intent.getStringExtra("package") ?: return

        val prefs = context.getSharedPreferences("narik_state", Context.MODE_PRIVATE)
        val playing = prefs.getBoolean("${channel}_play", false)
        val lat = prefs.getString("${channel}_lat", null)?.toDoubleOrNull() ?: 0.0
        val lng = prefs.getString("${channel}_lng", null)?.toDoubleOrNull() ?: 0.0

        runCatching {
            context.sendBroadcast(
                Intent(MainHook.ACTION_STATE).setPackage(senderPkg)
                    .putExtra("channel", channel)
                    .putExtra("play", playing)
                    .putExtra("lat", lat)
                    .putExtra("lng", lng)
            )
        }
    }
}
