package com.unkl3errl.helteccontroller.detection

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
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

class UsbFirmwareDetector(
    private val context: Context,
    private val listener: Listener,
) : SerialInputOutputManager.Listener {
    interface Listener {
        fun onUsbDetectionStatus(message: String)
        fun onUsbFirmwareDetected(detection: FirmwareDetection)
        fun onUsbDetectionUnknown(message: String)
    }

    companion object {
        private const val ACTION_USB_PERMISSION =
            "com.unkl3errl.helteccontroller.DETECT_USB_PERMISSION"
        private const val ESPRESSIF_VID = 0x303A
        private const val ESP32_USB_JTAG_PID = 0x1001
        private const val PROBE_TIMEOUT_MS = 12_000L
        private const val MAX_EVIDENCE_CHARS = 16_384
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private val evidence = StringBuilder()
    private var pendingDriver: UsbSerialDriver? = null
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var probing = false

    private val probeTimeout = Runnable {
        if (!probing) return@Runnable
        val preview = evidence.toString().lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.take(100)
            .orEmpty()
        finishUnknown(
            if (preview.isBlank()) "The board did not answer the read-only identity probe"
            else "USB responded, but its firmware signature was not recognized: $preview",
        )
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val driver = pendingDriver
            pendingDriver = null
            val device = intent.usbDevice()
            if (
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                driver != null && device != null
            ) open(driver)
            else listener.onUsbDetectionUnknown("USB permission was not granted")
        }
    }

    init { registerReceiver() }

    fun hasCandidate(): Boolean = findDriver() != null

    fun detect() {
        if (probing) {
            listener.onUsbDetectionStatus("USB firmware detection is already running…")
            return
        }
        val driver = findDriver()
        if (driver == null) {
            listener.onUsbDetectionUnknown("No compatible USB serial board is attached")
            return
        }
        if (!usbManager.hasPermission(driver.device)) {
            pendingDriver = driver
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usbManager.requestPermission(driver.device, permissionIntent)
            listener.onUsbDetectionStatus("Waiting for USB permission…")
            return
        }
        open(driver)
    }

    fun cancel() {
        probing = false
        mainHandler.removeCallbacksAndMessages(null)
        closePort()
    }

    fun destroy() {
        cancel()
        pendingDriver = null
        runCatching { context.unregisterReceiver(receiver) }
        writer.shutdownNow()
    }

    override fun onNewData(data: ByteArray) {
        if (!probing) return
        synchronized(evidence) {
            evidence.append(data.toString(Charsets.UTF_8).replace("\u0000", ""))
            if (evidence.length > MAX_EVIDENCE_CHARS) {
                evidence.delete(0, evidence.length - MAX_EVIDENCE_CHARS)
            }
            val kind = FirmwareIdentity.classifyUsb(evidence.toString())
            if (kind != FirmwareKind.UNKNOWN) {
                val line = evidence.lineSequence().firstOrNull { candidate ->
                    FirmwareIdentity.classifyUsb(candidate) == kind
                }?.trim()?.take(120) ?: kind.name
                mainHandler.post {
                    if (!probing) return@post
                    probing = false
                    mainHandler.removeCallbacksAndMessages(null)
                    closePort()
                    listener.onUsbFirmwareDetected(
                        FirmwareDetection(kind, DetectionSource.USB, line),
                    )
                }
            }
        }
    }

    override fun onRunError(error: Exception) {
        mainHandler.post {
            if (probing) finishUnknown(
                "USB identity probe stopped: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun open(driver: UsbSerialDriver) {
        try {
            cancel()
            val connection = usbManager.openDevice(driver.device)
                ?: throw IllegalStateException("Android could not open the USB device")
            val selected = driver.ports.firstOrNull()
                ?: throw IllegalStateException("The USB device has no serial port")
            selected.open(connection)
            selected.setParameters(115_200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { selected.setDTR(true) }
            port = selected
            evidence.clear()
            probing = true
            ioManager = SerialInputOutputManager(selected, this).also { it.start() }
            listener.onUsbDetectionStatus("Probing attached firmware with read-only commands…")
            scheduleWrite("info", 500L)
            scheduleWrite("help", 1_500L)
            scheduleWrite("version", 2_500L)
            scheduleWrite("help", 6_000L)
            scheduleWrite("version", 8_500L)
            mainHandler.postDelayed(probeTimeout, PROBE_TIMEOUT_MS)
        } catch (error: Exception) {
            finishUnknown("USB detection failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun scheduleWrite(command: String, delayMs: Long) {
        mainHandler.postDelayed({
            val active = port ?: return@postDelayed
            writer.execute {
                runCatching {
                    active.write((command + "\r\n").toByteArray(Charsets.UTF_8), 2_000)
                }.onFailure { error ->
                    mainHandler.post {
                        if (probing) finishUnknown(
                            "USB probe write failed: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                }
            }
        }, delayMs)
    }

    private fun finishUnknown(message: String) {
        probing = false
        mainHandler.removeCallbacksAndMessages(null)
        closePort()
        listener.onUsbDetectionUnknown(message)
    }

    private fun closePort() {
        ioManager?.stop()
        ioManager = null
        runCatching { port?.close() }
        port = null
    }

    private fun findDriver(): UsbSerialDriver? {
        val detected = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        detected.firstOrNull {
            it.device.vendorId == ESPRESSIF_VID && it.device.productId == ESP32_USB_JTAG_PID
        }?.let { return it }
        detected.firstOrNull()?.let { return it }
        val native = usbManager.deviceList.values.firstOrNull {
            it.vendorId == ESPRESSIF_VID && it.productId == ESP32_USB_JTAG_PID
        }
        return native?.let(::CdcAcmSerialDriver)
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
