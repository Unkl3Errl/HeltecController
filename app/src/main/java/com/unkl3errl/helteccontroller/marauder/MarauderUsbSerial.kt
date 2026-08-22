package com.unkl3errl.helteccontroller.marauder

import android.content.Context
import com.unkl3errl.helteccontroller.connection.DeviceSerialSession
import com.unkl3errl.helteccontroller.connection.PersistentDeviceConnections
import com.unkl3errl.helteccontroller.connection.PersistentUsbKind

/** Marauder-specific view of the device currently selected beneath the Marauder tab. */
class MarauderUsbSerial(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onSerialStatus(message: String, connected: Boolean)
        fun onSerialData(data: ByteArray)
        fun onSerialError(message: String)
    }

    private val session = PersistentDeviceConnections.selection(context, PersistentUsbKind.MARAUDER)
    private val relay = object : DeviceSerialSession.Listener {
        override fun onStatus(message: String, connected: Boolean) =
            listener.onSerialStatus(message, connected)

        override fun onData(data: ByteArray) = listener.onSerialData(data)

        override fun onError(message: String) = listener.onSerialError(message)
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
        session.addListener(relay)
    }

    fun connect() = session.connectUsb()

    fun connectBluetooth(address: String) = session.connectBluetooth(address)

    fun disconnectUsb() = session.disconnectUsb()

    fun disconnectBluetooth() = session.disconnectBluetooth()

    fun writeCommand(command: String, onDispatched: () -> Unit = {}) =
        session.writeCommand(command, onDispatched)

    fun close() = session.disconnectAll()

    /** Detach this screen without closing the service-owned serial port. */
    fun destroy() = session.removeListener(relay)
}
