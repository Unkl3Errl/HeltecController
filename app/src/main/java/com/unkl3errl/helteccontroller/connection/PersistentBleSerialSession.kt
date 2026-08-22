package com.unkl3errl.helteccontroller.connection

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class FirmwareBleDevice(
    val address: String,
    val name: String,
    val rssi: Int,
)

internal class FirmwareBleScanner(private val context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun scan(
        kind: PersistentUsbKind,
        durationMs: Long = 6_000L,
        completion: (Result<List<FirmwareBleDevice>>) -> Unit,
    ) {
        stop()
        val profile = FirmwareBleProfile.forKind(kind)
            ?: return completion(Result.failure(IllegalStateException("${kind.displayName} does not advertise an app-compatible Bluetooth service")))
        if (!hasScanPermission(appContext)) {
            completion(Result.failure(SecurityException("Nearby devices permission is required")))
            return
        }
        val adapter = manager.adapter
        if (adapter == null) {
            completion(Result.failure(IllegalStateException("This Android device does not support Bluetooth")))
            return
        }
        if (!adapter.isEnabled) {
            completion(Result.failure(IllegalStateException("Turn on Bluetooth, then try again")))
            return
        }
        val scanner = adapter.bluetoothLeScanner
            ?: return completion(Result.failure(IllegalStateException("Bluetooth scanning is unavailable")))
        val devices = linkedMapOf<String, FirmwareBleDevice>()
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val hasService = record?.serviceUuids?.any { it.uuid == profile.serviceUuid } == true
                if (!hasService) return
                val name = record.deviceName ?: profile.advertisedName
                devices[result.device.address] = FirmwareBleDevice(result.device.address, name, result.rssi)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) =
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }

            override fun onScanFailed(errorCode: Int) {
                if (callback !== this) return
                callback = null
                completion(Result.failure(IllegalStateException("Bluetooth scan failed ($errorCode)")))
            }
        }
        callback = scanCallback
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(profile.serviceUuid)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        handler.postDelayed({
            if (callback !== scanCallback) return@postDelayed
            callback = null
            runCatching { scanner.stopScan(scanCallback) }
            completion(Result.success(devices.values.sortedByDescending(FirmwareBleDevice::rssi)))
        }, durationMs)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val active = callback ?: return
        callback = null
        if (hasScanPermission(appContext)) {
            runCatching { manager.adapter?.bluetoothLeScanner?.stopScan(active) }
        }
    }
}

internal class PersistentBleSerialSession(
    context: Context,
    val kind: PersistentUsbKind,
    private val profile: FirmwareBleProfile,
    private val preferenceKey: String,
) {
    interface Listener {
        fun onStatus(message: String, connected: Boolean)
        fun onData(data: ByteArray)
        fun onError(message: String)
    }

    private data class PendingGattOperation(
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var status: Int = BluetoothGatt.GATT_FAILURE,
    )

    private data class GhostCommand(
        val ack: CountDownLatch = CountDownLatch(1),
        val end: CountDownLatch = CountDownLatch(1),
        @Volatile var ackStatus: Int = -1,
    )

    private companion object {
        const val TAG = "FirmwareBleSession"
        const val PREFERENCES = "persistent_bluetooth_devices"
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val GATT_OPERATION_TIMEOUT_MS = 5_000L
        const val MAX_CONSECUTIVE_WRITE_TIMEOUTS = 2
        const val GHOST_ACK_TIMEOUT_MS = 5_000L
        const val GHOST_END_TIMEOUT_MS = 120_000L
        const val MAX_RECONNECT_DELAY_MS = 30_000L
        val BRUCE_CHARACTERISTIC =
            java.util.UUID.fromString("d555ed97-bf2a-4f46-b3eb-d1fcdd7325e9")
        val CLIENT_CONFIG =
            java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private val commandLock = ReentrantLock(true)
    private val gattOperationLock = ReentrantLock(true)
    private val stateLock = Any()
    private val decoder = GhostBleBridgeProtocol.Decoder()
    private val ghostCommands = ConcurrentHashMap<Int, GhostCommand>()
    private val commandSequence = AtomicInteger(1)
    private val consecutiveWriteTimeouts = AtomicInteger(0)

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = GhostBleBridgeProtocol.DEFAULT_MTU
    @Volatile private var connecting = false
    @Volatile private var ready = false
    @Volatile private var manualDisconnect = false
    @Volatile private var currentAddress: String? = null
    @Volatile private var pendingWrite: PendingGattOperation? = null
    @Volatile private var pendingDescriptor: PendingGattOperation? = null
    @Volatile private var pendingMtu: PendingGattOperation? = null
    @Volatile private var activeGhostCommandId = 0
    @Volatile private var reconnectAttempts = 0
    @Volatile private var connectionGeneration = 0

    val isConnected: Boolean get() = ready
    val address: String? get() = currentAddress

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            if (!accept(callbackGatt)) {
                runCatching { callbackGatt.close() }
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        failConnection("Bluetooth connection failed ($status)")
                        return
                    }
                    val generation = connectionGeneration
                    emitStatus("${kind.displayName} Bluetooth connected · discovering services…", false)
                    handler.postDelayed({
                        if (generation != connectionGeneration || gatt !== callbackGatt) return@postDelayed
                        val started = runCatching { callbackGatt.discoverServices() }.getOrDefault(false)
                        if (!started) failConnection("Bluetooth service discovery could not start")
                    }, 300L)
                    handler.postDelayed({
                        if (generation == connectionGeneration && connecting && !ready) {
                            failConnection("Bluetooth connection timed out")
                        }
                    }, CONNECT_TIMEOUT_MS)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val shouldReconnect = !manualDisconnect
                    closeGatt(callbackGatt)
                    emitStatus("${kind.displayName} Bluetooth disconnected", false)
                    if (shouldReconnect) scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            if (!accept(callbackGatt)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection("Bluetooth service discovery failed ($status)")
                return
            }
            val service = callbackGatt.getService(profile.serviceUuid)
            val write = when (profile) {
                FirmwareBleProfile.BRUCE_SERIAL -> service?.getCharacteristic(BRUCE_CHARACTERISTIC)
                FirmwareBleProfile.GHOST_BRIDGE -> service?.getCharacteristic(GhostBleBridgeUuids.RX)
                FirmwareBleProfile.MARAUDER_UART -> service?.getCharacteristic(MarauderBleUuids.RX)
            }
            val notify = when (profile) {
                FirmwareBleProfile.BRUCE_SERIAL -> service?.getCharacteristic(BRUCE_CHARACTERISTIC)
                FirmwareBleProfile.GHOST_BRIDGE -> service?.getCharacteristic(GhostBleBridgeUuids.TX)
                FirmwareBleProfile.MARAUDER_UART -> service?.getCharacteristic(MarauderBleUuids.TX)
            }
            val hasControl = profile != FirmwareBleProfile.GHOST_BRIDGE ||
                service?.getCharacteristic(GhostBleBridgeUuids.CTRL) != null
            if (write == null || notify == null || !hasControl) {
                failConnection("The device does not expose the complete ${profile.advertisedName} service")
                return
            }
            writeCharacteristic = write
            notifyCharacteristic = notify
            if (!runCatching { callbackGatt.setCharacteristicNotification(notify, true) }.getOrDefault(false)) {
                failConnection("Android could not enable Bluetooth notifications")
                return
            }
            val descriptor = notify.getDescriptor(CLIENT_CONFIG)
            if (descriptor == null) {
                failConnection("The Bluetooth notification descriptor is missing")
                return
            }
            writer.execute {
                if (writeDescriptorBlocking(callbackGatt, descriptor) != BluetoothGatt.GATT_SUCCESS) {
                    failConnection("Bluetooth notification setup failed")
                    return@execute
                }
                val mtuStatus = requestMtuBlocking(callbackGatt, profile.requestedMtu)
                if (mtuStatus != BluetoothGatt.GATT_SUCCESS) {
                    mtu = GhostBleBridgeProtocol.DEFAULT_MTU
                }
                markReady(callbackGatt)
            }
        }

        override fun onMtuChanged(callbackGatt: BluetoothGatt, newMtu: Int, status: Int) {
            if (!accept(callbackGatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            pendingMtu?.also {
                it.status = status
                it.latch.countDown()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (accept(callbackGatt)) receiveNotification(characteristic.uuid, characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (accept(callbackGatt)) receiveNotification(characteristic.uuid, value)
        }

        override fun onCharacteristicWrite(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!accept(callbackGatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) consecutiveWriteTimeouts.set(0)
            pendingWrite?.also {
                it.status = status
                it.latch.countDown()
            }
        }

        override fun onDescriptorWrite(
            callbackGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!accept(callbackGatt)) return
            pendingDescriptor?.also {
                it.status = status
                it.latch.countDown()
            }
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        handler.post {
            if (!listeners.contains(listener)) return@post
            val connected = ready
            val message = when {
                connected -> "${kind.displayName} Bluetooth connected · ${currentAddress.orEmpty()}"
                connecting -> "${kind.displayName} Bluetooth is connecting…"
                else -> "${kind.displayName} Bluetooth is not connected"
            }
            listener.onStatus(message, connected)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String, remember: Boolean = true) {
        if (!hasConnectPermission(appContext)) {
            emitError("Nearby devices permission is required for Bluetooth")
            return
        }
        val adapter = manager.adapter
        if (adapter == null) {
            emitError("This Android device does not support Bluetooth")
            return
        }
        if (!adapter.isEnabled) {
            emitError("Turn on Bluetooth, then try again")
            return
        }
        if ((ready || connecting) && currentAddress == address) return
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrElse {
            emitError("The saved Bluetooth address is invalid")
            return
        }
        disconnectInternal(emit = false)
        manualDisconnect = false
        connecting = true
        ready = false
        currentAddress = address
        connectionGeneration++
        if (remember) {
            appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(preferenceKey, address).apply()
        }
        DeviceConnectionService.start(appContext)
        emitStatus("Connecting to ${profile.advertisedName} over Bluetooth…", false)
        val opened = runCatching {
            device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (opened == null) {
            connecting = false
            emitError("Android could not open the Bluetooth connection")
            scheduleReconnect()
            return
        }
        synchronized(stateLock) { gatt = opened }
    }

    fun reconnectSaved() {
        if (ready || connecting || !hasConnectPermission(appContext)) return
        val saved = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(preferenceKey, null) ?: return
        connect(saved, remember = false)
    }

    fun disconnect(forget: Boolean = false) {
        manualDisconnect = true
        reconnectAttempts = 0
        handler.removeCallbacksAndMessages(reconnectToken)
        disconnectInternal(emit = true)
        if (forget) {
            appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().remove(preferenceKey).apply()
        }
    }

    fun write(data: ByteArray) {
        writer.execute {
            runCatching { writeBlocking(data, isCommand = false) }
                .onFailure { emitError("Bluetooth write failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    fun writeCommand(command: String) {
        writer.execute {
            runCatching { writeCommandBlocking(command) }
                .onFailure { emitError("Bluetooth command failed: ${it.message ?: it.javaClass.simpleName}") }
        }
    }

    fun <T> withExclusiveCommands(block: ((String) -> Unit) -> T): T =
        commandLock.withLock {
            check(ready) { "${kind.displayName} Bluetooth is disconnected" }
            block(::writeCommandBlocking)
        }

    private fun writeCommandBlocking(command: String) {
        val clean = command.trim()
        require(clean.isNotEmpty()) { "Command must not be empty" }
        val bytes = when (profile) {
            FirmwareBleProfile.GHOST_BRIDGE -> clean.toByteArray(Charsets.UTF_8)
            FirmwareBleProfile.BRUCE_SERIAL,
            FirmwareBleProfile.MARAUDER_UART -> (clean + "\r\n").toByteArray(Charsets.UTF_8)
        }
        writeBlocking(bytes, isCommand = true)
    }

    private fun writeBlocking(data: ByteArray, isCommand: Boolean) {
        check(ready) { "${kind.displayName} Bluetooth is disconnected" }
        when (profile) {
            FirmwareBleProfile.GHOST_BRIDGE -> {
                val command = if (isCommand) data else data.toString(Charsets.UTF_8).trim().toByteArray()
                writeGhostCommandBlocking(command)
            }
            FirmwareBleProfile.BRUCE_SERIAL,
            FirmwareBleProfile.MARAUDER_UART -> {
                val capacity = (mtu - 3).coerceAtLeast(20)
                val callbackGatt = requireNotNull(gatt)
                val characteristic = requireNotNull(writeCharacteristic)
                data.asList().chunked(capacity).forEach { chunk ->
                    val status = writeCharacteristicBlocking(
                        callbackGatt,
                        characteristic,
                        chunk.toByteArray(),
                    )
                    check(status == BluetoothGatt.GATT_SUCCESS) { "GATT status $status" }
                }
            }
        }
    }

    private fun writeGhostCommandBlocking(command: ByteArray) {
        val previousId = activeGhostCommandId
        if (previousId != 0) {
            val previous = ghostCommands[previousId]
            val ended = previous?.end?.await(GHOST_END_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: true
            check(ended) { "Previous Ghost BLE command did not finish" }
            ghostCommands.remove(previousId)
            if (activeGhostCommandId == previousId) activeGhostCommandId = 0
        }

        val id = nextCommandId()
        Log.d(TAG, "Ghost command $id queued (${command.size} bytes)")
        val operation = GhostCommand()
        ghostCommands[id] = operation
        activeGhostCommandId = id
        try {
            val callbackGatt = requireNotNull(gatt)
            val characteristic = requireNotNull(writeCharacteristic)
            GhostBleBridgeProtocol.commandFrames(id, command, mtu).forEach { frame ->
                val status = writeCharacteristicBlocking(callbackGatt, characteristic, frame)
                check(status == BluetoothGatt.GATT_SUCCESS) { "GATT status $status" }
            }
            check(operation.ack.await(GHOST_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "Ghost BLE Bridge did not acknowledge the command"
            }
            Log.d(TAG, "Ghost command $id acknowledged with status ${operation.ackStatus}")
            check(operation.ackStatus == 0) { "Ghost BLE Bridge rejected the command" }
        } catch (error: Throwable) {
            ghostCommands.remove(id)
            if (activeGhostCommandId == id) activeGhostCommandId = 0
            throw error
        }
    }

    private fun receiveNotification(uuid: java.util.UUID, value: ByteArray) {
        if (value.isEmpty() || uuid != notifyCharacteristic?.uuid) return
        if (
            profile == FirmwareBleProfile.BRUCE_SERIAL ||
            profile == FirmwareBleProfile.MARAUDER_UART
        ) {
            emitData(value.copyOf())
            return
        }
        val decoded = decoder.feed(value)
        Log.d(
            TAG,
            "Ghost notification ${value.size} bytes, ${decoded.frames.size} frame(s), " +
                "${decoded.unframed.size} unframed byte(s)",
        )
        if (decoded.unframed.isNotEmpty()) emitData(decoded.unframed)
        decoded.frames.forEach { frame ->
            val command = ghostCommands[frame.commandId]
            when (frame.type) {
                GhostBleBridgeProtocol.TYPE_ACK -> command?.let {
                    it.ackStatus = frame.status
                    it.ack.countDown()
                }
                GhostBleBridgeProtocol.TYPE_DATA,
                GhostBleBridgeProtocol.TYPE_HAS_DATA -> if (frame.payload.isNotEmpty()) emitData(frame.payload)
                GhostBleBridgeProtocol.TYPE_ERROR -> {
                    if (frame.payload.isNotEmpty()) emitData(frame.payload)
                    command?.let {
                        it.ackStatus = frame.status.takeIf { status -> status != 0 } ?: 1
                        it.ack.countDown()
                        it.end.countDown()
                    }
                }
                GhostBleBridgeProtocol.TYPE_END -> command?.let {
                    Log.d(TAG, "Ghost command ${frame.commandId} ended with status ${frame.status}")
                    it.end.countDown()
                    if (activeGhostCommandId == frame.commandId) activeGhostCommandId = 0
                }
            }
        }
    }

    private fun nextCommandId(): Int {
        while (true) {
            val current = commandSequence.get()
            val next = if (current == Int.MAX_VALUE) 1 else current + 1
            if (commandSequence.compareAndSet(current, next)) return current
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicBlocking(
        callbackGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Int = gattOperationLock.withLock {
        check(hasConnectPermission(appContext)) { "Nearby devices permission is missing" }
        val operation = PendingGattOperation()
        pendingWrite = operation
        val dispatched = if (Build.VERSION.SDK_INT >= 33) {
            callbackGatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            callbackGatt.writeCharacteristic(characteristic)
        }
        if (!dispatched) {
            pendingWrite = null
            failConnection("${kind.displayName} Bluetooth write could not start")
            return@withLock BluetoothGatt.GATT_FAILURE
        }
        val completed = operation.latch.await(GATT_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (pendingWrite === operation) pendingWrite = null
        if (!completed) {
            // A Heltec can miss one Android write callback while switching from an active USB
            // console to its already-connected BLE path. Give that callback and the ATT queue
            // one retry window before replacing a link that may still be healthy. A second
            // consecutive timeout, or a failed dispatch on the next attempt, uses the normal
            // bounded reconnect path.
            val timeouts = consecutiveWriteTimeouts.incrementAndGet()
            if (timeouts >= MAX_CONSECUTIVE_WRITE_TIMEOUTS) {
                failConnection("${kind.displayName} Bluetooth write timed out")
            } else {
                Log.w(TAG, "${kind.displayName} Bluetooth write callback timed out; keeping the link for one retry")
            }
            return@withLock BluetoothGatt.GATT_FAILURE
        }
        if (operation.status == BluetoothGatt.GATT_SUCCESS) consecutiveWriteTimeouts.set(0)
        operation.status
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptorBlocking(
        callbackGatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
    ): Int = gattOperationLock.withLock {
        val operation = PendingGattOperation()
        pendingDescriptor = operation
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val dispatched = if (Build.VERSION.SDK_INT >= 33) {
            callbackGatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            callbackGatt.writeDescriptor(descriptor)
        }
        if (!dispatched) {
            pendingDescriptor = null
            return@withLock BluetoothGatt.GATT_FAILURE
        }
        operation.latch.await(GATT_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (pendingDescriptor === operation) pendingDescriptor = null
        operation.status
    }

    @SuppressLint("MissingPermission")
    private fun requestMtuBlocking(callbackGatt: BluetoothGatt, requested: Int): Int =
        gattOperationLock.withLock {
            val operation = PendingGattOperation()
            pendingMtu = operation
            if (!callbackGatt.requestMtu(requested)) {
                pendingMtu = null
                return@withLock BluetoothGatt.GATT_FAILURE
            }
            operation.latch.await(GATT_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (pendingMtu === operation) pendingMtu = null
            operation.status
        }

    private fun markReady(callbackGatt: BluetoothGatt) {
        if (!accept(callbackGatt)) return
        connecting = false
        ready = true
        consecutiveWriteTimeouts.set(0)
        reconnectAttempts = 0
        currentAddress?.let { address ->
            appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(preferenceKey, address).apply()
        }
        emitStatus("Connected to ${profile.advertisedName} over Bluetooth · ${currentAddress.orEmpty()}", true)
        DeviceConnectionService.refresh(appContext)
    }

    private fun failConnection(message: String) {
        // Automatic reconnect failures are transport state, not command output. Keep them in
        // Logcat and the connection status so an unavailable fallback cannot flood the selected
        // device's human console while USB or another transport remains usable.
        Log.w(TAG, message)
        val shouldReconnect = !manualDisconnect
        disconnectInternal(emit = false)
        emitStatus("${kind.displayName} Bluetooth connection failed", false)
        if (shouldReconnect) scheduleReconnect()
    }

    @SuppressLint("MissingPermission")
    private fun disconnectInternal(emit: Boolean) {
        connectionGeneration++
        connecting = false
        ready = false
        val closing = synchronized(stateLock) { gatt.also { gatt = null } }
        runCatching { closing?.disconnect() }
        runCatching { closing?.close() }
        writeCharacteristic = null
        notifyCharacteristic = null
        mtu = GhostBleBridgeProtocol.DEFAULT_MTU
        consecutiveWriteTimeouts.set(0)
        decoder.reset()
        pendingWrite?.latch?.countDown()
        pendingDescriptor?.latch?.countDown()
        pendingMtu?.latch?.countDown()
        pendingWrite = null
        pendingDescriptor = null
        pendingMtu = null
        ghostCommands.values.forEach {
            it.ack.countDown()
            it.end.countDown()
        }
        ghostCommands.clear()
        activeGhostCommandId = 0
        if (emit) emitStatus("${kind.displayName} Bluetooth disconnected", false)
        DeviceConnectionService.refresh(appContext)
    }

    private fun closeGatt(callbackGatt: BluetoothGatt) {
        synchronized(stateLock) {
            if (gatt !== callbackGatt) return
        }
        disconnectInternal(emit = false)
    }

    private val reconnectToken = Any()

    private fun scheduleReconnect() {
        val saved = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(preferenceKey, null) ?: return
        if (manualDisconnect || !hasConnectPermission(appContext)) return
        val exponent = reconnectAttempts.coerceAtMost(4)
        val delay = (2_000L shl exponent).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        reconnectAttempts++
        handler.removeCallbacksAndMessages(reconnectToken)
        handler.postAtTime({
            if (!manualDisconnect && !ready && !connecting) connect(saved, remember = false)
        }, reconnectToken, android.os.SystemClock.uptimeMillis() + delay)
        emitStatus("${kind.displayName} Bluetooth disconnected · retrying in ${delay / 1_000}s", false)
    }

    private fun accept(callbackGatt: BluetoothGatt): Boolean = synchronized(stateLock) {
        when {
            gatt === callbackGatt -> true
            gatt == null && connecting -> {
                gatt = callbackGatt
                true
            }
            else -> false
        }
    }

    private fun emitStatus(message: String, connected: Boolean) {
        listeners.forEach { it.onStatus(message, connected) }
        DeviceConnectionService.refresh(appContext)
    }

    private fun emitData(data: ByteArray) = listeners.forEach { it.onData(data) }

    private fun emitError(message: String) = listeners.forEach { it.onError(message) }
}

internal fun hasBluetoothPermissions(context: Context): Boolean =
    hasScanPermission(context) && hasConnectPermission(context)

private fun hasScanPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

private fun hasConnectPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
