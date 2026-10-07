package com.narik.terus

/**
 * Konstanta bersama antara aplikasi (MainActivity, StateQueryReceiver)
 * dan hook (MainHook).
 * WAJIB bebas dari referensi kelas Xposed apa pun, karena kelas ini
 * dimuat di proses aplikasi biasa yang TIDAK punya API Xposed.
 */
object HookContract {
    const val MODULE_PKG = "com.narik.terus"

    const val ACTION_STATE = "com.narik.terus.ACTION_STATE"
    const val ACTION_QUERY = "com.narik.terus.ACTION_QUERY"

    const val KEY_CHANNEL = "channel"
    const val KEY_PACKAGE = "package"
    const val KEY_PLAY = "play"
    const val KEY_LAT = "lat"
    const val KEY_LNG = "lng"
    const val KEY_METHODS = "methods"

    const val DEFAULT_TARGET_GRB = "com.pierwiastek.gpsdata"
    const val DEFAULT_TARGET_GJK = "com.khalnadj.khaledhabbachi.gps"

    // Flag metode (bitmask)
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
}
