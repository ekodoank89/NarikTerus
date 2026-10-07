package com.narik.terus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Menjawab ACTION_QUERY dari proses target: menetapkan channel
 * berdasarkan <channel>_target di prefs modul, lalu membalas
 * ACTION_STATE (channel + play + koordinat + metode).
 */
class StateQueryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HookContract.ACTION_QUERY) return
        val senderPkg = intent.getStringExtra(HookContract.KEY_PACKAGE) ?: return

        val prefs = context.getSharedPreferences("narik_state", Context.MODE_PRIVATE)
        val channel = when (senderPkg) {
            prefs.getString("grb_target", HookContract.DEFAULT_TARGET_GRB) -> "grb"
            prefs.getString("gjk_target", HookContract.DEFAULT_TARGET_GJK) -> "gjk"
            else -> return // bukan target siapa pun -> tidak ditugaskan
        }

        runCatching {
            context.sendBroadcast(
                Intent(HookContract.ACTION_STATE).setPackage(senderPkg)
                    .putExtra(HookContract.KEY_CHANNEL, channel)
                    .putExtra(HookContract.KEY_PLAY, prefs.getBoolean("${channel}_play", false))
                    .putExtra(
                        HookContract.KEY_LAT,
                        prefs.getString("${channel}_lat", null)?.toDoubleOrNull() ?: 0.0
                    )
                    .putExtra(
                        HookContract.KEY_LNG,
                        prefs.getString("${channel}_lng", null)?.toDoubleOrNull() ?: 0.0
                    )
                    .putExtra(HookContract.KEY_METHODS, prefs.getLong("${channel}_methods", 0L))
            )
        }
    }
}
