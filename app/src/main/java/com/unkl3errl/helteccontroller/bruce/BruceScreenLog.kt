package com.unkl3errl.helteccontroller.bruce

import java.nio.charset.StandardCharsets

data class BruceDrawCommand(
    val code: Int,
    val values: List<Int> = emptyList(),
    val text: String = "",
)

data class BruceScreenFrame(
    val width: Int,
    val height: Int,
    val commands: List<BruceDrawCommand>,
)

object BruceScreenLog {
    fun parse(data: ByteArray): BruceScreenFrame {
        var offset = 0
        var width = 240
        var height = 135
        val commands = mutableListOf<BruceDrawCommand>()

        while (offset + 3 <= data.size) {
            if ((data[offset].toInt() and 0xFF) != 0xAA) break
            val size = data[offset + 1].toInt() and 0xFF
            val code = data[offset + 2].toInt() and 0xFF
            if (size < 3 || offset + size > data.size) break
            var cursor = offset + 3
            val end = offset + size

            fun readU8(): Int? =
                if (cursor < end) data[cursor++].toInt() and 0xFF else null

            fun readU16(): Int? {
                if (cursor + 1 >= end) return null
                val value = ((data[cursor].toInt() and 0xFF) shl 8) or
                    (data[cursor + 1].toInt() and 0xFF)
                cursor += 2
                return value
            }

            fun readValues(count: Int): List<Int>? {
                val values = ArrayList<Int>(count)
                repeat(count) { values += readU16() ?: return null }
                return values
            }

            val command = when (code) {
                0 -> readValues(1)?.let { BruceDrawCommand(code, it) }
                1, 2 -> readValues(5)?.let { BruceDrawCommand(code, it) }
                3, 4 -> readValues(6)?.let { BruceDrawCommand(code, it) }
                5, 6 -> readValues(4)?.let { BruceDrawCommand(code, it) }
                7, 8 -> readValues(7)?.let { BruceDrawCommand(code, it) }
                9, 10, 11 -> readValues(5)?.let { BruceDrawCommand(code, it) }
                12 -> readValues(8)?.let { BruceDrawCommand(code, it) }
                13 -> readValues(7)?.let { BruceDrawCommand(code, it) }
                14, 15, 16, 17 -> readValues(5)?.let { values ->
                    val text = data.copyOfRange(cursor, end).toString(StandardCharsets.UTF_8)
                    BruceDrawCommand(code, values, text)
                }
                18 -> readValues(4)?.let { values ->
                    val fileSystem = readU8() ?: return@let null
                    val file = data.copyOfRange(cursor, end).toString(StandardCharsets.UTF_8)
                    BruceDrawCommand(code, values + fileSystem, file)
                }
                19 -> readValues(3)?.let { BruceDrawCommand(code, it) }
                20, 21 -> readValues(4)?.let { BruceDrawCommand(code, it) }
                99 -> {
                    val dimensions = readValues(2)
                    val rotation = readU8()
                    if (dimensions != null && rotation != null) {
                        width = dimensions[0].coerceAtLeast(1)
                        height = dimensions[1].coerceAtLeast(1)
                        BruceDrawCommand(code, dimensions + rotation)
                    } else null
                }
                else -> BruceDrawCommand(code)
            }
            if (command != null) commands += command
            offset += size
        }
        return BruceScreenFrame(width, height, commands)
    }
}
