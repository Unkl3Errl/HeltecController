package com.unkl3errl.helteccontroller.guided

import android.content.Context
import java.util.Locale

enum class GuidedFirmware(val catalogName: String) {
    BRUCE("bruce"),
    GHOSTESP("ghostesp"),
    MARAUDER("marauder"),
}

enum class GuidedCommandRisk {
    SAFE,
    REVIEW,
    ACTIVE,
}

data class GuidedCommandParameter(
    val token: String,
    val id: String,
    val label: String,
    val required: Boolean,
    val choices: List<String>,
    val secret: Boolean,
    val multiline: Boolean,
)

data class GuidedCommand(
    val firmware: GuidedFirmware,
    val category: String,
    val id: String,
    val title: String,
    val summary: String,
    val template: String,
    val risk: GuidedCommandRisk,
) {
    val requiresSerialPayload: Boolean
        get() = firmware == GuidedFirmware.BRUCE && id in BRUCE_SERIAL_PAYLOAD_COMMANDS

    val serialPayloadLabel: String
        get() = if (id == "bruce-encrypt") "Plaintext to encrypt" else "File contents"

    val serialPayloadHelp: String
        get() = if (id == "bruce-encrypt") {
            "The app sends this text to Bruce and saves the encrypted result at the output path."
        } else {
            "The app sends this text to Bruce and saves it at the selected virtual-SD path."
        }

    val parameters: List<GuidedCommandParameter> by lazy {
        PLACEHOLDER.findAll(template).map { match ->
            val requiredValue = match.groups[1]?.value
            val optionalValue = match.groups[2]?.value
            val raw = (requiredValue ?: optionalValue).orEmpty()
            val normalized = raw.removeSuffix("...")
            val choices = if (',' in normalized) {
                normalized.split(',').map(String::trim).filter(String::isNotEmpty)
            } else {
                emptyList()
            }
            val name = if (choices.isNotEmpty()) "choice" else normalized
            GuidedCommandParameter(
                token = match.value,
                id = parameterId(name),
                label = parameterLabel(name),
                required = requiredValue != null,
                choices = choices,
                secret = name.lowercase(Locale.US).let {
                    it.contains("password") || it.contains("token") || it.contains("psk")
                },
                multiline = raw.endsWith("...") || name in setOf("text", "command", "arguments", "options"),
            )
        }.distinctBy(GuidedCommandParameter::token).toList()
    }

    fun render(values: Map<String, String>): String {
        var command = template
        parameters.forEach { parameter ->
            val value = values[parameter.token].orEmpty().trim()
            require(!parameter.required || value.isNotEmpty()) {
                "${parameter.label} is required"
            }
            command = command.replace(parameter.token, value)
        }
        return command.trim().replace(WHITESPACE, " ")
    }

    fun preview(values: Map<String, String>): String {
        var command = template
        parameters.forEach { parameter ->
            val value = values[parameter.token].orEmpty().trim()
            command = command.replace(
                parameter.token,
                when {
                    value.isNotEmpty() -> value
                    parameter.required -> "<${parameter.label.lowercase(Locale.US)}>"
                    else -> ""
                },
            )
        }
        return command.trim().replace(WHITESPACE, " ")
    }

    companion object {
        private val BRUCE_SERIAL_PAYLOAD_COMMANDS = setOf(
            "bruce-storage-write",
            "bruce-encrypt",
        )
        private val PLACEHOLDER = Regex("<([^>]+)>|\\[([A-Za-z][A-Za-z0-9_.-]*(?:\\.\\.\\.)?)\\]")
        private val WHITESPACE = Regex("\\s+")

        private fun parameterId(raw: String): String = raw
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')

        private fun parameterLabel(raw: String): String = raw
            .removeSuffix("...")
            .replace('_', ' ')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }
}

object GuidedCommandCatalog {
    fun load(context: Context, firmware: GuidedFirmware): List<GuidedCommand> =
        context.assets.open("firmware_commands.tsv").bufferedReader().use { reader ->
            parse(reader.readText()).filter { it.firmware == firmware }
        }

    fun parse(text: String): List<GuidedCommand> = text.lineSequence()
        .map(String::trimEnd)
        .filter { it.isNotBlank() && !it.startsWith('#') }
        .mapIndexed { index, line ->
            val fields = line.split('\t')
            require(fields.size == 7) { "Invalid command catalog row ${index + 1}" }
            GuidedCommand(
                firmware = GuidedFirmware.entries.firstOrNull { it.catalogName == fields[0] }
                    ?: error("Unknown firmware '${fields[0]}' on row ${index + 1}"),
                category = fields[1],
                id = fields[2],
                title = fields[3],
                summary = fields[4],
                template = fields[5],
                risk = when (fields[6]) {
                    "safe" -> GuidedCommandRisk.SAFE
                    "review" -> GuidedCommandRisk.REVIEW
                    "active" -> GuidedCommandRisk.ACTIVE
                    else -> error("Unknown risk '${fields[6]}' on row ${index + 1}")
                },
            )
        }
        .toList()
        .also { commands ->
            require(commands.isNotEmpty()) { "Command catalog is empty" }
            require(commands.map(GuidedCommand::id).distinct().size == commands.size) {
                "Command catalog IDs must be unique"
            }
        }
}
