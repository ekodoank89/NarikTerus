package com.narik.terus

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook          // ← TAMBAHKAN BARIS INI
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicLong

/**
 * Titik masuk modul LSPosed.
 *
 * Alur:
 * 1. MainActivity menulis state play/stop + koordinat marker ke
 *    SharedPreferences "narik_state" (grb_play, grb_lat, grb_lng, dst).
 * 2. Hook ini di-inject ke proses aplikasi target, membaca state tsb
 *    via XSharedPreferences (dibantu SELinux patch LSPosed + chmod dari UI).
 * 3. Saat play aktif, Location.getLatitude/getLongitude di proses target
 *    mengembalikan koordinat marker -> aplikasi target membaca lokasi fake.
 *
 * Play BERTAHAN walau UI NarikTerus ditutup (tersimpan di prefs),
 * sampai tombol stop ditekan.
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val MODULE_PKG = "com.narik.terus"
        private const val PREFS_NAME = "narik_state"

        // Paket target -> channel prefs
        private const val PKG_GRB = "com.pierwiastek.gpsdata"
        private const val PKG_GJK = "com.khalnadj.khaledhabbachi.gps"

        /** Throttle reload prefs agar tidak membaca file di tiap pemanggilan. */
        private const val RELOAD_INTERVAL_MS = 500L
    }

    private var channel: String? = null
    private var prefs: XSharedPreferences? = null
    private val lastReload = AtomicLong(0L)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        channel = when (lpparam.packageName) {
            PKG_GRB -> "grb"
            PKG_GJK -> "gjk"
            else -> return // proses lain diabaikan total
        }

        prefs = try {
            XSharedPreferences(MODULE_PKG, PREFS_NAME)
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] XSharedPreferences gagal: $t")
            null
        }

        hookLocationClass(lpparam.classLoader)

        XposedBridge.log("[NarikTerus] Hook aktif: ${lpparam.packageName} -> channel=$channel")
    }

    /** Reload prefs maksimal 1x per RELOAD_INTERVAL_MS (thread-safe). */
    private fun reloadIfNeeded() {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        val last = lastReload.get()
        if (now - last >= RELOAD_INTERVAL_MS && lastReload.compareAndSet(last, now)) {
            try {
                p.reload()
            } catch (_: Throwable) {
            }
        }
    }

    private fun isPlaying(): Boolean {
        val p = prefs ?: return false
        reloadIfNeeded()
        return try {
            p.getBoolean("${channel}_play", false)
        } catch (_: Throwable) {
            false
        }
    }

    private fun fakeLat(): Double =
        prefs?.getString("${channel}_lat", null)?.toDoubleOrNull() ?: 0.0

    private fun fakeLng(): Double =
        prefs?.getString("${channel}_lng", null)?.toDoubleOrNull() ?: 0.0

    /**
     * Hook utama: Location.getLatitude/getLongitude.
     * Menutup hampir semua jalur pembacaan lokasi (LocationManager,
     * FusedLocationProvider, LocationResult) karena semuanya memakai
     * objek Location yang sama -> getLatitude/getLongitude-nya di-hook.
     */
    private fun hookLocationClass(classLoader: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isPlaying()) return
                param.result = when (param.method.name) {
                    "getLatitude" -> fakeLat()
                    else -> fakeLng()
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.location.Location", classLoader, "getLatitude", hook
            )
            XposedHelpers.findAndHookMethod(
                "android.location.Location", classLoader, "getLongitude", hook
            )
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] Gagal hook Location: $t")
        }
    }
}
