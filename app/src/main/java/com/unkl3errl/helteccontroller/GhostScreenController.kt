package com.unkl3errl.helteccontroller

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.unkl3errl.helteccontroller.ghost.GhostApiClient
import com.unkl3errl.helteccontroller.ghost.GhostUsbSerial
import com.unkl3errl.helteccontroller.guided.GuidedCommandDialog
import com.unkl3errl.helteccontroller.guided.GuidedFirmware
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class GhostExportRequest(
    val suggestedName: String,
    val mimeType: String,
    val content: ByteArray,
)

@SuppressLint("ClickableViewAccessibility")
class GhostScreenController(
    private val activity: Activity,
    private val root: View,
    private val requestGhostNet: () -> Unit,
    private val requestBluetooth: () -> Unit,
    private val requestExport: (GhostExportRequest) -> Unit,
    private val setGlobalStatus: (String) -> Unit,
) : GhostUsbSerial.Listener {
    private companion object {
        const val MAX_CONSOLE_CHARS = 40_000
        const val RETAINED_CONSOLE_CHARS = 32_000
    }

    private val usbStatus: TextView = root.findViewById(R.id.ghostUsbStatus)
    private val bluetoothStatus: TextView = root.findViewById(R.id.ghostBluetoothStatus)
    private val networkStatus: TextView = root.findViewById(R.id.ghostNetStatus)
    private val console: TextView = root.findViewById(R.id.ghostConsole)
    private val consoleLive: Button = root.findViewById(R.id.ghostConsoleLive)
    private val commandInput: EditText = root.findViewById(R.id.ghostCommand)
    private var consoleFile = File(activity.filesDir, "ghost-console-latest.txt")
    private var currentConnectionId = "GHOSTESP:none"
    private val consoleBuffer = StringBuilder(restoreConsole(consoleFile))
    private val consoleText = SerialConsoleText()
    private val protocolFilter = SerialConsoleProtocolFilter()
    private val executor = Executors.newSingleThreadExecutor()
    private val persistHandler = Handler(Looper.getMainLooper())
    private val persistRunnable = Runnable(::writeConsoleSnapshot)
    private val destroyed = AtomicBoolean(false)
    private val serial = GhostUsbSerial(activity, this)
    private val connectivityManager =
        activity.getSystemService(Activity.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var activeNetwork: Network? = null
    private var consoleFollowing = true
    private var webUiDialog: Dialog? = null

    init {
        console.text = consoleBuffer.toString()
        console.movementMethod = ScrollingMovementMethod()
        console.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    setConsoleFollowing(false)
                    view.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        root.findViewById<Button>(R.id.ghostUsbConnect).setOnClickListener(::onUsbConnectClicked)
        root.findViewById<Button>(R.id.ghostUsbDisconnect).setOnClickListener {
            serial.disconnectUsb()
            if (!serial.isUsbConnected) {
                usbStatus.text = "GhostESP USB disconnected"
                updateGlobalStatus()
            }
        }
        root.findViewById<Button>(R.id.ghostBluetoothConnect).setOnClickListener {
            bluetoothStatus.text = "Scanning for the upstream GhostESP BLE Bridge…"
            requestBluetooth()
        }
        root.findViewById<Button>(R.id.ghostBluetoothDisconnect).setOnClickListener {
            serial.disconnectBluetooth()
            bluetoothStatus.text = "GhostESP Bluetooth disconnected"
            updateGlobalStatus()
        }
        root.findViewById<Button>(R.id.ghostNetConnect).setOnClickListener {
            networkStatus.text = "Waiting for Android to approve GhostNet…"
            setGlobalStatus("JOINING GHOSTNET…")
            requestGhostNet()
        }
        root.findViewById<Button>(R.id.ghostNetRefresh).setOnClickListener {
            refreshGhostNet()
        }
        root.findViewById<Button>(R.id.ghostOpenWebUi).setOnClickListener {
            openWebUi()
        }

        bindCommand(R.id.ghostCmdHelp, "help")
        bindCommand(R.id.ghostCmdVersion, "version")
        bindCommand(R.id.ghostCmdStatus, "status")
        bindCommand(R.id.ghostCmdChipInfo, "chipinfo")
        bindCommand(R.id.ghostCmdGpsInfo, "gpsinfo")
        bindCommand(R.id.ghostCmdWifiStatus, "wifistatus")
        bindCommand(R.id.ghostCmdStop, "stop")
        root.findViewById<Button>(R.id.ghostGuidedCommands).setOnClickListener {
            GuidedCommandDialog.show(activity, GuidedFirmware.GHOSTESP) { _, command ->
                sendCommand(command)
            }
        }

        root.findViewById<Button>(R.id.ghostConsolePageUp).setOnClickListener {
            scrollConsoleBy(-(console.height * 3 / 4).coerceAtLeast(1))
        }
        root.findViewById<Button>(R.id.ghostConsolePageDown).setOnClickListener {
            scrollConsoleBy((console.height * 3 / 4).coerceAtLeast(1))
        }
        consoleLive.setOnClickListener {
            setConsoleFollowing(true)
            console.post(::scrollConsoleToBottom)
        }
        root.findViewById<Button>(R.id.ghostConsoleClear).setOnClickListener {
            persistHandler.removeCallbacks(persistRunnable)
            consoleBuffer.clear()
            consoleText.reset()
            protocolFilter.reset()
            console.text = ""
            runCatching { consoleFile.delete() }
            setConsoleFollowing(true)
            console.scrollTo(0, 0)
        }

        root.findViewById<Button>(R.id.ghostSend).setOnClickListener { sendInput() }
        commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendInput()
                true
            } else {
                false
            }
        }
        root.findViewById<Button>(R.id.ghostExportConsole).setOnClickListener {
            exportConsole()
        }

        usbStatus.text = "GhostESP detected · opening USB after firmware detection"
    }

    fun onNetworkAvailable(network: Network) {
        if (destroyed.get()) return
        activeNetwork = network
        onUi {
            if (destroyed.get()) return@onUi
            networkStatus.text = "GhostNet local link available · refreshing…"
            setGlobalStatus(
                when {
                    serial.isUsbConnected -> "GHOST USB"
                    serial.isBluetoothConnected -> "GHOST BLUETOOTH"
                    else -> "GHOSTNET"
                },
            )
            refreshGhostNet()
        }
    }

    fun connectUsb() {
        if (destroyed.get()) return
        usbStatus.text = "Opening the detected GhostESP USB link…"
        setGlobalStatus("USB CONNECTING…")
        serial.connect()
    }

    fun onDeviceSelected(connectionId: String) {
        if (destroyed.get()) return
        if (currentConnectionId != connectionId) {
            persistHandler.removeCallbacks(persistRunnable)
            writeConsoleSnapshot()
            currentConnectionId = connectionId
            consoleFile = File(
                activity.filesDir,
                "ghost-console-${connectionId.hashCode().toUInt().toString(16)}.txt",
            )
            consoleBuffer.clear()
            consoleBuffer.append(
                restoreConsole(consoleFile).ifBlank { "Selected GhostESP device.\n" },
            )
            consoleText.reset()
            protocolFilter.reset()
            console.text = consoleBuffer.toString()
            setConsoleFollowing(true)
            console.post(::scrollConsoleToBottom)
        }
        updateTransportStatus()
        updateGlobalStatus()
    }

    fun onNetworkLost() {
        activeNetwork = null
        onUi {
            if (destroyed.get()) return@onUi
            networkStatus.text = "GhostNet local link was disconnected"
            appendConsole("\n[network] GhostNet disconnected\n")
            updateGlobalStatus()
        }
    }

    fun onNetworkError(message: String) {
        activeNetwork = null
        onUi {
            if (destroyed.get()) return@onUi
            networkStatus.text = message
            appendConsole("\n[network error] $message\n")
            updateGlobalStatus()
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }

    fun onExportSaved(fileName: String) = onUi {
        if (destroyed.get()) return@onUi
        updateGlobalStatus()
        Toast.makeText(activity, "Saved $fileName", Toast.LENGTH_LONG).show()
    }

    fun onExportCancelled() = onUi {
        if (!destroyed.get()) updateGlobalStatus()
    }

    fun onExportError(message: String) = onUi {
        if (destroyed.get()) return@onUi
        setGlobalStatus("EXPORT ERROR")
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        activeNetwork = null
        webUiDialog?.dismiss()
        webUiDialog = null
        persistHandler.removeCallbacks(persistRunnable)
        writeConsoleSnapshot()
        executor.shutdownNow()
        serial.destroy()
    }

    override fun onSerialStatus(message: String, connected: Boolean) = onUi {
        if (destroyed.get()) return@onUi
        updateTransportStatus(message)
        updateGlobalStatus()
    }

    private fun updateTransportStatus(latestMessage: String? = null) {
        val labels = TransportStatusLabeler.labels(
            deviceName = "GhostESP",
            usbConnected = serial.isUsbConnected,
            bluetoothConnected = serial.isBluetoothConnected,
            latestMessage = latestMessage,
        )
        usbStatus.text = labels.usb
        bluetoothStatus.text = labels.bluetooth
    }

    override fun onSerialData(data: ByteArray) = onUi {
        val visible = protocolFilter.filter(data.toString(Charsets.UTF_8))
        if (!destroyed.get() && visible.isNotEmpty()) appendConsole(visible)
    }

    override fun onSerialError(message: String) = onUi {
        if (destroyed.get()) return@onUi
        appendConsole("\n[usb error] $message\n")
        setGlobalStatus("USB ERROR")
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private fun onUsbConnectClicked(@Suppress("UNUSED_PARAMETER") view: View) {
        usbStatus.text = "Opening the detected GhostESP USB link…"
        setGlobalStatus("USB CONNECTING…")
        serial.connect()
    }

    private fun bindCommand(buttonId: Int, command: String) {
        root.findViewById<Button>(buttonId).setOnClickListener { sendCommand(command) }
    }

    private fun sendInput() {
        val command = commandInput.text.toString()
        if (command.isEmpty()) return
        sendCommand(command) { commandInput.text.clear() }
    }

    private fun sendCommand(command: String, sent: () -> Unit = {}) {
        if (serial.isConnected) {
            appendConsole("\n> $command\n")
            serial.writeCommand(command) {
                if (command.trim().startsWith("sd", ignoreCase = true)) {
                    protocolFilter.showNextStorageResponse()
                }
            }
            sent()
            return
        }

        val network = activeNetwork
        if (network == null) {
            val message = "Connect GhostESP over USB, Bluetooth, or GhostNet first"
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            networkStatus.text = message
            return
        }

        appendConsole("\n> $command\n")
        sent()
        executeHttp {
            val response = GhostApiClient(network).sendCommand(command)
            onUi {
                if (destroyed.get()) return@onUi
                val body = response.body
                appendConsole(
                    if (body.isBlank()) "[http ${response.status}] Command accepted\n"
                    else "[http ${response.status}]\n$body\n",
                )
                setGlobalStatus("GHOSTNET")
            }
        }
    }

    private fun refreshGhostNet() {
        val network = activeNetwork
        if (network == null) {
            val message = "Connect to GhostNet before refreshing"
            networkStatus.text = message
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            return
        }

        networkStatus.text = "Refreshing Web UI, settings, and logs…"
        setGlobalStatus("GHOSTNET REFRESH…")
        executeHttp(showDefaultError = false) {
            val client = GhostApiClient(network)
            val rootResult = runCatching { client.getRoot() }
            val settingsResult = runCatching { client.getSettings() }
            val logsResult = runCatching { client.getLogs() }
            onUi {
                if (destroyed.get()) return@onUi
                var successes = 0
                rootResult.onSuccess { html ->
                    successes++
                    appendConsole(
                        "\n[http] GET / · ${html.toByteArray(Charsets.UTF_8).size} bytes\n",
                    )
                }.onFailure { appendHttpFailure("/", it) }
                settingsResult.onSuccess { settings ->
                    successes++
                    appendConsole("[settings]\n${settings.toString(2)}\n")
                }.onFailure { appendHttpFailure("/api/settings", it) }
                logsResult.onSuccess { logs ->
                    successes++
                    appendConsole("[logs]\n$logs\n")
                }.onFailure { appendHttpFailure("/api/logs", it) }

                networkStatus.text = when (successes) {
                    3 -> "GhostNet ready · Web UI, settings, and logs refreshed"
                    0 -> "GhostNet refresh failed · all three endpoints unavailable"
                    else -> "GhostNet reachable · refreshed $successes of 3 endpoints"
                }
                setGlobalStatus(if (successes == 0) "GHOSTNET ERROR" else "GHOSTNET")
            }
        }
    }

    private fun appendHttpFailure(path: String, error: Throwable) {
        appendConsole("[http error] GET $path · ${error.message ?: error.javaClass.simpleName}\n")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openWebUi() {
        webUiDialog?.dismiss()
        val network = activeNetwork
        if (network == null) {
            Toast.makeText(activity, "Join GhostNet before opening the Web UI", Toast.LENGTH_LONG).show()
            return
        }
        if (!connectivityManager.bindProcessToNetwork(network)) {
            Toast.makeText(
                activity,
                "Android could not bind the Web UI to GhostNet",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val webUiUrl = GhostApiClient.DEFAULT_BASE_URL
        val baseUri = Uri.parse(webUiUrl)
        val webView = runCatching {
            WebView(activity).apply {
                setBackgroundColor(Color.rgb(7, 16, 20))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val target = request.url
                        val local = target.scheme == baseUri.scheme &&
                            target.host == baseUri.host &&
                            target.port == baseUri.port
                        if (!local) {
                            Toast.makeText(
                                activity,
                                "Blocked navigation outside the GhostESP Web UI",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        return !local
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        networkStatus.text = "GhostNet ready · Web UI loaded"
                        setGlobalStatus("GHOSTNET")
                    }
                }
                loadUrl(webUiUrl)
            }
        }.getOrElse { error ->
            connectivityManager.bindProcessToNetwork(null)
            Toast.makeText(
                activity,
                "Could not open the embedded Web UI: " +
                    (error.message ?: error.javaClass.simpleName),
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
        val title = TextView(activity).apply {
            text = "GhostESP Web UI · local device"
            textSize = 20f
            setTextColor(activity.getColor(R.color.text))
            setPadding(24, 20, 24, 20)
        }
        val close = Button(activity).apply { text = "CLOSE" }
        val browser = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(activity.getColor(R.color.bg))
            addView(
                title,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                webView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            addView(
                close,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        dialog.setContentView(browser)
        dialog.setCanceledOnTouchOutside(false)
        close.setOnClickListener { dialog.dismiss() }
        webUiDialog = dialog
        dialog.setOnDismissListener {
            webView.stopLoading()
            webView.destroy()
            connectivityManager.bindProcessToNetwork(null)
            if (webUiDialog === dialog) webUiDialog = null
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(activity.getColor(R.color.bg)))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    private fun exportConsole() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        setGlobalStatus("EXPORTING…")
        requestExport(
            GhostExportRequest(
                suggestedName = "ghost-console-$stamp.txt",
                mimeType = "text/plain",
                content = consoleBuffer.toString().toByteArray(Charsets.UTF_8),
            ),
        )
    }

    private fun executeHttp(showDefaultError: Boolean = true, action: () -> Unit) {
        if (destroyed.get()) return
        runCatching {
            executor.execute {
                try {
                    action()
                } catch (error: Exception) {
                    if (!showDefaultError) return@execute
                    onUi {
                        if (destroyed.get()) return@onUi
                        val message = error.message ?: error.javaClass.simpleName
                        appendConsole("[http error] $message\n")
                        networkStatus.text = "GhostNet request failed · $message"
                        setGlobalStatus("GHOSTNET ERROR")
                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.onFailure { error ->
            if (!destroyed.get() && showDefaultError) {
                val message = error.message ?: error.javaClass.simpleName
                appendConsole("[http error] $message\n")
                networkStatus.text = "GhostNet request failed · $message"
                setGlobalStatus("GHOSTNET ERROR")
            }
        }
    }

    private fun appendConsole(text: String) {
        consoleBuffer.append(consoleText.normalize(text))
        if (consoleBuffer.length > MAX_CONSOLE_CHARS) {
            consoleBuffer.delete(0, consoleBuffer.length - RETAINED_CONSOLE_CHARS)
        }
        console.text = consoleBuffer.toString()
        persistHandler.removeCallbacks(persistRunnable)
        persistHandler.postDelayed(persistRunnable, 300L)
        if (consoleFollowing) console.post(::scrollConsoleToBottom)
    }

    private fun scrollConsoleBy(delta: Int) {
        setConsoleFollowing(false)
        console.scrollTo(0, (console.scrollY + delta).coerceIn(0, maxConsoleScroll()))
    }

    private fun scrollConsoleToBottom() {
        console.scrollTo(0, maxConsoleScroll())
    }

    private fun maxConsoleScroll(): Int {
        val layout = console.layout ?: return 0
        return (
            layout.getLineTop(console.lineCount) - console.height +
                console.totalPaddingTop + console.totalPaddingBottom
            ).coerceAtLeast(0)
    }

    private fun setConsoleFollowing(following: Boolean) {
        consoleFollowing = following
        consoleLive.text = if (following) "LIVE ON" else "LIVE"
    }

    fun refreshGlobalStatus() = updateGlobalStatus()

    private fun updateGlobalStatus() {
        setGlobalStatus(
            when {
                serial.isUsbConnected -> "GHOST USB"
                serial.isBluetoothConnected -> "GHOST BLUETOOTH"
                activeNetwork != null -> "GHOSTNET"
                else -> "IDLE"
            },
        )
    }

    private fun onUi(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else activity.runOnUiThread(action)
    }

    private fun writeConsoleSnapshot() {
        runCatching { consoleFile.writeText(consoleBuffer.toString(), Charsets.UTF_8) }
    }

    private fun restoreConsole(file: File): String {
        val restored = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?.takeLast(MAX_CONSOLE_CHARS)
            .orEmpty()
        return restored.ifEmpty { "Connect to begin.\n" }
    }
}
