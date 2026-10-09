package com.narik.terus

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Intent

/**
 * Membaca notifikasi app target (jalur "Notif order").
 * Hanya memicu auto-stop bila mode channel = "notif".
 * Notifikasi kadaluarsa (>15 dtk) diabaikan agar re-fire saat listener
 * reconnect tidak memicu auto-stop palsu.
 * Isi notifikasi dicatat ke daftar Recent (awalan NOTIF).
 */
class OrderNotificationListener : NotificationListenerService() {

    private val lastRecentAt = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        val prefs = getSharedPreferences("narik_state", MODE_PRIVATE)
        val channel = when (pkg) {
            prefs.getString("grb_target", HookContract.DEFAULT_TARGET_GRB) -> "grb"
            prefs.getString("gjk_target", HookContract.DEFAULT_TARGET_GJK) -> "gjk"
            else -> return
        }

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val ticker = sbn.notification.tickerText?.toString() ?: ""
        if (title.isBlank() && text.isBlank() && bigText.isBlank() && ticker.isBlank()) return

        val payload = "NOTIF{title=$title | text=$text | bigText=$bigText | ticker=$ticker}"

        // Abaikan notifikasi kadaluarsa (>15 dtk): re-fire listener reconnect
        // dan notifikasi lama tidak boleh memicu auto-stop palsu.
        if (sbn.`when` > 0 && System.currentTimeMillis() - sbn.`when` > 15_000L) return

        // Recent (throttle 2 dtk per channel)
        val now = System.currentTimeMillis()
        if (now - (lastRecentAt[channel] ?: 0L) >= 2_000L) {
            lastRecentAt[channel] = now
            val key = "recent_$channel"
            val old = prefs.getString(key, null)?.split('\n')?.toMutableList() ?: mutableListOf()
            old.add(0, payload)
            while (old.size > 20) old.removeAt(old.size - 1)
            prefs.edit().putString(key, old.joinToString("\n")).apply()
        }

        // Auto-stop hanya mode "notif"
        val playing = prefs.getBoolean("${channel}_play", false)
        val mode = prefs.getString("${channel}_stopmode", "notif") ?: "notif"
        if (!playing || mode != "notif") return

        val triggerRaw = (
            prefs.getString("${channel}_trigger_notif", null)
                ?: prefs.getString("${channel}_trigger", "")
                ?: ""
            )
        val keywords = triggerRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (keywords.isEmpty()) return

        val lower = payload.lowercase()
        if (keywords.any { lower.contains(it.lowercase()) }) {
            prefs.edit()
                .putBoolean("${channel}_play", false)
                .putString("${channel}_stop_request", "1")
                .apply()

            // Langsung matikan hook di proses target (walau UI modul tertutup)
            val targetPkg = prefs.getString("${channel}_target", null)
            if (targetPkg != null) {
                runCatching {
                    sendBroadcast(
                        Intent(HookContract.ACTION_STATE).setPackage(targetPkg)
                            .putExtra(HookContract.KEY_CHANNEL, channel)
                            .putExtra(HookContract.KEY_PLAY, false)
                            .putExtra(
                                HookContract.KEY_LAT,
                                prefs.getString("${channel}_lat", null)?.toDoubleOrNull() ?: 0.0
                            )
                            .putExtra(
                                HookContract.KEY_LNG,
                                prefs.getString("${channel}_lng", null)?.toDoubleOrNull() ?: 0.0
                            )
                            .putExtra(
                                HookContract.KEY_METHODS,
                                prefs.getLong("${channel}_methods", 0L)
                            )
                            .putExtra("trigger_keywords", keywords.joinToString(","))
                            .putExtra("trigger_enabled", true)
                            .putExtra("stopmode", mode)
                    )
                }
            }

            runCatching {
                sendBroadcast(
                    Intent(HookContract.ACTION_TRIGGER).setPackage(packageName)
                        .putExtra(HookContract.KEY_CHANNEL, channel)
                        .putExtra(HookContract.KEY_PAYLOAD, payload)
                        .putExtra(HookContract.KEY_MATCHED, true)
                )
            }
        }
    }
}
