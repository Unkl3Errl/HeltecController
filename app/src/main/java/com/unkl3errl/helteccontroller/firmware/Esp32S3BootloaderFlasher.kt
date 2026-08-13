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
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/** Minimal ESP32-S3 ROM flasher for a checksum-verified, merged offset-0 image. */
class Esp32S3BootloaderFlasher(context: Context) {
    interface Listener {
        fun onFlashProgress(percent: Int, message: String)
        fun onFlashComplete()
        fun onFlashFailed(message: String)
    }

    companion object {
        private const val ACTION_USB_PERMISSION =
            "com.unkl3errl.helteccontroller.FLASH_USB_PERMISSION"
        private const val ESPRESSIF_VID = 0x303A
        private const val ESP32_USB_JTAG_PID = 0x1001
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

    private data class PendingFlash(val image: File, val listener: Listener)
    private data class Reply(val value: Long, val data: ByteArray)

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val request = pending.also { pending = null } ?: return
            val device = intent.usbDevice()
            if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) || device == null) {
                busy.set(false)
                request.listener.onFlashFailed("USB permission was not granted")
                return
            }
            begin(device, request)
        }
    }

    init { registerPermissionReceiver() }

    fun hasNativeTarget(): Boolean = nativeDevice() != null

    fun flash(image: File, listener: Listener) {
        if (!busy.compareAndSet(false, true)) {
            listener.onFlashFailed("Another firmware flash is already running")
            return
        }
        if (!image.isFile || image.length() !in 64 * 1024..MAX_IMAGE_BYTES.toLong()) {
            busy.set(false)
            listener.onFlashFailed("The retained firmware image is invalid")
            return
        }
        val device = nativeDevice()
        if (device == null) {
            busy.set(false)
            listener.onFlashFailed("No native ESP32-S3 USB target is attached")
            return
        }
        val request = PendingFlash(image, listener)
        if (usbManager.hasPermission(device)) begin(device, request)
        else {
            pending = request
            val permissionIntent = PendingIntent.getBroadcast(
                appContext,
                9001,
                Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usbManager.requestPermission(device, permissionIntent)
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
            var port: UsbSerialPort? = null
            try {
                request.listener.onFlashProgress(0, "Entering ESP32-S3 recovery mode…")
                val driver = driverFor(device)
                val connection = usbManager.openDevice(device)
                    ?: error("Android could not open the USB target")
                port = driver.ports.firstOrNull() ?: error("The USB target has no serial port")
                port.open(connection)
                port.setParameters(115_200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                port.purgeHwBuffers(true, true)
                enterBootloader(port)
                sync(port)
                val security = command(
                    port,
                    GET_SECURITY_INFO,
                    byteArrayOf(),
                    responseBytes = 20,
                    timeoutMs = 3_000,
                )
                require(security.data.size >= 24) { "ESP32-S3 security response was incomplete" }
                val flags = security.data.uint32(0)
                val flashCryptCount = security.data[4].toInt() and 0xff
                val chipId = security.data.uint32(12).toInt()
                require(chipId == ESP32_S3_CHIP_ID) { "Attached chip is not an ESP32-S3" }
                require(flags and 0x1L == 0L) { "Secure Boot is enabled; unsigned recovery is blocked" }
                require(flags and 0x4L == 0L) { "Secure Download Mode is enabled; recovery is blocked" }
                require(Integer.bitCount(flashCryptCount) % 2 == 0) {
                    "Flash encryption is enabled; plaintext recovery is blocked"
                }
                request.listener.onFlashProgress(0, "Preparing a stable recovery session…")
                disableUsbWatchdogs(port)
                command(port, SPI_ATTACH, ByteArray(8), timeoutMs = 3_000)
                command(
                    port,
                    SPI_SET_PARAMS,
                    le32(0, FLASH_BYTES, 64 * 1024, 4 * 1024, 256, 0xffff),
                    timeoutMs = 3_000,
                )
                writeImage(port, request.image, request.listener)
                verifyImage(port, request.image)
                request.listener.onFlashProgress(100, "Verified. Restarting the device…")
                hardReset(port)
                request.listener.onFlashComplete()
            } catch (error: Exception) {
                request.listener.onFlashFailed(error.message ?: error.javaClass.simpleName)
            } finally {
                runCatching { port?.close() }
                busy.set(false)
            }
        }
    }

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
                // The ROM sends seven extra SYNC responses. A purge is safe before the next request.
                Thread.sleep(100)
                port.purgeHwBuffers(false, true)
                return
            } catch (error: Exception) {
                lastError = error
                enterBootloader(port)
            }
        }
        throw IllegalStateException("Could not synchronize with ESP32-S3 ROM", lastError)
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
            val count = try {
                port.read(buffer, remainingMs)
            } catch (error: java.io.IOException) {
                if (System.nanoTime() < deadline) continue else throw error
            }
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

    private fun nativeDevice(): UsbDevice? = usbManager.deviceList.values.firstOrNull {
        it.vendorId == ESPRESSIF_VID && it.productId == ESP32_USB_JTAG_PID
    }

    private fun driverFor(device: UsbDevice): UsbSerialDriver =
        UsbSerialProber.getDefaultProber().probeDevice(device) ?: CdcAcmSerialDriver(device)

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
