package com.narik.terus

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hook terpasang di SEMUA proses dalam scope. Channel (grb/gjk) DITETAPKAN
 * dinamis oleh modul lewat broadcast: saat app target start -> ACTION_QUERY
 * -> modul mencocokkan paket dgn prefs <channel>_target -> balasan
 * ACTION_STATE berisi channel + state + metode. Perubahan metode/play
 * diapply live tanpa restart target.
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        const val ACTION_STATE = "com.narik.terus.ACTION_STATE"
        const val ACTION_QUERY = "com.narik.terus.ACTION_QUERY"

        const val KEY_CHANNEL = "channel"
        const val KEY_PACKAGE = "package"
        const val KEY_PLAY = "play"
        const val KEY_LAT = "lat"
        const val KEY_LNG = "lng"
        const val KEY_METHODS = "methods"

        const val MODULE_PKG = "com.narik.terus"
        const val DEFAULT_TARGET_GRB = "com.pierwiastek.gpsdata"
        const val DEFAULT_TARGET_GJK = "com.khalnadj.khaledhabbachi.gps"

        // Flag metode (bitmask) — selaras MainActivity & StateQueryReceiver
        const val FLAG_SPEED = 1L shl 0
        const val FLAG_BEARING = 1L shl 1
        const val FLAG_ACCURACY = 1L shl 2
        const val FLAG_ALTITUDE = 1L shl 3
        const val FLAG_MOCK = 1L shl 4
        const val FLAG_GNSS = 1L shl 5

        val METHOD_DEFS: List<Pair<Long, String>> = listOf(
            FLAG_SPEED to "Kecepatan (getSpeed → 0)",
            FLAG_BEARING to "Arah (getBearing → 0°)",
            FLAG_ACCURACY to "Akurasi (getAccuracy → 10 m)",
            FLAG_ALTITUDE to "Altitude (getAltitude → 35 m)",
            FLAG_MOCK to "Samarkan mock (isMock → false)",
            FLAG_GNSS to "Satelit GNSS (GnssStatus palsu)"
        )

        private const val SERVE_LOG_INTERVAL_MS = 10_000L
    }

    data class State(
        val playing: Boolean,
        val lat: Double,
        val lng: Double,
        val methods: Long
    )

    @Volatile
    private var channel: String? = null

    @Volatile
    private var state: State? = null

    private val firstHookCall = AtomicBoolean(false)
    private val lastServeLog = AtomicLong(0L)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookLocation(lpparam.classLoader)
        hookGnssStatus(lpparam.classLoader)
        hookApplicationAttach(lpparam.packageName)
        XposedBridge.log(
            "[NarikTerus] hook terpasang (menunggu penugasan channel): ${lpparam.packageName}"
        )
    }

    // ------------------------------------------------- Application.attach

    private fun hookApplicationAttach(targetPackage: String) {
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.thisObject as? Context ?: return
                        registerStateReceiver(ctx)
                        requestAssignment(ctx, targetPackage)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] gagal hook Application.attach: $t")
        }
    }

    private fun registerStateReceiver(ctx: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val newChannel = intent.getStringExtra(KEY_CHANNEL) ?: return
                val playing = intent.getBooleanExtra(KEY_PLAY, false)
                val lat = intent.getDoubleExtra(KEY_LAT, 0.0)
                val lng = intent.getDoubleExtra(KEY_LNG, 0.0)
                val methods = intent.getLongExtra(KEY_METHODS, 0L)
                val oldPlaying = state?.playing
                channel = newChannel
                state = State(playing, lat, lng, methods)
                if (oldPlaying != playing || oldPlaying == null) {
                    XposedBridge.log(
                        "[NarikTerus] $newChannel: state -> " +
                            (if (playing) "ON ($lat, $lng)" else "OFF") +
                            " metode=$methods"
                    )
                }
            }
        }
        val filter = IntentFilter(ACTION_STATE)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] gagal daftar receiver state: $t")
        }
    }

    private fun requestAssignment(ctx: Context, targetPackage: String) {
        try {
            ctx.sendBroadcast(
                Intent(ACTION_QUERY).setPackage(MODULE_PKG)
                    .putExtra(KEY_PACKAGE, targetPackage)
            )
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] query assignment gagal: $t")
        }
    }

    // ------------------------------------------------------------- hooks

    private fun hookLocation(cl: ClassLoader) {
        val core = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (firstHookCall.compareAndSet(false, true)) {
                    XposedBridge.log(
                        "[NarikTerus] Location.${param.method.name} dipanggil pertama kali"
                    )
                }
                val st = state ?: return
                if (!st.playing) return
                val now = System.currentTimeMillis()
                if (now - lastServeLog.get() >= SERVE_LOG_INTERVAL_MS) {
                    lastServeLog.set(now)
                    XposedBridge.log("[NarikTerus] $channel: melayani ${st.lat}, ${st.lng}")
                }
                param.result =
                    if (param.method.name == "getLatitude") st.lat else st.lng
            }
        }
        try {
            XposedHelpers.findAndHookMethod("android.location.Location", cl, "getLatitude", core)
            XposedHelpers.findAndHookMethod("android.location.Location", cl, "getLongitude", core)
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] gagal hook Location: $t")
        }

        fun attr(name: String, value: Any?, flag: Long) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.location.Location", cl, name,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val st = state ?: return
                            if (st.playing && (st.methods and flag) != 0L) param.result = value
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }
        attr("getSpeed", 0f, FLAG_SPEED)
        attr("getBearing", 0f, FLAG_BEARING)
        attr("getAccuracy", 10f, FLAG_ACCURACY)
        attr("getAltitude", 35.0, FLAG_ALTITUDE)

        val mock = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val st = state ?: return
                if (st.playing && (st.methods and FLAG_MOCK) != 0L) param.result = false
            }
        }
        try {
            XposedHelpers.findAndHookMethod("android.location.Location", cl, "isMock", mock)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.location.Location", cl, "isFromMockProvider", mock
            )
        } catch (_: Throwable) {
        }
    }

    private fun hookGnssStatus(cl: ClassLoader) {
        val gnss = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val st = state ?: return
                if (!st.playing || (st.methods and FLAG_GNSS) == 0L) return
                when (param.method.name) {
                    "getSatelliteCount" -> param.result = 12
                    "usedInFix" -> param.result = true
                    "getSnr" -> param.result = 25f
                    "getElevation" -> param.result = 45f
                    "getAzimuth" -> param.result = 120f
                }
            }
        }
        try {
            val cls = XposedHelpers.findClass("android.location.GnssStatus", cl)
            XposedHelpers.findAndHookMethod(cls, "getSatelliteCount", gnss)
            XposedHelpers.findAndHookMethod(cls, "usedInFix", Int::class.javaPrimitiveType, gnss)
            XposedHelpers.findAndHookMethod(cls, "getSnr", Int::class.javaPrimitiveType, gnss)
            XposedHelpers.findAndHookMethod(cls, "getElevation", Int::class.javaPrimitiveType, gnss)
            XposedHelpers.findAndHookMethod(cls, "getAzimuth", Int::class.javaPrimitiveType, gnss)
        } catch (_: Throwable) {
        }
    }
}
