package com.narik.terus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import java.util.Locale

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        private const val DEFAULT_ZOOM = 17f
        private const val REQ_PERMS = 1
        private const val REQ_BACKGROUND = 2

        /** Durasi animasi gabungan kompas + terbang ke titik biru (ms). */
        private const val FLY_ANIM_MS = 400
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var imgCompass: ImageView
    private lateinit var imgCenterPin: ImageView
    private lateinit var chipCoords: TextView
    private lateinit var buttonsContainer: View

    private var map: GoogleMap? = null
    private var followMode = false
    private var firstFixApplied = false
    private var askedBackgroundSettings = false
    private var locationCallback: LocationCallback? = null

    /** true = chip sudah boleh menampilkan koordinat (ada fix lokasi ATAU user pernah menggeser map). */
    private var chipActive = false

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
        buttonsContainer = findViewById(R.id.buttons_container)
        keepOverlaysClearOfSystemBars()

        // Urutan kanan bawah : autofocus -> zoom in -> zoom out
        findViewById<View>(R.id.btn_autofocus).setOnClickListener { onAutofocusTapped() }
        findViewById<View>(R.id.btn_zoom_in).setOnClickListener { zoomToMax() }
        findViewById<View>(R.id.btn_zoom_out).setOnClickListener { zoomOut() }

        // Pin & chip = saklar hide/unhide chip koordinat
        imgCenterPin.setOnClickListener { toggleCoordsChip() }
        chipCoords.setOnClickListener { toggleCoordsChip() }

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

        // Ikon kompas + chip koordinat update AGRESIF di setiap frame gerakan
        updateCompass(googleMap.cameraPosition.bearing)
        googleMap.setOnCameraMoveListener {
            updateCompass(googleMap.cameraPosition.bearing)
            updateCoordsChip()
        }

        // Geser/putar map manual = matikan mode ikuti + aktifkan chip
        googleMap.setOnCameraMoveStartedListener { reason ->
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                followMode = false
                chipActive = true
            }
        }

        // Jaminan nilai akhir tepat setelah kamera berhenti
        googleMap.setOnCameraIdleListener { updateCoordsChip() }

        if (hasLocationPermission()) enableMyLocation()
    }

    private fun updateCompass(bearing: Float) {
        imgCompass.rotation = -bearing
    }

    // ------------------------------------------------------- chip koordinat

    /** Tap pin / tap chip : sembunyikan atau tampilkan chip koordinat. */
    private fun toggleCoordsChip() {
        chipCoords.isVisible = !chipCoords.isVisible
    }

    /**
     * Isi chip dengan koordinat pusat kamera = posisi ujung pin.
     * Dipanggil tiap frame kamera bergerak; guard != mencegah
     * re-render sia-sia saat target tidak berubah (mis. pinch zoom).
     */
    private fun updateCoordsChip() {
        if (!chipActive) return // masih tampil "Menunggu lokasi…"
        val target = map?.cameraPosition?.target ?: return
        val text = String.format(
            Locale.US, "%.6f, %.6f", target.latitude, target.longitude
        )
        if (chipCoords.text != text) chipCoords.text = text
    }

    // ------------------------------------------------------------- tombol

    /**
     * Tap autofocus : DUA fungsi sekaligus dalam SATU animasi.
     * 1. KOMPAS    — map diputar kembali ke utara 0°
     * 2. AUTOFOCUS — kamera terbang ke titik biru
     * Setelah itu kamera TERUS mengikuti titik biru.
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
            // Belum ada fix: luruskan utara dulu, terbang menyusul begitu lokasi datang
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
            chipCoords.translationY = -bars.bottom.toFloat()
            insets
        }
    }
}
