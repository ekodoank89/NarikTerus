package com.narik.terus

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Sumber state (urutan):
 * 1. MEMORI — diisi broadcast ACTION_STATE dari modul (PUSH real-time
 *    maupun balasan query saat app target start). SELinux-safe.
 * 2. XSharedPreferences — fallback.
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val PKG_GRB = "com.pierwiastek.gpsdata"
        private const val PKG_GJK = "com.khalnadj.khaledhabbachi.gps"
        private const val MODULE_PKG = "com.narik.terus"

        const val ACTION_STATE = "com.narik.terus.ACTION_STATE"
        const val ACTION_QUERY = "com.narik.terus.ACTION_QUERY"

        private const val KEY_CHANNEL = "channel"
        private const val KEY_PLAY = "play"
        private const val KEY_LAT = "lat"
        private const val KEY_LNG = "lng"
        private const val KEY_PACKAGE = "package"

        private const val PREFS_NAME = "narik_state"
        private const val PREFS_RELOAD_INTERVAL_MS = 500L
        private const val SERVE_LOG_INTERVAL_MS = 10_000L
    }

    private data class State(val playing: Boolean, val lat: Double, val lng: Double)

    private var channel: String? = null
    private var prefs: XSharedPreferences? = null

    @Volatile
    private var memState: State? = null

    private val appHookDone = AtomicBoolean(false)
    private val prefsDiagLogged = AtomicBoolean(false)
    private val firstHookCall = AtomicBoolean(false)
    private val lastPrefsReload = AtomicLong(0L)
    private val lastServeLog = AtomicLong(0L)

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        channel = when (lpparam.packageName) {
            PKG_GRB -> "grb"
            PKG_GJK -> "gjk"
            else -> return
        }

        prefs = try {
            XSharedPreferences(MODULE_PKG, PREFS_NAME)
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] XSharedPreferences gagal dibuat: $t")
            null
        }

        hookLocationClass(lpparam.classLoader)
        hookApplicationAttach(lpparam.packageName)

        val st = currentState()
        XposedBridge.log(
            "[NarikTerus] Hook aktif: ${lpparam.packageName} -> channel=$channel | " +
                "state awal: ${st?.let { "play=${it.playing}" } ?: "menunggu broadcast"}"
        )
    }

    // ------------------------------------------- broadcast state (utama)

    private fun hookApplicationAttach(targetPackage: String) {
        if (!appHookDone.compareAndSet(false, true)) return
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.thisObject as? Context ?: return
                        registerStateReceiver(ctx)
                        requestInitialState(ctx, targetPackage)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] Gagal hook Application.attach: $t")
        }
    }

    private fun registerStateReceiver(ctx: Context) {
        val ch = channel ?: return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra(KEY_CHANNEL) != ch) return
                val playing = intent.getBooleanExtra(KEY_PLAY, false)
                val lat = intent.getDoubleExtra(KEY_LAT, 0.0)
                val lng = intent.getDoubleExtra(KEY_LNG, 0.0)
                val old = memState
                memState = State(playing, lat, lng)
                if (old?.playing != playing) {
                    XposedBridge.log(
                        "[NarikTerus] $ch: state diterima -> " +
                            if (playing) "ON ($lat, $lng)" else "OFF"
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
            XposedBridge.log("[NarikTerus] $ch: receiver state terdaftar")
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] $ch: gagal daftar receiver: $t")
        }
    }

    private fun requestInitialState(ctx: Context, targetPackage: String) {
        val ch = channel ?: return
        try {
            ctx.sendBroadcast(
                Intent(ACTION_QUERY).setPackage(MODULE_PKG)
                    .putExtra(KEY_CHANNEL, ch)
                    .putExtra(KEY_PACKAGE, targetPackage)
            )
            XposedBridge.log("[NarikTerus] $ch: query state terkirim ke modul")
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] $ch: query state gagal: $t")
        }
    }

    // ------------------------------------------------------- pembacaan

    private fun currentState(): State? {
        memState?.let { return it } // UTAMA: dari broadcast

        // Fallback: prefs (hanya jendela sesaat sebelum balasan broadcast tiba)
        val p = prefs ?: return null
        val ch = channel ?: return null
        return try {
            val now = System.currentTimeMillis()
            val last = lastPrefsReload.get()
            if (now - last >= PREFS_RELOAD_INTERVAL_MS &&
                lastPrefsReload.compareAndSet(last, now)
            ) {
                if (prefsDiagLogged.compareAndSet(false, true)) {
                    val f = p.file
                    XposedBridge.log(
                        "[NarikTerus] diag prefs: path=${f?.absolutePath} " +
                            "exists=${f?.exists()} canRead=${f?.canRead()}"
                    )
                }
                p.reload()
            }
            if (p.getBoolean("${ch}_play", false)) {
                State(
                    true,
                    p.getString("${ch}_lat", null)?.toDoubleOrNull() ?: 0.0,
                    p.getString("${ch}_lng", null)?.toDoubleOrNull() ?: 0.0
                )
            } else {
                State(false, 0.0, 0.0)
            }
        } catch (_: Throwable) {
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
                val st = currentState() ?: return
                if (!st.playing) return

                val now = System.currentTimeMillis()
                if (now - lastServeLog.get() >= SERVE_LOG_INTERVAL_MS) {
                    lastServeLog.set(now)
                    XposedBridge.log(
                        "[NarikTerus] $channel: melayani ${st.lat}, ${st.lng}"
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
