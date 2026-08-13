package com.unkl3errl.helteccontroller.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

data class UsbDeviceTarget(
    val deviceId: Int,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturerName: String?,
    val productName: String?,
    val serialNumber: String?,
) {
    val stableKey: String = serialNumber?.takeIf(String::isNotBlank)?.let {
        "%04x:%04x:serial:%s".format(vendorId, productId, it.lowercase())
    } ?: "%04x:%04x:path:%s".format(vendorId, productId, deviceName)

    val isNativeEsp32S3: Boolean
        get() = vendorId == ESPRESSIF_VID && productId == ESP32_USB_JTAG_PID

    fun samePhysicalDevice(other: UsbDeviceTarget): Boolean =
        vendorId == other.vendorId && productId == other.productId &&
            (
                deviceName == other.deviceName ||
                    deviceId == other.deviceId ||
                    (
                        !serialNumber.isNullOrBlank() &&
                            serialNumber.equals(other.serialNumber, ignoreCase = true)
                    )
            )

    fun displayLabel(): String {
        val name = productName?.takeIf(String::isNotBlank)
            ?: manufacturerName?.takeIf(String::isNotBlank)
            ?: if (isNativeEsp32S3) "ESP32-S3 USB serial" else "USB serial device"
        val identity = serialNumber?.takeIf(String::isNotBlank)?.let { "S/N $it" }
            ?: "Android port $deviceId"
        return "$name · $identity · %04X:%04X".format(vendorId, productId)
    }

    internal fun matches(device: UsbDevice): Boolean =
        device.vendorId == vendorId && device.productId == productId &&
            (device.deviceName == deviceName || device.deviceId == deviceId)

    companion object {
        const val ESPRESSIF_VID = 0x303A
        const val ESP32_USB_JTAG_PID = 0x1001

        internal fun from(manager: UsbManager, device: UsbDevice): UsbDeviceTarget =
            UsbDeviceTarget(
                deviceId = device.deviceId,
                deviceName = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                manufacturerName = runCatching { device.manufacturerName }.getOrNull(),
                productName = runCatching { device.productName }.getOrNull(),
                serialNumber = if (manager.hasPermission(device)) {
                    runCatching { device.serialNumber }.getOrNull()
                } else {
                    null
                },
            )
    }
}

object UsbDeviceRegistry {
    fun serialTargets(manager: UsbManager): List<UsbDeviceTarget> =
        serialDrivers(manager).map { UsbDeviceTarget.from(manager, it.device) }

    fun nativeEsp32S3Targets(manager: UsbManager): List<UsbDeviceTarget> =
        manager.deviceList.values
            .filter(::isNativeEsp32S3)
            .sortedBy(UsbDevice::getDeviceName)
            .map { UsbDeviceTarget.from(manager, it) }

    fun target(manager: UsbManager, device: UsbDevice): UsbDeviceTarget =
        UsbDeviceTarget.from(manager, device)

    fun driverFor(manager: UsbManager, target: UsbDeviceTarget): UsbSerialDriver? =
        serialDrivers(manager).firstOrNull {
            target.samePhysicalDevice(UsbDeviceTarget.from(manager, it.device))
        }

    fun nativeDeviceFor(manager: UsbManager, target: UsbDeviceTarget): UsbDevice? =
        manager.deviceList.values.firstOrNull {
            isNativeEsp32S3(it) &&
                target.samePhysicalDevice(UsbDeviceTarget.from(manager, it))
        }

    fun driverForNative(device: UsbDevice): UsbSerialDriver =
        UsbSerialProber.getDefaultProber().probeDevice(device) ?: CdcAcmSerialDriver(device)

    private fun serialDrivers(manager: UsbManager): List<UsbSerialDriver> {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager).toMutableList()
        manager.deviceList.values
            .filter(::isNativeEsp32S3)
            .filter { native -> drivers.none { it.device.deviceName == native.deviceName } }
            .forEach { drivers += CdcAcmSerialDriver(it) }
        return drivers.distinctBy { it.device.deviceName }.sortedBy { it.device.deviceName }
    }

    private fun isNativeEsp32S3(device: UsbDevice): Boolean =
        device.vendorId == UsbDeviceTarget.ESPRESSIF_VID &&
            device.productId == UsbDeviceTarget.ESP32_USB_JTAG_PID
}
