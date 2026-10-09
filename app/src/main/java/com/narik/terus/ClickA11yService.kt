package com.narik.terus

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Jalur "Terima order": menangkap TYPE_VIEW_CLICKED dari app target —
 * TERMASUK tombol Jetpack Compose (yang tidak lewat View.performClick).
 * Hanya memicu auto-stop bila mode channel = "terima".
 * Setiap klik dicatat ke Recent (awalan A11Y) untuk kalibrasi.
 */
class ClickA11yService : AccessibilityService() {

    private var lastRecentAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val pkg = event.packageName?.toString() ?: return
        val prefs = getSharedPreferences("narik_state", MODE_PRIVATE)
        val channel = when (pkg) {
            prefs.getString("grb_target", HookContract.DEFAULT_TARGET_GRB) -> "grb"
            prefs.getString("gjk_target", HookContract.DEFAULT_TARGET_GJK) -> "gjk"
            else -> return
        }

        val cls = event.className?.toString() ?: ""
        val text = event.text?.joinToString(" ") { it.toString() }?.trim() ?: ""
        if (text.isBlank() && cls.isBlank()) return
        val payload = "A11Y{cls=$cls, text=$text}"

        // Recent (throttle 2 dtk)
        val now = System.currentTimeMillis()
        if (now - lastRecentAt >= 2_000L) {
            lastRecentAt = now
            val key = "recent_$channel"
            val old = prefs.getString(key, null)?.split('\n')?.toMutableList() ?: mutableListOf()
            old.add(0, payload)
            while (old.size > 20) old.removeAt(old.size - 1)
            prefs.edit().putString(key, old.joinToString("\n")).apply()
        }

        // Auto-stop hanya mode "terima"
        val playing = prefs.getBoolean("${channel}_play", false)
        val mode = prefs.getString("${channel}_stopmode", "notif") ?: "notif"
        if (!playing || mode != "terima") return

        val keywords = prefs.getString("${channel}_trigger_terima", null)?.let {
            if (it.isBlank()) null else it
        } ?: return
        val kws = keywords.split(',').map { k -> k.trim() }.filter { k -> k.isNotEmpty() }
        if (kws.isEmpty()) return

        val lower = payload.lowercase()
        if (kws.any { lower.contains(it.lowercase()) }) {
            prefs.edit()
                .putBoolean("${channel}_play", false)
                .putString("${channel}_stop_request", "1")
                .apply()
            runCatching {
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
                            .putExtra(HookContract.KEY_METHODS, prefs.getLong("${channel}_methods", 0L))
                            .putExtra("trigger_keywords", keywords.joinToString(","))
                            .putExtra("trigger_enabled", true)
                            .putExtra("stopmode", mode)
                    )
                }
            }
                sendBroadcast(
                    Intent(HookContract.ACTION_TRIGGER).setPackage(packageName)
                        .putExtra(HookContract.KEY_CHANNEL, channel)
                        .putExtra(HookContract.KEY_PAYLOAD, payload)
                        .putExtra(HookContract.KEY_MATCHED, true)
                )
            }
        }
    }

    override fun onInterrupt() {}
}
