package com.unkl3errl.helteccontroller.connection

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class DeviceTransport(val displayName: String) {
    USB("USB"),
    BLUETOOTH("Bluetooth"),
}

/** Common view used by firmware screens for either one physical device or the selected device. */
internal interface DeviceSerialSession {
    interface Listener {
        fun onStatus(message: String, connected: Boolean)
        fun onData(data: ByteArray)
        fun onError(message: String)
    }

    val kind: PersistentUsbKind
    val connectionId: String
    val isConnected: Boolean
    val isUsbConnected: Boolean
    val isBluetoothConnected: Boolean
    val bluetoothSupported: Boolean
    val activeTransport: DeviceTransport?

    fun addListener(listener: Listener, receiveExclusiveData: Boolean = false)
    fun removeListener(listener: Listener)
    fun connectUsb()
    fun connectBluetooth(address: String)
    fun reconnectSavedBluetooth()
    fun disconnectUsb()
    fun disconnectBluetooth(forget: Boolean = false)
    fun disconnectAll()
    fun write(data: ByteArray)
    fun writeCommand(command: String, onDispatched: () -> Unit = {})
    fun writeStagedCommand(command: String, payload: ByteArray, onDispatched: () -> Unit = {})
    fun <T> withExclusiveCommands(block: ((String) -> Unit) -> T): T
    fun description(): String
}

/**
 * One firmware session with USB-first routing and an optional warm Bluetooth fallback.
 * Storage protocol traffic remains exclusive and is never copied into the user console.
 */
internal class PersistentDeviceSession(
    context: Context,
    override val kind: PersistentUsbKind,
    override val connectionId: String,
    private val usb: PersistentUsbSerialSession,
    private val bluetooth: PersistentBleSerialSession?,
) : DeviceSerialSession {

    private companion object {
        // The line-oriented Bruce and Marauder consoles do not tag replies with a
        // request ID. Leave a short response window after a user command before
        // the storage mirror can take exclusive ownership of incoming data.
        const val USER_COMMAND_RESPONSE_WINDOW_MS = 3_000L
    }

    private val listeners = CopyOnWriteArraySet<DeviceSerialSession.Listener>()
    private val exclusiveDataListeners = CopyOnWriteArraySet<DeviceSerialSession.Listener>()
    private val commandLock = ReentrantLock(true)
    private val userCommandWriter = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var exclusiveDataActive = false
    @Volatile private var userCommandQuietUntilMs = 0L
    @Volatile private var lastStatus = "${kind.displayName} is not connected"

    private val usbRelay = object : PersistentUsbSerialSession.Listener {
        override fun onStatus(message: String, connected: Boolean) =
            sourceStatus(DeviceTransport.USB, message, connected)

        override fun onData(data: ByteArray) {
            if (usb.isConnected) emitData(data)
        }

        override fun onError(message: String) = sourceError(DeviceTransport.USB, message)
    }

    private val bluetoothRelay = object : PersistentBleSerialSession.Listener {
        override fun onStatus(message: String, connected: Boolean) =
            sourceStatus(DeviceTransport.BLUETOOTH, message, connected)

        override fun onData(data: ByteArray) {
            if (!usb.isConnected && bluetooth?.isConnected == true) emitData(data)
        }

        override fun onError(message: String) = sourceError(DeviceTransport.BLUETOOTH, message)
    }

    init {
        usb.addListener(usbRelay, receiveExclusiveData = true)
        bluetooth?.addListener(bluetoothRelay)
        AndroidStorageRouting.attach(context.applicationContext, this)
    }

    override val isConnected: Boolean get() = usb.isConnected || bluetooth?.isConnected == true
    override val isUsbConnected: Boolean get() = usb.isConnected
    override val isBluetoothConnected: Boolean get() = bluetooth?.isConnected == true
    override val bluetoothSupported: Boolean get() = bluetooth != null
    override val activeTransport: DeviceTransport?
        get() = when {
            usb.isConnected -> DeviceTransport.USB
            bluetooth?.isConnected == true -> DeviceTransport.BLUETOOTH
            else -> null
        }
    internal val usbTarget get() = usb.target
    internal val bluetoothAddress get() = bluetooth?.address

    override fun addListener(
        listener: DeviceSerialSession.Listener,
        receiveExclusiveData: Boolean,
    ) {
        listeners.add(listener)
        if (receiveExclusiveData) exclusiveDataListeners.add(listener)
        // Screen controllers register from their constructors; never re-enter them before their
        // remaining fields have been initialized.
        mainHandler.post {
            if (listeners.contains(listener)) listener.onStatus(lastStatus, isConnected)
        }
    }

    override fun removeListener(listener: DeviceSerialSession.Listener) {
        exclusiveDataListeners.remove(listener)
        listeners.remove(listener)
    }

    override fun connectUsb() = usb.connect()

    override fun connectBluetooth(address: String) {
        val session = bluetooth
        if (session == null) {
            emitError("${kind.displayName} does not expose an app-compatible Bluetooth service")
            return
        }
        session.connect(address)
    }

    override fun reconnectSavedBluetooth() {
        bluetooth?.reconnectSaved()
    }

    override fun disconnectUsb() = usb.disconnect()

    override fun disconnectBluetooth(forget: Boolean) {
        bluetooth?.disconnect(forget)
    }

    override fun disconnectAll() {
        usb.disconnect()
        bluetooth?.disconnect()
    }

    override fun write(data: ByteArray) {
        userCommandWriter.execute {
            commandLock.withLock {
                val sent = when {
                    usb.isConnected -> true.also { usb.write(data) }
                    bluetooth?.isConnected == true -> true.also { bluetooth.write(data) }
                    else -> false
                }
                if (sent) markUserResponseWindow()
                else emitError("Connect ${kind.displayName} over USB or Bluetooth first")
            }
        }
    }

    override fun writeCommand(command: String, onDispatched: () -> Unit) {
        userCommandWriter.execute {
            runCatching {
                commandLock.withLock {
                    val sent = when {
                        usb.isConnected -> {
                            usb.withExclusiveCommands { send ->
                                onDispatched()
                                send(command)
                            }
                            true
                        }
                        bluetooth?.isConnected == true -> {
                            bluetooth.withExclusiveCommands { send ->
                                onDispatched()
                                send(command)
                            }
                            true
                        }
                        else -> false
                    }
                    if (sent) markUserResponseWindow()
                    else emitError("Connect ${kind.displayName} over USB or Bluetooth first")
                }
            }.onFailure { error ->
                emitError(
                    "${kind.displayName} command failed: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            }
        }
    }

    override fun writeStagedCommand(command: String, payload: ByteArray, onDispatched: () -> Unit) {
        userCommandWriter.execute {
            runCatching {
                commandLock.withLock {
                    val commandBytes = (command.trim() + "\r\n").toByteArray(Charsets.UTF_8)
                    val sent = when {
                        usb.isConnected -> {
                            usb.withExclusiveWrites { write ->
                                onDispatched()
                                write(commandBytes)
                                write(payload)
                            }
                            true
                        }
                        bluetooth?.isConnected == true -> {
                            bluetooth.withExclusiveWrites { write ->
                                onDispatched()
                                write(commandBytes)
                                write(payload)
                            }
                            true
                        }
                        else -> false
                    }
                    if (sent) markUserResponseWindow()
                    else emitError("Connect ${kind.displayName} over USB or Bluetooth first")
                }
            }.onFailure { error ->
                emitError(
                    "${kind.displayName} upload failed: " +
                        (error.message ?: error.javaClass.simpleName),
                )
            }
        }
    }

    override fun <T> withExclusiveCommands(block: ((String) -> Unit) -> T): T {
        while (true) {
            val waitMs = commandLock.withLock {
                val remaining = userCommandQuietUntilMs - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    exclusiveDataActive = true
                    try {
                        return when {
                            usb.isConnected -> usb.withExclusiveCommands(block)
                            bluetooth?.isConnected == true -> bluetooth.withExclusiveCommands(block)
                            else -> throw IllegalStateException("${kind.displayName} is disconnected")
                        }
                    } finally {
                        exclusiveDataActive = false
                    }
                }
                remaining
            }
            SystemClock.sleep(waitMs.coerceAtMost(250L))
        }
    }

    private fun markUserResponseWindow() {
        userCommandQuietUntilMs = SystemClock.elapsedRealtime() + USER_COMMAND_RESPONSE_WINDOW_MS
    }

    override fun description(): String = when {
        usb.isConnected && bluetooth?.isConnected == true ->
            "${kind.displayName} USB + Bluetooth standby"
        usb.isConnected -> "${kind.displayName} USB"
        bluetooth?.isConnected == true -> "${kind.displayName} Bluetooth"
        else -> kind.displayName
    }

    private fun sourceStatus(source: DeviceTransport, message: String, connected: Boolean) {
        val combinedConnected = isConnected
        val combined = when {
            source == DeviceTransport.BLUETOOTH && connected && usb.isConnected ->
                "${kind.displayName} Bluetooth connected · USB remains preferred"
            source == DeviceTransport.BLUETOOTH && !connected && usb.isConnected ->
                "${kind.displayName} USB connected · Bluetooth fallback unavailable"
            source == DeviceTransport.USB && !connected && bluetooth?.isConnected == true ->
                "${kind.displayName} USB disconnected · continuing over Bluetooth"
            else -> message
        }
        lastStatus = combined
        listeners.forEach { it.onStatus(combined, combinedConnected) }
        DeviceConnectionService.refresh(usbContext())
    }

    private fun sourceError(source: DeviceTransport, message: String) {
        val labelled = if (message.startsWith(source.displayName, ignoreCase = true)) {
            message
        } else {
            "${source.displayName}: $message"
        }
        emitError(labelled)
    }

    private fun emitData(data: ByteArray) {
        val targets = if (exclusiveDataActive) exclusiveDataListeners else listeners
        targets.forEach { it.onData(data) }
    }

    private fun emitError(message: String) = listeners.forEach { it.onError(message) }

    // DeviceConnectionService only needs an application Context; retain one without exposing it.
    private val appContext = context.applicationContext
    private fun usbContext(): Context = appContext
}
