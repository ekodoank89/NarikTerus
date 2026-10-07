package com.narik.terus

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
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
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.slider.Slider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.Random

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
        private const val EARTH_RADIUS_M = 6_371_000.0
        private const val METERS_PER_DEG_LAT = 111_320.0

        private const val DOT_COLOR_GRB = 0xFFEA4335.toInt()
        private const val DOT_COLOR_GJK = 0xFF34A853.toInt()
    }

    private data class Favorite(val name: String, val lat: Double, val lng: Double)

    private class JitterConfig {
        var stepMeters = 2f      // 0.1 .. 5.0
        var maxDistMeters = 3f   // 0.1 .. 5.0 (radius dari pusat)
        var intervalSec = 4L     // 1 .. 5
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
    private lateinit var btnFavorite: View
    private lateinit var btnJitter: View
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

    // Marker pusat (STATIS), titik jitter (bergerak), lingkaran radius
    private var grbMarker: Marker? = null
    private var gjkMarker: Marker? = null
    private var grbDot: Marker? = null
    private var gjkDot: Marker? = null
    private var grbRadiusCircle: Circle? = null
    private var gjkRadiusCircle: Circle? = null

    private var grbPlaying = false
    private var gjkPlaying = false
    private var showGrbChip = true
    private var showGjkChip = true

    private var grbTarget = HookContract.DEFAULT_TARGET_GRB
    private var gjkTarget = HookContract.DEFAULT_TARGET_GJK
    private var grbMethods = 0L
    private var gjkMethods = 0L

    // Jitter
    private val jitterGrb = JitterConfig()
    private val jitterGjk = JitterConfig()
    private var grbJitterCenter: LatLng? = null
    private var gjkJitterCenter: LatLng? = null
    private var grbJitterRunning = false
    private var gjkJitterRunning = false
    private val jitterHandler = Handler(Looper.getMainLooper())
    private val random = Random()

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
        btnFavorite = findViewById(R.id.btn_favorite)
        btnJitter = findViewById(R.id.btn_jitter)
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

        grbTarget = statePrefs.getString("grb_target", grbTarget) ?: grbTarget
        gjkTarget = statePrefs.getString("gjk_target", gjkTarget) ?: gjkTarget
        grbMethods = statePrefs.getLong("grb_methods", 0L)
        gjkMethods = statePrefs.getLong("gjk_methods", 0L)
        loadJitterConfigs()

        findViewById<View>(R.id.btn_autofocus).setOnClickListener { onAutofocusTapped() }
        findViewById<View>(R.id.btn_zoom_in).setOnClickListener { zoomToMax() }
        findViewById<View>(R.id.btn_zoom_out).setOnClickListener { zoomOut() }

        btnGrb.setOnClickListener { toggleGrb() }
        btnGjk.setOnClickListener { toggleGjk() }
        updateServiceButtonUi(btnGrb, badgeGrb, false)
        updateServiceButtonUi(btnGjk, badgeGjk, false)
        btnFavorite.setOnClickListener { showFavoriteDialog() }
        btnJitter.setOnClickListener { showJitterDialog() }

        imgCenterPin.setOnClickListener { onPinTapped() }

        // Chip pin: TANPA aksi. Chip GRB/GJK: kamera terbang ke markernya.
        chipGrb.setOnClickListener { flyToServiceMarker("grb") }
        chipGjk.setOnClickListener { flyToServiceMarker("gjk") }

        btnSecret.setOnClickListener { toggleMenuPanel() }

        tabChip.setOnClickListener { switchTab(set = false) }
        tabSet.setOnClickListener { switchTab(set = true) }
        switchTab(set = false)

        menuRowChipPin.setOnClickListener {
            toggleCoordsChip(); refreshMenuLabels()
        }
        menuRowChipGrb.setOnClickListener {
            showGrbChip = !showGrbChip; refreshGrbChip(); refreshMenuLabels()
        }
        menuRowChipGjk.setOnClickListener {
            showGjkChip = !showGjkChip; refreshGjkChip(); refreshMenuLabels()
        }

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
        grbJitterRunning = false
        gjkJitterRunning = false
        jitterHandler.removeCallbacksAndMessages(null)
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
        val pos = grbDot?.position ?: grbMarker?.position ?: return
        chipGrb.text = formatLatLng(pos)
    }

    private fun updateGjkChipText() {
        val pos = gjkDot?.position ?: gjkMarker?.position ?: return
        chipGjk.text = formatLatLng(pos)
    }

    private fun flyToServiceMarker(channel: String) {
        val pos = markerFor(channel)?.position ?: return
        map?.animateCamera(CameraUpdateFactory.newLatLng(pos))
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

    private fun dotFor(channel: String): Marker? =
        if (channel == "grb") grbDot else gjkDot

    private fun circleFor(channel: String): Circle? =
        if (channel == "grb") grbRadiusCircle else gjkRadiusCircle

    private fun centerFor(channel: String): LatLng? =
        if (channel == "grb") grbJitterCenter else gjkJitterCenter

    private fun cfgFor(channel: String): JitterConfig =
        if (channel == "grb") jitterGrb else jitterGjk

    private fun refreshSetLabels() {
        valTargetGrb.text = grbTarget
        valMethodGrb.text = methodsLabel(grbMethods)
        valTargetGjk.text = gjkTarget
        valMethodGjk.text = methodsLabel(gjkMethods)
    }

    private fun methodsLabel(mask: Long): String {
        val active = HookContract.METHOD_DEFS
            .filter { (mask and it.first) != 0L }
            .map { it.second.substringBefore(" (") }
        return if (active.isEmpty()) getString(R.string.value_methods_core)
        else active.joinToString(", ")
    }

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
            sendStateTo(old, channel, false, null, methodsFor(channel))
            pushCurrentPosition(channel)
            Toast.makeText(this, R.string.toast_target_saved, Toast.LENGTH_LONG).show()
        }
        refreshSetLabels()
    }

    private fun showMethodPicker(channel: String) {
        var sel = methodsFor(channel)
        val names = HookContract.METHOD_DEFS.map { it.second }.toTypedArray()
        val checked = HookContract.METHOD_DEFS.map { (sel and it.first) != 0L }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Metode hook ${channel.uppercase()}")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val flag = HookContract.METHOD_DEFS[which].first
                sel = if (isChecked) sel or flag else sel and flag.inv()
            }
            .setPositiveButton("Simpan") { _, _ -> applyMethods(channel, sel) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun applyMethods(channel: String, mask: Long) {
        statePrefs.edit().putLong("${channel}_methods", mask).apply()
        if (channel == "grb") grbMethods = mask else gjkMethods = mask
        pushCurrentPosition(channel)
        refreshSetLabels()
    }

    // ------------------------------------------------------------ favorite

    private fun textWatcher(onChange: () -> Unit): TextWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = onChange()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }

    private fun loadFavorites(channel: String): MutableList<Favorite> {
        val out = mutableListOf<Favorite>()
        try {
            val arr = JSONArray(statePrefs.getString("favorites_$channel", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Favorite(
                        o.getString("name"),
                        o.getDouble("lat"),
                        o.getDouble("lng")
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return out
    }

    private fun persistFavorites(channel: String, list: List<Favorite>) {
        val arr = JSONArray()
        for (f in list) {
            arr.put(
                JSONObject()
                    .put("name", f.name)
                    .put("lat", f.lat)
                    .put("lng", f.lng)
            )
        }
        statePrefs.edit().putString("favorites_$channel", arr.toString()).apply()
    }

    private fun showFavoriteDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_favorite, null)

        val tabGrb = view.findViewById<TextView>(R.id.tab_fav_grb)
        val tabGjk = view.findViewById<TextView>(R.id.tab_fav_gjk)
        val pageGrb = view.findViewById<View>(R.id.page_fav_grb)
        val pageGjk = view.findViewById<View>(R.id.page_fav_gjk)

        var dlg: AlertDialog? = null

        fun bindChannel(ch: String) {
            val pageRoot = view.findViewById<View>(
                resources.getIdentifier("page_fav_$ch", "id", packageName)
            )

            fun vid(base: String) = resources.getIdentifier("${base}_$ch", "id", packageName)

            val headerPin = pageRoot.findViewById<TextView>(vid("header_pin"))
            val contentPin = pageRoot.findViewById<View>(vid("content_pin"))
            val inputNamePin = pageRoot.findViewById<EditText>(vid("input_name_pin"))
            val tvCoord = pageRoot.findViewById<TextView>(vid("coord_pin"))
            val btnSavePin = pageRoot.findViewById<Button>(vid("btn_save_pin"))
            val headerManual = pageRoot.findViewById<TextView>(vid("header_manual"))
            val contentManual = pageRoot.findViewById<View>(vid("content_manual"))
            val inputNameManual = pageRoot.findViewById<EditText>(vid("input_name_manual"))
            val inputLat = pageRoot.findViewById<EditText>(vid("input_lat_manual"))
            val inputLng = pageRoot.findViewById<EditText>(vid("input_lng_manual"))
            val btnSaveManual = pageRoot.findViewById<Button>(vid("btn_save_manual"))
            val listContainer = pageRoot.findViewById<LinearLayout>(vid("list_fav"))
            val emptyView = pageRoot.findViewById<View>(vid("empty_fav"))

            var editingIndex: Int? = null

            // Koordinat pin saat dialog dibuka (modal -> tidak berubah selama terbuka)
            val pinCoord = map?.cameraPosition?.target
            tvCoord.text = getString(
                R.string.fav_pin_coord, pinCoord?.let { formatLatLng(it) } ?: "-"
            )

            fun refreshButtons() {
                btnSavePin.isEnabled = inputNamePin.text.toString().trim().isNotEmpty()
                btnSaveManual.isEnabled =
                    inputNameManual.text.toString().trim().isNotEmpty() &&
                        inputLat.text.toString().trim().isNotEmpty() &&
                        inputLng.text.toString().trim().isNotEmpty()
            }

            fun resetManualToSave() {
                editingIndex = null
                btnSaveManual.setText(R.string.fav_save)
            }

            fun clearPinInputs() {
                inputNamePin.setText("")
                refreshButtons()
            }

            fun clearManualInputs() {
                inputNameManual.setText("")
                inputLat.setText("")
                inputLng.setText("")
                refreshButtons()
            }

            fun refreshList() {
                val favs = loadFavorites(ch)
                listContainer.removeAllViews()
                emptyView.isVisible = favs.isEmpty()
                favs.forEachIndexed { index, f ->
                    val row = layoutInflater.inflate(R.layout.row_favorite, listContainer, false)
                    row.findViewById<TextView>(R.id.fav_name).text = f.name
                    row.findViewById<TextView>(R.id.fav_coords).text =
                        formatLatLng(LatLng(f.lat, f.lng))

                    // Tap nama = play/stop channel di koordinat favorite
                    row.findViewById<View>(R.id.fav_row_click).setOnClickListener {
                        if (playingFor(ch)) stopChannel(ch)
                        else startChannel(ch, LatLng(f.lat, f.lng))
                        dlg?.dismiss()
                    }

                    // Edit: isi MANUAL + unhide + tombol jadi Update
                    row.findViewById<View>(R.id.btn_edit_fav).setOnClickListener {
                        inputNameManual.setText(f.name)
                        inputLat.setText(f.lat.toString())
                        inputLng.setText(f.lng.toString())
                        editingIndex = index
                        btnSaveManual.setText(R.string.fav_update)
                        contentManual.isVisible = true
                        refreshButtons()
                    }

                    // Hapus: dialog konfirmasi dulu
                    row.findViewById<View>(R.id.btn_delete_fav).setOnClickListener {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.fav_delete_title)
                            .setMessage(getString(R.string.fav_delete_confirm, f.name))
                            .setPositiveButton(R.string.fav_delete_yes) { _, _ ->
                                val cur = loadFavorites(ch)
                                if (index < cur.size) {
                                    cur.removeAt(index)
                                    persistFavorites(ch, cur)
                                }
                                resetManualToSave()
                                refreshList()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }

                    listContainer.addView(row)
                }
            }

            // Collapse/expand sub menu
            headerPin.setOnClickListener { contentPin.isVisible = !contentPin.isVisible }
            headerManual.setOnClickListener {
                contentManual.isVisible = !contentManual.isVisible
            }

            inputNamePin.addTextChangedListener(textWatcher { refreshButtons() })
            inputNameManual.addTextChangedListener(textWatcher { refreshButtons() })
            inputLat.addTextChangedListener(textWatcher { refreshButtons() })
            inputLng.addTextChangedListener(textWatcher { refreshButtons() })

            // Simpan DARI PIN
            btnSavePin.setOnClickListener {
                val name = inputNamePin.text.toString().trim()
                val pos = map?.cameraPosition?.target ?: return@setOnClickListener
                if (name.isEmpty()) return@setOnClickListener
                val cur = loadFavorites(ch)
                cur.add(Favorite(name, pos.latitude, pos.longitude))
                persistFavorites(ch, cur)
                clearPinInputs()
                contentPin.isVisible = false // auto-hide setelah simpan
                refreshList()
            }

            // Simpan / Update MANUAL
            btnSaveManual.setOnClickListener {
                val name = inputNameManual.text.toString().trim()
                val lat = inputLat.text.toString().trim().toDoubleOrNull()
                val lng = inputLng.text.toString().trim().toDoubleOrNull()
                if (name.isEmpty() || lat == null || lng == null) return@setOnClickListener
                val cur = loadFavorites(ch)
                val editing = editingIndex
                if (editing != null && editing < cur.size) {
                    cur[editing] = Favorite(name, lat, lng)
                } else {
                    cur.add(Favorite(name, lat, lng))
                }
                persistFavorites(ch, cur)
                clearManualInputs()
                resetManualToSave()
                contentManual.isVisible = false // auto-hide setelah simpan/update
                refreshList()
            }

            refreshButtons()
            refreshList()
        }

        bindChannel("grb")
        bindChannel("gjk")

        fun switchFavTab(ch: String) {
            pageGrb.isVisible = ch == "grb"
            pageGjk.isVisible = ch == "gjk"
            styleTab(tabGrb, ch == "grb")
            styleTab(tabGjk, ch == "gjk")
        }
        tabGrb.setOnClickListener { switchFavTab("grb") }
        tabGjk.setOnClickListener { switchFavTab("gjk") }
        switchFavTab("grb")

        dlg = AlertDialog.Builder(this)
            .setTitle(R.string.fav_title)
            .setView(view)
            .show()
    }

    // ------------------------------------------------------------- jitter

    private fun defaultJitterFor(channel: String): Triple<Float, Float, Long> =
        if (channel == "grb") Triple(2f, 3f, 4L) else Triple(3f, 4f, 5L)

    private fun loadJitterConfigs() {
        for (ch in listOf("grb", "gjk")) {
            val cfg = cfgFor(ch)
            val d = defaultJitterFor(ch)
            cfg.stepMeters = statePrefs.getFloat("${ch}_jitter_step", d.first)
                .coerceIn(0.1f, 5f)
            cfg.maxDistMeters = statePrefs.getFloat("${ch}_jitter_radius", d.second)
                .coerceIn(0.1f, 5f)
            cfg.intervalSec = statePrefs.getLong("${ch}_jitter_interval", d.third)
                .coerceIn(1L, 5L)
        }
    }

    private fun persistJitter(channel: String) {
        val cfg = cfgFor(channel)
        statePrefs.edit()
            .putFloat("${channel}_jitter_step", cfg.stepMeters)
            .putLong("${channel}_jitter_interval", cfg.intervalSec)
            .putFloat("${channel}_jitter_radius", cfg.maxDistMeters)
            .apply()
    }

    private fun showJitterDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_jitter, null)

        val tabGrb = view.findViewById<TextView>(R.id.tab_jitter_grb)
        val tabGjk = view.findViewById<TextView>(R.id.tab_jitter_gjk)
        val pageGrb = view.findViewById<View>(R.id.page_jitter_grb)
        val pageGjk = view.findViewById<View>(R.id.page_jitter_gjk)

        fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)

        fun bindChannel(ch: String) {
            val cfg = cfgFor(ch)
            val suffix = if (ch == "grb") "_grb" else "_gjk"
            val d = defaultJitterFor(ch)

            fun id(name: String) = resources.getIdentifier(name + suffix, "id", packageName)

            val lblStep = view.findViewById<TextView>(id("label_step"))
            val lblMax = view.findViewById<TextView>(id("label_maxdist"))
            val lblInt = view.findViewById<TextView>(id("label_interval"))
            val slStep = view.findViewById<Slider>(id("slider_step"))
            val slMax = view.findViewById<Slider>(id("slider_maxdist"))
            val slInt = view.findViewById<Slider>(id("slider_interval"))
            val btnDefault = view.findViewById<Button>(id("btn_default"))

            fun refreshLabels() {
                lblStep.text = getString(R.string.jitter_step_label, fmt1(cfg.stepMeters))
                lblMax.text = getString(R.string.jitter_maxdist_label, fmt1(cfg.maxDistMeters))
                lblInt.text = getString(
                    R.string.jitter_interval_label, cfg.intervalSec.toString()
                )
            }

            fun applyToSliders() {
                slStep.value = cfg.stepMeters.coerceIn(0.1f, 5f)
                slMax.value = cfg.maxDistMeters.coerceIn(0.1f, 5f)
                slInt.value = cfg.intervalSec.toFloat().coerceIn(1f, 5f)
            }

            applyToSliders()
            refreshLabels()

            slStep.addOnChangeListener { _, value, _ ->
                cfg.stepMeters = value
                persistJitter(ch)
                refreshLabels()
            }

            slMax.addOnChangeListener { _, value, _ ->
                cfg.maxDistMeters = value
                persistJitter(ch)
                refreshLabels()
                circleFor(ch)?.radius = value.toDouble()
            }

            slInt.addOnChangeListener { _, value, _ ->
                cfg.intervalSec = value.toLong()
                persistJitter(ch)
                refreshLabels()
            }

            btnDefault.setOnClickListener {
                cfg.stepMeters = d.first
                cfg.maxDistMeters = d.second
                cfg.intervalSec = d.third
                persistJitter(ch)
                applyToSliders()
                refreshLabels()
                circleFor(ch)?.radius = cfg.maxDistMeters.toDouble()
            }
        }

        bindChannel("grb")
        bindChannel("gjk")

        fun switchTab(ch: String) {
            pageGrb.isVisible = ch == "grb"
            pageGjk.isVisible = ch == "gjk"
            styleTab(tabGrb, ch == "grb")
            styleTab(tabGjk, ch == "gjk")
        }
        tabGrb.setOnClickListener { switchTab("grb") }
        tabGjk.setOnClickListener { switchTab("gjk") }
        switchTab("grb")

        AlertDialog.Builder(this)
            .setTitle(R.string.jitter_title)
            .setView(view)
            .show()
    }

    // ---------------------------------------------------- mesin jitter

    private fun startJitter(channel: String) {
        val running = if (channel == "grb") grbJitterRunning else gjkJitterRunning
        if (running) return
        if (centerFor(channel) == null) return
        if (channel == "grb") grbJitterRunning = true else gjkJitterRunning = true
        val cfg = cfgFor(channel)
        jitterHandler.postDelayed({ jitterTick(channel) }, cfg.intervalSec * 1000)
    }

    private fun stopJitter(channel: String) {
        if (channel == "grb") grbJitterRunning = false else gjkJitterRunning = false
    }

    private fun ensureJitterVisuals(channel: String) {
        val googleMap = map ?: return
        val center = centerFor(channel) ?: return
        val cfg = cfgFor(channel)

        if (dotFor(channel) == null) {
            val color = if (channel == "grb") DOT_COLOR_GRB else DOT_COLOR_GJK
            val dot = googleMap.addMarker(
                MarkerOptions()
                    .position(center)
                    .icon(dotIcon(color))
                    .anchor(0.5f, 0.5f)
                    .zIndex(4f)
            )
            if (channel == "grb") grbDot = dot else gjkDot = dot
        }
        if (circleFor(channel) == null) {
            val circle = googleMap.addCircle(
                CircleOptions()
                    .center(center)
                    .radius(cfg.maxDistMeters.toDouble())
                    .strokeWidth(2f)
                    .strokeColor(0xCC1A73E8.toInt())
                    .fillColor(0x1A1A73E8.toInt())
                    .clickable(false)
                    .zIndex(1f)
            )
            if (channel == "grb") grbRadiusCircle = circle else gjkRadiusCircle = circle
        }
    }

    private fun clearJitterVisuals(channel: String) {
        if (channel == "grb") {
            grbDot?.remove(); grbDot = null
            grbRadiusCircle?.remove(); grbRadiusCircle = null
        } else {
            gjkDot?.remove(); gjkDot = null
            gjkRadiusCircle?.remove(); gjkRadiusCircle = null
        }
    }

    private fun jitterTick(channel: String) {
        val running = if (channel == "grb") grbJitterRunning else gjkJitterRunning
        if (!running) return
        val cfg = cfgFor(channel)
        val dot = dotFor(channel) ?: return
        val center = centerFor(channel) ?: return

        val cur = dot.position
        val dist = random.nextDouble() * cfg.stepMeters
        val bearing = Math.toRadians(random.nextDouble() * 360.0)
        val dLat = dist * Math.cos(bearing) / METERS_PER_DEG_LAT
        val dLng = dist * Math.sin(bearing) /
            (METERS_PER_DEG_LAT * Math.cos(Math.toRadians(center.latitude)))
        var nLat = cur.latitude + dLat
        var nLng = cur.longitude + dLng

        val fromCenter = haversineMeters(center, nLat, nLng)
        if (fromCenter > cfg.maxDistMeters && fromCenter > 0.0) {
            val scale = cfg.maxDistMeters / fromCenter
            nLat = center.latitude + (nLat - center.latitude) * scale
            nLng = center.longitude + (nLng - center.longitude) * scale
        }

        val newPos = LatLng(nLat, nLng)
        dot.position = newPos
        if (channel == "grb") updateGrbChipText() else updateGjkChipText()

        statePrefs.edit()
            .putString("${channel}_lat", newPos.latitude.toString())
            .putString("${channel}_lng", newPos.longitude.toString())
            .apply()
        sendStateTo(currentTarget(channel), channel, true, newPos, methodsFor(channel))

        jitterHandler.postDelayed({ jitterTick(channel) }, cfg.intervalSec * 1000)
    }

    private fun haversineMeters(a: LatLng, lat: Double, lng: Double): Double {
        val dLat = Math.toRadians(lat - a.latitude)
        val dLng = Math.toRadians(lng - a.longitude)
        val s = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(lat)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(s))
    }

    // --------------------------------------------------- play/stop channel

    /** Mulai channel pada posisi tertentu (dari pin ATAU dari favorite). */
    private fun startChannel(channel: String, pos: LatLng) {
        val googleMap = map ?: return
        if (channel == "grb") {
            grbPlaying = true
            if (grbMarker == null) {
                grbMarker = addServiceMarker(R.drawable.ic_marker_grb, pos)
            } else {
                grbMarker?.position = pos
            }
            updateGrbChipText()
            persistServiceState("grb", true, pos)
            grbJitterCenter = pos
            ensureJitterVisuals("grb")
            grbDot?.position = pos
            grbRadiusCircle?.center = pos
            grbRadiusCircle?.radius = jitterGrb.maxDistMeters.toDouble()
            stopJitter("grb")
            startJitter("grb")
            refreshGrbChip()
            updateServiceButtonUi(btnGrb, badgeGrb, true)
            val p = grbDot?.position ?: pos
            sendStateTo(grbTarget, "grb", true, p, grbMethods)
        } else {
            gjkPlaying = true
            if (gjkMarker == null) {
                gjkMarker = addServiceMarker(R.drawable.ic_marker_gjk, pos)
            } else {
                gjkMarker?.position = pos
            }
            updateGjkChipText()
            persistServiceState("gjk", true, pos)
            gjkJitterCenter = pos
            ensureJitterVisuals("gjk")
            gjkDot?.position = pos
            gjkRadiusCircle?.center = pos
            gjkRadiusCircle?.radius = jitterGjk.maxDistMeters.toDouble()
            stopJitter("gjk")
            startJitter("gjk")
            refreshGjkChip()
            updateServiceButtonUi(btnGjk, badgeGjk, true)
            val p = gjkDot?.position ?: pos
            sendStateTo(gjkTarget, "gjk", true, p, gjkMethods)
        }
    }

    /** Hentikan channel + pastikan OFF terkirim ke target. */
    private fun stopChannel(channel: String) {
        if (channel == "grb") {
            grbPlaying = false
            stopJitter("grb")
            clearJitterVisuals("grb")
            grbMarker?.remove()
            grbMarker = null
            grbJitterCenter = null
            persistServiceState("grb", false, null)
            refreshGrbChip()
            updateServiceButtonUi(btnGrb, badgeGrb, false)
            sendStateTo(grbTarget, "grb", false, null, grbMethods)
        } else {
            gjkPlaying = false
            stopJitter("gjk")
            clearJitterVisuals("gjk")
            gjkMarker?.remove()
            gjkMarker = null
            gjkJitterCenter = null
            persistServiceState("gjk", false, null)
            refreshGjkChip()
            updateServiceButtonUi(btnGjk, badgeGjk, false)
            sendStateTo(gjkTarget, "gjk", false, null, gjkMethods)
        }
    }

    private fun toggleGrb() {
        if (grbPlaying) stopChannel("grb")
        else startChannel("grb", map?.cameraPosition?.target ?: return)
    }

    private fun toggleGjk() {
        if (gjkPlaying) stopChannel("gjk")
        else startChannel("gjk", map?.cameraPosition?.target ?: return)
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
        if (statePrefs.getBoolean("grb_play", false)) {
            val lat = statePrefs.getString("grb_lat", null)?.toDoubleOrNull()
            val lng = statePrefs.getString("grb_lng", null)?.toDoubleOrNull()
            if (lat != null && lng != null) startChannel("grb", LatLng(lat, lng))
        }
        if (statePrefs.getBoolean("gjk_play", false)) {
            val lat = statePrefs.getString("gjk_lat", null)?.toDoubleOrNull()
            val lng = statePrefs.getString("gjk_lng", null)?.toDoubleOrNull()
            if (lat != null && lng != null) startChannel("gjk", LatLng(lat, lng))
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

    private fun pushCurrentPosition(channel: String) {
        if (!playingFor(channel)) return
        val pos = dotFor(channel)?.position ?: markerFor(channel)?.position ?: return
        sendStateTo(currentTarget(channel), channel, true, pos, methodsFor(channel))
    }

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
                Intent(HookContract.ACTION_STATE).setPackage(targetPkg)
                    .putExtra(HookContract.KEY_CHANNEL, channel)
                    .putExtra(HookContract.KEY_PLAY, playing)
                    .putExtra(HookContract.KEY_LAT, pos?.latitude ?: 0.0)
                    .putExtra(HookContract.KEY_LNG, pos?.longitude ?: 0.0)
                    .putExtra(HookContract.KEY_METHODS, methods)
            )
        }
    }

    // ------------------------------------------------- ikon marker & titik

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

    private fun dotIcon(color: Int): BitmapDescriptor {
        val density = resources.displayMetrics.density
        val size = (10 * density).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - paint.strokeWidth / 2f, paint)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

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
