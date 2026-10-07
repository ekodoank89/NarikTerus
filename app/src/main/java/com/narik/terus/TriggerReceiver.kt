package com.narik.terus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Menerima broadcast dari MainHook di proses target saat trigger payload
 * terpicu (order masuk): mematikan channel terkait.
 * Jalur broadcast = SELinux-safe (terbukti berjalan).
 */
class TriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HookContract.ACTION_TRIGGER) return
        val channel = intent.getStringExtra(HookContract.KEY_CHANNEL) ?: return
        if (!intent.getBooleanExtra(HookContract.KEY_MATCHED, false)) return

        // stopChannel() aman dipanggil dari background thread receiver.
        MainActivity.requestStopChannel(context, channel)
    }
}
