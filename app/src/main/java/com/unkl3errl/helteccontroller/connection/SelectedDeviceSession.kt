package com.unkl3errl.helteccontroller.connection

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Stable screen-facing session that follows the device selected beneath a firmware tab.
 * Non-selected physical sessions stay alive so their storage mirrors can continue draining.
 */
internal class SelectedDeviceSession(
    override val kind: PersistentUsbKind,
    private val selectedSession: () -> PersistentDeviceSession?,
) : DeviceSerialSession {
    private val listeners = CopyOnWriteArraySet<DeviceSerialSession.Listener>()
    private val exclusiveListeners = CopyOnWriteArraySet<DeviceSerialSession.Listener>()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var attached: PersistentDeviceSession? = null
    @Volatile private var attachedRelay: DeviceSerialSession.Listener? = null
    @Volatile private var lastStatus = "No ${kind.displayName} device selected"

    override val connectionId: String
        get() = current()?.connectionId ?: "${kind.name}:none"
    override val isConnected: Boolean get() = current()?.isConnected == true
    override val isUsbConnected: Boolean get() = current()?.isUsbConnected == true
    override val isBluetoothConnected: Boolean get() = current()?.isBluetoothConnected == true
    override val bluetoothSupported: Boolean
        get() = FirmwareBleProfile.forKind(kind) != null
    override val activeTransport: DeviceTransport? get() = current()?.activeTransport

    override fun addListener(
        listener: DeviceSerialSession.Listener,
        receiveExclusiveData: Boolean,
    ) {
        listeners.add(listener)
        if (receiveExclusiveData) exclusiveListeners.add(listener)
        refreshSelection()
        handler.post {
            if (listeners.contains(listener)) listener.onStatus(lastStatus, isConnected)
        }
    }

    override fun removeListener(listener: DeviceSerialSession.Listener) {
        exclusiveListeners.remove(listener)
        listeners.remove(listener)
    }

    /** Called by the registry whenever the selected physical device changes. */
    fun refreshSelection() {
        val next = selectedSession()
        if (attached === next) return
        attachedRelay?.let { relay -> attached?.removeListener(relay) }
        attached = next
        attachedRelay = next?.let(::relayFor)
        attachedRelay?.let { relay ->
            next?.addListener(relay, receiveExclusiveData = exclusiveListeners.isNotEmpty())
        }
        lastStatus = next?.description()?.let { "Selected $it" }
            ?: "No ${kind.displayName} device selected"
        handler.post {
            // Selection and transport connection can complete before this deferred callback.
            // Deliver their live combined state so a captured pre-connect false value cannot
            // overwrite the newer USB/Bluetooth status.
            listeners.forEach { it.onStatus(lastStatus, isConnected) }
        }
    }

    private fun relayFor(source: PersistentDeviceSession) =
        object : DeviceSerialSession.Listener {
            private fun isCurrent(): Boolean =
                attached === source && attachedRelay === this

            override fun onStatus(message: String, connected: Boolean) {
                if (!isCurrent()) return
                lastStatus = message
                listeners.forEach { it.onStatus(message, connected) }
            }

            override fun onData(data: ByteArray) {
                if (!isCurrent()) return
                listeners.forEach { it.onData(data) }
            }

            override fun onError(message: String) {
                if (!isCurrent()) return
                listeners.forEach { it.onError(message) }
            }
        }

    override fun connectUsb() = current()?.connectUsb() ?: emitNoSelection()
    override fun connectBluetooth(address: String) =
        current()?.connectBluetooth(address) ?: emitNoSelection()
    override fun reconnectSavedBluetooth() = current()?.reconnectSavedBluetooth() ?: Unit
    override fun disconnectUsb() = current()?.disconnectUsb() ?: Unit
    override fun disconnectBluetooth(forget: Boolean) =
        current()?.disconnectBluetooth(forget) ?: Unit
    override fun disconnectAll() = current()?.disconnectAll() ?: Unit
    override fun write(data: ByteArray) = current()?.write(data) ?: emitNoSelection()
    override fun writeCommand(command: String, onDispatched: () -> Unit) =
        current()?.writeCommand(command, onDispatched) ?: emitNoSelection()

    override fun <T> withExclusiveCommands(block: ((String) -> Unit) -> T): T =
        requireSelected().withExclusiveCommands(block)

    override fun description(): String = current()?.description() ?: kind.displayName

    private fun current(): PersistentDeviceSession? {
        refreshSelection()
        return attached
    }

    private fun requireSelected(): PersistentDeviceSession = current() ?: run {
        val message = noSelectionMessage()
        listeners.forEach { it.onError(message) }
        throw IllegalStateException(message)
    }

    private fun emitNoSelection() = listeners.forEach { it.onError(noSelectionMessage()) }

    private fun noSelectionMessage() = "Select or add a ${kind.displayName} device first"
}
