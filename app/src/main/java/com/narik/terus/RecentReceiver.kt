package com.narik.terus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Menerima payload FCM terbaru dari proses target (untuk daftar Recent
 * yang dapat disalin) — disimpan ke prefs, dibaca saat menu dibuka.
 */
class RecentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HookContract.ACTION_RECENT) return
        val channel = intent.getStringExtra(HookContract.KEY_CHANNEL) ?: return
        val payload = intent.getStringExtra(HookContract.KEY_PAYLOAD) ?: return

        val prefs = context.getSharedPreferences("narik_state", Context.MODE_PRIVATE)
        val key = "recent_$channel"
        val old = prefs.getString(key, null)?.split('\n')?.toMutableList() ?: mutableListOf()
        old.add(0, payload) // terbaru di atas
        while (old.size > 20) old.removeAt(old.size - 1)
        prefs.edit().putString(key, old.joinToString("\n")).apply()
    }
}
