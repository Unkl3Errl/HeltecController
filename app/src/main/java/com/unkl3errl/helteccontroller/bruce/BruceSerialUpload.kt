package com.unkl3errl.helteccontroller.bruce

data class BruceSerialUpload(
    val command: String,
    val wirePayload: ByteArray,
    val contentBytes: Int,
)

object BruceSerialUploadProtocol {
    const val STORAGE_WRITE_ID = "bruce-storage-write"
    const val ENCRYPT_ID = "bruce-encrypt"

    fun commandIdFor(command: String): String? {
        val normalized = command.trimStart().lowercase()
        return when {
            normalized.startsWith("storage write ") -> STORAGE_WRITE_ID
            normalized.startsWith("encrypt ") -> ENCRYPT_ID
            else -> null
        }
    }

    fun prepare(commandId: String, renderedCommand: String, payload: String): BruceSerialUpload {
        require(commandId == STORAGE_WRITE_ID || commandId == ENCRYPT_ID) {
            "This Bruce command does not accept a serial file payload"
        }
        val normalized = payload.replace("\r\n", "\n").replace('\r', '\n')
        require(normalized.isNotBlank()) { "File contents are required" }
        require(normalized.lineSequence().none { it == "EOF" }) {
            "A line containing only EOF is reserved by the Bruce transfer protocol"
        }

        val content = if (normalized.endsWith('\n')) normalized else "$normalized\n"
        val contentBytes = content.toByteArray(Charsets.UTF_8).size
        val command = if (commandId == STORAGE_WRITE_ID) {
            storageWriteCommand(renderedCommand, contentBytes)
        } else {
            renderedCommand.trim()
        }
        val wirePayload = (content + "EOF\n").toByteArray(Charsets.UTF_8)
        return BruceSerialUpload(command, wirePayload, contentBytes)
    }

    private fun storageWriteCommand(renderedCommand: String, contentBytes: Int): String {
        val parts = renderedCommand.trim().split(Regex("\\s+"))
        require(parts.size in 3..4 && parts[0].equals("storage", true) && parts[1].equals("write", true)) {
            "Expected storage write <file_path> [size_bytes]"
        }
        val requestedSize = parts.getOrNull(3)?.toIntOrNull() ?: 0
        return "storage write ${parts[2]} ${maxOf(requestedSize, contentBytes)}"
    }
}
