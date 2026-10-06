# ============================================================
# NarikTerus — Aturan R8/ProGuard (release)
# ============================================================

# ------------------------------------------------------------
# XPOSED / LSPOSED — WAJIB, JANGAN DIHAPUS
# ------------------------------------------------------------
# Kelas entry point dibaca LSPosed dari assets/xposed_init
# lewat REFLEKSI saat runtime. R8 tidak bisa "melihat"
# referensi tersebut, jadi tanpa aturan ini MainHook akan
# di-rename/dihapus dan modul DIANGGAP TIDAK VALID oleh LSPosed.
-keep class com.narik.terus.MainHook { *; }

# Jaga semua kelas yang mengimplementasikan antarmuka Xposed
-keep class * implements de.robv.android.xposed.IXposedHook* { *; }

# API Xposed hanya compileOnly (tidak dikemas dalam APK),
# abaikan warning kelas yang hilang saat runtime
-dontwarn de.robv.android.xposed.**

# ------------------------------------------------------------
# Umum — jaga info yang sering dipakai refleksi / library
# ------------------------------------------------------------
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-dontwarn java.lang.invoke.**

# ------------------------------------------------------------
# Google Play Services / Maps
# (AAR-nya sudah membawa consumer rules sendiri; ini jaring pengaman)
# ------------------------------------------------------------
-dontwarn com.google.android.gms.**

# ------------------------------------------------------------
# biarkan R8 merapikan modifier internal (lebih agresif)
# ------------------------------------------------------------
-allowaccessmodification
