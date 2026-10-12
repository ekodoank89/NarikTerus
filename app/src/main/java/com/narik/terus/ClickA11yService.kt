package com.narik.terus

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Jalur aksesibilitas:
 * 1. Klik (mode "terima")  — mencatat klik & auto-stop sesuai kata kunci.
 * 2. AUTO-TERIMA BERSYARAT — baca popup order (harga + tombol); jika harga
 *    >= minimum, klik tombol otomatis lalu auto-stop (GPS asli).
 */
class ClickA11yService : AccessibilityService() {

    private var lastRecentAt = 0L
    private var lastAutoAcceptAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        val prefs = getSharedPreferences("narik_state", MODE_PRIVATE)
        val channel = when (pkg) {
            prefs.getString("grb_target", HookContract.DEFAULT_TARGET_GRB) -> "grb"
            prefs.getString("gjk_target", HookContract.DEFAULT_TARGET_GJK) -> "gjk"
            else -> return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                handleClickEvent(channel, prefs, event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                tryAutoAccept(channel, prefs)
        }
    }

    // ---------------- jalur 1: pencatatan klik + auto-stop mode "terima"

    private fun handleClickEvent(
        channel: String,
        prefs: android.content.SharedPreferences,
        event: AccessibilityEvent
    ) {
        val cls = event.className?.toString() ?: ""
        val text = event.text?.joinToString(" ") { it.toString() }?.trim() ?: ""
        if (text.isBlank() && cls.isBlank()) return
        val payload = "A11Y{cls=$cls, text=$text}"

        val now = System.currentTimeMillis()
        if (now - lastRecentAt >= 2_000L) {
            lastRecentAt = now
            val key = "recent_$channel"
            val old = prefs.getString(key, null)?.split('\n')?.toMutableList() ?: mutableListOf()
            old.add(0, payload)
            while (old.size > 20) old.removeAt(old.size - 1)
            prefs.edit().putString(key, old.joinToString("\n")).apply()
        }

        val playing = prefs.getBoolean("${channel}_play", false)
        val mode = prefs.getString("${channel}_stopmode", "notif") ?: "notif"
        if (!playing || mode != "terima") return

        val triggerRaw = prefs.getString("${channel}_trigger_terima", "") ?: ""
        val keywords = triggerRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (keywords.isEmpty()) return

        val lower = payload.lowercase()
        if (keywords.any { lower.contains(it.lowercase()) }) {
            stopChannel(channel, prefs, payload)
        }
    }

    // ---------------- jalur 2: AUTO-TERIMA BERSYARHAT

    private fun tryAutoAccept(
        channel: String,
        prefs: android.content.SharedPreferences
    ) {
        val enabled = prefs.getBoolean("${channel}_autoaccept", false)
        val playing = prefs.getBoolean("${channel}_play", false)
        if (!enabled || !playing) return

        val now = System.currentTimeMillis()
        if (now - lastAutoAcceptAt < 1_500L) return // debounce

        val minKm = prefs.getString("${channel}_autoaccept_km", null)
            ?.replace(",", ".")?.toDoubleOrNull() ?: return
        if (minKm <= 0.0) return
        val btnKw = (prefs.getString("${channel}_autoaccept_btn", "terima") ?: "terima")
            .lowercase()
        if (btnKw.isBlank()) return

        val root = rootInActiveWindow ?: return

        // 1) Kumpulkan semua jarak "X km" di jendela, ambil yang TERBESAR
        //    (biasanya jarak trip pengantaran)
        val kmRegex = Regex("([\\d]+[.,]?[\\d]*)\\s*km", RegexOption.IGNORE_CASE)
        var maxKm = -1.0
        walkNodes(root, 0) { t ->
            kmRegex.findAll(t).forEach { m ->
                val v = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: -1.0
                if (v > maxKm) maxKm = v
            }
        }
        if (maxKm < 0.0) return

        android.util.Log.i(
            "NarikTerus",
            "$channel: jarak terbaca $maxKm km (min $minKm km)"
        )
        if (maxKm < minKm) return // di bawah kriteria -> biarkan manual

        // 2) Cari tombol sesuai kata kunci, naik ke leluhur yang clickable
        val nodes = root.findAccessibilityNodeInfosByText(btnKw)
        for (n in nodes) {
            var cur: AccessibilityNodeInfo? = n
            var depth = 0
            while (cur != null && depth < 8) {
                if (cur.isClickable &&
                    cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    lastAutoAcceptAt = System.currentTimeMillis()
                    android.util.Log.i(
                        "NarikTerus",
                        "$channel: AUTO-TERIMA $maxKm km >= $minKm km, klik '$btnKw'"
                    )
                    // Order diterima -> GPS asli (rantai auto-stop terbukti)
                    stopChannel(channel, prefs, "AUTO-TERIMA $maxKm km (min $minKm)")
                    return
                }
                cur = cur.parent
                depth++
            }
        }
    }

    /** Telusuri seluruh node, panggil onText untuk tiap teks. */
    private fun walkNodes(
        node: AccessibilityNodeInfo?,
        depth: Int,
        onText: (String) -> Unit
    ) {
        if (node == null || depth > 15) return
        try {
            node.text?.let { if (it.isNotBlank()) onText(it.toString()) }
            for (i in 0 until node.childCount) {
                walkNodes(node.getChild(i), depth + 1, onText)
            }
        } catch (_: Throwable) {
        }
    }

    /** Matikan channel: prefs + OFF ke hook + trigger ke UI (rantai terbukti). */
    private fun stopChannel(
        channel: String,
        prefs: android.content.SharedPreferences,
        payload: String
    ) {
        prefs.edit()
            .putBoolean("${channel}_play", false)
            .putString("${channel}_stop_request", "1")
            .commit()

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
                        .putExtra(
                            "trigger_keywords",
                            prefs.getString("${channel}_trigger_" +
                                (prefs.getString("${channel}_stopmode", "notif") ?: "notif"),
                                "") ?: ""
                        )
                        .putExtra("trigger_enabled", true)
                        .putExtra(
                            "stopmode",
                            prefs.getString("${channel}_stopmode", "notif") ?: "notif"
                        )
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

    /** Log ringan via Log.i agar terlihat di logcat/LSPosed. */
    private fun XposedBridgeLike(channel: String, msg: String) {
        android.util.Log.i("NarikTerus", "$channel: $msg")
    }

    override fun onInterrupt() {}
}
