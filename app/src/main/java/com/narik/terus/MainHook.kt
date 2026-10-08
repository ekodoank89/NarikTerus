package com.narik.terus

import android.app.AndroidAppHelper
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Hook di proses target:
 * - Spoof lokasi (state dari broadcast ACTION_STATE, termasuk kata kunci trigger).
 * - Auto-stop: payload FCM (DATA maupun NOTIFICATION-only) dicek terhadap
 *   kata kunci channel. Cocok -> spoof OFF seketika + ACTION_TRIGGER ke modul.
 * - Setiap pesan FCM yang masuk SELALU tercatat (karena itu diagnostik jalur).
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val SERVE_LOG_INTERVAL_MS = 10_000L
        private const val RECENT_LOG_INTERVAL_MS = 3_000L
        private const val MAX_PENDING = 10
    }

    data class State(
        val playing: Boolean,
        val lat: Double,
        val lng: Double,
        val methods: Long,
        val triggerKeywords: String
    )

    @Volatile
    private var channel: String? = null

    @Volatile
    private var state: State? = null

    private val lastServeLog = AtomicLong(0L)
    private val lastRecentLog = AtomicLong(0L)

    /** Payload yang tiba sebelum penugasan channel (push antrean awal). */
    private val pendingPayloads = mutableListOf<String>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookLocation(lpparam.classLoader)
        hookGnssStatus(lpparam.classLoader)
        hookFcm(lpparam.classLoader)
        hookApplicationAttach(lpparam.packageName)
        XposedBridge.log(
            "[NarikTerus] hook terpasang (menunggu penugasan channel): ${lpparam.packageName}"
        )
    }

    // ------------------------------------------------- Application.attach

    private fun hookApplicationAttach(targetPackage: String) {
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java, "attach", Context::class.java,
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
                val newChannel = intent.getStringExtra(HookContract.KEY_CHANNEL) ?: return
                val playing = intent.getBooleanExtra(HookContract.KEY_PLAY, false)
                val lat = intent.getDoubleExtra(HookContract.KEY_LAT, 0.0)
                val lng = intent.getDoubleExtra(HookContract.KEY_LNG, 0.0)
                val methods = intent.getLongExtra(HookContract.KEY_METHODS, 0L)
                val trigger = intent.getStringExtra("trigger_keywords") ?: ""
                val oldPlaying = state?.playing
                channel = newChannel
                state = State(playing, lat, lng, methods, trigger)
                if (oldPlaying != playing || oldPlaying == null) {
                    XposedBridge.log(
                        "[NarikTerus] $newChannel: state -> " +
                            (if (playing) "ON ($lat, $lng)" else "OFF") +
                            " metode=$methods trigger='$trigger'"
                    )
                }

                val pending = synchronized(pendingPayloads) {
                    val copy = pendingPayloads.toList()
                    pendingPayloads.clear()
                    copy
                }
                if (pending.isNotEmpty()) {
                    XposedBridge.log(
                        "[NarikTerus] $newChannel: mengevaluasi ${pending.size} payload awal"
                    )
                    for (p in pending) evaluatePayload(p)
                }
            }
        }
        val filter = IntentFilter(HookContract.ACTION_STATE)
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
                Intent(HookContract.ACTION_QUERY).setPackage(HookContract.MODULE_PKG)
                    .putExtra(HookContract.KEY_PACKAGE, targetPackage)
            )
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] query assignment gagal: $t")
        }
    }

    // ------------------------------------------------------------- hooks

    private fun hookLocation(cl: ClassLoader) {
        val core = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
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
        attr("getSpeed", 0f, HookContract.FLAG_SPEED)
        attr("getBearing", 0f, HookContract.FLAG_BEARING)
        attr("getAccuracy", 10f, HookContract.FLAG_ACCURACY)
        attr("getAltitude", 35.0, HookContract.FLAG_ALTITUDE)

        val mock = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val st = state ?: return
                if (st.playing && (st.methods and HookContract.FLAG_MOCK) != 0L) {
                    param.result = false
                }
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
                if (!st.playing || (st.methods and HookContract.FLAG_GNSS) == 0L) return
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

    // ------------------------------------------------------- FCM trigger

    /** Data payload + notification payload (title/body) — saling melengkapi. */
    private fun extractFromRemoteMessage(obj: Any?): String? {
        if (obj == null) return null
        val parts = mutableListOf<String>()
        try {
            val data = XposedHelpers.callMethod(obj, "getData") as? Map<*, *>
            if (!data.isNullOrEmpty()) parts.add(data.toString())
        } catch (_: Throwable) {
        }
        try {
            val notif = XposedHelpers.callMethod(obj, "getNotification")
            if (notif != null) {
                val title = XposedHelpers.callMethod(notif, "getTitle")?.toString() ?: ""
                val body = XposedHelpers.callMethod(notif, "getBody")?.toString() ?: ""
                val tag = XposedHelpers.callMethod(notif, "getTag")?.toString() ?: ""
                if (title.isNotEmpty() || body.isNotEmpty() || tag.isNotEmpty()) {
                    parts.add("notif{title=$title, body=$body, tag=$tag}")
                }
            }
        } catch (_: Throwable) {
        }
        return if (parts.isEmpty()) null else parts.joinToString(" | ")
    }

    /** Bundle handleIntent: menyertakan key gcm.notification.* untuk pesan notif-only. */
    private fun extractFromBundle(bundle: android.os.Bundle?): String? {
        if (bundle == null || bundle.isEmpty) return null
        return try {
            bundle.keySet().joinToString(",") { "$it=${bundle.get(it)}" }
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookFcm(cl: ClassLoader) {
        var ok1 = false
        var ok2 = false
        try {
            val fcmBase = XposedHelpers.findClass(
                "com.google.firebase.messaging.FirebaseMessagingService", cl
            )
            val remoteMsg = XposedHelpers.findClass(
                "com.google.firebase.messaging.RemoteMessage", cl
            )
            XposedHelpers.findAndHookMethod(
                fcmBase, "onMessageReceived", remoteMsg,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // Selalu tanda-tandai bahwa pesan MASUK (walau payload kosong)
                        XposedBridge.log("[NarikTerus] FCM onMessageReceived TERPANGGIL")
                        handlePayload(extractFromRemoteMessage(param.args.firstOrNull()))
                    }
                }
            )
            ok1 = true
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] FCM onMessageReceived gagal: $t")
        }
        try {
            val fcmBase = XposedHelpers.findClass(
                "com.google.firebase.messaging.FirebaseMessagingService", cl
            )
            XposedHelpers.findAndHookMethod(
                fcmBase, "handleIntent", Intent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val i = param.args.firstOrNull() as? Intent ?: return
                        XposedBridge.log("[NarikTerus] FCM handleIntent TERPANGGIL")
                        handlePayload(extractFromBundle(i.extras))
                    }
                }
            )
            ok2 = true
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] FCM handleIntent gagal: $t")
        }
        XposedBridge.log("[NarikTerus] FCM hook: onMessageReceived=$ok1 handleIntent=$ok2")
    }

    /** Titik masuk payload: bila state belum tiba, cadangkan — jangan buang. */
    private fun handlePayload(payload: String?) {
        if (payload.isNullOrEmpty()) {
            XposedBridge.log("[NarikTerus] FCM pesan masuk tapi payload kosong/tak terbaca")
            return
        }
        if (channel == null || state == null) {
            synchronized(pendingPayloads) {
                pendingPayloads.add(payload)
                if (pendingPayloads.size > MAX_PENDING) pendingPayloads.removeAt(0)
            }
            XposedBridge.log("[NarikTerus] payload awal dicadangkan (state belum tiba): $payload")
            return
        }
        evaluatePayload(payload)
    }

    private fun evaluatePayload(payload: String) {
        val st = state ?: return
        val ch = channel ?: return

        val now = System.currentTimeMillis()
        if (now - lastRecentLog.get() >= RECENT_LOG_INTERVAL_MS) {
            lastRecentLog.set(now)
            XposedBridge.log("[NarikTerus] $ch FCM payload: $payload")
        }
        sendToModule(HookContract.ACTION_RECENT, ch, payload, false)

        val keywords = st.triggerKeywords.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (keywords.isEmpty() || !st.playing) return

        val lower = payload.lowercase()
        if (keywords.any { lower.contains(it.lowercase()) }) {
            XposedBridge.log("[NarikTerus] $ch: TRIGGER ORDER MASUK -> auto-stop")
            state = st.copy(playing = false)
            sendToModule(HookContract.ACTION_TRIGGER, ch, payload, true)
        }
    }

    private fun sendToModule(action: String, ch: String, payload: String, matched: Boolean) {
        try {
            val app = AndroidAppHelper.currentApplication() ?: return
            app.sendBroadcast(
                Intent(action).setPackage(HookContract.MODULE_PKG)
                    .putExtra(HookContract.KEY_CHANNEL, ch)
                    .putExtra(HookContract.KEY_PAYLOAD, payload)
                    .putExtra(HookContract.KEY_MATCHED, matched)
            )
        } catch (t: Throwable) {
            XposedBridge.log("[NarikTerus] kirim $action gagal: $t")
        }
    }
}
