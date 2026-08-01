package com.unkl3errl.helteccontroller

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.unkl3errl.helteccontroller.bruce.BruceCommandRisk
import com.unkl3errl.helteccontroller.bruce.BruceCommandSafety
import com.unkl3errl.helteccontroller.bruce.BruceUsbSerial

class BruceUsbConsoleController(
    private val activity: Activity,
    root: View,
    private val setGlobalStatus: (String) -> Unit,
) : BruceUsbSerial.Listener {
    private val status: TextView = root.findViewById(R.id.bruceUsbStatus)
    private val console: TextView = root.findViewById(R.id.bruceUsbConsole)
    private val input: EditText = root.findViewById(R.id.bruceUsbCommand)
    private val serial = BruceUsbSerial(activity, this)
    private val buffer = StringBuilder("Connect the Bruce device to begin.\n")

    init {
        console.movementMethod = ScrollingMovementMethod()
        root.findViewById<Button>(R.id.bruceUsbConnect).setOnClickListener {
            setGlobalStatus("USB CONNECTING…")
            serial.connect()
        }
        root.findViewById<Button>(R.id.bruceUsbDisconnect).setOnClickListener {
            serial.close()
            onBruceUsbStatus("Bruce USB disconnected", false)
        }
        root.findViewById<Button>(R.id.bruceUsbClear).setOnClickListener {
            buffer.clear()
            console.text = ""
            console.scrollTo(0, 0)
        }
        root.findViewById<Button>(R.id.bruceUsbSend).setOnClickListener { sendInput() }
        bind(root, R.id.bruceUsbHelp, "help")
        bind(root, R.id.bruceUsbInfo, "info")
        bind(root, R.id.bruceUsbUptime, "uptime")
        bind(root, R.id.bruceUsbFree, "free")
        bind(root, R.id.bruceUsbOptions, "optionsJSON")
    }

    fun destroy() = serial.destroy()

    private fun bind(root: View, id: Int, command: String) {
        root.findViewById<Button>(id).setOnClickListener { sendGuarded(command) }
    }

    private fun sendInput() {
        val command = input.text.toString().trim()
        if (command.isBlank()) return
        sendGuarded(command) { input.text.clear() }
    }

    private fun sendGuarded(command: String, sent: () -> Unit = {}) {
        if (!serial.isConnected) {
            toast("Connect the Bruce USB device first")
            return
        }
        when (BruceCommandSafety.classify(command)) {
            BruceCommandRisk.SAFE -> send(command, sent)
            BruceCommandRisk.CONFIRM -> AlertDialog.Builder(activity)
                .setTitle("Send unclassified Bruce command?")
                .setMessage("Review this command before sending it over USB:\n\n$command")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send") { _, _ -> send(command, sent) }
                .show()
            BruceCommandRisk.ACTIVE -> typedConfirmation(command, sent)
        }
    }

    private fun typedConfirmation(command: String, sent: () -> Unit) {
        val confirmation = EditText(activity).apply {
            hint = "AUTHORIZE"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setPadding(48, 16, 48, 16)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Authorize state-changing command")
            .setMessage(
                "This command may transmit, change device/network state, modify storage, run a script, or restart the device:\n\n$command\n\nType AUTHORIZE to send it.",
            )
            .setView(confirmation)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (confirmation.text.toString().trim() == "AUTHORIZE") {
                    dialog.dismiss()
                    send(command, sent)
                } else confirmation.error = "Type AUTHORIZE exactly"
            }
        }
        dialog.show()
    }

    private fun send(command: String, sent: () -> Unit) {
        append("\n> $command\n")
        serial.writeCommand(command)
        sent()
    }

    override fun onBruceUsbStatus(message: String, connected: Boolean) = activity.runOnUiThread {
        status.text = message
        setGlobalStatus(if (connected) "BRUCE USB" else "IDLE")
        append("\n[link] $message\n")
    }

    override fun onBruceUsbData(data: ByteArray) = activity.runOnUiThread {
        append(data.toString(Charsets.UTF_8))
    }

    override fun onBruceUsbError(message: String) = activity.runOnUiThread {
        append("\n[error] $message\n")
        setGlobalStatus("USB ERROR")
        toast(message)
    }

    private fun append(text: String) {
        buffer.append(text.replace("\u0000", ""))
        if (buffer.length > 40_000) buffer.delete(0, buffer.length - 32_000)
        console.text = buffer.toString()
        console.post {
            val bottom = (console.layout?.getLineTop(console.lineCount) ?: 0) - console.height
            console.scrollTo(0, bottom.coerceAtLeast(0))
        }
    }

    private fun toast(message: String) =
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
}
