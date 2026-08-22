package com.unkl3errl.helteccontroller

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.unkl3errl.helteccontroller.bruce.BruceCommandRisk
import com.unkl3errl.helteccontroller.bruce.BruceCommandSafety
import com.unkl3errl.helteccontroller.bruce.BruceUsbSerial
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("ClickableViewAccessibility")
class BruceUsbConsoleController(
    private val activity: Activity,
    root: View,
    private val setGlobalStatus: (String) -> Unit,
    private val requestBluetooth: () -> Unit,
    private val bridgeState: (Boolean, String) -> Unit,
) : BruceUsbSerial.Listener {
    private val status: TextView = root.findViewById(R.id.bruceUsbStatus)
    private val bluetoothStatus: TextView = root.findViewById(R.id.bruceBluetoothStatus)
    private val console: TextView = root.findViewById(R.id.bruceUsbConsole)
    private val consoleLive: Button = root.findViewById(R.id.bruceUsbConsoleLive)
    private val input: EditText = root.findViewById(R.id.bruceUsbCommand)
    private val serial = BruceUsbSerial(activity, this)
    private val buffer = StringBuilder("Connect the Bruce device to begin.\n")
    private val deviceBuffers = mutableMapOf<String, String>()
    private var currentConnectionId = serial.connectionId
    private val bridgeBuffer = StringBuilder()
    private val bridgeRequests = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
    private val bridgeSequence = AtomicLong()
    private val protocolFilter = SerialConsoleProtocolFilter()
    private var consoleFollowing = true

    val isBridgeConnected: Boolean get() = serial.isConnected

    init {
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
        root.findViewById<Button>(R.id.bruceUsbConnect).setOnClickListener {
            setGlobalStatus("USB CONNECTING…")
            serial.connect()
        }
        root.findViewById<Button>(R.id.bruceUsbDisconnect).setOnClickListener {
            serial.disconnectUsb()
        }
        root.findViewById<Button>(R.id.bruceBluetoothConnect).setOnClickListener {
            bluetoothStatus.text = "Scanning for the Bruce BLE serial service…"
            requestBluetooth()
        }
        root.findViewById<Button>(R.id.bruceBluetoothDisconnect).setOnClickListener {
            serial.disconnectBluetooth()
            bluetoothStatus.text = "Bruce Bluetooth disconnected"
        }
        root.findViewById<Button>(R.id.bruceUsbClear).setOnClickListener {
            buffer.clear()
            consoleText.reset()
            protocolFilter.reset()
            console.text = ""
            setConsoleFollowing(true)
            console.scrollTo(0, 0)
        }
        root.findViewById<Button>(R.id.bruceUsbConsolePageUp).setOnClickListener {
            scrollConsoleBy(-(console.height * 3 / 4).coerceAtLeast(1))
        }
        root.findViewById<Button>(R.id.bruceUsbConsolePageDown).setOnClickListener {
            scrollConsoleBy((console.height * 3 / 4).coerceAtLeast(1))
        }
        consoleLive.setOnClickListener {
            setConsoleFollowing(true)
            console.post(::scrollConsoleToBottom)
        }
        root.findViewById<Button>(R.id.bruceUsbSend).setOnClickListener { sendInput() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendInput()
                true
            } else false
        }
        bind(root, R.id.bruceUsbHelp, "help")
        bind(root, R.id.bruceUsbInfo, "info")
        bind(root, R.id.bruceUsbUptime, "uptime")
        bind(root, R.id.bruceUsbFree, "free")
        bind(root, R.id.bruceUsbOptions, "optionsJSON")
    }

    fun destroy() {
        failBridgeRequests("Bruce device bridge closed")
        serial.destroy()
    }

    fun connectBridge() = serial.connect()

    fun onDeviceSelected(connectionId: String) {
        if (currentConnectionId == connectionId) return
        deviceBuffers[currentConnectionId] = buffer.toString()
        failBridgeRequests("Switched to another Bruce device")
        bridgeBuffer.clear()
        protocolFilter.reset()
        currentConnectionId = connectionId
        buffer.clear()
        buffer.append(deviceBuffers[connectionId] ?: "Selected Bruce device.\n")
        consoleText.reset()
        console.text = buffer.toString()
        setConsoleFollowing(true)
        console.post(::scrollConsoleToBottom)
    }

    fun globalStatusLabel(): String? = when {
        serial.isUsbConnected -> "BRUCE USB"
        serial.isBluetoothConnected -> "BRUCE BLUETOOTH"
        else -> null
    }

    fun bridgeRequest(
        action: String,
        values: Map<String, String> = emptyMap(),
        timeoutMs: Long = 7_000L,
    ): JSONObject {
        if (!serial.isConnected) throw IllegalStateException("Connect Bruce over USB or Bluetooth first")
        require(action in BRIDGE_ACTIONS) { "Unsupported device bridge action" }
        val id = bridgeSequence.incrementAndGet()
        val future = CompletableFuture<JSONObject>()
        bridgeRequests[id] = future
        val form = values.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val command = buildString {
            append("@HELTEC-BRIDGE ").append(id).append(' ').append(action)
            if (form.isNotEmpty()) append(' ').append(form)
        }
        serial.writeCommand(command)
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            bridgeRequests.remove(id)
        }
    }

    private fun bind(root: View, id: Int, command: String) {
        root.findViewById<Button>(id).setOnClickListener { sendGuarded(command) }
    }

    private fun sendInput() {
        val command = input.text.toString().trim()
        if (command.isBlank()) return
        sendGuarded(command) { input.text.clear() }
    }

    private fun sendGuarded(command: String, sent: () -> Unit = {}) {
        if (!serial.isConnected) {
            toast("Connect Bruce over USB or Bluetooth first")
            return
        }
        when (BruceCommandSafety.classify(command)) {
            BruceCommandRisk.SAFE -> send(command, sent)
            BruceCommandRisk.CONFIRM -> AlertDialog.Builder(activity)
                .setTitle("Send unclassified Bruce command?")
                .setMessage("Review this command before sending it over the active device link:\n\n$command")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send") { _, _ -> send(command, sent) }
                .show()
            BruceCommandRisk.ACTIVE -> typedConfirmation(command, sent)
        }
    }

    private fun typedConfirmation(command: String, sent: () -> Unit) {
        val confirmation = EditText(activity).apply {
            hint = "AUTHORIZE"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(48, 16, 48, 16)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Authorize state-changing command")
            .setMessage(
                "This command may transmit, change device/network state, modify storage, run a script, or restart the device:\n\n$command\n\nType AUTHORIZE to send it.",
            )
            .setView(confirmation)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (confirmation.text.toString().trim() == "AUTHORIZE") {
                    dialog.dismiss()
                    send(command, sent)
                } else confirmation.error = "Type AUTHORIZE exactly"
            }
        }
        dialog.show()
    }

    private fun send(command: String, sent: () -> Unit) {
        append("\n> $command\n")
        serial.writeCommand(command) {
            if (command.trim().startsWith("sd", ignoreCase = true)) {
                protocolFilter.showNextStorageResponse()
            }
        }
        sent()
    }

    override fun onBruceUsbStatus(message: String, connected: Boolean) = activity.runOnUiThread {
        if (!connected) failBridgeRequests(message)
        if (message.contains("Bluetooth", ignoreCase = true)) bluetoothStatus.text = message
        else status.text = message
        setGlobalStatus(
            when {
                serial.isUsbConnected -> "BRUCE USB"
                serial.isBluetoothConnected -> "BRUCE BLUETOOTH"
                else -> "IDLE"
            },
        )
        append("\n[link] $message\n")
        bridgeState(connected, message)
    }

    override fun onBruceUsbData(data: ByteArray) {
        val raw = data.toString(Charsets.UTF_8)
        parseBridgeData(raw)
        val visible = protocolFilter.filter(raw)
        if (visible.isNotEmpty()) activity.runOnUiThread { append(visible) }
    }

    override fun onBruceUsbError(message: String) = activity.runOnUiThread {
        failBridgeRequests(message)
        append("\n[error] $message\n")
        setGlobalStatus("DEVICE LINK ERROR")
        toast(message)
    }

    private val consoleText = SerialConsoleText()

    private fun append(text: String) {
        buffer.append(consoleText.normalize(text))
        if (buffer.length > 40_000) buffer.delete(0, buffer.length - 32_000)
        console.text = buffer.toString()
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

    private fun toast(message: String) =
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private fun parseBridgeData(text: String) {
        synchronized(bridgeBuffer) {
            bridgeBuffer.append(text.replace("\u0000", ""))
            while (true) {
                val end = bridgeBuffer.indexOf("\n")
                if (end < 0) break
                val line = bridgeBuffer.substring(0, end).trim()
                bridgeBuffer.delete(0, end + 1)
                if (!line.startsWith("@HELTEC-BRIDGE ")) continue
                val fields = line.split(' ', limit = 4)
                if (fields.size != 4) continue
                val id = fields[1].toLongOrNull() ?: continue
                val future = bridgeRequests[id] ?: continue
                val payload = runCatching { JSONObject(fields[3]) }.getOrElse {
                    JSONObject().put("error", "Malformed USB bridge response")
                }
                if (fields[2] == "OK") future.complete(payload)
                else future.completeExceptionally(
                    IllegalStateException(payload.optString("error", "USB bridge request failed")),
                )
            }
            if (bridgeBuffer.length > 32_768) bridgeBuffer.delete(0, bridgeBuffer.length - 8_192)
        }
    }

    private fun failBridgeRequests(message: String) {
        bridgeRequests.values.forEach { it.completeExceptionally(IllegalStateException(message)) }
        bridgeRequests.clear()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private companion object {
        val BRIDGE_ACTIONS = setOf(
            "logger-start",
            "logger-stop",
            "logger-status",
            "logger-files",
            "logger-read",
            "logger-ack",
            "phone-gps",
            "phone-wifi",
        )
    }
}
