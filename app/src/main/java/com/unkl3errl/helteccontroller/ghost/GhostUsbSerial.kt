package com.unkl3errl.helteccontroller.ghost

import android.content.Context
import com.unkl3errl.helteccontroller.connection.PersistentDeviceConnections
import com.unkl3errl.helteccontroller.connection.PersistentUsbKind
import com.unkl3errl.helteccontroller.connection.PersistentUsbSerialSession

/** GhostESP-specific view of the process-wide USB session. */
class GhostUsbSerial(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onSerialStatus(message: String, connected: Boolean)
        fun onSerialData(data: ByteArray)
        fun onSerialError(message: String)
    }

    private val session = PersistentDeviceConnections.usb(context, PersistentUsbKind.GHOSTESP)
    private val relay = object : PersistentUsbSerialSession.Listener {
        override fun onStatus(message: String, connected: Boolean) =
            listener.onSerialStatus(message, connected)

        override fun onData(data: ByteArray) = listener.onSerialData(data)

        override fun onError(message: String) = listener.onSerialError(message)
    }

    val isConnected: Boolean
        get() = session.isConnected

    init {
        session.addListener(relay)
    }

    fun connect() = session.connect()

    fun write(data: ByteArray) = session.write(data)

    fun writeCommand(command: String) = session.writeCommand(command)

    fun disconnect() = session.disconnect()

    fun close() = disconnect()

    /** Detach this screen without closing the service-owned serial port. */
    fun destroy() = session.removeListener(relay)
}
