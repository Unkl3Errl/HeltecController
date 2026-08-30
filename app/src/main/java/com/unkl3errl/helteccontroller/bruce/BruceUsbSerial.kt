package com.unkl3errl.helteccontroller.bruce

import android.content.Context
import com.unkl3errl.helteccontroller.connection.DeviceSerialSession
import com.unkl3errl.helteccontroller.connection.PersistentDeviceConnections
import com.unkl3errl.helteccontroller.connection.PersistentUsbKind

/** Bruce-specific view of the device currently selected beneath the Bruce tab. */
class BruceUsbSerial(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onBruceUsbStatus(message: String, connected: Boolean)
        fun onBruceUsbData(data: ByteArray)
        fun onBruceUsbError(message: String)
    }

    private val session = PersistentDeviceConnections.selection(context, PersistentUsbKind.BRUCE)
    private val relay = object : DeviceSerialSession.Listener {
        override fun onStatus(message: String, connected: Boolean) =
            listener.onBruceUsbStatus(message, connected)

        override fun onData(data: ByteArray) = listener.onBruceUsbData(data)

        override fun onError(message: String) = listener.onBruceUsbError(message)
    }

    val isConnected: Boolean
        get() = session.isConnected

    val isUsbConnected: Boolean
        get() = session.isUsbConnected

    val isBluetoothConnected: Boolean
        get() = session.isBluetoothConnected

    val connectionId: String
        get() = session.connectionId

    init {
        // Controller bridge transactions hold exclusive ownership through their response.
        // Keep this listener eligible for that response while the console filter hides
        // unrelated background storage protocol traffic.
        session.addListener(relay, receiveExclusiveData = true)
    }

    fun connect() = session.connectUsb()

    fun connectBluetooth(address: String) = session.connectBluetooth(address)

    fun disconnectUsb() = session.disconnectUsb()

    fun disconnectBluetooth() = session.disconnectBluetooth()

    fun writeCommand(command: String, onDispatched: () -> Unit = {}) =
        session.writeCommand(command, onDispatched)

    fun writeStagedCommand(command: String, payload: ByteArray, onDispatched: () -> Unit = {}) =
        session.writeStagedCommand(command, payload, onDispatched)

    fun <T> withExclusiveCommands(block: ((String) -> Unit) -> T): T =
        session.withExclusiveCommands(block)

    fun close() = session.disconnectAll()

    /** Detach this screen without closing the service-owned serial port. */
    fun destroy() = session.removeListener(relay)
}
