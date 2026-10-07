package com.narik.terus

import android.app.AndroidAppHelper
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Sumber state (urutan): ContentProvider modul (UTAMA) -> XSharedPreferences (CADANGAN).
 * Watchdog internal memastikan perubahan play/stop tercatat di log
 * dalam 1 detik, terlepas dari apakah app target sedang memanggil lokasi.
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val PKG_GRB = "com.pierwiastek.gpsdata"
        private const val PKG_GJK = "com.khalnadj.khaledhabbachi.gps"

        private const val STATE_AUTHORITY = "com.narik.terus.state"
        private const val METHOD_GET = "get"
        private const val KEY_PLAY = "play"
        private const val KEY_LAT = "lat"
        private const val KEY_LNG = "lng"

        private const val REFRESH_INTERVAL_MS = 500L
        private const val WATCHDOG_INTERVAL_MS = 1_000L
        private const val SERVE_LOG_INTERVAL_MS = 10_000L
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
    private val lastPrefsReload = AtomicLong(0L)
    private val lastServeLog = AtomicLong(0L)
    private val firstHookCall = AtomicBoolean(false)
    private val providerErrLogged = AtomicBoolean(false)
    private val prefsDiagLogged = AtomicBoolean(false)

    @Volatile
    private var cached = State(false, 0.0, 0.0, "-")

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        channel = when (lpparam.packageName) {
            PKG_GRB -> "grb"
            PKG_GJK -> "gjk"
            else -> return
        }

        prefs = try {
            XSharedPreferences("com.narik.terus", "narik_state")
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] XSharedPreferences gagal dibuat: $t")
            null
        }

        hookLocationClass(lpparam.classLoader)
        startWatchdog()

        val st = currentState(force = true)
        XposedBridge.log(
            "[NarikTerus] Hook aktif: ${lpparam.packageName} -> channel=$channel | " +
                "state awal: play=${st.playing}, sumber=${st.source}"
        )
    }

    // ------------------------------------------------------------ watchdog

    /** Cek state tiap 1 dtk -> perubahan play/stop TERCATAT walau app target diam. */
    private fun startWatchdog() {
        val thread = HandlerThread("NarikTerusWatchdog")
        thread.start()
        Handler(thread.looper).apply {
            val task = object : Runnable {
                override fun run() {
                    try {
                        currentState()
                    } catch (_: Throwable) {
                    }
                    postDelayed(this, WATCHDOG_INTERVAL_MS)
                }
            }
            post(task)
        }
    }

    // ------------------------------------------------------------- state

    private fun currentState(force: Boolean = false): State {
        val now = System.currentTimeMillis()
        synchronized(stateLock) {
            if (force || now - lastRefreshAt >= REFRESH_INTERVAL_MS) {
                lastRefreshAt = now
                val st = readFromPrefs() ?: readFromProvider()
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

    private fun contextForProvider(): Context? {
        AndroidAppHelper.currentApplication()?.let { return it }
        // Fallback untuk saat Application belum dibuat
        return try {
            val at = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentActivityThread"
            )
            XposedHelpers.callMethod(at, "getApplication") as? Context
        } catch (t: Throwable) {
            if (providerErrLogged.compareAndSet(false, true)) {
                XposedBridge.log("[NarikTerus] provider: context gagal: $t")
            }
            null
        }
    }

    private fun readFromProvider(): State? {
        val ch = channel ?: return null
        return try {
            val app = contextForProvider() ?: return null
            val bundle = app.contentResolver.call(
                Uri.parse("content://$STATE_AUTHORITY/state/$ch"),
                METHOD_GET, ch, null
            )
            if (bundle == null) {
                if (providerErrLogged.compareAndSet(false, true)) {
                    XposedBridge.log("[NarikTerus] provider: bundle null (authority tak terjangkau?)")
                }
                return null
            }
            val playing = bundle.getBoolean(KEY_PLAY, false)
            if (!playing) return State(false, 0.0, 0.0, "provider")
            val lat = bundle.getString(KEY_LAT)?.toDoubleOrNull() ?: return null
            val lng = bundle.getString(KEY_LNG)?.toDoubleOrNull() ?: return null
            State(true, lat, lng, "provider")
        } catch (t: Throwable) {
            if (providerErrLogged.compareAndSet(false, true)) {
                XposedBridge.log("[NarikTerus] provider error: $t")
            }
            null
        }
    }

    private fun readFromPrefs(): State? {
        val p = prefs ?: return null
        val ch = channel ?: return null

        // Diagnostik sekali: apakah file prefs benar-benar bisa dibaca?
        if (prefsDiagLogged.compareAndSet(false, true)) {
            try {
                val f = p.file
                XposedBridge.log(
                    "[NarikTerus] diag prefs: path=${f?.absolutePath} " +
                        "exists=${f?.exists()} canRead=${f?.canRead()}"
                )
            } catch (t: Throwable) {
                XposedBridge.log("[NarikTerus] diag prefs error: $t")
            }
        }

        return try {
            val now = System.currentTimeMillis()
            val last = lastPrefsReload.get()
            if (now - last >= REFRESH_INTERVAL_MS && lastPrefsReload.compareAndSet(last, now)) {
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

    // ------------------------------------------------------------- hook

    private fun hookLocationClass(classLoader: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (firstHookCall.compareAndSet(false, true)) {
                    XposedBridge.log(
                        "[NarikTerus] $channel: Location.${param.method.name} " +
                            "pertama kali dipanggil oleh app target"
                    )
                }
                val st = currentState()
                if (!st.playing) return

                val now = System.currentTimeMillis()
                if (now - lastServeLog.get() >= SERVE_LOG_INTERVAL_MS) {
                    lastServeLog.set(now)
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
