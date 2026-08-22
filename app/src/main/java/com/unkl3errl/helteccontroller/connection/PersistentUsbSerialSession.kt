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
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.unkl3errl.helteccontroller.usb.UsbDeviceRegistry
import com.unkl3errl.helteccontroller.usb.UsbDeviceTarget
import java.util.EnumMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class PersistentUsbKind(val displayName: String) {
    BRUCE("Bruce"),
    GHOSTESP("GhostESP"),
    MARAUDER("Marauder"),
}

/** One process-wide USB session per physical board. */
class PersistentUsbSerialSession internal constructor(
    context: Context,
    val kind: PersistentUsbKind,
    private val sessionKey: String,
) : SerialInputOutputManager.Listener {
    interface Listener {
        fun onStatus(message: String, connected: Boolean)
        fun onData(data: ByteArray)
        fun onError(message: String)
    }

    private companion object {
        const val BAUD_RATE = 115_200
        const val MAX_BACKLOG_BYTES = 64 * 1024
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val writer = Executors.newSingleThreadExecutor()
    private val commandLock = ReentrantLock(true)
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val exclusiveDataListeners = CopyOnWriteArraySet<Listener>()
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
    private var boundTarget: UsbDeviceTarget? = null

    @Volatile
    private var lastStatus = "${kind.displayName} USB is not connected"

    @Volatile
    private var exclusiveDataActive = false

    val isConnected: Boolean
        get() = port?.isOpen == true

    val target: UsbDeviceTarget?
        get() = boundTarget

    private val permissionAction =
        "com.unkl3errl.helteccontroller.${kind.name}_${sessionKey.hashCode()}_USB_PERMISSION"

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

    fun addListener(listener: Listener, receiveExclusiveData: Boolean = false) {
        listeners.add(listener)
        if (receiveExclusiveData) exclusiveDataListeners.add(listener)
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
        exclusiveDataListeners.remove(listener)
        listeners.remove(listener)
    }

    fun connect() {
        DeviceConnectionService.start(appContext)
        if (isConnected) {
            emitStatus("${kind.displayName} USB is already connected", true)
            return
        }
        val selectedTarget = boundTarget
        if (selectedTarget == null) {
            emitStatus(
                "Select which USB device belongs to ${kind.displayName} first.",
                false,
            )
            return
        }
        val driver = UsbDeviceRegistry.driverFor(usbManager, selectedTarget)
        if (driver == null) {
            emitStatus(
                "The selected ${kind.displayName} USB device is no longer attached.",
                false,
            )
            return
        }
        if (!usbManager.hasPermission(driver.device)) {
            pendingDriver = driver
            val permissionIntent = PendingIntent.getBroadcast(
                appContext,
                sessionKey.hashCode(),
                Intent(permissionAction).setPackage(appContext.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usbManager.requestPermission(driver.device, permissionIntent)
            emitStatus("Waiting for USB permission…", false)
            return
        }
        open(driver)
    }

    fun bind(target: UsbDeviceTarget) {
        val previous = boundTarget
        if (previous?.samePhysicalDevice(target) == true) {
            boundTarget = target
            return
        }
        if (isConnected || pendingDriver != null) disconnect()
        boundTarget = target
        emitStatus("${kind.displayName} assigned to ${target.displayLabel()}", false)
    }

    internal fun clearBindingIf(target: UsbDeviceTarget) {
        if (boundTarget?.samePhysicalDevice(target) != true) return
        disconnect()
        boundTarget = null
        emitStatus("${kind.displayName} USB target is not assigned", false)
    }

    fun write(data: ByteArray) {
        val activePort = port
        if (activePort == null || !activePort.isOpen) {
            emitError("Connect the ${kind.displayName} USB device first")
            return
        }
        writer.execute {
            try {
                commandLock.withLock { activePort.write(data, 2_000) }
            } catch (error: Exception) {
                emitError("USB write failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun writeCommand(command: String) {
        write((command.trim() + "\r\n").toByteArray(Charsets.UTF_8))
    }

    /** Holds normal UI commands while a request/response storage transaction is active. */
    internal fun <T> withExclusiveCommands(block: ((String) -> Unit) -> T): T =
        commandLock.withLock {
            exclusiveDataActive = true
            try {
                block { command ->
                    val activePort = port
                    if (activePort == null || !activePort.isOpen) {
                        throw IllegalStateException("${kind.displayName} USB is disconnected")
                    }
                    activePort.write(
                        (command.trim() + "\r\n").toByteArray(Charsets.UTF_8),
                        2_000,
                    )
                }
            } finally {
                exclusiveDataActive = false
            }
        }

    fun disconnect() {
        val wasConnected = isConnected || pendingDriver != null
        pendingDriver = null
        closePort()
        if (wasConnected) emitStatus("${kind.displayName} USB disconnected", false)
    }

    override fun onNewData(data: ByteArray) {
        val snapshot = if (exclusiveDataActive) {
            exclusiveDataListeners.toList()
        } else {
            listeners.toList()
        }
        if (snapshot.isNotEmpty()) {
            snapshot.forEach { it.onData(data) }
            return
        }
        // Storage-mirror responses are protocol traffic, not user console output. Never replay
        // them into a screen later if the internal listener disappears during a transaction.
        if (exclusiveDataActive) return
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
            boundTarget = UsbDeviceRegistry.target(usbManager, driver.device)
            ioManager = SerialInputOutputManager(selectedPort, this).also { it.start() }
            emitStatus(
                "Connected: ${boundTarget?.displayLabel() ?: kind.displayName} · $BAUD_RATE baud",
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

data class DeviceConnectionSummary(
    val connectionId: String,
    val kind: PersistentUsbKind,
    val displayLabel: String,
    val usbTarget: UsbDeviceTarget?,
    val bluetoothAddress: String?,
    val usbConnected: Boolean,
    val bluetoothConnected: Boolean,
    val selected: Boolean,
) {
    val connected: Boolean get() = usbConnected || bluetoothConnected
    val transportLabel: String get() = buildList {
        if (usbConnected) add("USB")
        if (bluetoothConnected) add("Bluetooth")
    }.ifEmpty { listOf("offline") }.joinToString(" + ")
}

object PersistentDeviceConnections {
    interface Listener {
        fun onDeviceConnectionsChanged(kind: PersistentUsbKind)
    }

    private const val BLE_PREFERENCES = "persistent_bluetooth_devices"
    private const val BLE_KEY_PREFIX = "device:"
    private const val BLE_OWNER_PREFIX = "owner:"
    private const val BLE_HARDWARE_OWNER_PREFIX = "owner-hardware:"
    private val lock = Any()
    private val usbSessions = linkedMapOf<String, PersistentUsbSerialSession>()
    private val deviceSessions = linkedMapOf<String, PersistentDeviceSession>()
    private val selectedIds = EnumMap<PersistentUsbKind, String>(PersistentUsbKind::class.java)
    private val selectors = EnumMap<PersistentUsbKind, SelectedDeviceSession>(PersistentUsbKind::class.java)
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var localNetworkName: String? = null

    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    internal fun selection(context: Context, kind: PersistentUsbKind): DeviceSerialSession =
        synchronized(lock) {
            selectors.getOrPut(kind) {
                SelectedDeviceSession(kind) { selectedSession(kind) }
            }
        }

    fun bindUsb(context: Context, kind: PersistentUsbKind, target: UsbDeviceTarget): String {
        val id: String
        synchronized(lock) {
            val conflictingIds = deviceSessions.values
                .filter {
                    it.kind != kind &&
                        (
                            it.usbTarget?.samePhysicalDevice(target) == true ||
                                Esp32BluetoothIdentity.sameHardware(
                                    it.bluetoothAddress,
                                    target.serialNumber,
                                )
                        )
                }
                .map(PersistentDeviceSession::connectionId)
            conflictingIds.forEach { removeSessionLocked(it, forgetBluetooth = true) }

            // USB exposes the ESP32 base MAC while BLE commonly exposes another address from
            // the same four-address block. Attach USB to that existing BLE session so one board
            // has one command lock, one storage mirror, and USB-first/BLE-fallback routing.
            val bluetoothMatch = deviceSessions.values.firstOrNull {
                it.kind == kind &&
                    it.bluetoothAddress != null &&
                    Esp32BluetoothIdentity.sameHardware(
                        it.bluetoothAddress,
                        target.serialNumber,
                    )
            }
            val usbMatch = deviceSessions.values.firstOrNull {
                it.kind == kind && it.usbTarget?.samePhysicalDevice(target) == true
            }
            if (bluetoothMatch != null && usbMatch != null && bluetoothMatch !== usbMatch) {
                removeSessionLocked(usbMatch.connectionId)
            }

            id = bluetoothMatch?.connectionId
                ?: usbMatch?.connectionId
                ?: usbConnectionId(kind, target)
            val session = deviceSessions[id] ?: createSessionLocked(context, kind, id)
            usbSessions.getValue(id).bind(target)
            selectedIds[kind] = id
            selectors[kind]?.refreshSelection()
        }
        notifyChanged(kind)
        return id
    }

    fun addBluetooth(
        context: Context,
        kind: PersistentUsbKind,
        address: String,
        attachToSelected: Boolean = false,
    ): String {
        val normalized = address.uppercase()
        val id: String
        val session: PersistentDeviceSession
        synchronized(lock) {
            val conflictingIds = deviceSessions.values
                .filter {
                    it.kind != kind &&
                        Esp32BluetoothIdentity.sameHardware(it.bluetoothAddress, normalized)
                }
                .map(PersistentDeviceSession::connectionId)
            conflictingIds.forEach { removeSessionLocked(it, forgetBluetooth = true) }
            rememberBluetoothOwner(context, kind, normalized)

            val existing = deviceSessions.values.firstOrNull {
                it.kind == kind && it.bluetoothAddress.equals(normalized, ignoreCase = true)
            }
            val attach = if (attachToSelected) selectedSession(kind)?.takeIf {
                it.bluetoothSupported &&
                    (it.bluetoothAddress == null || it.bluetoothAddress.equals(normalized, true))
            } else null
            id = existing?.connectionId ?: attach?.connectionId
                ?: bluetoothConnectionId(kind, normalized)
            session = deviceSessions[id] ?: createSessionLocked(context, kind, id)
            selectedIds[kind] = id
            selectors[kind]?.refreshSelection()
        }
        session.connectBluetooth(normalized)
        notifyChanged(kind)
        return id
    }

    fun select(kind: PersistentUsbKind, connectionId: String): Boolean {
        synchronized(lock) {
            val session = deviceSessions[connectionId]
            if (session?.kind != kind) return false
            selectedIds[kind] = connectionId
            selectors[kind]?.refreshSelection()
        }
        notifyChanged(kind)
        return true
    }

    fun devices(kind: PersistentUsbKind): List<DeviceConnectionSummary> = synchronized(lock) {
        deviceSessions.values.filter { it.kind == kind }.map { session ->
            DeviceConnectionSummary(
                connectionId = session.connectionId,
                kind = kind,
                displayLabel = deviceLabel(session),
                usbTarget = session.usbTarget,
                bluetoothAddress = session.bluetoothAddress,
                usbConnected = session.isUsbConnected,
                bluetoothConnected = session.isBluetoothConnected,
                selected = selectedIds[kind] == session.connectionId,
            )
        }.sortedWith(compareByDescending<DeviceConnectionSummary> { it.selected }
            .thenByDescending { it.connected }
            .thenBy(DeviceConnectionSummary::displayLabel))
    }

    fun selected(kind: PersistentUsbKind): DeviceConnectionSummary? =
        devices(kind).firstOrNull(DeviceConnectionSummary::selected)

    fun disconnectUsbTarget(target: UsbDeviceTarget) = synchronized(lock) {
        usbSessions.values
            .filter { it.target?.samePhysicalDevice(target) == true }
            .forEach(PersistentUsbSerialSession::disconnect)
    }

    fun assignedKind(target: UsbDeviceTarget): PersistentUsbKind? = synchronized(lock) {
        deviceSessions.values.firstOrNull {
            it.usbTarget?.samePhysicalDevice(target) == true
        }?.kind
    }

    fun selectUsbTarget(target: UsbDeviceTarget): PersistentUsbKind? {
        val session = synchronized(lock) {
            deviceSessions.values.firstOrNull {
                it.usbTarget?.samePhysicalDevice(target) == true
            }?.also {
                selectedIds[it.kind] = it.connectionId
                selectors[it.kind]?.refreshSelection()
            }
        } ?: return null
        notifyChanged(session.kind)
        return session.kind
    }

    fun isUsbTargetConnected(target: UsbDeviceTarget): Boolean = synchronized(lock) {
        deviceSessions.values.any {
            it.isUsbConnected && it.usbTarget?.samePhysicalDevice(target) == true
        }
    }

    fun activeUsbKinds(): List<PersistentUsbKind> = synchronized(lock) {
        deviceSessions.values.filter { it.isUsbConnected }.map { it.kind }.distinct()
    }

    fun activeKinds(): List<PersistentUsbKind> = synchronized(lock) {
        deviceSessions.values.filter { it.isConnected }.map { it.kind }.distinct()
    }

    internal fun activeSessions(kind: PersistentUsbKind? = null): List<PersistentDeviceSession> =
        synchronized(lock) {
            deviceSessions.values.filter { it.isConnected && (kind == null || it.kind == kind) }
        }

    fun target(kind: PersistentUsbKind): UsbDeviceTarget? = synchronized(lock) {
        selectedSession(kind)?.usbTarget
    }

    fun activeUsbKind(): PersistentUsbKind? = synchronized(lock) {
        deviceSessions.values.firstOrNull { it.isUsbConnected }?.kind
    }

    /** Releases every serial port before the ROM bootloader takes ownership. */
    fun disconnectAllUsb() = synchronized(lock) {
        usbSessions.values.forEach(PersistentUsbSerialSession::disconnect)
    }

    fun restoreBluetooth(context: Context) = synchronized(lock) {
        val preferences = context.getSharedPreferences(BLE_PREFERENCES, Context.MODE_PRIVATE)
        val saved = preferences.all
        val restoredAddresses = mutableSetOf<String>()
        listOf(
            PersistentUsbKind.BRUCE,
            PersistentUsbKind.GHOSTESP,
            PersistentUsbKind.MARAUDER,
        ).forEach { kind ->
            val prefix = "$BLE_KEY_PREFIX${kind.name}:"
            val remembered = saved.entries.mapNotNull { (key, value) ->
                val address = value as? String ?: return@mapNotNull null
                if (!key.startsWith(prefix)) return@mapNotNull null
                key.removePrefix(prefix) to address
            }.toMutableList()
            (saved[kind.name] as? String)?.let { address ->
                remembered += bluetoothConnectionId(kind, address) to address
            }
            remembered.distinctBy { it.first }.forEach { (id, address) ->
                val normalized = address.uppercase()
                val owner = saved["$BLE_OWNER_PREFIX$normalized"] as? String
                if (owner != null && owner != kind.name) return@forEach
                val hardwareOwner = Esp32BluetoothIdentity.hardwareKey(normalized)?.let { key ->
                    saved["$BLE_HARDWARE_OWNER_PREFIX$key"] as? String
                }
                if (hardwareOwner != null && hardwareOwner != kind.name) return@forEach
                if (!restoredAddresses.add(normalized)) return@forEach
                val session = deviceSessions[id] ?: createSessionLocked(context, kind, id)
                if (selectedIds[kind] == null) selectedIds[kind] = id
                session.connectBluetooth(normalized)
            }
            selectors[kind]?.refreshSelection()
        }
    }

    fun setLocalNetwork(name: String?) {
        localNetworkName = name
    }

    fun connectionSummary(): String = synchronized(lock) {
        val names = buildList {
            addAll(deviceSessions.values.filter { it.isConnected }.map(PersistentDeviceSession::description))
            localNetworkName?.let(::add)
        }
        if (names.isEmpty()) "Ready to retain device sessions" else "Connected to ${names.joinToString()}"
    }

    private fun selectedSession(kind: PersistentUsbKind): PersistentDeviceSession? =
        synchronized(lock) { selectedIds[kind]?.let(deviceSessions::get) }

    private fun createSessionLocked(
        context: Context,
        kind: PersistentUsbKind,
        connectionId: String,
    ): PersistentDeviceSession {
        val appContext = context.applicationContext
        val usb = PersistentUsbSerialSession(appContext, kind, connectionId)
        val profile = FirmwareBleProfile.forKind(kind)
        val bluetooth = profile?.let {
            PersistentBleSerialSession(
                appContext,
                kind,
                it,
                "$BLE_KEY_PREFIX${kind.name}:$connectionId",
            )
        }
        val session = PersistentDeviceSession(appContext, kind, connectionId, usb, bluetooth)
        usbSessions[connectionId] = usb
        deviceSessions[connectionId] = session
        session.addListener(object : DeviceSerialSession.Listener {
            override fun onStatus(message: String, connected: Boolean) {
                if (connected && session.isBluetoothConnected) {
                    claimBluetoothHardware(appContext, session)
                }
                notifyChanged(kind)
            }
            override fun onData(data: ByteArray) = Unit
            override fun onError(message: String) = notifyChanged(kind)
        })
        return session
    }

    private fun removeSessionLocked(connectionId: String, forgetBluetooth: Boolean = false) {
        deviceSessions.remove(connectionId)?.also { session ->
            if (forgetBluetooth) session.disconnectBluetooth(forget = true)
            session.disconnectAll()
        }
        usbSessions.remove(connectionId)
        selectedIds.entries.removeAll { it.value == connectionId }
    }

    private fun rememberBluetoothOwner(
        context: Context,
        kind: PersistentUsbKind,
        address: String,
    ) {
        val preferences = context.getSharedPreferences(BLE_PREFERENCES, Context.MODE_PRIVATE)
        val hardwareKey = Esp32BluetoothIdentity.hardwareKey(address)
        val editor = preferences.edit().putString("$BLE_OWNER_PREFIX$address", kind.name)
        if (hardwareKey != null) {
            editor.putString("$BLE_HARDWARE_OWNER_PREFIX$hardwareKey", kind.name)
        }
        preferences.all.forEach { (key, value) ->
            if (
                value is String && Esp32BluetoothIdentity.sameHardware(value, address) &&
                key != kind.name && !key.startsWith("$BLE_KEY_PREFIX${kind.name}:")
            ) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    private fun claimBluetoothHardware(context: Context, owner: PersistentDeviceSession) {
        val address = owner.bluetoothAddress ?: return
        val removedKinds = mutableSetOf<PersistentUsbKind>()
        synchronized(lock) {
            val conflicts = deviceSessions.values.filter { candidate ->
                candidate !== owner && candidate.kind != owner.kind &&
                    Esp32BluetoothIdentity.sameHardware(candidate.bluetoothAddress, address)
            }
            conflicts.forEach { candidate ->
                removedKinds += candidate.kind
                removeSessionLocked(candidate.connectionId, forgetBluetooth = true)
            }
            rememberBluetoothOwner(context, owner.kind, address)
            selectors.values.forEach(SelectedDeviceSession::refreshSelection)
        }
        removedKinds.forEach(::notifyChanged)
    }

    private fun notifyChanged(kind: PersistentUsbKind) {
        mainHandler.post { listeners.forEach { it.onDeviceConnectionsChanged(kind) } }
    }

    private fun deviceLabel(session: PersistentDeviceSession): String {
        val target = session.usbTarget
        if (target != null) {
            val identity = target.serialNumber?.takeIf(String::isNotBlank)
                ?: "port ${target.deviceId}"
            return "${session.kind.displayName} · $identity"
        }
        val address = session.bluetoothAddress
        return if (address != null) {
            "${session.kind.displayName} · BT ${address.takeLast(8)}"
        } else {
            "${session.kind.displayName} · new device"
        }
    }

    private fun usbConnectionId(kind: PersistentUsbKind, target: UsbDeviceTarget): String =
        "${kind.name}:usb:${target.stableKey}"

    private fun bluetoothConnectionId(kind: PersistentUsbKind, address: String): String =
        "${kind.name}:ble:${address.uppercase()}"
}
