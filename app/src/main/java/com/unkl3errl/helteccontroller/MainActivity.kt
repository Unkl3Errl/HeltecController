package com.unkl3errl.helteccontroller

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.unkl3errl.helteccontroller.bruce.BruceApiClient
import com.unkl3errl.helteccontroller.bruce.BruceNetworkManager
import com.unkl3errl.helteccontroller.bruce.PhoneWifiObservation
import com.unkl3errl.helteccontroller.connection.PersistentDeviceConnections
import com.unkl3errl.helteccontroller.connection.PersistentUsbKind
import com.unkl3errl.helteccontroller.connection.AndroidStorageRouting
import com.unkl3errl.helteccontroller.connection.FirmwareBleDevice
import com.unkl3errl.helteccontroller.connection.FirmwareBleScanner
import com.unkl3errl.helteccontroller.connection.hasBluetoothPermissions
import com.unkl3errl.helteccontroller.detection.DetectionSource
import com.unkl3errl.helteccontroller.detection.FirmwareDetection
import com.unkl3errl.helteccontroller.detection.FirmwareIdentity
import com.unkl3errl.helteccontroller.detection.FirmwareKind
import com.unkl3errl.helteccontroller.detection.UsbFirmwareDetector
import com.unkl3errl.helteccontroller.firmware.Esp32S3BootloaderFlasher
import com.unkl3errl.helteccontroller.firmware.FirmwareCatalog
import com.unkl3errl.helteccontroller.firmware.FirmwareImageRepository
import com.unkl3errl.helteccontroller.firmware.FirmwareRelease
import com.unkl3errl.helteccontroller.firmware.FirmwareVersion
import com.unkl3errl.helteccontroller.firmware.FirmwareUpdateJobService
import com.unkl3errl.helteccontroller.firmware.UpstreamRelease
import com.unkl3errl.helteccontroller.ghost.GhostApiClient
import com.unkl3errl.helteccontroller.usb.UsbDeviceRegistry
import com.unkl3errl.helteccontroller.usb.UsbDeviceTarget
import java.io.File
import java.util.EnumMap
import java.util.concurrent.Executors

class MainActivity :
    Activity(),
    BruceNetworkManager.Listener,
    AndroidWifiFieldScanner.Listener,
    UsbFirmwareDetector.Listener,
    PersistentDeviceConnections.Listener {
    companion object {
        private const val WIFI_PERMISSION_REQUEST = 2001
        private const val BRUCE_EXPORT_REQUEST = 2002
        private const val PHONE_GPS_PERMISSION_REQUEST = 2003
        private const val PHONE_WIFI_PERMISSION_REQUEST = 2004
        private const val SESSION_NOTIFICATION_PERMISSION_REQUEST = 2005
        private const val BLUETOOTH_PERMISSION_REQUEST = 2006
        private const val MARAUDER_EXPORT_REQUEST = 3001
        private const val GHOST_EXPORT_REQUEST = 4001
        private const val ANDROID_STORAGE_TREE_REQUEST = 4002
        private const val STATE_BRUCE_EXPORT_NAME = "pendingBruceExportName"
        private const val STATE_BRUCE_EXPORT_PATH = "pendingBruceExportPath"
        private const val STATE_BRUCE_EXPORT_DEVICE = "pendingBruceExportDevice"
        private const val STATE_MARAUDER_EXPORT_NAME = "pendingMarauderExportName"
        private const val STATE_MARAUDER_EXPORT_PATH = "pendingMarauderExportPath"
        private const val STATE_GHOST_EXPORT_NAME = "pendingGhostExportName"
        private const val STATE_GHOST_EXPORT_PATH = "pendingGhostExportPath"
        private const val DEFAULT_BRUCENET_SSID = "BruceNet"
        private const val DEFAULT_BRUCENET_PASSWORD = "brucenet"
        private const val DEFAULT_BRUCE_URL = "http://172.0.0.1"
        private const val DEFAULT_GHOSTNET_SSID = "GhostNet"
        private const val DEFAULT_GHOSTNET_PASSWORD = "GhostNet"
        private const val DEFAULT_GHOST_URL = "http://192.168.4.1"
        private const val SESSION_PREFS = "persistent_device_session"
        private const val PREF_FIRMWARE_KIND = "firmware_kind"
        private const val PREF_DETECTION_SOURCE = "detection_source"
        private const val PREF_FIRMWARE_VERSION = "firmware_version"
        private const val PREF_FIRMWARE_COMMIT = "firmware_commit"
        private const val MENU_DETECT_USB = 5001
        private const val MENU_CONNECT_BRUCENET = 5002
        private const val MENU_CONNECT_GHOSTNET = 5003
        private const val MENU_CHOOSE_ANDROID_STORAGE = 5004
        private const val MENU_SYNC_ANDROID_STORAGE = 5005
        private const val RELEASE_NOTICE_PREFS = "firmware_release_notices"
        private const val FIRMWARE_REFRESH_INTERVAL_MS = 5 * 60_000L
        private const val FIRMWARE_RESUME_REFRESH_AGE_MS = 30_000L
    }

    private lateinit var globalStatus: TextView
    private lateinit var appSubtitle: TextView
    private lateinit var detectionStatus: TextView
    private lateinit var androidStorageStatus: TextView
    private lateinit var container: FrameLayout
    private lateinit var deviceTabs: LinearLayout
    private lateinit var tabBruce: Button
    private lateinit var tabGhost: Button
    private lateinit var tabMarauder: Button
    private lateinit var bruceView: View
    private lateinit var ghostView: View
    private lateinit var marauderView: View
    private lateinit var bruceController: BruceScreenController
    private lateinit var ghostController: GhostScreenController
    private lateinit var marauderController: MarauderScreenController
    private lateinit var networkManager: BruceNetworkManager
    private lateinit var usbDetector: UsbFirmwareDetector
    private lateinit var phoneLocationManager: LocationManager
    private lateinit var phoneWifiScanner: AndroidWifiFieldScanner
    private lateinit var firmwareBleScanner: FirmwareBleScanner
    private lateinit var firmwareRepository: FirmwareImageRepository
    private lateinit var bootloaderFlasher: Esp32S3BootloaderFlasher
    private lateinit var bruceFirmwareStatus: TextView
    private lateinit var ghostFirmwareStatus: TextView
    private lateinit var marauderFirmwareStatus: TextView
    private lateinit var flashBruceFirmware: Button
    private lateinit var flashGhostFirmware: Button
    private lateinit var flashMarauderFirmware: Button
    private lateinit var bruceUsbTargetStatus: TextView
    private lateinit var ghostUsbTargetStatus: TextView
    private lateinit var marauderUsbTargetStatus: TextView
    private lateinit var selectBruceUsbTarget: Button
    private lateinit var selectGhostUsbTarget: Button
    private lateinit var selectMarauderUsbTarget: Button

    private val client = BruceApiClient()
    private val ghostClient = GhostApiClient()
    private val detectorExecutor = Executors.newSingleThreadExecutor()
    private val firmwareRefreshHandler = Handler(Looper.getMainLooper())
    private val firmwareRefreshRunnable = object : Runnable {
        override fun run() {
            if (::firmwareRepository.isInitialized) {
                firmwareRepository.refreshIfStale(FIRMWARE_REFRESH_INTERVAL_MS)
            }
            firmwareRefreshHandler.postDelayed(this, FIRMWARE_REFRESH_INTERVAL_MS)
        }
    }
    private var detectedFirmware: FirmwareDetection? = null
    private val detectedFirmwares = EnumMap<FirmwareKind, FirmwareDetection>(FirmwareKind::class.java)
    private val deviceDetections = linkedMapOf<String, FirmwareDetection>()
    private val displayedConnectionIds = EnumMap<PersistentUsbKind, String>(PersistentUsbKind::class.java)
    private var pendingWifi: Pair<String, String>? = null
    private var pendingBruceNetDetection = false
    private var pendingGhostNetDetection = false
    private var pendingBruceExportName: String? = null
    private var pendingBruceExportPath: String? = null
    private var pendingBruceExportDevice = false
    private var pendingMarauderExportName: String? = null
    private var pendingMarauderExportPath: String? = null
    private var pendingGhostExportName: String? = null
    private var pendingGhostExportPath: String? = null
    private var phoneGpsRequested = false
    private var phoneWifiRequested = false
    private var usbTransportDetached = false
    private var selectedScreen = FirmwareKind.BRUCE
    private var requestedNetworkKind: FirmwareKind? = null
    private var pendingBluetoothKind: PersistentUsbKind? = null
    private var pendingBluetoothAsNewDevice = false
    private var activeNetworkKind: FirmwareKind? = null
    private var flashingKind: FirmwareKind? = null

    @Suppress("DEPRECATION")
    private val controllerVersionName: String by lazy {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }

    private val phoneLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = bruceController.submitPhoneLocation(location)
    }

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                if (flashingKind != null) return
                val device = intent.usbDevice() ?: return
                val target = UsbDeviceRegistry.target(
                    getSystemService(Context.USB_SERVICE) as UsbManager,
                    device,
                )
                runOnUiThread { retainDetectedFirmwareAfterUsbDetach(target) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets()
        restoreExportState(savedInstanceState)

        globalStatus = findViewById(R.id.globalStatus)
        appSubtitle = findViewById(R.id.appSubtitle)
        detectionStatus = findViewById(R.id.detectionStatus)
        androidStorageStatus = findViewById(R.id.androidStorageStatus)
        container = findViewById(R.id.screenContainer)
        deviceTabs = findViewById(R.id.deviceTabs)
        tabBruce = findViewById(R.id.tabBruce)
        tabGhost = findViewById(R.id.tabGhost)
        tabMarauder = findViewById(R.id.tabMarauder)
        networkManager = BruceNetworkManager.get(this)
        usbDetector = UsbFirmwareDetector(this, this)
        phoneLocationManager = getSystemService(LocationManager::class.java)
        phoneWifiScanner = AndroidWifiFieldScanner(this, this)
        firmwareBleScanner = FirmwareBleScanner(this)
        PersistentDeviceConnections.addListener(this)
        registerUsbDetachReceiver()
        requestSessionNotificationPermission()
        FirmwareUpdateJobService.schedule(this)

        val inflater = LayoutInflater.from(this)
        bruceView = inflater.inflate(R.layout.screen_bruce, container, false)
        ghostView = inflater.inflate(R.layout.screen_ghost, container, false)
        marauderView = inflater.inflate(R.layout.screen_marauder, container, false)
        bruceFirmwareStatus = bruceView.findViewById(R.id.bruceFirmwareImageStatus)
        ghostFirmwareStatus = ghostView.findViewById(R.id.ghostFirmwareImageStatus)
        marauderFirmwareStatus = marauderView.findViewById(R.id.marauderFirmwareImageStatus)
        flashBruceFirmware = bruceView.findViewById(R.id.flashBruceFirmware)
        flashGhostFirmware = ghostView.findViewById(R.id.flashGhostFirmware)
        flashMarauderFirmware = marauderView.findViewById(R.id.flashMarauderFirmware)
        bruceUsbTargetStatus = bruceView.findViewById(R.id.bruceUsbTargetStatus)
        ghostUsbTargetStatus = ghostView.findViewById(R.id.ghostUsbTargetStatus)
        marauderUsbTargetStatus = marauderView.findViewById(R.id.marauderUsbTargetStatus)
        selectBruceUsbTarget = bruceView.findViewById(R.id.selectBruceUsbTarget)
        selectGhostUsbTarget = ghostView.findViewById(R.id.selectGhostUsbTarget)
        selectMarauderUsbTarget = marauderView.findViewById(R.id.selectMarauderUsbTarget)
        firmwareRepository = FirmwareImageRepository(this)
        bootloaderFlasher = Esp32S3BootloaderFlasher(this)
        bruceController = BruceScreenController(
            activity = this,
            root = bruceView,
            client = client,
            requestWifi = ::requestBruceWifi,
            requestPhoneGps = ::requestPhoneGps,
            requestPhoneWifi = ::requestPhoneWifi,
            requestFieldLogExport = ::requestBruceFieldLogExport,
            requestDeviceFileExport = ::requestBruceDeviceFileExport,
            requestBluetooth = { requestBluetooth(PersistentUsbKind.BRUCE) },
            setGlobalStatus = { status ->
                setFirmwareGlobalStatus(FirmwareKind.BRUCE, status)
            },
        )
        marauderController = MarauderScreenController(
            activity = this,
            root = marauderView,
            requestExport = ::requestMarauderExport,
            requestBluetooth = { requestBluetooth(PersistentUsbKind.MARAUDER) },
            setGlobalStatus = { status ->
                setFirmwareGlobalStatus(FirmwareKind.MARAUDER, status)
            },
        )
        ghostController = GhostScreenController(
            activity = this,
            root = ghostView,
            requestGhostNet = ::requestGhostNet,
            requestBluetooth = { requestBluetooth(PersistentUsbKind.GHOSTESP) },
            requestExport = ::requestGhostExport,
            setGlobalStatus = { status ->
                setFirmwareGlobalStatus(FirmwareKind.GHOSTESP, status)
            },
        )
        networkManager.attach(this)
        if (hasBluetoothPermissions(this)) PersistentDeviceConnections.restoreBluetooth(this)

        globalStatus.setOnClickListener { showConnectionMenu() }
        tabBruce.setOnClickListener { showScreen(bruceView, FirmwareKind.BRUCE) }
        tabMarauder.setOnClickListener { showScreen(marauderView, FirmwareKind.MARAUDER) }
        tabGhost.setOnClickListener { showScreen(ghostView, FirmwareKind.GHOSTESP) }
        flashBruceFirmware.setOnClickListener { confirmFirmwareFlash(FirmwareKind.BRUCE) }
        flashGhostFirmware.setOnClickListener { confirmFirmwareFlash(FirmwareKind.GHOSTESP) }
        flashMarauderFirmware.setOnClickListener { confirmFirmwareFlash(FirmwareKind.MARAUDER) }
        selectBruceUsbTarget.setOnClickListener { selectUsbTargetFor(FirmwareKind.BRUCE) }
        selectGhostUsbTarget.setOnClickListener { selectUsbTargetFor(FirmwareKind.GHOSTESP) }
        selectMarauderUsbTarget.setOnClickListener { selectUsbTargetFor(FirmwareKind.MARAUDER) }

        clearDetection(
            "All firmware screens are available. Automatic USB detection is active; " +
                "USB and local Wi-Fi connections can remain active while you switch screens.",
        )
        refreshAndroidStorageStatus()
        container.post(::restorePersistentConnectionOrDetect)
        firmwareRepository.initialize(object : FirmwareImageRepository.Listener {
            override fun onCatalogChanged(catalog: FirmwareCatalog) = runOnUiThread {
                updateFirmwareCards()
                notifyIfFirmwareUpdateExists()
            }

            override fun onUpstreamReleasesChanged(
                releases: Map<FirmwareKind, UpstreamRelease>,
            ) = runOnUiThread {
                updateFirmwareCards()
                showReleaseNotice(selectedScreen)
            }

            override fun onCatalogStatus(message: String) = runOnUiThread {
                updateFirmwareCards()
            }
        })
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.activityRoot)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(
                initialLeft + safeArea.left,
                initialTop + safeArea.top,
                initialRight + safeArea.right,
                initialBottom + safeArea.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            updateFirmwareCards()
            // The flasher owns re-enumeration while a recovery attempt is active. Opening the
            // normal detector here would claim the same serial interface before it can resume.
            if (flashingKind != null) return
            val device = intent.usbDevice()
            if (device != null) {
                startUsbDetection(
                    UsbDeviceRegistry.target(
                        getSystemService(Context.USB_SERVICE) as UsbManager,
                        device,
                    ),
                )
            } else {
                startUsbDetection()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAndroidStorageStatus()
        refreshSelectedGlobalStatus()
        if (::firmwareRepository.isInitialized) {
            firmwareRepository.refreshIfStale(FIRMWARE_RESUME_REFRESH_AGE_MS)
        }
        firmwareRefreshHandler.removeCallbacks(firmwareRefreshRunnable)
        firmwareRefreshHandler.postDelayed(
            firmwareRefreshRunnable,
            FIRMWARE_REFRESH_INTERVAL_MS,
        )
    }

    override fun onPause() {
        firmwareRefreshHandler.removeCallbacks(firmwareRefreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        firmwareRefreshHandler.removeCallbacks(firmwareRefreshRunnable)
        if (::firmwareBleScanner.isInitialized) firmwareBleScanner.stop()
        stopPhoneWifi()
        stopPhoneGps()
        if (::bruceController.isInitialized) bruceController.destroy()
        if (::ghostController.isInitialized) ghostController.destroy()
        if (::marauderController.isInitialized) marauderController.destroy()
        if (::networkManager.isInitialized) networkManager.detach(this)
        if (::usbDetector.isInitialized) usbDetector.destroy()
        if (::firmwareRepository.isInitialized) firmwareRepository.close()
        if (::bootloaderFlasher.isInitialized) bootloaderFlasher.close()
        PersistentDeviceConnections.removeListener(this)
        runCatching { unregisterReceiver(usbDetachReceiver) }
        detectorExecutor.shutdownNow()
        if (isFinishing) {
            clearPendingMarauderExport()
            clearPendingGhostExport()
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingBruceExportName?.let { outState.putString(STATE_BRUCE_EXPORT_NAME, it) }
        pendingBruceExportPath?.let { outState.putString(STATE_BRUCE_EXPORT_PATH, it) }
        outState.putBoolean(STATE_BRUCE_EXPORT_DEVICE, pendingBruceExportDevice)
        pendingMarauderExportName?.let { outState.putString(STATE_MARAUDER_EXPORT_NAME, it) }
        pendingMarauderExportPath?.let { outState.putString(STATE_MARAUDER_EXPORT_PATH, it) }
        pendingGhostExportName?.let { outState.putString(STATE_GHOST_EXPORT_NAME, it) }
        pendingGhostExportPath?.let { outState.putString(STATE_GHOST_EXPORT_PATH, it) }
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android; retained for API 29 document-provider compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            BRUCE_EXPORT_REQUEST -> finishBruceExport(resultCode, data?.data)
            MARAUDER_EXPORT_REQUEST -> finishMarauderExport(resultCode, data?.data)
            GHOST_EXPORT_REQUEST -> finishGhostExport(resultCode, data?.data)
            ANDROID_STORAGE_TREE_REQUEST -> finishAndroidStorageSelection(resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SESSION_NOTIFICATION_PERMISSION_REQUEST) return
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST) {
            val kind = pendingBluetoothKind
            val asNewDevice = pendingBluetoothAsNewDevice
            pendingBluetoothKind = null
            pendingBluetoothAsNewDevice = false
            if (kind != null && hasBluetoothPermissions(this)) {
                beginBluetoothScan(kind, asNewDevice)
            }
            else Toast.makeText(
                this,
                "Nearby devices permission was denied",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (requestCode == PHONE_GPS_PERMISSION_REQUEST) {
            val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (granted && phoneGpsRequested) startPhoneGps()
            else {
                phoneGpsRequested = false
                bruceController.onPhoneGpsError("Precise location permission was denied")
            }
            return
        }
        if (requestCode == PHONE_WIFI_PERMISSION_REQUEST) {
            val granted = phoneWifiPermissions().all {
                checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
            if (granted && phoneWifiRequested) startPhoneWifi()
            else {
                phoneWifiRequested = false
                bruceController.onPhoneWifiError("Wi-Fi scan permission was denied")
            }
            return
        }
        if (requestCode != WIFI_PERMISSION_REQUEST) return

        val request = pendingWifi
        pendingWifi = null
        val granted = if (Build.VERSION.SDK_INT in 31..32) {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } else grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED

        if (granted && request != null) {
            beginWifiRequest(request.first, request.second)
        } else if (pendingGhostNetDetection) {
            pendingGhostNetDetection = false
            requestedNetworkKind = null
            onUsbDetectionUnknown("Nearby Wi-Fi permission was denied")
        } else if (pendingBruceNetDetection) {
            pendingBruceNetDetection = false
            requestedNetworkKind = null
            onUsbDetectionUnknown("Nearby Wi-Fi permission was denied")
        } else {
            val target = requestedNetworkKind ?: selectedScreen
            requestedNetworkKind = null
            when (target) {
                FirmwareKind.GHOSTESP ->
                    ghostController.onNetworkError("Nearby Wi-Fi permission was denied")
                FirmwareKind.BRUCE ->
                    bruceController.onNetworkError("Nearby Wi-Fi permission was denied")
                else -> onUsbDetectionUnknown("Nearby Wi-Fi permission was denied")
            }
        }
    }

    private fun requestSessionNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                SESSION_NOTIFICATION_PERMISSION_REQUEST,
            )
        }
    }

    private fun requestBluetooth(kind: PersistentUsbKind, asNewDevice: Boolean = false) {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            beginBluetoothScan(kind, asNewDevice)
        } else {
            pendingBluetoothKind = kind
            pendingBluetoothAsNewDevice = asNewDevice
            requestPermissions(permissions, BLUETOOTH_PERMISSION_REQUEST)
        }
    }

    private fun beginBluetoothScan(kind: PersistentUsbKind, asNewDevice: Boolean) {
        setGlobalStatus("BLUETOOTH SCAN")
        Toast.makeText(
            this,
            "Scanning for ${kind.displayName} Bluetooth…",
            Toast.LENGTH_SHORT,
        ).show()
        firmwareBleScanner.scan(kind) { outcome -> runOnUiThread {
            outcome.onSuccess { devices -> showBluetoothDevices(kind, devices, asNewDevice) }
                .onFailure { error ->
                    setGlobalStatus("BLUETOOTH UNAVAILABLE")
                    Toast.makeText(
                        this,
                        error.message ?: "Bluetooth scan failed",
                        Toast.LENGTH_LONG,
                    ).show()
                }
        } }
    }

    private fun showBluetoothDevices(
        kind: PersistentUsbKind,
        devices: List<FirmwareBleDevice>,
        asNewDevice: Boolean,
    ) {
        if (devices.isEmpty()) {
            setGlobalStatus("BLUETOOTH NOT FOUND")
            val setup = when (kind) {
                PersistentUsbKind.GHOSTESP ->
                    "No GhostESP Bridge was found. Keep the device powered and nearby; relay-only builds also need a configured GhostLink peer."
                PersistentUsbKind.BRUCE ->
                    "No Bruce BLE service was found. Enable BLE API on the Bruce device, then scan again."
                PersistentUsbKind.MARAUDER ->
                    "No Marauder UART service was found. Flash the mobile Marauder image, let it finish booting, then scan again."
            }
            AlertDialog.Builder(this)
                .setTitle("No ${kind.displayName} Bluetooth device found")
                .setMessage(setup)
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = devices.map { device ->
            "${device.name} · ${device.rssi} dBm\n${device.address}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Connect ${kind.displayName} over Bluetooth")
            .setItems(labels) { _, index ->
                val selected = devices[index]
                setGlobalStatus("BLUETOOTH CONNECTING")
                PersistentDeviceConnections.addBluetooth(
                    this,
                    kind,
                    selected.address,
                    attachToSelected = !asNewDevice,
                )
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    override fun onBruceNetworkAvailable(network: Network) {
        val networkKind = requestedNetworkKind
            ?: networkKindForSsid(networkManager.requestedSsid)
            ?: activeNetworkKind
            ?: detectedFirmware?.kind
        client.network = network.takeIf { networkKind == FirmwareKind.BRUCE }
        ghostClient.network = network.takeIf { networkKind == FirmwareKind.GHOSTESP }
        requestedNetworkKind = null
        val replacedKind = activeNetworkKind?.takeIf { it != networkKind }
        activeNetworkKind = networkKind
        if (replacedKind != null) runOnUiThread {
            when (replacedKind) {
                FirmwareKind.BRUCE -> bruceController.onNetworkLost()
                FirmwareKind.GHOSTESP -> ghostController.onNetworkLost()
                else -> Unit
            }
        }
        if (pendingGhostNetDetection) {
            probeGhostNet()
            return
        }
        if (pendingBruceNetDetection) {
            probeBruceNet()
            return
        }
        runOnUiThread {
            when (networkKind) {
                FirmwareKind.GHOSTESP -> {
                    if (usbTransportDetached && detectedFirmware?.kind == FirmwareKind.GHOSTESP) {
                        detectedFirmware = detectedFirmware?.copy(
                            source = DetectionSource.GHOSTNET,
                            evidence = "USB detached · continuing through GhostNet",
                        )
                        detectionStatus.text =
                            "Verified GhostESP · USB detached · continuing through GhostNet"
                    }
                    ghostController.onNetworkAvailable(network)
                }
                FirmwareKind.BRUCE -> {
                    if (usbTransportDetached && detectedFirmware?.kind == FirmwareKind.BRUCE) {
                        detectedFirmware = detectedFirmware?.copy(
                            source = DetectionSource.BRUCENET,
                            evidence = "USB detached · continuing through BruceNet",
                        )
                        detectionStatus.text =
                            "Verified Bruce · USB detached · continuing through BruceNet"
                    }
                    bruceController.onNetworkAvailable()
                }
                else -> Unit
            }
        }
    }

    override fun onBruceNetworkLost() {
        client.network = null
        ghostClient.network = null
        val lostKind = activeNetworkKind
            ?: networkKindForSsid(networkManager.requestedSsid)
            ?: requestedNetworkKind
        activeNetworkKind = null
        requestedNetworkKind = null
        runOnUiThread {
            when (lostKind) {
                FirmwareKind.GHOSTESP -> {
                    detectionStatus.text =
                        "GhostNet disconnected. Any USB connection remains active; " +
                            "reconnect GhostNet to resume over-the-air control."
                    ghostController.onNetworkLost()
                }
                FirmwareKind.BRUCE -> {
                    detectionStatus.text =
                        "BruceNet disconnected. Any USB connection remains active; " +
                            "reconnect BruceNet to resume over-the-air control."
                    bruceController.onNetworkLost()
                }
                else -> Unit
            }
        }
    }

    override fun onBruceNetworkError(message: String) = runOnUiThread {
        val target = requestedNetworkKind
            ?: activeNetworkKind
            ?: networkKindForSsid(networkManager.requestedSsid)
            ?: selectedScreen
        requestedNetworkKind = null
        if (pendingGhostNetDetection || pendingBruceNetDetection) {
            pendingGhostNetDetection = false
            pendingBruceNetDetection = false
            detectionStatus.text = message
            setControllerSubtitle("DETECT ERROR")
            setGlobalStatus("DETECT ERROR")
        } else if (target == FirmwareKind.GHOSTESP) {
            ghostController.onNetworkError(message)
        } else if (target == FirmwareKind.BRUCE) {
            bruceController.onNetworkError(message)
        }
    }

    override fun onUsbDetectionStatus(message: String) = runOnUiThread {
        detectionStatus.text = message
        setGlobalStatus("DETECTING")
    }

    override fun onUsbFirmwareDetected(detection: FirmwareDetection) = runOnUiThread {
        applyDetection(detection)
    }

    override fun onUsbDetectionUnknown(message: String) = runOnUiThread {
        clearDetection(message)
        setGlobalStatus("UNKNOWN")
    }

    private fun startUsbDetection(preferred: UsbDeviceTarget? = null) {
        val targets = usbDetector.targets()
        if (targets.isEmpty()) {
            onUsbDetectionUnknown("No compatible USB serial board is attached")
            return
        }
        val target = preferred?.let { requested ->
            targets.firstOrNull(requested::samePhysicalDevice)
        }
        if (target != null) {
            detectUsbTarget(target)
            return
        }
        if (targets.size == 1) {
            detectUsbTarget(targets.single())
            return
        }
        showUsbTargetPicker(
            title = "Detect which USB device?",
            targets = targets,
            onSelected = ::detectUsbTarget,
        )
    }

    private fun detectUsbTarget(target: UsbDeviceTarget) {
        val assigned = PersistentDeviceConnections.assignedKind(target)
        if (assigned != null && PersistentDeviceConnections.isUsbTargetConnected(target)) {
            PersistentDeviceConnections.selectUsbTarget(target)
            restorePersistentUsbDetection(assigned)
            return
        }
        probeUsbTarget(target)
    }

    private fun probeUsbTarget(target: UsbDeviceTarget) {
        usbTransportDetached = false
        pendingBruceNetDetection = false
        pendingGhostNetDetection = false
        detectionStatus.text = "Opening ${target.displayLabel()} for a read-only firmware identity probe…"
        setGlobalStatus("DETECTING")
        usbDetector.detect(target)
    }

    private fun showUsbTargetPicker(
        title: String,
        targets: List<UsbDeviceTarget>,
        onSelected: (UsbDeviceTarget) -> Unit,
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(targets.map(UsbDeviceTarget::displayLabel).toTypedArray()) { _, which ->
                targets.getOrNull(which)?.let(onSelected)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun selectUsbTargetFor(kind: FirmwareKind) {
        val targets = usbDetector.targets()
        if (targets.isEmpty()) {
            Toast.makeText(this, "No compatible wired device is attached", Toast.LENGTH_LONG).show()
            return
        }
        showUsbTargetPicker(
            title = "Select ${kind.displayName} wired device",
            targets = targets,
        ) { target ->
            val previousKind = PersistentDeviceConnections.assignedKind(target)
            val requestedKind = kind.toPersistentUsbKind()
            if (
                previousKind == requestedKind &&
                PersistentDeviceConnections.isUsbTargetConnected(target)
            ) {
                PersistentDeviceConnections.selectUsbTarget(target)
                restorePersistentUsbDetection(requestedKind)
                Toast.makeText(
                    this,
                    "${target.displayLabel()} is already connected to ${kind.displayName}",
                    Toast.LENGTH_SHORT,
                ).show()
            } else if (previousKind != null && previousKind != requestedKind) {
                AlertDialog.Builder(this)
                    .setTitle("Different firmware assignment")
                    .setMessage(
                        "${target.displayLabel()} is assigned to ${previousKind.displayName}. " +
                            "Run a read-only identity probe before changing its assignment?",
                    )
                    .setNegativeButton("CANCEL", null)
                    .setPositiveButton("VERIFY DEVICE") { _, _ ->
                        PersistentDeviceConnections.disconnectUsbTarget(target)
                        probeUsbTarget(target)
                    }
                    .show()
            } else {
                probeUsbTarget(target)
            }
        }
    }

    private fun startBruceNetDetection() {
        usbDetector.cancel()
        clearDetection("Requesting the default BruceNet local Wi-Fi link…")
        pendingBruceNetDetection = true
        pendingGhostNetDetection = false
        requestWifi(DEFAULT_BRUCENET_SSID, DEFAULT_BRUCENET_PASSWORD, FirmwareKind.BRUCE)
    }

    private fun startGhostNetDetection() {
        usbDetector.cancel()
        clearDetection("Requesting the default GhostNet local Wi-Fi link…")
        pendingBruceNetDetection = false
        pendingGhostNetDetection = true
        requestWifi(DEFAULT_GHOSTNET_SSID, DEFAULT_GHOSTNET_PASSWORD, FirmwareKind.GHOSTESP)
    }

    private fun probeBruceNet() {
        detectionStatus.post {
            detectionStatus.text = "Checking the local WebUI for a Bruce signature…"
            setGlobalStatus("DETECTING")
        }
        detectorExecutor.execute {
            val outcome = runCatching {
                client.configure(DEFAULT_BRUCE_URL)
                client.probeWebUi()
            }
            runOnUiThread {
                pendingBruceNetDetection = false
                outcome.onSuccess { result ->
                    if (FirmwareIdentity.isBruceWebUi(result.status, result.body)) {
                        applyDetection(
                            FirmwareDetection(
                                FirmwareKind.BRUCE,
                                DetectionSource.BRUCENET,
                                "Bruce WebUI login signature",
                            ),
                        )
                    } else {
                        clearDetection(
                            "The local server did not present a recognized Bruce WebUI signature.",
                        )
                    }
                }.onFailure { error ->
                    clearDetection(
                        "BruceNet identity check failed: " +
                            (error.message ?: error.javaClass.simpleName),
                    )
                }
            }
        }
    }

    private fun probeGhostNet() {
        detectionStatus.post {
            detectionStatus.text = "Checking the local Web UI for a GhostESP signature…"
            setGlobalStatus("DETECTING")
        }
        detectorExecutor.execute {
            val outcome = runCatching {
                ghostClient.configure(DEFAULT_GHOST_URL, ghostClient.network)
                ghostClient.probeWebUi()
            }
            runOnUiThread {
                pendingGhostNetDetection = false
                outcome.onSuccess { result ->
                    if (FirmwareIdentity.isGhostEspWebUi(result.status, result.body)) {
                        applyDetection(
                            FirmwareDetection(
                                FirmwareKind.GHOSTESP,
                                DetectionSource.GHOSTNET,
                                "GhostNet Web UI signature",
                            ),
                        )
                    } else {
                        clearDetection(
                            "The local server did not present a recognized GhostESP Web UI signature.",
                        )
                    }
                }.onFailure { error ->
                    clearDetection(
                        "GhostNet identity check failed: " +
                            (error.message ?: error.javaClass.simpleName),
                    )
                }
            }
        }
    }

    private fun applyDetection(incoming: FirmwareDetection) {
        val previous = incoming.usbTarget?.let { target ->
            deviceDetections.values.firstOrNull {
                it.usbTarget?.samePhysicalDevice(target) == true
            }
        } ?: detectedFirmwares[incoming.kind]
        val persistentTarget = if (incoming.kind == FirmwareKind.UNKNOWN) null else {
            PersistentDeviceConnections.target(incoming.kind.toPersistentUsbKind())
        }
        val detection = incoming.copy(
            version = incoming.version ?: previous?.version,
            commit = incoming.commit ?: previous?.commit,
            usbTarget = incoming.usbTarget ?: previous?.usbTarget ?: persistentTarget,
        )
        detection.usbTarget?.let { target ->
            detectedFirmwares.entries.removeAll {
                it.key != detection.kind && it.value.usbTarget?.samePhysicalDevice(target) == true
            }
        }
        usbTransportDetached = false
        detectedFirmware = detection
        detectedFirmwares[detection.kind] = detection
        val connectionId = if (detection.source == DetectionSource.USB && detection.usbTarget != null) {
            PersistentDeviceConnections.bindUsb(
                this,
                detection.kind.toPersistentUsbKind(),
                detection.usbTarget,
            )
        } else null
        if (connectionId != null) deviceDetections[connectionId] = detection
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE).edit()
            .putString(PREF_FIRMWARE_KIND, detection.kind.name)
            .putString(PREF_DETECTION_SOURCE, detection.source.name)
            .putString(PREF_FIRMWARE_VERSION, detection.version)
            .putString(PREF_FIRMWARE_COMMIT, detection.commit)
            .putString("${PREF_FIRMWARE_VERSION}_${detection.kind.name}", detection.version)
            .putString("${PREF_FIRMWARE_COMMIT}_${detection.kind.name}", detection.commit)
            .apply()
        val source = when (detection.source) {
            DetectionSource.USB -> "USB"
            DetectionSource.BRUCENET -> "BruceNet"
            DetectionSource.GHOSTNET -> "GhostNet"
            DetectionSource.MANUAL -> "manual override"
        }
        detectionStatus.text =
            "Verified ${detection.kind.displayName}" +
                detection.version?.let { " $it" }.orEmpty() +
                " via $source · ${detection.evidence}"
        setControllerSubtitle(detection.kind.displayName.uppercase())
        setGlobalStatus("${detection.kind.displayName.uppercase()} READY")
        tabBruce.isEnabled = true
        tabGhost.isEnabled = true
        tabMarauder.isEnabled = true
        updateFirmwareCards()
        updateUsbTargetCards()
        notifyIfFirmwareUpdateExists()
        when (detection.kind) {
            FirmwareKind.BRUCE -> {
                showScreen(bruceView, FirmwareKind.BRUCE)
                if (detection.source == DetectionSource.USB) bruceController.onUsbDetected()
                if (client.network != null && activeNetworkKind == FirmwareKind.BRUCE) {
                    bruceController.onNetworkAvailable()
                }
            }
            FirmwareKind.MARAUDER -> {
                showScreen(marauderView, FirmwareKind.MARAUDER)
                if (detection.source == DetectionSource.USB) marauderController.connectUsb()
            }
            FirmwareKind.GHOSTESP -> {
                showScreen(ghostView, FirmwareKind.GHOSTESP)
                if (detection.source == DetectionSource.USB) ghostController.connectUsb()
                if (activeNetworkKind == FirmwareKind.GHOSTESP) {
                    ghostClient.network?.let(ghostController::onNetworkAvailable)
                }
            }
            FirmwareKind.UNKNOWN -> clearDetection("The firmware signature is unknown.")
        }
    }

    private fun clearDetection(message: String) {
        usbTransportDetached = false
        detectedFirmware = detectedFirmwares[selectedScreen]
            ?: detectedFirmwares.values.firstOrNull()
        detectionStatus.text = message
        setControllerSubtitle(if (detectedFirmwares.isEmpty()) "DETECTING" else "USB SESSIONS ACTIVE")
        tabBruce.isEnabled = true
        tabGhost.isEnabled = true
        tabMarauder.isEnabled = true
        if (container.childCount == 0) showScreen(bruceView, FirmwareKind.BRUCE)
        else setTabAppearance(selectedScreen)
        setGlobalStatus(
            if (PersistentDeviceConnections.activeKinds().isEmpty()) "UNKNOWN" else "DEVICE READY",
        )
        updateFirmwareCards()
    }

    private fun retainDetectedFirmwareAfterUsbDetach(target: UsbDeviceTarget) {
        val detection = detectedFirmwares.values.firstOrNull {
            it.usbTarget?.samePhysicalDevice(target) == true
        } ?: return
        detectedFirmware = detection
        detectedFirmwares.remove(detection.kind)
        usbTransportDetached = true
        when (detection.kind) {
            FirmwareKind.GHOSTESP -> {
                val network = ghostClient.network
                if (network != null && activeNetworkKind == FirmwareKind.GHOSTESP) {
                    detectedFirmware = detection.copy(
                        source = DetectionSource.GHOSTNET,
                        evidence = "USB detached · continuing through GhostNet",
                    )
                    detectedFirmwares[FirmwareKind.GHOSTESP] = requireNotNull(detectedFirmware)
                    detectionStatus.text =
                        "GhostESP USB detached · continuing automatically through GhostNet."
                    ghostController.onNetworkAvailable(network)
                    setGlobalStatus("GHOSTNET")
                } else {
                    detectionStatus.text =
                        "GhostESP USB detached. The board continues standalone; connect GhostNet " +
                            "or reattach USB to resume control."
                    setGlobalStatus("GHOST OFFLINE")
                }
            }
            FirmwareKind.MARAUDER -> {
                detectionStatus.text =
                    "Marauder USB detached. The board continues standalone; reattach USB to " +
                        "resume the saved app session automatically."
                setGlobalStatus("MARAUDER OFFLINE")
            }
            FirmwareKind.BRUCE -> {
                if (client.network != null && activeNetworkKind == FirmwareKind.BRUCE) {
                    detectedFirmware = detection.copy(
                        source = DetectionSource.BRUCENET,
                        evidence = "USB detached · continuing through BruceNet",
                    )
                    detectedFirmwares[FirmwareKind.BRUCE] = requireNotNull(detectedFirmware)
                    detectionStatus.text =
                        "Bruce USB detached · continuing automatically through BruceNet."
                    bruceController.onNetworkAvailable()
                    setGlobalStatus("BRUCENET")
                } else {
                    detectionStatus.text =
                        "Bruce USB detached. The board continues standalone; connect BruceNet " +
                            "or reattach USB to resume control."
                    setGlobalStatus("BRUCE OFFLINE")
                }
            }
            FirmwareKind.UNKNOWN -> clearDetection("The disconnected firmware was unknown.")
        }
        updateFirmwareCards()
    }

    private fun restorePersistentConnectionOrDetect() {
        val activeKinds = PersistentDeviceConnections.activeUsbKinds()
        if (activeKinds.isNotEmpty()) {
            activeKinds.forEach(::restorePersistentUsbDetection)
            return
        }

        val network = networkManager.activeNetwork
        if (network != null) {
            val preferences = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
            val storedKind = runCatching {
                FirmwareKind.valueOf(preferences.getString(PREF_FIRMWARE_KIND, "") ?: "")
            }.getOrNull()
            val expectedSsid = when (storedKind) {
                FirmwareKind.GHOSTESP -> DEFAULT_GHOSTNET_SSID
                FirmwareKind.BRUCE -> DEFAULT_BRUCENET_SSID
                else -> null
            }
            if (
                storedKind != null &&
                expectedSsid != null &&
                networkManager.requestedSsid == expectedSsid
            ) {
                activeNetworkKind = storedKind
                client.network = network.takeIf { storedKind == FirmwareKind.BRUCE }
                ghostClient.network = network.takeIf { storedKind == FirmwareKind.GHOSTESP }
                val restoredSource = if (storedKind == FirmwareKind.GHOSTESP) {
                    DetectionSource.GHOSTNET
                } else {
                    DetectionSource.BRUCENET
                }
                applyDetection(
                    FirmwareDetection(
                        storedKind,
                        restoredSource,
                        "restored live background ${networkManager.requestedSsid} session",
                    ),
                )
                return
            }
        }

        if (usbDetector.hasCandidate()) startUsbDetection()
    }

    private fun restorePersistentUsbDetection(kind: PersistentUsbKind) {
        val firmwareKind = when (kind) {
            PersistentUsbKind.BRUCE -> FirmwareKind.BRUCE
            PersistentUsbKind.GHOSTESP -> FirmwareKind.GHOSTESP
            PersistentUsbKind.MARAUDER -> FirmwareKind.MARAUDER
        }
        val preferences = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
        val version = preferences.getString("${PREF_FIRMWARE_VERSION}_${firmwareKind.name}", null)
            ?: preferences.getString(PREF_FIRMWARE_VERSION, null)
        val commit = preferences.getString("${PREF_FIRMWARE_COMMIT}_${firmwareKind.name}", null)
            ?: preferences.getString(PREF_FIRMWARE_COMMIT, null)
        val detection =
            FirmwareDetection(
                firmwareKind,
                DetectionSource.USB,
                "resumed existing background USB session without reopening it",
                version,
                commit,
                PersistentDeviceConnections.target(kind),
            )
        detectedFirmwares[firmwareKind] = detection
        applyDetection(detection)
    }

    private fun setControllerSubtitle(state: String) {
        appSubtitle.text = "CONTROLLER $controllerVersionName // $state"
    }

    private fun setFirmwareGlobalStatus(kind: FirmwareKind, status: String) {
        if (selectedScreen == kind) setGlobalStatus(status)
    }

    private fun showScreen(view: View, selected: FirmwareKind) {
        selectedScreen = selected
        if (container.childCount != 1 || container.getChildAt(0) !== view) {
            container.removeAllViews()
            container.addView(view)
        }
        setTabAppearance(selected)
        updateDeviceTabs()
        refreshSelectedGlobalStatus()
        if (::firmwareRepository.isInitialized) {
            firmwareRepository.refreshIfStale(FIRMWARE_RESUME_REFRESH_AGE_MS)
        }
        showReleaseNotice(selected)
    }

    private fun refreshSelectedGlobalStatus() {
        when (selectedScreen) {
            FirmwareKind.BRUCE -> if (::bruceController.isInitialized) {
                bruceController.refreshGlobalStatus()
            }
            FirmwareKind.GHOSTESP -> if (::ghostController.isInitialized) {
                ghostController.refreshGlobalStatus()
            }
            FirmwareKind.MARAUDER -> if (::marauderController.isInitialized) {
                marauderController.refreshGlobalStatus()
            }
            FirmwareKind.UNKNOWN -> setGlobalStatus("IDLE")
        }
    }

    override fun onDeviceConnectionsChanged(kind: PersistentUsbKind) = runOnUiThread {
        refreshAndroidStorageStatus()
        setTabAppearance(selectedScreen)
        if (selectedScreen.toPersistentUsbKind() == kind) {
            updateDeviceTabs()
            updateUsbTargetCards()
            refreshSelectedGlobalStatus()
        }
    }

    private fun updateDeviceTabs() {
        if (!::deviceTabs.isInitialized) return
        val kind = selectedScreen.toPersistentUsbKind()
        val devices = PersistentDeviceConnections.devices(kind)
        devices.firstOrNull { it.selected }?.connectionId?.let { connectionId ->
            if (displayedConnectionIds.put(kind, connectionId) != connectionId) {
                notifyControllerSelection(kind, connectionId)
            }
        }
        deviceTabs.removeAllViews()
        devices.forEach { device ->
            deviceTabs.addView(deviceTabButton(
                text = "${device.displayLabel.substringAfter(" · ")} · ${device.transportLabel}",
                selected = device.selected,
            ) {
                PersistentDeviceConnections.select(kind, device.connectionId)
            })
        }
        deviceTabs.addView(deviceTabButton(
            text = if (devices.isEmpty()) "+ ADD DEVICE" else "+ ADD",
            selected = false,
        ) { showAddDeviceDialog(kind) })
    }

    private fun deviceTabButton(
        text: String,
        selected: Boolean,
        action: () -> Unit,
    ): Button = Button(this).apply {
        this.text = text
        contentDescription = if (selected) "$text, selected device" else text
        isSelected = selected
        isAllCaps = false
        textSize = 11f
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(12), 0, dp(12), 0)
        setTextColor(getColor(if (selected) R.color.bg else R.color.text))
        backgroundTintList = ColorStateList.valueOf(
            getColor(if (selected) R.color.teal else R.color.surface_high),
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginEnd = dp(6)
        }
        setOnClickListener { action() }
    }

    private fun showAddDeviceDialog(kind: PersistentUsbKind) {
        val labels = buildList {
            add("Wired USB device")
            add("Bluetooth device")
        }
        AlertDialog.Builder(this)
            .setTitle("Add ${kind.displayName} device")
            .setItems(labels.toTypedArray()) { _, index ->
                when (labels[index]) {
                    "Wired USB device" -> selectUsbTargetFor(kind.toFirmwareKind())
                    else -> requestBluetooth(kind, asNewDevice = true)
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun notifyControllerSelection(kind: PersistentUsbKind, connectionId: String) {
        deviceDetections[connectionId]?.let { detection ->
            detectedFirmware = detection
            detectedFirmwares[detection.kind] = detection
            updateFirmwareCards()
            notifyIfFirmwareUpdateExists()
        }
        when (kind) {
            PersistentUsbKind.BRUCE -> bruceController.onDeviceSelected(connectionId)
            PersistentUsbKind.GHOSTESP -> ghostController.onDeviceSelected(connectionId)
            PersistentUsbKind.MARAUDER -> marauderController.onDeviceSelected(connectionId)
        }
    }

    private fun setTabAppearance(selected: FirmwareKind) {
        val active = ColorStateList.valueOf(getColor(R.color.teal))
        val inactive = ColorStateList.valueOf(getColor(R.color.surface_high))
        val disabled = ColorStateList.valueOf(getColor(R.color.surface))
        tabBruce.backgroundTintList = when {
            !tabBruce.isEnabled -> disabled
            selected == FirmwareKind.BRUCE -> active
            else -> inactive
        }
        tabGhost.backgroundTintList = when {
            !tabGhost.isEnabled -> disabled
            selected == FirmwareKind.GHOSTESP -> active
            else -> inactive
        }
        tabMarauder.backgroundTintList = when {
            !tabMarauder.isEnabled -> disabled
            selected == FirmwareKind.MARAUDER -> active
            else -> inactive
        }
        tabBruce.setTextColor(
            getColor(if (selected == FirmwareKind.BRUCE) R.color.bg else R.color.text),
        )
        tabGhost.setTextColor(
            getColor(if (selected == FirmwareKind.GHOSTESP) R.color.bg else R.color.text),
        )
        tabMarauder.setTextColor(
            getColor(if (selected == FirmwareKind.MARAUDER) R.color.bg else R.color.text),
        )
        tabBruce.text = firmwareTabLabel("BRUCE", PersistentUsbKind.BRUCE)
        tabGhost.text = firmwareTabLabel("GHOSTESP", PersistentUsbKind.GHOSTESP)
        tabMarauder.text = firmwareTabLabel("MARAUDER", PersistentUsbKind.MARAUDER)
    }

    private fun firmwareTabLabel(label: String, kind: PersistentUsbKind): String {
        val count = PersistentDeviceConnections.devices(kind).size
        return if (count > 0) "$label ($count)" else label
    }

    private fun updateFirmwareCards() {
        if (!::firmwareRepository.isInitialized || !::bootloaderFlasher.isInitialized) return
        val releases = firmwareRepository.catalog?.releases.orEmpty()
        updateFirmwareCard(
            FirmwareKind.BRUCE,
            releases[FirmwareKind.BRUCE],
            bruceFirmwareStatus,
            flashBruceFirmware,
        )
        updateFirmwareCard(
            FirmwareKind.GHOSTESP,
            releases[FirmwareKind.GHOSTESP],
            ghostFirmwareStatus,
            flashGhostFirmware,
        )
        updateFirmwareCard(
            FirmwareKind.MARAUDER,
            releases[FirmwareKind.MARAUDER],
            marauderFirmwareStatus,
            flashMarauderFirmware,
        )
        updateUsbTargetCards()
    }

    private fun updateUsbTargetCards() {
        if (!::bruceUsbTargetStatus.isInitialized) return
        updateUsbTargetCard(
            PersistentUsbKind.BRUCE,
            bruceUsbTargetStatus,
            selectBruceUsbTarget,
        )
        updateUsbTargetCard(
            PersistentUsbKind.GHOSTESP,
            ghostUsbTargetStatus,
            selectGhostUsbTarget,
        )
        updateUsbTargetCard(
            PersistentUsbKind.MARAUDER,
            marauderUsbTargetStatus,
            selectMarauderUsbTarget,
        )
    }

    private fun updateUsbTargetCard(
        kind: PersistentUsbKind,
        status: TextView,
        button: Button,
    ) {
        val target = PersistentDeviceConnections.target(kind)
        val attached = target?.let { selected ->
            usbDetector.targets().any(selected::samePhysicalDevice)
        } == true
        status.text = when {
            target == null -> "No wired device assigned"
            attached -> "Assigned: ${target.displayLabel()}"
            else -> "Assigned device is disconnected: ${target.displayLabel()}"
        }
        button.text = if (target == null) "SELECT WIRED DEVICE" else "CHANGE WIRED DEVICE"
        button.isEnabled = flashingKind == null
    }

    private fun updateFirmwareCard(
        kind: FirmwareKind,
        release: FirmwareRelease?,
        status: TextView,
        button: Button,
    ) {
        if (release == null) {
            status.text = "Preparing the verified offline bootable image…"
            button.text = "PREPARING ${kind.displayName.uppercase()} IMAGE"
            button.isEnabled = false
            return
        }
        val imageReady = firmwareRepository.imageFile(kind) != null
        val flashTargets = bootloaderFlasher.targets()
        val upstream = firmwareRepository.upstreamReleases[kind]
        val upstreamPending = upstream != null &&
            FirmwareVersion.isUpstreamBaselineOlder(
                release.upstream.baselineVersion,
                upstream.version,
            ) == true
        val imageStatus = if (imageReady) {
            "Compatible image ${release.version} · ${release.releasedAt} · retained in app files."
        } else {
            "Compatible image ${release.version} · download or verification is still pending."
        }
        val upstreamStatus = when {
            upstreamPending ->
                "Upstream ${upstream.version} · ${upstream.releasedAt} · compatibility build pending."
            upstream != null -> "Upstream ${upstream.version} · ${upstream.releasedAt} · included."
            firmwareRepository.upstreamRefreshComplete ->
                "Newest upstream check unavailable · compatible image retained."
            else -> "Checking the newest stable upstream release…"
        }
        status.text = "$imageStatus\n$upstreamStatus"
        val detection = detectedFirmwares[kind]
        val exactCurrent = detection?.kind == kind &&
            FirmwareVersion.matches(detection.version, release.version)
        val updateAvailable = detection?.kind == kind &&
            FirmwareVersion.isOlder(detection.version, release.version) == true
        button.text = when {
            flashingKind == kind -> "FLASHING ${kind.displayName.uppercase()}…"
            exactCurrent && flashTargets.size > 1 -> "CURRENT DEVICE · FLASH ANOTHER"
            exactCurrent -> "CURRENT DEVICE FIRMWARE"
            updateAvailable -> "UPDATE TO ${release.version.uppercase()}"
            else -> "FLASH ${kind.displayName.uppercase()} TO CONNECTED DEVICE"
        }
        button.isEnabled = flashingKind == null && imageReady &&
            flashTargets.isNotEmpty() && (!exactCurrent || flashTargets.size > 1)
    }

    private fun showReleaseNotice(kind: FirmwareKind) {
        if (kind == FirmwareKind.UNKNOWN || !::firmwareRepository.isInitialized) return
        val release = firmwareRepository.catalog?.releases?.get(kind) ?: return
        val upstream = firmwareRepository.upstreamReleases[kind]
        val upstreamPending = upstream != null &&
            FirmwareVersion.isUpstreamBaselineOlder(
                release.upstream.baselineVersion,
                upstream.version,
            ) == true
        val key = if (upstreamPending) {
            "seen_upstream_${kind.name}_${upstream.version}"
        } else {
            "seen_${kind.name}_${release.version}"
        }
        val preferences = getSharedPreferences(RELEASE_NOTICE_PREFS, MODE_PRIVATE)
        if (preferences.getBoolean(key, false)) return
        preferences.edit().putBoolean(key, true).apply()
        Toast.makeText(
            this,
            if (upstreamPending) {
                "${release.displayName} upstream ${upstream.version} · ${upstream.releasedAt}\n" +
                    "Compatible image ${release.version} remains current while integration is validated."
            } else {
                "${release.displayName} ${release.version} · ${release.releasedAt}\n${release.summary}"
            },
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun notifyIfFirmwareUpdateExists() {
        val detection = detectedFirmware ?: return
        val release = firmwareRepository.catalog?.releases?.get(detection.kind) ?: return
        if (FirmwareVersion.isOlder(detection.version, release.version) != true) return
        val key = "update_${detection.kind.name}_${detection.version}_${release.version}"
        val preferences = getSharedPreferences(RELEASE_NOTICE_PREFS, MODE_PRIVATE)
        if (preferences.getBoolean(key, false)) return
        preferences.edit().putBoolean(key, true).apply()
        Toast.makeText(
            this,
            "${release.displayName} ${release.version} is available for this device.",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun confirmFirmwareFlash(kind: FirmwareKind) {
        val release = firmwareRepository.catalog?.releases?.get(kind) ?: return
        val image = firmwareRepository.imageFile(kind)
        if (image == null) {
            Toast.makeText(this, "The verified firmware image is not ready", Toast.LENGTH_LONG).show()
            return
        }
        val targets = bootloaderFlasher.targets()
        if (targets.isEmpty()) {
            Toast.makeText(this, "No ESP32-S3 USB target is attached", Toast.LENGTH_LONG).show()
            return
        }
        val knownTarget = detectedFirmwares[kind]?.usbTarget?.let { detected ->
            targets.firstOrNull(detected::samePhysicalDevice)
        }
        if (targets.size == 1) {
            confirmFirmwareFlashTarget(kind, release, image, targets.single())
        } else {
            val orderedTargets = targets.sortedByDescending { candidate ->
                knownTarget?.samePhysicalDevice(candidate) == true
            }
            val labels = orderedTargets.map { target ->
                val current = if (knownTarget?.samePhysicalDevice(target) == true) {
                    "Current ${kind.displayName} · "
                } else {
                    ""
                }
                current + target.displayLabel()
            }
            AlertDialog.Builder(this)
                .setTitle("Select ${release.displayName} flash target")
                .setItems(labels.toTypedArray()) { _, which ->
                    orderedTargets.getOrNull(which)?.let { target ->
                        confirmFirmwareFlashTarget(kind, release, image, target)
                    }
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }
    }

    private fun confirmFirmwareFlashTarget(
        kind: FirmwareKind,
        release: FirmwareRelease,
        image: File,
        target: UsbDeviceTarget,
    ) {
        val assigned = PersistentDeviceConnections.assignedKind(target)
        val assignment = assigned?.let { "\nCurrent app assignment: ${it.displayName}." }.orEmpty()
        AlertDialog.Builder(this)
            .setTitle("Flash ${release.displayName} ${release.version}?")
            .setMessage(
                "Selected USB target:\n${target.displayLabel()}$assignment\n\n" +
                    "Supported device type: Heltec WiFi LoRa 32 V4 (ESP32-S3, 16 MB) only.\n\n" +
                    "This replaces the bootloader, partition table, and application. It does not " +
                    "erase the entire chip, but changing firmware can make unsynced device storage " +
                    "inaccessible. Keep both devices powered and connected until verification finishes.",
            )
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("FLASH SELECTED DEVICE") { _, _ ->
                beginFirmwareFlash(kind, release, image, target)
            }
            .show()
    }

    private fun beginFirmwareFlash(
        kind: FirmwareKind,
        release: FirmwareRelease,
        image: File,
        target: UsbDeviceTarget,
    ) {
        usbDetector.cancel()
        PersistentDeviceConnections.disconnectUsbTarget(target)
        flashingKind = kind
        detectedFirmwares.entries.removeAll {
            it.value.usbTarget?.samePhysicalDevice(target) == true
        }
        deviceDetections.entries.removeAll {
            it.value.usbTarget?.samePhysicalDevice(target) == true
        }
        if (detectedFirmware?.usbTarget?.samePhysicalDevice(target) == true) {
            detectedFirmware = null
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updateFirmwareCards()
        setGlobalStatus("FLASHING ${release.displayName.uppercase()}")
        detectionStatus.text =
            "Preparing ${release.displayName} ${release.version} for ${target.displayLabel()}…"
        bootloaderFlasher.flash(target, image, object : Esp32S3BootloaderFlasher.Listener {
            override fun onFlashProgress(percent: Int, message: String) = runOnUiThread {
                detectionStatus.text = message
                setGlobalStatus("FLASH $percent%")
            }

            override fun onFlashComplete(flashedTarget: UsbDeviceTarget) = runOnUiThread {
                flashingKind = null
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                detectionStatus.text = "${release.displayName} ${release.version} flashed and verified. Reconnecting…"
                setGlobalStatus("FLASH VERIFIED")
                updateFirmwareCards()
                container.postDelayed({ startUsbDetection(flashedTarget) }, 1_500L)
            }

            override fun onFlashFailed(message: String) = runOnUiThread {
                flashingKind = null
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                detectionStatus.text = "Flash stopped safely: $message"
                setGlobalStatus("FLASH FAILED")
                updateFirmwareCards()
            }
        })
    }

    private fun requestBruceWifi(ssid: String, password: String) {
        pendingBruceNetDetection = false
        pendingGhostNetDetection = false
        requestWifi(ssid, password, FirmwareKind.BRUCE)
    }

    private fun requestGhostNet() {
        pendingBruceNetDetection = false
        pendingGhostNetDetection = false
        requestWifi(DEFAULT_GHOSTNET_SSID, DEFAULT_GHOSTNET_PASSWORD, FirmwareKind.GHOSTESP)
    }

    private fun requestWifi(ssid: String, password: String, kind: FirmwareKind) {
        requestedNetworkKind = kind
        val permissions = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            Build.VERSION.SDK_INT >= 31 -> arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            beginWifiRequest(ssid, password)
        } else {
            pendingWifi = ssid to password
            requestPermissions(permissions, WIFI_PERMISSION_REQUEST)
        }
    }

    private fun beginWifiRequest(ssid: String, password: String) {
        val replacing = activeNetworkKind?.takeIf { it != requestedNetworkKind }
        activeNetworkKind = null
        when (replacing) {
            FirmwareKind.BRUCE -> bruceController.onNetworkLost()
            FirmwareKind.GHOSTESP -> ghostController.onNetworkLost()
            else -> Unit
        }
        networkManager.request(ssid, password)
    }

    private fun networkKindForSsid(ssid: String?): FirmwareKind? = when (ssid) {
        DEFAULT_BRUCENET_SSID -> FirmwareKind.BRUCE
        DEFAULT_GHOSTNET_SSID -> FirmwareKind.GHOSTESP
        else -> null
    }

    private fun requestPhoneGps(enabled: Boolean) {
        if (!enabled) {
            stopPhoneGps()
            return
        }
        phoneGpsRequested = true
        val permissions = arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            startPhoneGps()
        } else requestPermissions(permissions, PHONE_GPS_PERMISSION_REQUEST)
    }

    @SuppressLint("MissingPermission")
    private fun startPhoneGps() {
        if (!phoneGpsRequested) return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { phoneLocationManager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            phoneGpsRequested = false
            bruceController.onPhoneGpsError("Enable Android location services, then try again")
            return
        }
        var registered = false
        providers.forEach { provider ->
            runCatching {
                phoneLocationManager.requestLocationUpdates(
                    provider, 5_000L, 0f, phoneLocationListener, mainLooper,
                )
                registered = true
            }
        }
        if (!registered) {
            phoneGpsRequested = false
            bruceController.onPhoneGpsError("Android could not start location updates")
            return
        }
        bruceController.onPhoneGpsStarted()
        providers.mapNotNull {
            runCatching { phoneLocationManager.getLastKnownLocation(it) }.getOrNull()
        }.maxByOrNull(Location::getTime)?.let(bruceController::submitPhoneLocation)
    }

    private fun stopPhoneGps() {
        phoneGpsRequested = false
        if (::phoneLocationManager.isInitialized) {
            runCatching { phoneLocationManager.removeUpdates(phoneLocationListener) }
        }
        if (::bruceController.isInitialized) bruceController.onPhoneGpsStopped()
    }

    private fun requestPhoneWifi(enabled: Boolean) {
        if (!enabled) {
            stopPhoneWifi()
            return
        }
        phoneWifiRequested = true
        val permissions = phoneWifiPermissions()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            startPhoneWifi()
        } else requestPermissions(permissions, PHONE_WIFI_PERMISSION_REQUEST)
    }

    private fun phoneWifiPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()

    private fun startPhoneWifi() {
        if (!phoneWifiRequested) return
        phoneWifiScanner.start()
    }

    private fun stopPhoneWifi() {
        phoneWifiRequested = false
        if (::phoneWifiScanner.isInitialized) phoneWifiScanner.stop()
        if (::bruceController.isInitialized) bruceController.onPhoneWifiStopped()
    }

    override fun onPhoneWifiStarted() = runOnUiThread {
        bruceController.onPhoneWifiStarted()
    }

    override fun onPhoneWifiStopped() = runOnUiThread {
        bruceController.onPhoneWifiStopped()
    }

    override fun onPhoneWifiStatus(message: String) = runOnUiThread {
        bruceController.onPhoneWifiStatus(message)
    }

    override fun onPhoneWifiBatch(observations: List<PhoneWifiObservation>) = runOnUiThread {
        bruceController.submitPhoneWifiBatch(observations)
    }

    override fun onPhoneWifiError(message: String) = runOnUiThread {
        phoneWifiRequested = false
        bruceController.onPhoneWifiError(message)
    }

    private fun requestBruceFieldLogExport(fileName: String) {
        requestBruceExport(fileName, fileName, "application/x-ndjson", false)
    }

    private fun requestBruceDeviceFileExport(path: String) {
        val name = path.substringAfterLast('/').ifBlank { "bruce-file.bin" }
        requestBruceExport(name, path, mimeTypeFor(name), true)
    }

    private fun requestBruceExport(
        name: String,
        path: String,
        mimeType: String,
        deviceFile: Boolean,
    ) {
        pendingBruceExportName = name
        pendingBruceExportPath = path
        pendingBruceExportDevice = deviceFile
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, name)
        }
        runCatching { startActivityForResult(intent, BRUCE_EXPORT_REQUEST) }
            .onFailure {
                clearPendingBruceExport()
                bruceController.onExportError("No Android document provider is available")
            }
    }

    private fun finishBruceExport(resultCode: Int, destination: Uri?) {
        val name = pendingBruceExportName
        val path = pendingBruceExportPath
        val deviceFile = pendingBruceExportDevice
        clearPendingBruceExport()
        if (resultCode == RESULT_OK && name != null && path != null && destination != null) {
            if (deviceFile) bruceController.exportDeviceFile(path, destination)
            else bruceController.exportFieldLog(path, destination)
        } else bruceController.onExportCancelled()
    }

    private fun requestMarauderExport(request: MarauderExportRequest) {
        clearPendingMarauderExport()
        val temporary = runCatching {
            File.createTempFile("marauder-export-", ".tmp", cacheDir).apply {
                writeBytes(request.content)
            }
        }.getOrElse {
            marauderController.onExportError("Could not prepare the export: ${it.message}")
            return
        }
        pendingMarauderExportPath = temporary.absolutePath
        pendingMarauderExportName = request.suggestedName
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = request.mimeType
            putExtra(Intent.EXTRA_TITLE, request.suggestedName)
        }
        runCatching { startActivityForResult(intent, MARAUDER_EXPORT_REQUEST) }
            .onFailure {
                clearPendingMarauderExport()
                marauderController.onExportError("No Android document provider is available")
            }
    }

    private fun finishMarauderExport(resultCode: Int, destination: Uri?) {
        val path = pendingMarauderExportPath
        val name = pendingMarauderExportName
        if (resultCode != RESULT_OK || destination == null || path == null || name == null) {
            clearPendingMarauderExport()
            marauderController.onExportCancelled()
            return
        }
        runCatching {
            copyPreparedDocument(File(path)) {
                contentResolver.openOutputStream(destination, "w")
                    ?: throw IllegalStateException("Android could not open the selected destination")
            }
        }.onSuccess {
            clearPendingMarauderExport()
            marauderController.onExportSaved(name)
        }.onFailure { error ->
            clearPendingMarauderExport()
            marauderController.onExportError(
                "Export failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun requestGhostExport(request: GhostExportRequest) {
        clearPendingGhostExport()
        val temporary = runCatching {
            File.createTempFile("ghost-export-", ".tmp", cacheDir).apply {
                writeBytes(request.content)
            }
        }.getOrElse {
            ghostController.onExportError("Could not prepare the export: ${it.message}")
            return
        }
        pendingGhostExportPath = temporary.absolutePath
        pendingGhostExportName = request.suggestedName
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = request.mimeType
            putExtra(Intent.EXTRA_TITLE, request.suggestedName)
        }
        runCatching { startActivityForResult(intent, GHOST_EXPORT_REQUEST) }
            .onFailure {
                clearPendingGhostExport()
                ghostController.onExportError("No Android document provider is available")
            }
    }

    private fun finishGhostExport(resultCode: Int, destination: Uri?) {
        val path = pendingGhostExportPath
        val name = pendingGhostExportName
        if (resultCode != RESULT_OK || destination == null || path == null || name == null) {
            clearPendingGhostExport()
            ghostController.onExportCancelled()
            return
        }
        runCatching {
            copyPreparedDocument(File(path)) {
                contentResolver.openOutputStream(destination, "w")
                    ?: throw IllegalStateException("Android could not open the selected destination")
            }
        }.onSuccess {
            clearPendingGhostExport()
            ghostController.onExportSaved(name)
        }.onFailure { error ->
            clearPendingGhostExport()
            ghostController.onExportError(
                "Export failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun restoreExportState(state: Bundle?) {
        pendingBruceExportName = state?.getString(STATE_BRUCE_EXPORT_NAME)
        pendingBruceExportPath = state?.getString(STATE_BRUCE_EXPORT_PATH)
        pendingBruceExportDevice = state?.getBoolean(STATE_BRUCE_EXPORT_DEVICE) ?: false
        pendingMarauderExportName = state?.getString(STATE_MARAUDER_EXPORT_NAME)
        pendingMarauderExportPath = state?.getString(STATE_MARAUDER_EXPORT_PATH)
        pendingGhostExportName = state?.getString(STATE_GHOST_EXPORT_NAME)
        pendingGhostExportPath = state?.getString(STATE_GHOST_EXPORT_PATH)
    }

    private fun clearPendingBruceExport() {
        pendingBruceExportName = null
        pendingBruceExportPath = null
        pendingBruceExportDevice = false
    }

    private fun clearPendingMarauderExport() {
        pendingMarauderExportPath?.let { path -> runCatching { File(path).delete() } }
        pendingMarauderExportName = null
        pendingMarauderExportPath = null
    }

    private fun clearPendingGhostExport() {
        pendingGhostExportPath?.let { path -> runCatching { File(path).delete() } }
        pendingGhostExportName = null
        pendingGhostExportPath = null
    }

    private fun mimeTypeFor(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "txt", "log", "conf", "ini", "json", "ndjson", "csv", "js", "bjs" -> "text/plain"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }

    private fun setGlobalStatus(status: String) {
        if (Thread.currentThread() == mainLooper.thread) globalStatus.text = status
        else runOnUiThread { globalStatus.text = status }
    }

    private fun showConnectionMenu() {
        PopupMenu(this, globalStatus).apply {
            menu.add(0, MENU_DETECT_USB, 0, "USB Detect")
            menu.add(0, MENU_CONNECT_BRUCENET, 1, "Connect BruceNet")
            menu.add(0, MENU_CONNECT_GHOSTNET, 2, "Connect GhostNet")
            menu.add(0, MENU_CHOOSE_ANDROID_STORAGE, 3, AndroidStorageRouting.capacityLabel(this@MainActivity))
            menu.add(0, MENU_SYNC_ANDROID_STORAGE, 4, "Sync Android storage now")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_DETECT_USB -> startUsbDetection()
                    MENU_CONNECT_BRUCENET -> startBruceNetDetection()
                    MENU_CONNECT_GHOSTNET -> startGhostNetDetection()
                    MENU_CHOOSE_ANDROID_STORAGE -> chooseAndroidStorage()
                    MENU_SYNC_ANDROID_STORAGE -> syncAndroidStorage()
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    private fun chooseAndroidStorage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            AndroidStorageRouting.selectedRoot(this@MainActivity)?.let {
                putExtra("android.provider.extra.INITIAL_URI", it)
            }
        }
        runCatching { startActivityForResult(intent, ANDROID_STORAGE_TREE_REQUEST) }
            .onFailure {
                Toast.makeText(this, "No Android folder provider is available", Toast.LENGTH_LONG)
                    .show()
            }
    }

    @SuppressLint("WrongConstant")
    private fun finishAndroidStorageSelection(resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val permissions = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching {
            contentResolver.takePersistableUriPermission(uri, permissions)
            AndroidStorageRouting.selectRoot(this, uri)
        }.onSuccess {
            refreshAndroidStorageStatus()
            Toast.makeText(this, "Android storage selected; continuous sync is active", Toast.LENGTH_LONG)
                .show()
            syncAndroidStorage()
        }.onFailure { error ->
            Toast.makeText(
                this,
                "Could not retain access to that folder: ${error.message}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun syncAndroidStorage() {
        val kinds = PersistentDeviceConnections.activeKinds()
        if (kinds.isEmpty()) {
            Toast.makeText(
                this,
                "Connect a firmware over USB or Bluetooth first",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (AndroidStorageRouting.selectedRoot(this) == null) {
            chooseAndroidStorage()
            return
        }
        setGlobalStatus("STORAGE SYNC")
        val messages = mutableListOf<String>()
        val messageLock = Any()
        kinds.forEach { kind ->
            AndroidStorageRouting.syncNow(this, kind) { message ->
                val completeMessages = synchronized(messageLock) {
                    messages += message
                    if (messages.size == kinds.size) messages.toList() else null
                }
                if (completeMessages != null) runOnUiThread {
                    Toast.makeText(
                        this,
                        completeMessages.joinToString("\n"),
                        Toast.LENGTH_LONG,
                    ).show()
                    refreshAndroidStorageStatus()
                    setGlobalStatus(
                        if (PersistentDeviceConnections.activeKinds().isNotEmpty()) {
                            "DEVICE READY"
                        } else {
                            "READY"
                        },
                    )
                }
            }
        }
    }

    private fun refreshAndroidStorageStatus() {
        if (!::androidStorageStatus.isInitialized) return
        androidStorageStatus.text = AndroidStorageRouting.capacityLabel(this)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerUsbDetachReceiver() {
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbDetachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbDetachReceiver, filter)
        }
    }

    private val FirmwareKind.displayName: String
        get() = when (this) {
            FirmwareKind.BRUCE -> "Bruce"
            FirmwareKind.MARAUDER -> "Marauder"
            FirmwareKind.GHOSTESP -> "GhostESP"
            FirmwareKind.UNKNOWN -> "Unknown"
        }

    private fun FirmwareKind.toPersistentUsbKind(): PersistentUsbKind = when (this) {
        FirmwareKind.BRUCE -> PersistentUsbKind.BRUCE
        FirmwareKind.GHOSTESP -> PersistentUsbKind.GHOSTESP
        FirmwareKind.MARAUDER -> PersistentUsbKind.MARAUDER
        FirmwareKind.UNKNOWN -> error("Unknown firmware cannot own a USB session")
    }

    private fun PersistentUsbKind.toFirmwareKind(): FirmwareKind = when (this) {
        PersistentUsbKind.BRUCE -> FirmwareKind.BRUCE
        PersistentUsbKind.GHOSTESP -> FirmwareKind.GHOSTESP
        PersistentUsbKind.MARAUDER -> FirmwareKind.MARAUDER
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
