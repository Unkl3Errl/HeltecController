package com.unkl3errl.helteccontroller

/** Normalizes serial terminal text without joining or duplicating output lines. */
internal class SerialConsoleText {
    private var suppressLeadingLineFeed = false
    private var escapeState = EscapeState.NONE

    private enum class EscapeState { NONE, ESCAPE, CSI }

    fun normalize(chunk: String): String = buildString(chunk.length) {
        chunk.forEach { character ->
            when (escapeState) {
                EscapeState.ESCAPE -> {
                    escapeState = if (character == '[') EscapeState.CSI else EscapeState.NONE
                    return@forEach
                }
                EscapeState.CSI -> {
                    if (character.code in 0x40..0x7e) escapeState = EscapeState.NONE
                    return@forEach
                }
                EscapeState.NONE -> if (character == '\u001b') {
                    escapeState = EscapeState.ESCAPE
                    return@forEach
                }
            }
            when (character) {
                '\u0000' -> Unit
                '\r' -> {
                    append('\n')
                    suppressLeadingLineFeed = true
                }
                '\n' -> {
                    if (!suppressLeadingLineFeed) append('\n')
                    suppressLeadingLineFeed = false
                }
                else -> {
                    suppressLeadingLineFeed = false
                    append(character)
                }
            }
        }
    }

    fun reset() {
        suppressLeadingLineFeed = false
        escapeState = EscapeState.NONE
    }
}
