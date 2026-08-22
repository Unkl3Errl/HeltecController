package com.unkl3errl.helteccontroller.connection

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Espressif assigns Wi-Fi, classic Bluetooth, and BLE addresses from one
 * four-address hardware block. Different firmware stacks can therefore expose
 * the same board with the last octet offset by one or two.
 */
internal object Esp32BluetoothIdentity {
    fun hardwareKey(address: String?): String? {
        val bytes = address?.split(':')
        if (bytes?.size != 6) return null
        val parsed = bytes.map { part ->
            if (part.length != 2) return null
            part.toIntOrNull(16) ?: return null
        }.toMutableList()
        parsed[5] = parsed[5] and 0xfc
        return parsed.joinToString(":") { "%02X".format(it) }
    }

    fun sameHardware(first: String?, second: String?): Boolean {
        val firstKey = hardwareKey(first) ?: return false
        return firstKey == hardwareKey(second)
    }
}

internal enum class FirmwareBleProfile(
    val serviceUuid: UUID,
    val advertisedName: String,
    val requestedMtu: Int,
) {
    BRUCE_SERIAL(
        UUID.fromString("4371ec0b-3d43-49f9-b731-7c72a4a7bb91"),
        "Bruce",
        247,
    ),
    GHOST_BRIDGE(
        UUID.fromString("47686f73-7445-5350-4272-696467655376"),
        "GhostESP Bridge",
        128,
    ),
    MARAUDER_UART(
        UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
        "Marauder",
        185,
    ),
    ;

    companion object {
        fun forKind(kind: PersistentUsbKind): FirmwareBleProfile? = when (kind) {
            PersistentUsbKind.BRUCE -> BRUCE_SERIAL
            PersistentUsbKind.GHOSTESP -> GHOST_BRIDGE
            PersistentUsbKind.MARAUDER -> MARAUDER_UART
        }
    }
}

internal object MarauderBleUuids {
    val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
}

internal object GhostBleBridgeUuids {
    val RX: UUID = UUID.fromString("0147686f-7374-4553-5042-726964675852")
    val TX: UUID = UUID.fromString("0247686f-7374-4553-5042-726964675854")
    val CTRL: UUID = UUID.fromString("0347686f-7374-4553-5042-72694c525443")
    val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** The wire format documented and implemented by GhostESP's upstream BLE Bridge. */
internal object GhostBleBridgeProtocol {
    const val HEADER_BYTES = 12
    const val DEFAULT_MTU = 23
    const val MAX_COMMAND_BYTES = 250
    const val TYPE_COMMAND = 1
    const val TYPE_ACK = 2
    const val TYPE_DATA = 3
    const val TYPE_END = 4
    const val TYPE_ERROR = 5
    const val TYPE_HAS_DATA = 7

    private const val FLAG_FIRST = 0x01
    private const val FLAG_MORE = 0x02
    private const val ATT_WRITE_OVERHEAD = 3
    private const val MAX_FRAME_PAYLOAD = 512

    data class Frame(
        val type: Int,
        val status: Int,
        val commandId: Int,
        val payload: ByteArray,
    )

    data class DecodeResult(
        val frames: List<Frame>,
        val unframed: ByteArray,
    )

    class Decoder {
        private val pending = ByteArrayOutputStream()

        @Synchronized
        fun feed(bytes: ByteArray): DecodeResult {
            pending.write(bytes)
            val input = pending.toByteArray()
            val frames = mutableListOf<Frame>()
            val unframed = ByteArrayOutputStream()
            var offset = 0

            while (input.size - offset >= 3) {
                if (
                    input[offset] != 0x47.toByte() ||
                    input[offset + 1] != 0x42.toByte() ||
                    input[offset + 2] != 0x01.toByte()
                ) {
                    unframed.write(input[offset].toInt() and 0xff)
                    offset++
                    continue
                }
                if (input.size - offset < HEADER_BYTES) break

                val payloadLength = (input[offset + 10].toInt() and 0xff) or
                    ((input[offset + 11].toInt() and 0xff) shl 8)
                val type = input[offset + 3].toInt() and 0xff
                if (type !in TYPE_COMMAND..TYPE_HAS_DATA || payloadLength > MAX_FRAME_PAYLOAD) {
                    offset += HEADER_BYTES
                    continue
                }
                val frameLength = HEADER_BYTES + payloadLength
                if (input.size - offset < frameLength) break

                val commandId = (input[offset + 6].toInt() and 0xff) or
                    ((input[offset + 7].toInt() and 0xff) shl 8) or
                    ((input[offset + 8].toInt() and 0xff) shl 16) or
                    ((input[offset + 9].toInt() and 0xff) shl 24)
                frames += Frame(
                    type = type,
                    status = input[offset + 4].toInt() and 0xff,
                    commandId = commandId,
                    payload = input.copyOfRange(offset + HEADER_BYTES, offset + frameLength),
                )
                offset += frameLength
            }

            if (input.size - offset in 1..2) {
                val prefix = when {
                    input.size - offset == 2 &&
                        input[offset] == 0x47.toByte() &&
                        input[offset + 1] == 0x42.toByte() -> 2
                    input.last() == 0x47.toByte() -> 1
                    else -> 0
                }
                val fallbackBytes = input.size - offset - prefix
                if (fallbackBytes > 0) {
                    unframed.write(input, offset, fallbackBytes)
                    offset += fallbackBytes
                }
            }

            pending.reset()
            if (offset < input.size) pending.write(input, offset, input.size - offset)
            return DecodeResult(frames, unframed.toByteArray())
        }

        @Synchronized
        fun reset() = pending.reset()
    }

    fun commandFrames(commandId: Int, command: ByteArray, mtu: Int): List<ByteArray> {
        require(command.isNotEmpty()) { "Command must not be empty" }
        require(command.size <= MAX_COMMAND_BYTES) {
            "Ghost BLE Bridge commands are limited to $MAX_COMMAND_BYTES bytes"
        }
        val payloadCapacity = mtu.coerceAtLeast(DEFAULT_MTU) - ATT_WRITE_OVERHEAD - HEADER_BYTES
        require(payloadCapacity > 0) { "Bluetooth MTU is too small for Ghost framing" }

        if (command.size <= payloadCapacity) return listOf(frame(commandId, command, 0))
        return command.indices.step(payloadCapacity).map { offset ->
            val end = (offset + payloadCapacity).coerceAtMost(command.size)
            val flags = (if (offset == 0) FLAG_FIRST else 0) or
                (if (end < command.size) FLAG_MORE else 0)
            frame(commandId, command.copyOfRange(offset, end), flags)
        }
    }

    private fun frame(commandId: Int, payload: ByteArray, flags: Int): ByteArray =
        ByteArray(HEADER_BYTES + payload.size).also { frame ->
            frame[0] = 0x47
            frame[1] = 0x42
            frame[2] = 0x01
            frame[3] = TYPE_COMMAND.toByte()
            frame[4] = 0
            frame[5] = flags.toByte()
            frame[6] = (commandId and 0xff).toByte()
            frame[7] = ((commandId ushr 8) and 0xff).toByte()
            frame[8] = ((commandId ushr 16) and 0xff).toByte()
            frame[9] = ((commandId ushr 24) and 0xff).toByte()
            frame[10] = (payload.size and 0xff).toByte()
            frame[11] = ((payload.size ushr 8) and 0xff).toByte()
            payload.copyInto(frame, HEADER_BYTES)
        }
}
