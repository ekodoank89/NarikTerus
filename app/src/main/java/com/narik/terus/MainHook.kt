package com.narik.terus

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Titik masuk modul LSPosed.
 * Dipanggil LSPosed setiap kali sebuah proses aplikasi dimulai.
 */
class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Kerangka modul — tambahkan logika hook (findAndHookMethod,
        // XC_MethodHook, dll.) di sini.
        XposedBridge.log("[NarikTerus] Modul dimuat di proses: ${lpparam.packageName}")
    }
}
