package com.narik.terus

import android.app.AndroidAppHelper
import android.net.Uri
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicLong

/**
 * Titik masuk modul LSPosed.
 *
 * Sumber state (urutan):
 * 1. ContentProvider modul (Binder antar-aplikasi, tahan SELinux) — UTAMA
 * 2. XSharedPreferences (dibantu daemon LSPosed) — CADANGAN
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val PKG_GRB = "com.pierwiastek.gpsdata"
        private const val PKG_GJK = "com.khalnadj.khaledhabbachi.gps"

        // Harus sama dengan StateProvider (const -> inline, aman di proses target)
        private const val STATE_AUTHORITY = "com.narik.terus.state"
        private const val METHOD_GET = "get"
        private const val KEY_PLAY = "play"
        private const val KEY_LAT = "lat"
        private const val KEY_LNG = "lng"

        private const val REFRESH_INTERVAL_MS = 500L
        private const val LOG_INTERVAL_MS = 10_000L
    }

    private data class State(
        val playing: Boolean,
        val lat: Double,
        val lng: Double,
        val source: String
    )

    private var channel: String? = null
    private var prefs: XSharedPreferences? = null

    private val stateLock = Any()
    private var lastRefreshAt = 0L
    private val lastReloadPrefs = AtomicLong(0L)
    private val lastServeLogAt = AtomicLong(0L)

    @Volatile
    private var cached = State(false, 0.0, 0.0, "-")

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        channel = when (lpparam.packageName) {
            PKG_GRB -> "grb"
            PKG_GJK -> "gjk"
            else -> return // proses lain diabaikan
        }

        prefs = try {
            XSharedPreferences("com.narik.terus", "narik_state")
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] XSharedPreferences gagal dibuat: $t")
            null
        }

        hookLocationClass(lpparam.classLoader)

        val st = currentState(force = true)
        XposedBridge.log(
            "[NarikTerus] Hook aktif: ${lpparam.packageName} -> channel=$channel | " +
                "state awal: play=${st.playing}, sumber=${st.source}"
        )
    }

    // ------------------------------------------------------------ state

    /** Baca state (cache 500 ms) lewat provider, fallback prefs. */
    private fun currentState(force: Boolean = false): State {
        val now = System.currentTimeMillis()
        synchronized(stateLock) {
            if (force || now - lastRefreshAt >= REFRESH_INTERVAL_MS) {
                lastRefreshAt = now
                val st = readFromProvider() ?: readFromPrefs()
                    ?: State(false, 0.0, 0.0, "none")
                if (st.playing != cached.playing) {
                    XposedBridge.log(
                        "[NarikTerus] $channel: spoof " +
                            if (st.playing) "ON -> ${st.lat}, ${st.lng} (sumber=${st.source})"
                            else "OFF (sumber=${st.source})"
                    )
                }
                cached = st
            }
            return cached
        }
    }

    /** UTAMA: query provider modul via Binder. */
    private fun readFromProvider(): State? {
        val ch = channel ?: return null
        return try {
            val app = AndroidAppHelper.currentApplication() ?: return null
            val bundle = app.contentResolver.call(
                Uri.parse("content://$STATE_AUTHORITY/state/$ch"),
                METHOD_GET, ch, null
            ) ?: return null
            val playing = bundle.getBoolean(KEY_PLAY, false)
            if (!playing) return State(false, 0.0, 0.0, "provider")
            val lat = bundle.getString(KEY_LAT)?.toDoubleOrNull() ?: return null
            val lng = bundle.getString(KEY_LNG)?.toDoubleOrNull() ?: return null
            State(true, lat, lng, "provider")
        } catch (t: Throwable) {
            null
        }
    }

    /** CADANGAN: XSharedPreferences (dibantu daemon LSPosed). */
    private fun readFromPrefs(): State? {
        val p = prefs ?: return null
        val ch = channel ?: return null
        return try {
            val now = System.currentTimeMillis()
            val last = lastReloadPrefs.get()
            if (now - last >= REFRESH_INTERVAL_MS && lastReloadPrefs.compareAndSet(last, now)) {
                p.reload()
            }
            val playing = p.getBoolean("${ch}_play", false)
            if (!playing) State(false, 0.0, 0.0, "prefs")
            else State(
                true,
                p.getString("${ch}_lat", null)?.toDoubleOrNull() ?: 0.0,
                p.getString("${ch}_lng", null)?.toDoubleOrNull() ?: 0.0,
                "prefs"
            )
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] readFromPrefs error: $t")
            null
        }
    }

    // ------------------------------------------------------------ hook

    private fun hookLocationClass(classLoader: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val st = currentState()
                if (!st.playing) return

                val now = System.currentTimeMillis()
                if (now - lastServeLogAt.get() >= LOG_INTERVAL_MS) {
                    lastServeLogAt.set(now)
                    XposedBridge.log(
                        "[NarikTerus] $channel: melayani ${st.lat}, ${st.lng} (sumber=${st.source})"
                    )
                }
                param.result =
                    if (param.method.name == "getLatitude") st.lat else st.lng
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
