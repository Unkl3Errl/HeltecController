package com.unkl3errl.helteccontroller.bruce

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
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

class BruceUsbSerial(
    private val context: Context,
    private val listener: Listener,
) : SerialInputOutputManager.Listener {
    interface Listener {
        fun onBruceUsbStatus(message: String, connected: Boolean)
        fun onBruceUsbData(data: ByteArray)
        fun onBruceUsbError(message: String)
    }

    companion object {
        private const val ACTION_USB_PERMISSION =
            "com.unkl3errl.helteccontroller.BRUCE_USB_PERMISSION"
        private const val ESPRESSIF_VID = 0x303A
        private const val ESP32_USB_JTAG_PID = 0x1001
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val writer = Executors.newSingleThreadExecutor()
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private var pendingDriver: UsbSerialDriver? = null
    private var currentDeviceId: Int? = null

    val isConnected: Boolean get() = port?.isOpen == true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice()
                    val driver = pendingDriver
                    pendingDriver = null
                    if (
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                        device != null && driver != null
                    ) open(driver)
                    else listener.onBruceUsbStatus("USB permission was not granted", false)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (intent.usbDevice()?.deviceId == currentDeviceId) {
                        close()
                        listener.onBruceUsbStatus("Bruce USB device disconnected", false)
                    }
                }
            }
        }
    }

    init { registerUsbReceiver() }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    fun connect() {
        if (isConnected) {
            listener.onBruceUsbStatus("Bruce USB is already connected", true)
            return
        }
        val driver = findDriver()
        if (driver == null) {
            listener.onBruceUsbStatus(
                "No compatible Bruce USB serial device found. Check the OTG cable.", false,
            )
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
            listener.onBruceUsbStatus("Waiting for USB permission…", false)
            return
        }
        open(driver)
    }

    fun writeCommand(command: String) {
        val active = port
        if (active == null || !active.isOpen) {
            listener.onBruceUsbError("Connect the Bruce USB device first")
            return
        }
        writer.execute {
            try {
                active.write((command.trim() + "\r\n").toByteArray(Charsets.UTF_8), 2_000)
            } catch (error: Exception) {
                listener.onBruceUsbError("USB write failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun close() {
        ioManager?.stop()
        ioManager = null
        runCatching { port?.close() }
        port = null
        currentDeviceId = null
    }

    fun destroy() {
        close()
        runCatching { context.unregisterReceiver(receiver) }
        writer.shutdownNow()
    }

    override fun onNewData(data: ByteArray) = listener.onBruceUsbData(data)

    override fun onRunError(error: Exception) {
        close()
        listener.onBruceUsbError("USB serial stopped: ${error.message ?: error.javaClass.simpleName}")
        listener.onBruceUsbStatus("Bruce USB disconnected", false)
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

    private fun open(driver: UsbSerialDriver) {
        try {
            val connection = usbManager.openDevice(driver.device)
                ?: throw IllegalStateException("Android could not open the USB device")
            val selected = driver.ports.firstOrNull()
                ?: throw IllegalStateException("The USB device has no serial port")
            selected.open(connection)
            selected.setParameters(115_200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = selected
            currentDeviceId = driver.device.deviceId
            ioManager = SerialInputOutputManager(selected, this).also { it.start() }
            listener.onBruceUsbStatus(
                "Connected: ${driver.device.productName ?: "ESP32-S3"} · 115200 baud", true,
            )
        } catch (error: Exception) {
            close()
            listener.onBruceUsbError("USB connection failed: ${error.message ?: error.javaClass.simpleName}")
            listener.onBruceUsbStatus("Bruce USB connection failed", false)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
