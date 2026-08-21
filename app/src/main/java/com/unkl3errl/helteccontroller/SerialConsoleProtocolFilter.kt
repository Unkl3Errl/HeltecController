package com.unkl3errl.helteccontroller

/** Removes internal device/app bridge protocol lines from a human-facing serial console. */
internal class SerialConsoleProtocolFilter {
    private val undecidedPrefix = StringBuilder()
    private val visibleStorageLine = StringBuilder()
    private var visibleLine = false
    private var suppressLine = false
    private var storageLineVisible = false
    private var showStorageResponseUntilNanos = 0L

    @Synchronized
    fun showNextStorageResponse() {
        showStorageResponseUntilNanos = System.nanoTime() + STORAGE_RESPONSE_TIMEOUT_NANOS
    }

    @Synchronized
    fun filter(chunk: String): String = buildString(chunk.length) {
        chunk.forEach { character ->
            when {
                suppressLine -> {
                    if (character == '\n') resetLine()
                }
                visibleLine -> {
                    if (storageLineVisible) {
                        visibleStorageLine.append(character)
                        if (character == '\n') {
                            if (!isBackgroundStorageLine(visibleStorageLine)) {
                                append(visibleStorageLine)
                                if (isStorageTerminator(visibleStorageLine)) {
                                    showStorageResponseUntilNanos = 0L
                                }
                            }
                            resetLine()
                        }
                    } else {
                        append(character)
                        if (character == '\n') resetLine()
                    }
                }
                else -> {
                    undecidedPrefix.append(character)
                    if (character == '\n') {
                        if (protocolCandidate(undecidedPrefix).isNotBlank()) append(undecidedPrefix)
                        resetLine()
                    } else {
                        val candidate = protocolCandidate(undecidedPrefix)
                        when {
                            isCompleteMarker(candidate, BRIDGE_MARKER) ||
                                isCompleteMarker(candidate, STORAGE_HOST_MARKER) -> {
                                undecidedPrefix.clear()
                                suppressLine = true
                            }
                            isCompleteMarker(candidate, STORAGE_RESPONSE_MARKER) -> {
                                if (storageResponseIsVisible()) {
                                    // A device can emit prompts without a newline before its reply.
                                    // Buffer the complete line so an in-flight background capacity
                                    // acknowledgement cannot consume the requested response window.
                                    visibleStorageLine.append(candidate)
                                    undecidedPrefix.clear()
                                    visibleLine = true
                                    storageLineVisible = true
                                } else {
                                    undecidedPrefix.clear()
                                    suppressLine = true
                                }
                            }
                            candidate.isEmpty() || protocolMarkers.any {
                                it.startsWith(candidate, ignoreCase = true)
                            } -> Unit
                            else -> {
                                append(undecidedPrefix)
                                undecidedPrefix.clear()
                                visibleLine = true
                            }
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun reset() {
        undecidedPrefix.clear()
        showStorageResponseUntilNanos = 0L
        resetLine()
    }

    private fun resetLine() {
        undecidedPrefix.clear()
        visibleStorageLine.clear()
        visibleLine = false
        suppressLine = false
        storageLineVisible = false
    }

    private fun protocolCandidate(value: CharSequence): String {
        var index = 0
        while (index < value.length && value[index].isWhitespace()) index++
        while (index < value.length && value[index] in PROMPT_CHARACTERS) {
            index++
            while (index < value.length && value[index].isWhitespace()) index++
        }
        return value.substring(index)
    }

    private fun storageResponseIsVisible(): Boolean {
        val visible = showStorageResponseUntilNanos > System.nanoTime()
        if (!visible) showStorageResponseUntilNanos = 0L
        return visible
    }

    private fun isStorageTerminator(value: CharSequence): Boolean {
        val candidate = protocolCandidate(value).trim()
        return candidate.startsWith("SD:OK", ignoreCase = true) ||
            candidate.startsWith("SD:ERR", ignoreCase = true)
    }

    private fun isBackgroundStorageLine(value: CharSequence): Boolean {
        val candidate = protocolCandidate(value).trim()
        return candidate.startsWith("SD:HOST:", ignoreCase = true) ||
            candidate.equals("SD:OK:host-capacity", ignoreCase = true)
    }

    private fun isCompleteMarker(candidate: String, marker: String): Boolean =
        candidate.equals(marker, ignoreCase = true)

    private companion object {
        const val BRIDGE_MARKER = "@HELTEC-BRIDGE"
        const val STORAGE_RESPONSE_MARKER = "SD:"
        const val STORAGE_HOST_MARKER = "SD HOST"
        const val STORAGE_RESPONSE_TIMEOUT_NANOS = 5_000_000_000L
        val PROMPT_CHARACTERS = setOf('>', '#', '$')
        val protocolMarkers = listOf(BRIDGE_MARKER, STORAGE_RESPONSE_MARKER, STORAGE_HOST_MARKER)
    }
}
