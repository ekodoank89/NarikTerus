package com.narik.terus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Auto-stop terpicu dari hook (order masuk): tandai prefs play=false +
 * stop_request. Jika UI hidup, onResume akan membersihkan marker; jika
 * tidak, restore berikutnya membaca play=false -> marker tidak dibuat.
 */
class TriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HookContract.ACTION_TRIGGER) return
        val channel = intent.getStringExtra(HookContract.KEY_CHANNEL) ?: return
        if (!intent.getBooleanExtra(HookContract.KEY_MATCHED, false)) return

        context.getSharedPreferences("narik_state", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("${channel}_play", false)
            .putString("${channel}_stop_request", "1")
            .commit()
    }
}
