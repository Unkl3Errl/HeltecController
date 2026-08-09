package com.unkl3errl.helteccontroller.connection

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.EnumMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

enum class PersistentUsbKind(val displayName: String) {
    BRUCE("Bruce"),
    GHOSTESP("GhostESP"),
    MARAUDER("Marauder"),
}

/**
 * One process-wide USB session per firmware family. Screen controllers subscribe and unsubscribe,
 * but the serial port remains owned by the foreground service's process while the UI is absent.
 */
class PersistentUsbSerialSession internal constructor(
    context: Context,
    val kind: PersistentUsbKind,
) : SerialInputOutputManager.Listener {
    interface Listener {
        fun onStatus(message: String, connected: Boolean)
        fun onData(data: ByteArray)
        fun onError(message: String)
    }

    private companion object {
        const val ESPRESSIF_VID = 0x303A
        const val ESP32_USB_JTAG_PID = 0x1001
        const val BAUD_RATE = 115_200
        const val MAX_BACKLOG_BYTES = 64 * 1024
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val writer = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backlogLock = Any()
    private val backlog = ArrayDeque<ByteArray>()
    private var backlogBytes = 0

    @Volatile
    private var port: UsbSerialPort? = null

    @Volatile
    private var ioManager: SerialInputOutputManager? = null

    @Volatile
    private var pendingDriver: UsbSerialDriver? = null

    @Volatile
    private var currentDeviceId: Int? = null

    @Volatile
    private var lastStatus = "${kind.displayName} USB is not connected"

    val isConnected: Boolean
        get() = port?.isOpen == true

    private val permissionAction =
        "com.unkl3errl.helteccontroller.${kind.name}_USB_PERMISSION"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val device = intent.usbDevice()
                    val driver = pendingDriver
                    pendingDriver = null
                    if (
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                        device != null && driver?.device?.deviceId == device.deviceId
                    ) {
                        open(driver)
                    } else {
                        emitStatus("USB permission was not granted", false)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (intent.usbDevice()?.deviceId == currentDeviceId) {
                        closePort()
                        emitStatus("${kind.displayName} USB device disconnected", false)
                    }
                }
            }
        }
    }

    init {
        registerUsbReceiver()
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        val status = lastStatus
        val connected = isConnected
        val pending = synchronized(backlogLock) {
            if (backlog.isEmpty()) {
                emptyList()
            } else {
                backlog.toList().also {
                    backlog.clear()
                    backlogBytes = 0
                }
            }
        }
        // Avoid callbacks re-entering a screen controller while its constructor is still running.
        mainHandler.post {
            if (!listeners.contains(listener)) return@post
            listener.onStatus(status, connected)
            pending.forEach(listener::onData)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun connect() {
        DeviceConnectionService.start(appContext)
        if (isConnected) {
            emitStatus("${kind.displayName} USB is already connected", true)
            return
        }
        val driver = findDriver()
        if (driver == null) {
            emitStatus(
                "No compatible USB serial device found. Check the OTG adapter and cable.",
                false,
            )
            return
        }
        if (!usbManager.hasPermission(driver.device)) {
            pendingDriver = driver
            val permissionIntent = PendingIntent.getBroadcast(
                appContext,
                kind.ordinal,
                Intent(permissionAction).setPackage(appContext.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usbManager.requestPermission(driver.device, permissionIntent)
            emitStatus("Waiting for USB permission…", false)
            return
        }
        open(driver)
    }

    fun write(data: ByteArray) {
        val activePort = port
        if (activePort == null || !activePort.isOpen) {
            emitError("Connect the ${kind.displayName} USB device first")
            return
        }
        writer.execute {
            try {
                activePort.write(data, 2_000)
            } catch (error: Exception) {
                emitError("USB write failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun writeCommand(command: String) {
        write((command.trim() + "\r\n").toByteArray(Charsets.UTF_8))
    }

    fun disconnect() {
        val wasConnected = isConnected || pendingDriver != null
        pendingDriver = null
        closePort()
        if (wasConnected) emitStatus("${kind.displayName} USB disconnected", false)
    }

    override fun onNewData(data: ByteArray) {
        val snapshot = listeners.toList()
        if (snapshot.isNotEmpty()) {
            snapshot.forEach { it.onData(data) }
            return
        }
        synchronized(backlogLock) {
            val retained = if (data.size > MAX_BACKLOG_BYTES) {
                data.copyOfRange(data.size - MAX_BACKLOG_BYTES, data.size)
            } else {
                data.copyOf()
            }
            backlog.addLast(retained)
            backlogBytes += retained.size
            while (backlogBytes > MAX_BACKLOG_BYTES && backlog.isNotEmpty()) {
                backlogBytes -= backlog.removeFirst().size
            }
        }
    }

    override fun onRunError(error: Exception) {
        closePort()
        emitError("USB serial stopped: ${error.message ?: error.javaClass.simpleName}")
        emitStatus("${kind.displayName} USB disconnected", false)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun findDriver(): UsbSerialDriver? {
        val detected = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        detected.firstOrNull {
            it.device.vendorId == ESPRESSIF_VID && it.device.productId == ESP32_USB_JTAG_PID
        }?.let { return it }
        detected.firstOrNull()?.let { return it }

        val nativeEspressif = usbManager.deviceList.values.firstOrNull {
            it.vendorId == ESPRESSIF_VID && it.productId == ESP32_USB_JTAG_PID
        }
        return nativeEspressif?.let(::CdcAcmSerialDriver)
    }

    private fun open(driver: UsbSerialDriver) {
        try {
            val connection = usbManager.openDevice(driver.device)
                ?: throw IllegalStateException("Android could not open the USB device")
            val selectedPort = driver.ports.firstOrNull()
                ?: throw IllegalStateException("The USB device has no serial port")
            selectedPort.open(connection)
            selectedPort.setParameters(
                BAUD_RATE,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            runCatching { selectedPort.setDTR(true) }
            port = selectedPort
            currentDeviceId = driver.device.deviceId
            ioManager = SerialInputOutputManager(selectedPort, this).also { it.start() }
            emitStatus(
                "Connected: ${driver.device.productName ?: kind.displayName} · $BAUD_RATE baud",
                true,
            )
            DeviceConnectionService.refresh(appContext)
        } catch (error: Exception) {
            closePort()
            emitError("USB connection failed: ${error.message ?: error.javaClass.simpleName}")
            emitStatus("${kind.displayName} USB connection failed", false)
        }
    }

    private fun closePort() {
        ioManager?.stop()
        ioManager = null
        runCatching { port?.close() }
        port = null
        currentDeviceId = null
        DeviceConnectionService.refresh(appContext)
    }

    private fun emitStatus(message: String, connected: Boolean) {
        lastStatus = message
        listeners.forEach { it.onStatus(message, connected) }
        DeviceConnectionService.refresh(appContext)
    }

    private fun emitError(message: String) {
        listeners.forEach { it.onError(message) }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}

object PersistentDeviceConnections {
    private val lock = Any()
    private val sessions = EnumMap<PersistentUsbKind, PersistentUsbSerialSession>(
        PersistentUsbKind::class.java,
    )

    @Volatile
    private var localNetworkName: String? = null

    fun usb(context: Context, kind: PersistentUsbKind): PersistentUsbSerialSession =
        synchronized(lock) {
            sessions.getOrPut(kind) {
                PersistentUsbSerialSession(context.applicationContext, kind)
            }
        }

    fun activeUsbKind(): PersistentUsbKind? = synchronized(lock) {
        sessions.entries.firstOrNull { it.value.isConnected }?.key
    }

    fun setLocalNetwork(name: String?) {
        localNetworkName = name
    }

    fun connectionSummary(): String = synchronized(lock) {
        val names = buildList {
            addAll(sessions.values.filter { it.isConnected }.map { it.kind.displayName })
            localNetworkName?.let(::add)
        }
        if (names.isEmpty()) "Ready to retain device sessions" else "Connected to ${names.joinToString()}"
    }
}
