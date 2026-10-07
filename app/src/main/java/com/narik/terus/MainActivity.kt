package com.narik.terus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Typeface
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        private const val FLY_ANIM_MS = 400
        private const val SECRET_TAPS_REQUIRED = 7
        private const val SECRET_TAP_TIMEOUT_MS = 2_000L
        private const val MARKER_SIZE_DP = 56f
        private const val MARKER_BOTTOM_GAP_DP = 8f
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
    private lateinit var tabChip: TextView
    private lateinit var tabSet: TextView
    private lateinit var pageChip: View
    private lateinit var pageSet: View
    private lateinit var rowTargetGrb: View
    private lateinit var rowMethodGrb: View
    private lateinit var rowTargetGjk: View
    private lateinit var rowMethodGjk: View
    private lateinit var valTargetGrb: TextView
    private lateinit var valMethodGrb: TextView
    private lateinit var valTargetGjk: TextView
    private lateinit var valMethodGjk: TextView

    private var map: GoogleMap? = null
    private var followMode = false
    private var firstFixApplied = false
    private var askedBackgroundSettings = false
    private var locationCallback: LocationCallback? = null
    private var chipActive = false
    private var secretTapCount = 0
    private var lastSecretTapAt = 0L

    private var grbMarker: Marker? = null
    private var gjkMarker: Marker? = null
    private var grbPlaying = false
    private var gjkPlaying = false
    private var showGrbChip = true
    private var showGjkChip = true

    // Konfigurasi fleksibel per channel
    private var grbTarget = MainHook.DEFAULT_TARGET_GRB
    private var gjkTarget = MainHook.DEFAULT_TARGET_GJK
    private var grbMethods = 0L
    private var gjkMethods = 0L

    private val statePrefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    // ------------------------------------------------------------ onCreate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        tabChip = findViewById(R.id.tab_chip)
        tabSet = findViewById(R.id.tab_set)
        pageChip = findViewById(R.id.page_chip)
        pageSet = findViewById(R.id.page_set)
        rowTargetGrb = findViewById(R.id.row_target_grb)
        rowMethodGrb = findViewById(R.id.row_method_grb)
        rowTargetGjk = findViewById(R.id.row_target_gjk)
        rowMethodGjk = findViewById(R.id.row_method_gjk)
        valTargetGrb = findViewById(R.id.val_target_grb)
        valMethodGrb = findViewById(R.id.val_method_grb)
        valTargetGjk = findViewById(R.id.val_target_gjk)
        valMethodGjk = findViewById(R.id.val_method_gjk)
        keepOverlaysClearOfSystemBars()

        // Muat konfigurasi tersimpan
        grbTarget = statePrefs.getString("grb_target", grbTarget) ?: grbTarget
        gjkTarget = statePrefs.getString("gjk_target", gjkTarget) ?: gjkTarget
        grbMethods = statePrefs.getLong("grb_methods", 0L)
        gjkMethods = statePrefs.getLong("gjk_methods", 0L)

        // Kanan bawah
        findViewById<View>(R.id.btn_autofocus).setOnClickListener { onAutofocusTapped() }
        findViewById<View>(R.id.btn_zoom_in).setOnClickListener { zoomToMax() }
        findViewById<View>(R.id.btn_zoom_out).setOnClickListener { zoomOut() }

        // Kiri bawah
        btnGrb.setOnClickListener { toggleGrb() }
        btnGjk.setOnClickListener { toggleGjk() }
        updateServiceButtonUi(btnGrb, badgeGrb, false)
        updateServiceButtonUi(btnGjk, badgeGjk, false)

        imgCenterPin.setOnClickListener { onPinTapped() }
        chipCoords.setOnClickListener { toggleCoordsChip() }
        btnSecret.setOnClickListener { toggleMenuPanel() }

        // Tab menu rahasia
        tabChip.setOnClickListener { switchTab(set = false) }
        tabSet.setOnClickListener { switchTab(set = true) }
        switchTab(set = false)

        // Halaman CHIP
        menuRowChipPin.setOnClickListener {
            toggleCoordsChip(); refreshMenuLabels()
        }
        menuRowChipGrb.setOnClickListener {
            showGrbChip = !showGrbChip; refreshGrbChip(); refreshMenuLabels()
        }
        menuRowChipGjk.setOnClickListener {
            showGjkChip = !showGjkChip; refreshGjkChip(); refreshMenuLabels()
        }

        // Halaman SET
        rowTargetGrb.setOnClickListener { showAppPicker("grb") }
        rowMethodGrb.setOnClickListener { showMethodPicker("grb") }
        rowTargetGjk.setOnClickListener { showAppPicker("gjk") }
        rowMethodGjk.setOnClickListener { showMethodPicker("gjk") }

        btnCloseMenu.setOnClickListener {
            menuPanel.isVisible = false
            btnSecret.isVisible = false
        }
        refreshMenuLabels()
        refreshSetLabels()

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

        updateCompass(googleMap.cameraPosition.bearing)
        googleMap.setOnCameraMoveListener {
            updateCompass(googleMap.cameraPosition.bearing)
            updateCoordsChip()
        }
        googleMap.setOnCameraIdleListener { updateCoordsChip() }
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

    private fun formatLatLng(pos: LatLng): String =
        String.format(Locale.US, "%.6f, %.6f", pos.latitude, pos.longitude)

    private fun toggleCoordsChip() {
        chipCoords.isVisible = !chipCoords.isVisible
    }

    private fun updateCoordsChip() {
        if (!chipActive) return
        val target = map?.cameraPosition?.target ?: return
        val text = formatLatLng(target)
        if (chipCoords.text != text) chipCoords.text = text
    }

    private fun refreshGrbChip() {
        chipGrb.isVisible = showGrbChip && grbMarker != null
    }

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

    private fun onPinTapped() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSecretTapAt > SECRET_TAP_TIMEOUT_MS) secretTapCount = 0
        lastSecretTapAt = now
        if (++secretTapCount >= SECRET_TAPS_REQUIRED) {
            secretTapCount = 0
            btnSecret.isVisible = !btnSecret.isVisible
            if (!btnSecret.isVisible) menuPanel.isVisible = false
        }
    }

    private fun toggleMenuPanel() {
        menuPanel.isVisible = !menuPanel.isVisible
    }

    private fun switchTab(set: Boolean) {
        pageChip.isVisible = !set
        pageSet.isVisible = set
        styleTab(tabChip, selected = !set)
        styleTab(tabSet, selected = set)
    }

    private fun styleTab(tab: TextView, selected: Boolean) {
        tab.setTextColor(
            ContextCompat.getColor(
                this, if (selected) R.color.tab_selected else R.color.tab_unselected
            )
        )
        tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

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

    // ------------------------------------------------- SET: target & metode

    private fun currentTarget(channel: String): String =
        if (channel == "grb") grbTarget else gjkTarget

    private fun methodsFor(channel: String): Long =
        if (channel == "grb") grbMethods else gjkMethods

    private fun playingFor(channel: String): Boolean =
        if (channel == "grb") grbPlaying else gjkPlaying

    private fun markerFor(channel: String): Marker? =
        if (channel == "grb") grbMarker else gjkMarker

    private fun refreshSetLabels() {
        valTargetGrb.text = grbTarget
        valMethodGrb.text = methodsLabel(grbMethods)
        valTargetGjk.text = gjkTarget
        valMethodGjk.text = methodsLabel(gjkMethods)
    }

    private fun methodsLabel(mask: Long): String {
        val active = MainHook.METHOD_DEFS
            .filter { (mask and it.first) != 0L }
            .map { it.second.substringBefore(" (") }
        return if (active.isEmpty()) getString(R.string.value_methods_core)
        else active.joinToString(", ")
    }

    /** Picker aplikasi terinstall (dengan pencarian). */
    private fun showAppPicker(channel: String) {
        val pm = packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val allItems = pm.queryIntentActivities(launchIntent, 0)
            .asSequence()
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName) null
                else "${ri.loadLabel(pm)}\n$pkg"
            }
            .sortedBy { it.lowercase() }
            .toList()

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val input = EditText(this).apply {
            hint = "Cari aplikasi…"
            setSingleLine()
        }
        val list = ListView(this).apply { divider = null }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList(allItems))
        list.adapter = adapter

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.lowercase().orEmpty()
                adapter.clear()
                adapter.addAll(allItems.filter { it.lowercase().contains(q) })
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(input)
            addView(list)
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("Target ${channel.uppercase()}")
            .setView(container)
            .setNegativeButton("Batal", null)
            .create()
        list.setOnItemClickListener { _, _, pos, _ ->
            val text = adapter.getItem(pos) ?: return@setOnItemClickListener
            dlg.dismiss()
            applyTarget(channel, text.substringAfterLast('\n'))
        }
        dlg.show()
    }

    private fun applyTarget(channel: String, pkg: String) {
        val old = currentTarget(channel)
        statePrefs.edit().putString("${channel}_target", pkg).apply()
        if (channel == "grb") grbTarget = pkg else gjkTarget = pkg

        if (old != pkg) {
            // Matikan spoof di target lama (receiver lamanya akan menerima OFF)
            sendStateTo(old, channel, false, null, methodsFor(channel))
            // Sinkronkan target baru bila prosesnya sudah berjalan
            sendStateTo(pkg, channel, playingFor(channel), markerFor(channel)?.position, methodsFor(channel))
            Toast.makeText(this, R.string.toast_target_saved, Toast.LENGTH_LONG).show()
        }
        refreshSetLabels()
    }

    /** Picker metode hook (multi-pilih). */
    private fun showMethodPicker(channel: String) {
        var sel = methodsFor(channel)
        val names = MainHook.METHOD_DEFS.map { it.second }.toTypedArray()
        val checked = MainHook.METHOD_DEFS.map { (sel and it.first) != 0L }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Metode hook ${channel.uppercase()}")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val flag = MainHook.METHOD_DEFS[which].first
                sel = if (isChecked) sel or flag else sel and flag.inv()
            }
            .setPositiveButton("Simpan") { _, _ -> applyMethods(channel, sel) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun applyMethods(channel: String, mask: Long) {
        statePrefs.edit().putLong("${channel}_methods", mask).apply()
        if (channel == "grb") grbMethods = mask else gjkMethods = mask
        // Apply live ke target (tanpa restart)
        sendStateTo(currentTarget(channel), channel, playingFor(channel), markerFor(channel)?.position, mask)
        refreshSetLabels()
    }

    // --------------------------------------------------- marker GRB / GJK

    private fun toggleGrb() {
        val googleMap = map ?: return
        grbPlaying = !grbPlaying
        if (grbPlaying) {
            val pos = googleMap.cameraPosition.target
            grbMarker = addServiceMarker(R.drawable.ic_marker_grb, pos)
            updateGrbChipText()
            persistServiceState("grb", true, pos)
        } else {
            grbMarker?.remove()
            grbMarker = null
            persistServiceState("grb", false, null)
        }
        sendStateTo(currentTarget("grb"), "grb", grbPlaying, grbMarker?.position, grbMethods)
        refreshGrbChip()
        updateServiceButtonUi(btnGrb, badgeGrb, grbPlaying)
    }

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
        sendStateTo(currentTarget("gjk"), "gjk", gjkPlaying, gjkMarker?.position, gjkMethods)
        refreshGjkChip()
        updateServiceButtonUi(btnGjk, badgeGjk, gjkPlaying)
    }

    private fun addServiceMarker(drawableRes: Int, pos: LatLng): Marker? =
        map?.addMarker(
            MarkerOptions()
                .position(pos)
                .icon(markerIconAtPinSize(drawableRes))
                .anchor(0.5f, 1f)
                .zIndex(3f)
        )

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
            sendStateTo(grbTarget, "grb", true, grbMarker?.position, grbMethods)
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
            sendStateTo(gjkTarget, "gjk", true, gjkMarker?.position, gjkMethods)
        }
    }

    // --------------------------------------------- prefs + broadcast target

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

    /** PUSH state (play + koordinat + metode) ke satu paket target. */
    private fun sendStateTo(
        targetPkg: String?,
        channel: String,
        playing: Boolean,
        pos: LatLng?,
        methods: Long
    ) {
        if (targetPkg.isNullOrEmpty()) return
        runCatching {
            sendBroadcast(
                Intent(MainHook.ACTION_STATE).setPackage(targetPkg)
                    .putExtra(MainHook.KEY_CHANNEL, channel)
                    .putExtra(MainHook.KEY_PLAY, playing)
                    .putExtra(MainHook.KEY_LAT, pos?.latitude ?: 0.0)
                    .putExtra(MainHook.KEY_LNG, pos?.longitude ?: 0.0)
                    .putExtra(MainHook.KEY_METHODS, methods)
            )
        }
    }

    // ------------------------------------------------- scaling icon marker

    private fun markerIconAtPinSize(drawableRes: Int): BitmapDescriptor {
        val density = resources.displayMetrics.density
        val targetHeightPx = (MARKER_SIZE_DP * density).toInt().coerceAtLeast(1)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, drawableRes, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) {
            return BitmapDescriptorFactory.fromResource(drawableRes)
        }

        val src = BitmapFactory.decodeResource(resources, drawableRes, null)
        val targetWidthPx = (targetHeightPx * (srcW.toFloat() / srcH)).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, targetWidthPx, targetHeightPx, true)

        val gapPx = (MARKER_BOTTOM_GAP_DP * density).toInt()
        val padded = Bitmap.createBitmap(
            scaled.width, scaled.height + gapPx, Bitmap.Config.ARGB_8888
        )
        Canvas(padded).drawBitmap(scaled, 0f, 0f, null)

        return BitmapDescriptorFactory.fromBitmap(padded)
    }

    /** Wujud tombol sesuai state. */
    private fun updateServiceButtonUi(button: View, badge: ImageView, playing: Boolean) {
        button.setBackgroundResource(
            if (playing) R.drawable.bg_fab_active else R.drawable.bg_fab_circle
        )
        badge.setImageResource(if (playing) R.drawable.ic_stop else R.drawable.ic_play)
        button.alpha = if (playing) 1f else 0.8f
    }

    // ------------------------------------------------------------- tombol

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

    private fun zoomToMax() {
        val googleMap = map ?: return
        googleMap.animateCamera(CameraUpdateFactory.zoomTo(googleMap.maxZoomLevel))
    }

    private fun zoomOut() {
        val googleMap = map ?: return
        googleMap.animateCamera(CameraUpdateFactory.zoomOut())
    }

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
        googleMap.isMyLocationEnabled = true

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
                    if (followMode) moveTo(loc)
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
