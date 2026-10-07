package com.narik.terus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val DEFAULT_ZOOM = 17f
        private const val REQ_PERMS = 1
        private const val REQ_BACKGROUND = 2

        /** Durasi animasi gabungan kompas + terbang ke titik biru (ms). */
        private const val FLY_ANIM_MS = 400

        /** Konfigurasi menu rahasia ala Developer Options. */
        private const val SECRET_TAPS_REQUIRED = 7
        private const val SECRET_TAP_TIMEOUT_MS = 2_000L

        /** Tinggi marker GRB/GJK = tinggi tampilan pin (56dp). */
        private const val MARKER_SIZE_DP = 56f

        /** Celah transparan di bawah marker agar titik biru tidak tertutup. */
        private const val MARKER_BOTTOM_GAP_DP = 8f

        /** Nama file prefs yang dibaca MainHook via XSharedPreferences. */
        private const val PREFS_NAME = "narik_state"
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var imgCompass: ImageView
    private lateinit var imgCenterPin: ImageView
    private lateinit var chipCoords: TextView
    private lateinit var chipGrb: TextView
    private lateinit var chipGjk: TextView
    private lateinit var buttonsContainer: View
    private lateinit var serviceButtons: View
    private lateinit var btnGrb: View
    private lateinit var btnGjk: View
    private lateinit var badgeGrb: ImageView
    private lateinit var badgeGjk: ImageView
    private lateinit var btnSecret: ImageView
    private lateinit var menuPanel: View
    private lateinit var menuRowChipPin: TextView
    private lateinit var menuRowChipGrb: TextView
    private lateinit var menuRowChipGjk: TextView
    private lateinit var btnCloseMenu: ImageView

    private var map: GoogleMap? = null
    private var followMode = false
    private var firstFixApplied = false
    private var askedBackgroundSettings = false
    private var locationCallback: LocationCallback? = null

    /** true = chip sudah boleh menampilkan koordinat (ada fix lokasi ATAU user pernah menggeser map). */
    private var chipActive = false

    /** Penghitung tap rahasia. */
    private var secretTapCount = 0
    private var lastSecretTapAt = 0L

    /** Marker layanan + status play. */
    private var grbMarker: Marker? = null
    private var gjkMarker: Marker? = null
    private var grbPlaying = false
    private var gjkPlaying = false

    /** Toggle chip koordinat GRB/GJK dari menu rahasia (default aktif). */
    private var showGrbChip = true
    private var showGjkChip = true

    /** Prefs state yang dibaca hook di proses aplikasi target. */
    private val statePrefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    // ------------------------------------------------------------ onCreate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Peta tampil penuh sampai tepi layar
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        imgCompass = findViewById(R.id.img_compass)
        imgCenterPin = findViewById(R.id.img_center_pin)
        chipCoords = findViewById(R.id.chip_coords)
        chipGrb = findViewById(R.id.chip_coords_grb)
        chipGjk = findViewById(R.id.chip_coords_gjk)
        buttonsContainer = findViewById(R.id.buttons_container)
        serviceButtons = findViewById(R.id.service_buttons)
        btnGrb = findViewById(R.id.btn_grb)
        btnGjk = findViewById(R.id.btn_gjk)
        badgeGrb = findViewById(R.id.badge_grb)
        badgeGjk = findViewById(R.id.badge_gjk)
        btnSecret = findViewById(R.id.btn_secret)
        menuPanel = findViewById(R.id.menu_panel)
        menuRowChipPin = findViewById(R.id.menu_row_chip_pin)
        menuRowChipGrb = findViewById(R.id.menu_row_chip_grb)
        menuRowChipGjk = findViewById(R.id.menu_row_chip_gjk)
        btnCloseMenu = findViewById(R.id.btn_close_menu)
        keepOverlaysClearOfSystemBars()

        // Kanan bawah : (rahasia) -> autofocus -> zoom in -> zoom out
        findViewById<View>(R.id.btn_autofocus).setOnClickListener { onAutofocusTapped() }
        findViewById<View>(R.id.btn_zoom_in).setOnClickListener { zoomToMax() }
        findViewById<View>(R.id.btn_zoom_out).setOnClickListener { zoomOut() }

        // Kiri bawah : play/stop marker GRB & GJK
        btnGrb.setOnClickListener { toggleGrb() }
        btnGjk.setOnClickListener { toggleGjk() }
        updateServiceButtonUi(btnGrb, badgeGrb, false)
        updateServiceButtonUi(btnGjk, badgeGjk, false)

        // Pin = pemicu rahasia 7x tap (senyap, tanpa info apa pun)
        imgCenterPin.setOnClickListener { onPinTapped() }

        // Chip pin: tap = sembunyikan (munculkan lagi lewat menu rahasia)
        chipCoords.setOnClickListener { toggleCoordsChip() }

        // Icon NT = buka/tutup panel menu rahasia
        btnSecret.setOnClickListener { toggleMenuPanel() }

        // Baris-baris menu rahasia (hanya 3 toggle chip)
        menuRowChipPin.setOnClickListener {
            toggleCoordsChip()
            refreshMenuLabels()
        }
        menuRowChipGrb.setOnClickListener {
            showGrbChip = !showGrbChip
            refreshGrbChip()
            refreshMenuLabels()
        }
        menuRowChipGjk.setOnClickListener {
            showGjkChip = !showGjkChip
            refreshGjkChip()
            refreshMenuLabels()
        }

        // X : tutup menu + sembunyikan icon rahasia (kembali seperti semula)
        btnCloseMenu.setOnClickListener {
            menuPanel.isVisible = false
            btnSecret.isVisible = false
        }
        refreshMenuLabels()

        (supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment)
            .getMapAsync(this)

        requestMainPermissions()
    }

    override fun onDestroy() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        super.onDestroy()
    }

    // --------------------------------------------------------------- peta

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

        // Hapus SEMUA tombol bawaan Google Maps
        googleMap.uiSettings.apply {
            isZoomControlsEnabled = false
            isCompassEnabled = false
            isMyLocationButtonEnabled = false
            isMapToolbarEnabled = false
            isIndoorLevelPickerEnabled = false
            isZoomGesturesEnabled = true
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
        }

        // Ikon kompas + chip koordinat pin update AGRESIF di setiap frame gerakan
        updateCompass(googleMap.cameraPosition.bearing)
        googleMap.setOnCameraMoveListener {
            updateCompass(googleMap.cameraPosition.bearing)
            updateCoordsChip()
        }

        // Jaminan nilai akhir tepat setelah kamera berhenti
        googleMap.setOnCameraIdleListener { updateCoordsChip() }

        // Geser/putar map manual = matikan mode ikuti + aktifkan chip pin
        googleMap.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                followMode = false
                chipActive = true
            }
        }

        restoreServiceStates()
        if (hasLocationPermission()) enableMyLocation()
    }

    private fun updateCompass(bearing: Float) {
        imgCompass.rotation = -bearing
    }

    // ------------------------------------------------------- chip koordinat

    /** Format koordinat standar: 6 desimal, Locale.US. */
    private fun formatLatLng(pos: LatLng): String =
        String.format(Locale.US, "%.6f, %.6f", pos.latitude, pos.longitude)

    /** Sembunyikan/tampilkan chip koordinat pin (dari tap chip / menu rahasia). */
    private fun toggleCoordsChip() {
        chipCoords.isVisible = !chipCoords.isVisible
    }

    /**
     * Isi chip pin dengan koordinat pusat kamera = posisi ujung pin.
     * Dipanggil tiap frame kamera bergerak; guard != mencegah re-render sia-sia.
     */
    private fun updateCoordsChip() {
        if (!chipActive) return // masih tampil "Menunggu lokasi…"
        val target = map?.cameraPosition?.target ?: return
        val text = formatLatLng(target)
        if (chipCoords.text != text) chipCoords.text = text
    }

    /** Chip GRB tampil hanya jika toggle AKTIF dan markernya ada. */
    private fun refreshGrbChip() {
        chipGrb.isVisible = showGrbChip && grbMarker != null
    }

    /** Chip GJK tampil hanya jika toggle AKTIF dan markernya ada. */
    private fun refreshGjkChip() {
        chipGjk.isVisible = showGjkChip && gjkMarker != null
    }

    private fun updateGrbChipText() {
        grbMarker?.position?.let { chipGrb.text = formatLatLng(it) }
    }

    private fun updateGjkChipText() {
        gjkMarker?.position?.let { chipGjk.text = formatLatLng(it) }
    }

    // -------------------------------------------------- menu rahasia (7x tap)

    /**
     * Hitung tap pin secara SENYAP. 7x tap berturut-turut (jeda < 2 dtk)
     * menampilkan/menyembunyikan icon menu rahasia. Tanpa dialog/toast.
     */
    private fun onPinTapped() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSecretTapAt > SECRET_TAP_TIMEOUT_MS) secretTapCount = 0
        lastSecretTapAt = now
        if (++secretTapCount >= SECRET_TAPS_REQUIRED) {
            secretTapCount = 0
            btnSecret.isVisible = !btnSecret.isVisible
            if (!btnSecret.isVisible) menuPanel.isVisible = false // tutup juga menunya
        }
    }

    private fun toggleMenuPanel() {
        menuPanel.isVisible = !menuPanel.isVisible
    }

    /** Sinkronkan label state di menu rahasia. */
    private fun refreshMenuLabels() {
        menuRowChipPin.text = getString(
            R.string.menu_chip_pin, if (chipCoords.isVisible) "AKTIF" else "MATI"
        )
        menuRowChipGrb.text = getString(
            R.string.menu_chip_grb, if (showGrbChip) "AKTIF" else "MATI"
        )
        menuRowChipGjk.text = getString(
            R.string.menu_chip_gjk, if (showGjkChip) "AKTIF" else "MATI"
        )
    }

    // --------------------------------------------------- marker GRB / GJK

    /**
     * Play  : patok marker GRB pada KOORDINAT PIN SAAT INI (pusat kamera)
     *         + tulis state ke prefs agar hook di proses target meng-spoof
     *         lokasi ke koordinat ini.
     * Stop  : hapus marker + matikan spoofing di proses target.
     */
    private fun toggleGrb() {
        val googleMap = map ?: return
        grbPlaying = !grbPlaying
        if (grbPlaying) {
            val pos = googleMap.cameraPosition.target // koordinat pin saat play
            grbMarker = addServiceMarker(R.drawable.ic_marker_grb, pos)
            updateGrbChipText()
            persistServiceState("grb", true, pos)
        } else {
            grbMarker?.remove()
            grbMarker = null
            persistServiceState("grb", false, null)
        }
        refreshGrbChip()
        updateServiceButtonUi(btnGrb, badgeGrb, grbPlaying)
    }

    /** Sama seperti GRB, untuk proses com.khalnadj.khaledhabbachi.gps. */
    private fun toggleGjk() {
        val googleMap = map ?: return
        gjkPlaying = !gjkPlaying
        if (gjkPlaying) {
            val pos = googleMap.cameraPosition.target
            gjkMarker = addServiceMarker(R.drawable.ic_marker_gjk, pos)
            updateGjkChipText()
            persistServiceState("gjk", true, pos)
        } else {
            gjkMarker?.remove()
            gjkMarker = null
            persistServiceState("gjk", false, null)
        }
        refreshGjkChip()
        updateServiceButtonUi(btnGjk, badgeGjk, gjkPlaying)
    }

    /** Buat marker layanan: ukuran = pin, anchor bawah-tengah, gap di bawah. */
    private fun addServiceMarker(drawableRes: Int, pos: LatLng): Marker? =
        map?.addMarker(
            MarkerOptions()
                .position(pos)
                .icon(markerIconAtPinSize(drawableRes))
                .anchor(0.5f, 1f) // anchor bawah tengah
                .zIndex(3f)
        )

    /**
     * Pulihkan state play saat aplikasi dibuka ulang (mis. setelah proses
     * mati): marker + tombol + chip dibuat ulang dari prefs. Spoofing di
     * proses target tidak pernah berhenti selama play=true di prefs.
     */
    private fun restoreServiceStates() {
        val googleMap = map ?: return

        if (statePrefs.getBoolean("grb_play", false)) {
            grbPlaying = true
            val lat = statePrefs.getString("grb_lat", null)?.toDoubleOrNull()
            val lng = statePrefs.getString("grb_lng", null)?.toDoubleOrNull()
            if (lat != null && lng != null) {
                grbMarker = addServiceMarker(R.drawable.ic_marker_grb, LatLng(lat, lng))
            }
            updateGrbChipText()
            refreshGrbChip()
            updateServiceButtonUi(btnGrb, badgeGrb, true)
        }

        if (statePrefs.getBoolean("gjk_play", false)) {
            gjkPlaying = true
            val lat = statePrefs.getString("gjk_lat", null)?.toDoubleOrNull()
            val lng = statePrefs.getString("gjk_lng", null)?.toDoubleOrNull()
            if (lat != null && lng != null) {
                gjkMarker = addServiceMarker(R.drawable.ic_marker_gjk, LatLng(lat, lng))
            }
            updateGjkChipText()
            refreshGjkChip()
            updateServiceButtonUi(btnGjk, badgeGjk, true)
        }
    }

    // --------------------------------------------- prefs untuk hook target

    /**
     * Tulis state play/stop + koordinat marker agar dibaca MainHook
     * di proses aplikasi target (via XSharedPreferences).
     */
    private fun persistServiceState(key: String, playing: Boolean, pos: LatLng?) {
        val editor = statePrefs.edit()
            .putBoolean("${key}_play", playing)
        if (pos != null) {
            editor.putString("${key}_lat", pos.latitude.toString())
            editor.putString("${key}_lng", pos.longitude.toString())
        }
        editor.apply()
        makeStatePrefsWorldReadable()
    }

    /**
     * Buka akses baca file prefs agar XSharedPreferences di proses target
     * dapat membacanya (dibantu SELinux patch LSPosed).
     */
    private fun makeStatePrefsWorldReadable() {
        try {
            val dataDir = filesDir.parentFile ?: return
            dataDir.setExecutable(true, false)
            val spDir = File(dataDir, "shared_prefs")
            spDir.setExecutable(true, false)
            File(spDir, "$PREFS_NAME.xml").setReadable(true, false)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------- scaling icon marker

    /**
     * Skalakan PNG marker agar tingginya = MARKER_SIZE_DP (56dp, sama dengan pin),
     * lalu tambahkan celah transparan MARKER_BOTTOM_GAP_DP di bawahnya.
     */
    private fun markerIconAtPinSize(drawableRes: Int): BitmapDescriptor {
        val density = resources.displayMetrics.density
        val targetHeightPx = (MARKER_SIZE_DP * density).toInt().coerceAtLeast(1)

        // Baca dimensi asli tanpa meng decode penuh
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, drawableRes, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) {
            return BitmapDescriptorFactory.fromResource(drawableRes) // fallback aman
        }

        val src = BitmapFactory.decodeResource(resources, drawableRes, null)
        val targetWidthPx = (targetHeightPx * (srcW.toFloat() / srcH)).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, targetWidthPx, targetHeightPx, true)

        // Bitmap baru lebih tinggi (asli + gap); area baru otomatis transparan
        val gapPx = (MARKER_BOTTOM_GAP_DP * density).toInt()
        val padded = Bitmap.createBitmap(
            scaled.width, scaled.height + gapPx, Bitmap.Config.ARGB_8888
        )
        Canvas(padded).drawBitmap(scaled, 0f, 0f, null)

        return BitmapDescriptorFactory.fromBitmap(padded)
    }

    /** Wujud tombol sesuai state: hijau + ⏹ saat jalan, putih + ▶ saat mati. */
    private fun updateServiceButtonUi(button: View, badge: ImageView, playing: Boolean) {
        button.setBackgroundResource(
            if (playing) R.drawable.bg_fab_active else R.drawable.bg_fab_circle
        )
        badge.setImageResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play)
        button.alpha = if (playing) 1f else 0.8f
    }

    // ------------------------------------------------------------- tombol

    /**
     * Tap autofocus : DUA fungsi sekaligus dalam SATU animasi.
     * 1. KOMPAS    — map diputar kembali ke utara 0°
     * 2. AUTOFOCUS — kamera terbang ke titik biru, mendarat di zoom 17
     */
    @SuppressLint("MissingPermission")
    private fun onAutofocusTapped() {
        val googleMap = map ?: return
        if (!hasLocationPermission()) {
            requestMainPermissions()
            return
        }
        followMode = true

        val myLocation: Location? = googleMap.myLocation
        if (myLocation != null) {
            flyNorthTo(myLocation)
        } else {
            animateBearing(0f)
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && followMode) flyNorthTo(loc)
            }
            startLocationUpdates()
        }
    }

    /** Satu animasi gabungan: target = titik biru, bearing = 0° utara, selalu mendarat di zoom 17. */
    private fun flyNorthTo(location: Location) {
        val googleMap = map ?: return
        googleMap.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(googleMap.cameraPosition)
                    .target(LatLng(location.latitude, location.longitude))
                    .zoom(DEFAULT_ZOOM)
                    .bearing(0f)
                    .build()
            ),
            FLY_ANIM_MS,
            null
        )
    }

    /** Putar kamera ke bearing tertentu tanpa mengubah posisi/zoom. */
    private fun animateBearing(bearing: Float) {
        val googleMap = map ?: return
        googleMap.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(googleMap.cameraPosition)
                    .bearing(bearing)
                    .build()
            ),
            FLY_ANIM_MS,
            null
        )
    }

    /** Zoom in : langsung lompat ke zoom MAKSIMAL. */
    private fun zoomToMax() {
        val googleMap = map ?: return
        googleMap.animateCamera(CameraUpdateFactory.zoomTo(googleMap.maxZoomLevel))
    }

    /** Zoom out : default, mundur satu tingkat zoom. */
    private fun zoomOut() {
        val googleMap = map ?: return
        googleMap.animateCamera(CameraUpdateFactory.zoomOut())
    }

    /** Gerakkan kamera ke lokasi (dipakai saat mode ikuti aktif). */
    private fun moveTo(location: Location) {
        val googleMap = map ?: return
        val target = LatLng(location.latitude, location.longitude)
        val zoom = maxOf(googleMap.cameraPosition.zoom, DEFAULT_ZOOM)
        googleMap.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder(googleMap.cameraPosition)
                    .target(target)
                    .zoom(zoom)
                    .build()
            )
        )
    }

    // -------------------------------------------------------------- lokasi

    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        val googleMap = map ?: return
        googleMap.isMyLocationEnabled = true // titik biru

        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && !firstFixApplied) {
                firstFixApplied = true
                chipActive = true
                googleMap.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(loc.latitude, loc.longitude), DEFAULT_ZOOM
                    )
                )
            }
        }
        startLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission() || locationCallback != null) return

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1000L
        ).setMinUpdateDistanceMeters(1f).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    if (followMode) moveTo(loc) // kamera terus mengikuti titik biru
                }
            }
        }
        fusedLocationClient.requestLocationUpdates(request, locationCallback!!, mainLooper)
    }

    // ----------------------------------------------------------- permission

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /** Izin lokasi + izin notifikasi (Android 13+). */
    private fun requestMainPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_PERMS)
    }

    /** Izin "Selalu izinkan" (lokasi latar belakang). */
    private fun requestBackgroundPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!askedBackgroundSettings) {
                askedBackgroundSettings = true
                Toast.makeText(
                    this,
                    "Buka Izin Lokasi → pilih \"Selalu izinkan\"",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQ_BACKGROUND
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_PERMS -> if (hasLocationPermission()) {
                enableMyLocation()
                requestBackgroundPermissionIfNeeded()
            }
            REQ_BACKGROUND -> { /* dipakai saat modul meng-hook di latar belakang */ }
        }
    }

    // ---------------------------------------------------------------- insets

    /** Angkat chip & tombol di atas navigation bar (mode edge-to-edge). */
    private fun keepOverlaysClearOfSystemBars() {
        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            buttonsContainer.translationX = -bars.right.toFloat()
            buttonsContainer.translationY = -bars.bottom.toFloat()
            serviceButtons.translationX = bars.left.toFloat()
            serviceButtons.translationY = -bars.bottom.toFloat()
            chipCoords.translationY = -bars.bottom.toFloat()
            chipGrb.translationY = -bars.bottom.toFloat()
            chipGjk.translationY = -bars.bottom.toFloat()
            insets
        }
    }
}
