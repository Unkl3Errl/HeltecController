package com.unkl3errl.helteccontroller.bruce

import android.content.Context
import com.unkl3errl.helteccontroller.connection.PersistentDeviceConnections
import com.unkl3errl.helteccontroller.connection.PersistentUsbKind
import com.unkl3errl.helteccontroller.connection.PersistentUsbSerialSession

/** Bruce-specific view of the process-wide USB session. */
class BruceUsbSerial(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onBruceUsbStatus(message: String, connected: Boolean)
        fun onBruceUsbData(data: ByteArray)
        fun onBruceUsbError(message: String)
    }

    private val session = PersistentDeviceConnections.usb(context, PersistentUsbKind.BRUCE)
    private val relay = object : PersistentUsbSerialSession.Listener {
        override fun onStatus(message: String, connected: Boolean) =
            listener.onBruceUsbStatus(message, connected)

        override fun onData(data: ByteArray) = listener.onBruceUsbData(data)

        override fun onError(message: String) = listener.onBruceUsbError(message)
    }

    val isConnected: Boolean
        get() = session.isConnected

    init {
        session.addListener(relay)
    }

    fun connect() = session.connect()

    fun writeCommand(command: String) = session.writeCommand(command)

    fun close() = session.disconnect()

    /** Detach this screen without closing the service-owned serial port. */
    fun destroy() = session.removeListener(relay)
}
