package com.unkl3errl.helteccontroller

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.unkl3errl.helteccontroller.bruce.BruceApiClient
import com.unkl3errl.helteccontroller.bruce.BruceNetworkManager
import com.unkl3errl.helteccontroller.bruce.PhoneWifiObservation
import com.unkl3errl.helteccontroller.detection.DetectionSource
import com.unkl3errl.helteccontroller.detection.FirmwareDetection
import com.unkl3errl.helteccontroller.detection.FirmwareIdentity
import com.unkl3errl.helteccontroller.detection.FirmwareKind
import com.unkl3errl.helteccontroller.detection.UsbFirmwareDetector
import java.io.File
import java.util.concurrent.Executors

class MainActivity :
    Activity(),
    BruceNetworkManager.Listener,
    AndroidWifiFieldScanner.Listener,
    UsbFirmwareDetector.Listener {
    companion object {
        private const val WIFI_PERMISSION_REQUEST = 2001
        private const val BRUCE_EXPORT_REQUEST = 2002
        private const val PHONE_GPS_PERMISSION_REQUEST = 2003
        private const val PHONE_WIFI_PERMISSION_REQUEST = 2004
        private const val MARAUDER_EXPORT_REQUEST = 3001
        private const val STATE_BRUCE_EXPORT_NAME = "pendingBruceExportName"
        private const val STATE_BRUCE_EXPORT_PATH = "pendingBruceExportPath"
        private const val STATE_BRUCE_EXPORT_DEVICE = "pendingBruceExportDevice"
        private const val STATE_MARAUDER_EXPORT_NAME = "pendingMarauderExportName"
        private const val STATE_MARAUDER_EXPORT_PATH = "pendingMarauderExportPath"
        private const val DEFAULT_BRUCENET_SSID = "BruceNet"
        private const val DEFAULT_BRUCENET_PASSWORD = "brucenet"
        private const val DEFAULT_BRUCE_URL = "http://172.0.0.1"
    }

    private lateinit var globalStatus: TextView
    private lateinit var appSubtitle: TextView
    private lateinit var detectionStatus: TextView
    private lateinit var container: FrameLayout
    private lateinit var tabBruce: Button
    private lateinit var tabMarauder: Button
    private lateinit var bruceView: View
    private lateinit var marauderView: View
    private lateinit var bruceController: BruceScreenController
    private lateinit var marauderController: MarauderScreenController
    private lateinit var networkManager: BruceNetworkManager
    private lateinit var usbDetector: UsbFirmwareDetector
    private lateinit var phoneLocationManager: LocationManager
    private lateinit var phoneWifiScanner: AndroidWifiFieldScanner

    private val client = BruceApiClient()
    private val detectorExecutor = Executors.newSingleThreadExecutor()
    private var detectedFirmware: FirmwareDetection? = null
    private var pendingWifi: Pair<String, String>? = null
    private var pendingBruceNetDetection = false
    private var pendingBruceExportName: String? = null
    private var pendingBruceExportPath: String? = null
    private var pendingBruceExportDevice = false
    private var pendingMarauderExportName: String? = null
    private var pendingMarauderExportPath: String? = null
    private var phoneGpsRequested = false
    private var phoneWifiRequested = false

    @Suppress("DEPRECATION")
    private val controllerVersionName: String by lazy {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }

    private val phoneLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = bruceController.submitPhoneLocation(location)
    }

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (
                intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED &&
                detectedFirmware?.source == DetectionSource.USB
            ) {
                runOnUiThread {
                    clearDetection("USB board disconnected. Connect a board and detect its firmware again.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        restoreExportState(savedInstanceState)

        globalStatus = findViewById(R.id.globalStatus)
        appSubtitle = findViewById(R.id.appSubtitle)
        detectionStatus = findViewById(R.id.detectionStatus)
        container = findViewById(R.id.screenContainer)
        tabBruce = findViewById(R.id.tabBruce)
        tabMarauder = findViewById(R.id.tabMarauder)
        networkManager = BruceNetworkManager(this, this)
        usbDetector = UsbFirmwareDetector(this, this)
        phoneLocationManager = getSystemService(LocationManager::class.java)
        phoneWifiScanner = AndroidWifiFieldScanner(this, this)
        registerUsbDetachReceiver()

        val inflater = LayoutInflater.from(this)
        bruceView = inflater.inflate(R.layout.screen_bruce, container, false)
        marauderView = inflater.inflate(R.layout.screen_marauder, container, false)
        bruceController = BruceScreenController(
            activity = this,
            root = bruceView,
            client = client,
            requestWifi = ::requestBruceWifi,
            requestPhoneGps = ::requestPhoneGps,
            requestPhoneWifi = ::requestPhoneWifi,
            requestFieldLogExport = ::requestBruceFieldLogExport,
            requestDeviceFileExport = ::requestBruceDeviceFileExport,
            setGlobalStatus = ::setGlobalStatus,
        )
        marauderController = MarauderScreenController(
            activity = this,
            root = marauderView,
            requestExport = ::requestMarauderExport,
            setGlobalStatus = ::setGlobalStatus,
        )

        findViewById<Button>(R.id.detectUsb).setOnClickListener { startUsbDetection() }
        findViewById<Button>(R.id.detectBruceNet).setOnClickListener { startBruceNetDetection() }
        tabBruce.setOnClickListener {
            if (detectedFirmware?.kind == FirmwareKind.BRUCE) showScreen(bruceView, FirmwareKind.BRUCE)
        }
        tabMarauder.setOnClickListener {
            if (detectedFirmware?.kind == FirmwareKind.MARAUDER) {
                showScreen(marauderView, FirmwareKind.MARAUDER)
            }
        }

        clearDetection(
            "Connect a board by USB, or detect Bruce through BruceNet. " +
                "Firmware tabs stay locked until a signature is verified.",
        )
        container.post {
            if (usbDetector.hasCandidate()) startUsbDetection()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            startUsbDetection()
        }
    }

    override fun onDestroy() {
        stopPhoneWifi()
        stopPhoneGps()
        if (::bruceController.isInitialized) bruceController.destroy()
        if (::marauderController.isInitialized) marauderController.destroy()
        if (::networkManager.isInitialized) networkManager.release()
        if (::usbDetector.isInitialized) usbDetector.destroy()
        runCatching { unregisterReceiver(usbDetachReceiver) }
        detectorExecutor.shutdownNow()
        if (isFinishing) clearPendingMarauderExport()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingBruceExportName?.let { outState.putString(STATE_BRUCE_EXPORT_NAME, it) }
        pendingBruceExportPath?.let { outState.putString(STATE_BRUCE_EXPORT_PATH, it) }
        outState.putBoolean(STATE_BRUCE_EXPORT_DEVICE, pendingBruceExportDevice)
        pendingMarauderExportName?.let { outState.putString(STATE_MARAUDER_EXPORT_NAME, it) }
        pendingMarauderExportPath?.let { outState.putString(STATE_MARAUDER_EXPORT_PATH, it) }
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android; retained for API 29 document-provider compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            BRUCE_EXPORT_REQUEST -> finishBruceExport(resultCode, data?.data)
            MARAUDER_EXPORT_REQUEST -> finishMarauderExport(resultCode, data?.data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
            networkManager.request(request.first, request.second)
        } else if (pendingBruceNetDetection) {
            pendingBruceNetDetection = false
            onUsbDetectionUnknown("Nearby Wi-Fi permission was denied")
        } else {
            bruceController.onNetworkError("Nearby Wi-Fi permission was denied")
        }
    }

    override fun onBruceNetworkAvailable(network: Network) {
        client.network = network
        if (pendingBruceNetDetection || detectedFirmware == null) {
            probeBruceNet()
            return
        }
        runOnUiThread {
            if (detectedFirmware?.kind == FirmwareKind.BRUCE) bruceController.onNetworkAvailable()
        }
    }

    override fun onBruceNetworkLost() {
        client.network = null
        runOnUiThread {
            if (detectedFirmware?.source == DetectionSource.BRUCENET) {
                clearDetection("BruceNet disconnected. Reconnect and detect the firmware again.")
            } else if (detectedFirmware?.kind == FirmwareKind.BRUCE) {
                bruceController.onNetworkLost()
            }
        }
    }

    override fun onBruceNetworkError(message: String) = runOnUiThread {
        if (pendingBruceNetDetection || detectedFirmware == null) {
            pendingBruceNetDetection = false
            detectionStatus.text = message
            setControllerSubtitle("DETECT ERROR")
            setGlobalStatus("DETECT ERROR")
        } else if (detectedFirmware?.kind == FirmwareKind.BRUCE) {
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

    private fun startUsbDetection() {
        pendingBruceNetDetection = false
        clearDetection("Opening the attached board for a read-only firmware identity probe…")
        usbDetector.detect()
    }

    private fun startBruceNetDetection() {
        usbDetector.cancel()
        clearDetection("Requesting the default BruceNet local Wi-Fi link…")
        pendingBruceNetDetection = true
        requestWifi(DEFAULT_BRUCENET_SSID, DEFAULT_BRUCENET_PASSWORD)
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

    private fun applyDetection(detection: FirmwareDetection) {
        detectedFirmware = detection
        val source = when (detection.source) {
            DetectionSource.USB -> "USB"
            DetectionSource.BRUCENET -> "BruceNet"
            DetectionSource.MANUAL -> "manual override"
        }
        detectionStatus.text =
            "Verified ${detection.kind.displayName} via $source · ${detection.evidence}"
        setControllerSubtitle(detection.kind.displayName.uppercase())
        setGlobalStatus("${detection.kind.displayName.uppercase()} READY")
        tabBruce.isEnabled = detection.kind == FirmwareKind.BRUCE
        tabMarauder.isEnabled = detection.kind == FirmwareKind.MARAUDER
        when (detection.kind) {
            FirmwareKind.BRUCE -> {
                showScreen(bruceView, FirmwareKind.BRUCE)
                if (detection.source == DetectionSource.USB) bruceController.onUsbDetected()
                if (client.network != null) bruceController.onNetworkAvailable()
            }
            FirmwareKind.MARAUDER -> showScreen(marauderView, FirmwareKind.MARAUDER)
            FirmwareKind.UNKNOWN -> clearDetection("The firmware signature is unknown.")
        }
    }

    private fun clearDetection(message: String) {
        detectedFirmware = null
        stopPhoneGps()
        stopPhoneWifi()
        detectionStatus.text = message
        setControllerSubtitle("DETECTING")
        tabBruce.isEnabled = false
        tabMarauder.isEnabled = false
        setTabAppearance(FirmwareKind.UNKNOWN)
        container.removeAllViews()
        setGlobalStatus("UNKNOWN")
    }

    private fun setControllerSubtitle(state: String) {
        appSubtitle.text = "CONTROLLER $controllerVersionName // $state"
    }

    private fun showScreen(view: View, selected: FirmwareKind) {
        if (detectedFirmware?.kind != selected) return
        container.removeAllViews()
        container.addView(view)
        setTabAppearance(selected)
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
        tabMarauder.backgroundTintList = when {
            !tabMarauder.isEnabled -> disabled
            selected == FirmwareKind.MARAUDER -> active
            else -> inactive
        }
        tabBruce.setTextColor(
            getColor(if (selected == FirmwareKind.BRUCE) R.color.bg else R.color.text),
        )
        tabMarauder.setTextColor(
            getColor(if (selected == FirmwareKind.MARAUDER) R.color.bg else R.color.text),
        )
    }

    private fun requestBruceWifi(ssid: String, password: String) {
        pendingBruceNetDetection = false
        requestWifi(ssid, password)
    }

    private fun requestWifi(ssid: String, password: String) {
        val permissions = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            Build.VERSION.SDK_INT >= 31 -> arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
            else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            networkManager.request(ssid, password)
        } else {
            pendingWifi = ssid to password
            requestPermissions(permissions, WIFI_PERMISSION_REQUEST)
        }
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
        if (!phoneGpsRequested || detectedFirmware?.kind != FirmwareKind.BRUCE) return
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
        if (!phoneWifiRequested || detectedFirmware?.kind != FirmwareKind.BRUCE) return
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
            contentResolver.openOutputStream(destination, "w")?.use { output ->
                File(path).inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Android could not open the selected destination")
        }.onSuccess {
            clearPendingMarauderExport()
            marauderController.onExportSaved(name)
        }.onFailure { error ->
            runCatching { contentResolver.delete(destination, null, null) }
            clearPendingMarauderExport()
            marauderController.onExportError(
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

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerUsbDetachReceiver() {
        val filter = IntentFilter(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_DETACHED)
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
            FirmwareKind.UNKNOWN -> "Unknown"
        }
}
