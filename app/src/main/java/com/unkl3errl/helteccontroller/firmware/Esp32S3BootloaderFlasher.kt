package com.unkl3errl.helteccontroller.firmware

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.unkl3errl.helteccontroller.usb.UsbDeviceRegistry
import com.unkl3errl.helteccontroller.usb.UsbDeviceTarget
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

internal data class Esp32S3SecurityInfo(
    val flags: Long,
    val flashCryptCount: Int,
    val chipId: Int,
)

/** Parses the 20-byte ESP32-S3 ROM GET_SECURITY_INFO payload (without status bytes). */
internal fun parseEsp32S3SecurityInfo(data: ByteArray): Esp32S3SecurityInfo {
    require(data.size >= 20) {
        "ESP32-S3 security payload was ${data.size} bytes; expected 20"
    }
    fun uint32(offset: Int): Long =
        (data[offset].toLong() and 0xff) or
            ((data[offset + 1].toLong() and 0xff) shl 8) or
            ((data[offset + 2].toLong() and 0xff) shl 16) or
            ((data[offset + 3].toLong() and 0xff) shl 24)

    return Esp32S3SecurityInfo(
        flags = uint32(0),
        flashCryptCount = data[4].toInt() and 0xff,
        chipId = uint32(12).toInt(),
    )
}

/** USB bulk transfers report a detached or reset Android host endpoint as an IOException. */
internal fun isRecoverableEsp32S3UsbFailure(error: Throwable): Boolean =
    generateSequence(error as Throwable?) { it.cause }.any { it is IOException }

/** Minimal ESP32-S3 ROM flasher for a checksum-verified, merged offset-0 image. */
class Esp32S3BootloaderFlasher(context: Context) {
    interface Listener {
        fun onFlashProgress(percent: Int, message: String)
        fun onFlashComplete(target: UsbDeviceTarget)
        fun onFlashFailed(message: String)
    }

    companion object {
        private const val TAG = "Esp32S3Flasher"
        private const val ACTION_USB_PERMISSION =
            "com.unkl3errl.helteccontroller.FLASH_USB_PERMISSION"
        private const val FLASH_BEGIN = 0x02
        private const val FLASH_DATA = 0x03
        private const val SYNC = 0x08
        private const val WRITE_REG = 0x09
        private const val READ_REG = 0x0A
        private const val SPI_SET_PARAMS = 0x0B
        private const val SPI_ATTACH = 0x0D
        private const val SPI_FLASH_MD5 = 0x13
        private const val GET_SECURITY_INFO = 0x14
        private const val BLOCK_SIZE = 0x400
        private const val FLASH_BYTES = 16 * 1024 * 1024
        private const val ESP32_S3_CHIP_ID = 9
        private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        private const val MAX_FLASH_ATTEMPTS = 3
        private const val RECONNECT_TIMEOUT_MS = 30_000L
        private const val RECONNECT_POLL_MS = 200L
        private const val RTC_CNTL_WDTCONFIG0_REG = 0x60008098L
        private const val RTC_CNTL_WDTWPROTECT_REG = 0x600080B0L
        private const val RTC_CNTL_WDT_WKEY = 0x50D83AA1L
        private const val RTC_CNTL_SWD_CONF_REG = 0x600080B4L
        private const val RTC_CNTL_SWD_WPROTECT_REG = 0x600080B8L
        private const val RTC_CNTL_SWD_WKEY = 0x8F1D312AL
        private const val RTC_CNTL_SWD_AUTO_FEED_EN = 1L shl 31
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var pending: PendingFlash? = null

    private data class PendingFlash(
        val target: UsbDeviceTarget,
        val image: File,
        val listener: Listener,
    )
    private data class Reply(val value: Long, val data: ByteArray)
    private class FlashAttemptFailure(
        val stage: String,
        cause: Exception,
    ) : Exception(cause.message, cause)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val request = pending.also { pending = null } ?: return
            val device = intent.usbDevice()
            if (
                !intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
                device == null || !request.target.matches(device)
            ) {
                busy.set(false)
                request.listener.onFlashFailed("USB permission was not granted for the selected target")
                return
            }
            begin(device, request)
        }
    }

    init { registerPermissionReceiver() }

    fun targets(): List<UsbDeviceTarget> = UsbDeviceRegistry.nativeEsp32S3Targets(usbManager)

    fun flash(target: UsbDeviceTarget, image: File, listener: Listener) {
        if (!busy.compareAndSet(false, true)) {
            listener.onFlashFailed("Another firmware flash is already running")
            return
        }
        if (!image.isFile || image.length() !in 64 * 1024..MAX_IMAGE_BYTES.toLong()) {
            busy.set(false)
            listener.onFlashFailed("The retained firmware image is invalid")
            return
        }
        val device = UsbDeviceRegistry.nativeDeviceFor(usbManager, target)
        if (device == null) {
            busy.set(false)
            listener.onFlashFailed("The selected ESP32-S3 USB target is no longer attached")
            return
        }
        val request = PendingFlash(target, image, listener)
        if (usbManager.hasPermission(device)) begin(device, request)
        else {
            pending = request
            usbManager.requestPermission(device, permissionIntent())
            listener.onFlashProgress(0, "Waiting for USB permission…")
        }
    }

    fun close() {
        pending = null
        runCatching { appContext.unregisterReceiver(permissionReceiver) }
        executor.shutdownNow()
    }

    private fun begin(device: UsbDevice, request: PendingFlash) {
        executor.execute {
            try {
                var currentDevice = device
                for (attempt in 1..MAX_FLASH_ATTEMPTS) {
                    try {
                        Log.i(
                            TAG,
                            "Starting flash attempt $attempt/$MAX_FLASH_ATTEMPTS for " +
                                request.target.displayLabel(),
                        )
                        val flashedTarget = flashSession(
                            currentDevice,
                            request,
                            resumeBootloader = attempt > 1,
                        )
                        Log.i(TAG, "Flash verified for ${request.target.displayLabel()}")
                        request.listener.onFlashComplete(flashedTarget)
                        return@execute
                    } catch (failure: FlashAttemptFailure) {
                        val cause = failure.cause ?: failure
                        val detail = cause.message ?: cause.javaClass.simpleName
                        val canRetry = attempt < MAX_FLASH_ATTEMPTS &&
                            isRecoverableEsp32S3UsbFailure(cause)
                        if (!canRetry) {
                            Log.e(
                                TAG,
                                "${failure.stage} failed for ${request.target.displayLabel()}: $detail",
                                cause,
                            )
                            request.listener.onFlashFailed("${failure.stage} failed: $detail")
                            return@execute
                        }

                        Log.w(
                            TAG,
                            "USB link dropped during ${failure.stage}; waiting for " +
                                "${request.target.displayLabel()} before attempt ${attempt + 1}",
                            cause,
                        )
                        request.listener.onFlashProgress(
                            0,
                            "USB link reset. Waiting for the board; unplug and reconnect it " +
                                "if Android does not restore USB automatically…",
                        )
                        val reattached = waitForReattachedDevice(request)
                        if (reattached == null) {
                            request.listener.onFlashFailed(
                                "USB link was lost during ${failure.stage.lowercase()} and the " +
                                    "same board did not reconnect within 30 seconds",
                            )
                            return@execute
                        }
                        currentDevice = reattached
                        request.listener.onFlashProgress(
                            0,
                            "Board reconnected. Restarting the flash safely " +
                                "(${attempt + 1}/$MAX_FLASH_ATTEMPTS)…",
                        )
                    }
                }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun flashSession(
        device: UsbDevice,
        request: PendingFlash,
        resumeBootloader: Boolean,
    ): UsbDeviceTarget {
        var port: UsbSerialPort? = null
        var stage = "Opening the selected USB target"
        try {
            request.listener.onFlashProgress(0, "Entering ESP32-S3 recovery mode…")
            val openedTarget = UsbDeviceRegistry.target(usbManager, device)
            require(request.target.samePhysicalDevice(openedTarget)) {
                "Android opened a different USB target"
            }
            val driver = UsbDeviceRegistry.driverForNative(device)
            val connection = usbManager.openDevice(device)
                ?: throw IOException("Android could not open the USB target")
            port = driver.ports.firstOrNull() ?: error("The USB target has no serial port")
            port.open(connection)
            port.setParameters(115_200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            clearInput(port, purgeOutput = true)
            if (resumeBootloader) {
                // The native USB endpoint commonly disappears at the exact moment the first
                // reset enters ROM download mode. Once Android restores the endpoint, resetting
                // it again can cause an endless disconnect cycle, so synchronize first.
                stage = "Resuming the ESP32-S3 recovery session"
                request.listener.onFlashProgress(
                    0,
                    "Board reconnected. Resuming ROM recovery without another reset…",
                )
                try {
                    sync(port)
                } catch (error: Exception) {
                    if (isRecoverableEsp32S3UsbFailure(error)) throw error
                    stage = "Entering ESP32-S3 recovery mode"
                    request.listener.onFlashProgress(
                        0,
                        "The board was not in ROM recovery. Entering recovery mode…",
                    )
                    enterBootloader(port)
                    stage = "Synchronizing with the ESP32-S3 ROM"
                    sync(port)
                }
            } else {
                stage = "Entering ESP32-S3 recovery mode"
                enterBootloader(port)
                stage = "Synchronizing with the ESP32-S3 ROM"
                sync(port)
            }
            stage = "Reading the ESP32-S3 security state"
            val security = command(
                port,
                GET_SECURITY_INFO,
                byteArrayOf(),
                responseBytes = 20,
                timeoutMs = 3_000,
            )
            val securityInfo = parseEsp32S3SecurityInfo(security.data)
            require(securityInfo.chipId == ESP32_S3_CHIP_ID) {
                "Attached chip is not an ESP32-S3"
            }
            require(securityInfo.flags and 0x1L == 0L) {
                "Secure Boot is enabled; unsigned recovery is blocked"
            }
            require(securityInfo.flags and 0x4L == 0L) {
                "Secure Download Mode is enabled; recovery is blocked"
            }
            require(Integer.bitCount(securityInfo.flashCryptCount) % 2 == 0) {
                "Flash encryption is enabled; plaintext recovery is blocked"
            }
            request.listener.onFlashProgress(0, "Preparing a stable recovery session…")
            stage = "Preparing the ESP32-S3 flash"
            disableUsbWatchdogs(port)
            command(port, SPI_ATTACH, ByteArray(8), timeoutMs = 3_000)
            command(
                port,
                SPI_SET_PARAMS,
                le32(0, FLASH_BYTES, 64 * 1024, 4 * 1024, 256, 0xffff),
                timeoutMs = 3_000,
            )
            stage = "Erasing and writing the firmware image"
            writeImage(port, request.image, request.listener)
            stage = "Verifying the firmware image"
            verifyImage(port, request.image)
            request.listener.onFlashProgress(100, "Verified. Restarting the device…")
            stage = "Restarting the flashed device"
            hardReset(port)
            return openedTarget
        } catch (error: Exception) {
            throw FlashAttemptFailure(stage, error)
        } finally {
            runCatching { port?.close() }
        }
    }

    private fun waitForReattachedDevice(request: PendingFlash): UsbDevice? {
        val deadline = System.nanoTime() + RECONNECT_TIMEOUT_MS * 1_000_000L
        var permissionRequested = false
        while (!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) {
            val device = UsbDeviceRegistry.nativeDeviceFor(usbManager, request.target)
            if (device != null) {
                if (usbManager.hasPermission(device) && canOpen(device)) {
                    Log.i(TAG, "Recovered openable USB endpoint for ${request.target.displayLabel()}")
                    return device
                }
                if (!usbManager.hasPermission(device) && !permissionRequested) {
                    permissionRequested = true
                    usbManager.requestPermission(device, permissionIntent())
                    request.listener.onFlashProgress(
                        0,
                        "Board reconnected. Waiting for USB permission to resume…",
                    )
                }
            }
            Thread.sleep(RECONNECT_POLL_MS)
        }
        return null
    }

    /** UsbManager briefly retains a detached device; opening it distinguishes that stale entry. */
    private fun canOpen(device: UsbDevice): Boolean {
        val connection = runCatching { usbManager.openDevice(device) }.getOrNull() ?: return false
        connection.close()
        return true
    }

    private fun permissionIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        9001,
        Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun writeImage(port: UsbSerialPort, image: File, listener: Listener) {
        val imageSize = image.length().toInt()
        val blockCount = ceil(imageSize.toDouble() / BLOCK_SIZE).toInt()
        command(
            port,
            FLASH_BEGIN,
            le32(imageSize, blockCount, BLOCK_SIZE, 0, 0),
            timeoutMs = 120_000,
        )
        FileInputStream(image).use { input ->
            val block = ByteArray(BLOCK_SIZE)
            repeat(blockCount) { sequence ->
                block.fill(0xff.toByte())
                var used = 0
                while (used < block.size) {
                    val count = input.read(block, used, block.size - used)
                    if (count < 0) break
                    used += count
                }
                var checksum = 0xef
                block.forEach { checksum = checksum xor (it.toInt() and 0xff) }
                val payload = le32(BLOCK_SIZE, sequence, 0, 0) + block
                var lastError: Exception? = null
                for (attempt in 1..3) {
                    try {
                        command(port, FLASH_DATA, payload, checksum.toLong(), timeoutMs = 10_000)
                        lastError = null
                        break
                    } catch (error: Exception) {
                        if (isRecoverableEsp32S3UsbFailure(error)) throw error
                        lastError = error
                    }
                }
                lastError?.let { throw it }
                if (sequence % 32 == 0 || sequence == blockCount - 1) {
                    val percent = ((sequence + 1) * 95 / blockCount).coerceIn(1, 95)
                    listener.onFlashProgress(percent, "Writing image · $percent%")
                }
            }
        }
    }

    private fun verifyImage(port: UsbSerialPort, image: File) {
        val expected = md5(image)
        val reply = command(
            port,
            SPI_FLASH_MD5,
            le32(0, image.length().toInt(), 0, 0),
            responseBytes = 32,
            timeoutMs = 120_000,
        )
        val actual = reply.data.copyOfRange(0, 32).toString(Charsets.US_ASCII).lowercase()
        require(actual == expected) { "Flash verification failed" }
    }

    private fun sync(port: UsbSerialPort) {
        val payload = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
        var lastError: Exception? = null
        repeat(7) {
            try {
                command(port, SYNC, payload, timeoutMs = 1_000)
                // The ROM sends seven extra SYNC responses. CDC ACM does not implement the
                // library's hardware-purge API, so drain it through normal reads when necessary.
                Thread.sleep(100)
                clearInput(port, purgeOutput = false)
                return
            } catch (error: Exception) {
                if (isRecoverableEsp32S3UsbFailure(error)) throw error
                lastError = error
            }
        }
        throw IllegalStateException("Could not synchronize with ESP32-S3 ROM", lastError)
    }

    private fun clearInput(port: UsbSerialPort, purgeOutput: Boolean) {
        if (runCatching { port.purgeHwBuffers(purgeOutput, true) }.isSuccess) return

        val buffer = ByteArray(256)
        var quietDeadline = System.nanoTime() + 100_000_000L
        while (System.nanoTime() < quietDeadline) {
            val count = try {
                port.read(buffer, 20)
            } catch (_: Exception) {
                return
            }
            if (count > 0) quietDeadline = System.nanoTime() + 50_000_000L
        }
    }

    /** Native USB does not feed these ROM watchdogs while a multi-megabyte image is written. */
    private fun disableUsbWatchdogs(port: UsbSerialPort) {
        writeRegister(port, RTC_CNTL_WDTWPROTECT_REG, RTC_CNTL_WDT_WKEY)
        writeRegister(port, RTC_CNTL_WDTCONFIG0_REG, 0)
        writeRegister(port, RTC_CNTL_WDTWPROTECT_REG, 0)

        writeRegister(port, RTC_CNTL_SWD_WPROTECT_REG, RTC_CNTL_SWD_WKEY)
        val swdConfig = readRegister(port, RTC_CNTL_SWD_CONF_REG)
        writeRegister(port, RTC_CNTL_SWD_CONF_REG, swdConfig or RTC_CNTL_SWD_AUTO_FEED_EN)
        writeRegister(port, RTC_CNTL_SWD_WPROTECT_REG, 0)
    }

    private fun readRegister(port: UsbSerialPort, address: Long): Long =
        command(port, READ_REG, le32(address), timeoutMs = 3_000).value

    private fun writeRegister(port: UsbSerialPort, address: Long, value: Long) {
        command(
            port,
            WRITE_REG,
            le32(address) + le32(value) + le32(0xffffffffL) + le32(0),
            timeoutMs = 3_000,
        )
    }

    private fun command(
        port: UsbSerialPort,
        operation: Int,
        data: ByteArray,
        checksum: Long = 0,
        responseBytes: Int = 0,
        timeoutMs: Int,
    ): Reply {
        val request = byteArrayOf(0, operation.toByte()) + le16(data.size) + le32(checksum) + data
        port.write(slip(request), timeoutMs)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        repeat(100) {
            val packet = readSlip(port, deadline)
            if (packet.size < 8 || packet[0].toInt() != 1 || (packet[1].toInt() and 0xff) != operation) {
                return@repeat
            }
            val length = (packet[2].toInt() and 0xff) or ((packet[3].toInt() and 0xff) shl 8)
            require(packet.size >= 8 + length) { "Truncated bootloader response" }
            val replyData = packet.copyOfRange(8, 8 + length)
            require(replyData.size >= responseBytes + 2) { "Bootloader response was incomplete" }
            val status = replyData[responseBytes].toInt() and 0xff
            val reason = replyData[responseBytes + 1].toInt() and 0xff
            require(status == 0) { "Bootloader rejected command 0x${operation.toString(16)} ($reason)" }
            return Reply(packet.uint32(4), replyData)
        }
        error("Bootloader response did not match command 0x${operation.toString(16)}")
    }

    private fun readSlip(port: UsbSerialPort, deadline: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        var inFrame = false
        var escaped = false
        while (System.nanoTime() < deadline) {
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceIn(1, 250).toInt()
            // This driver reports an ordinary read timeout as zero bytes. An IOException means
            // Android's USB endpoint is no longer usable and must reach the reconnect loop now.
            val count = port.read(buffer, remainingMs)
            for (index in 0 until count) {
                val value = buffer[index].toInt() and 0xff
                if (value == 0xc0) {
                    if (inFrame && output.size() > 0) return output.toByteArray()
                    inFrame = true
                    escaped = false
                    output.reset()
                } else if (inFrame && escaped) {
                    output.write(if (value == 0xdc) 0xc0 else if (value == 0xdd) 0xdb else value)
                    escaped = false
                } else if (inFrame && value == 0xdb) escaped = true
                else if (inFrame) output.write(value)
            }
        }
        error("Timed out waiting for the ESP32-S3 bootloader")
    }

    private fun enterBootloader(port: UsbSerialPort) {
        port.setRTS(false)
        port.setDTR(false)
        Thread.sleep(100)
        port.setDTR(true)
        port.setRTS(false)
        Thread.sleep(100)
        port.setRTS(true)
        port.setDTR(false)
        port.setRTS(true)
        Thread.sleep(100)
        port.setDTR(false)
        port.setRTS(false)
        Thread.sleep(150)
    }

    private fun hardReset(port: UsbSerialPort) {
        port.setRTS(true)
        Thread.sleep(200)
        port.setRTS(false)
        Thread.sleep(200)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerPermissionReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(permissionReceiver, filter)
        }
    }

    private fun slip(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size + 2)
        output.write(0xc0)
        data.forEach {
            when (it.toInt() and 0xff) {
                0xc0 -> { output.write(0xdb); output.write(0xdc) }
                0xdb -> { output.write(0xdb); output.write(0xdd) }
                else -> output.write(it.toInt())
            }
        }
        output.write(0xc0)
        return output.toByteArray()
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())
    private fun le32(vararg values: Int) = values.fold(ByteArray(0)) { result, value ->
        result + le32(value.toLong())
    }
    private fun le32(value: Long) = byteArrayOf(
        value.toByte(),
        (value shr 8).toByte(),
        (value shr 16).toByte(),
        (value shr 24).toByte(),
    )
    private fun ByteArray.uint32(offset: Int): Long =
        (this[offset].toLong() and 0xff) or
            ((this[offset + 1].toLong() and 0xff) shl 8) or
            ((this[offset + 2].toLong() and 0xff) shl 16) or
            ((this[offset + 3].toLong() and 0xff) shl 24)

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
