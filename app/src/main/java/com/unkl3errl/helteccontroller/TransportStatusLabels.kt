package com.unkl3errl.helteccontroller

internal data class TransportStatusLabels(
    val usb: String,
    val bluetooth: String,
)

/** Builds both transport labels from the selected device's live connection state. */
internal object TransportStatusLabeler {
    fun labels(
        deviceName: String,
        usbConnected: Boolean,
        bluetoothConnected: Boolean,
        latestMessage: String? = null,
    ): TransportStatusLabels {
        val detail = latestMessage?.trim().orEmpty()
        val bluetoothDetail = detail.takeIf { message ->
            message.startsWith("$deviceName Bluetooth", ignoreCase = true) ||
                (message.startsWith("Connecting to ", ignoreCase = true) &&
                    message.contains("Bluetooth", ignoreCase = true)) ||
                message.startsWith("Bluetooth:", ignoreCase = true)
        }
        val usbDetail = detail.takeIf { message ->
            !message.contains("Bluetooth", ignoreCase = true) &&
                (message.contains("USB", ignoreCase = true) ||
                    message.startsWith("Connected:", ignoreCase = true))
        }

        val usb = when {
            usbConnected && bluetoothConnected ->
                "$deviceName USB connected · preferred transport"
            usbConnected ->
                "$deviceName USB connected · active transport"
            usbDetail != null && !bluetoothConnected -> usbDetail
            else -> "$deviceName USB disconnected"
        }
        val bluetooth = when {
            bluetoothConnected && usbConnected ->
                "$deviceName Bluetooth connected · standby fallback available"
            bluetoothConnected ->
                "$deviceName Bluetooth connected · active transport"
            bluetoothDetail != null -> bluetoothDetail
            usbConnected ->
                "$deviceName Bluetooth disconnected · fallback unavailable"
            else -> "$deviceName Bluetooth disconnected"
        }
        return TransportStatusLabels(usb, bluetooth)
    }
}
