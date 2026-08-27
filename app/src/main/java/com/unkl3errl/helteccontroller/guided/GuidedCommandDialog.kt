package com.unkl3errl.helteccontroller.guided

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

object GuidedCommandDialog {
    fun show(
        activity: Activity,
        firmware: GuidedFirmware,
        onRun: (GuidedCommand, String) -> Unit,
    ) {
        val commands = runCatching { GuidedCommandCatalog.load(activity, firmware) }
            .getOrElse {
                Toast.makeText(activity, "Command guide unavailable: ${it.message}", Toast.LENGTH_LONG).show()
                return
            }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(8), activity.dp(20), activity.dp(4))
        }
        content.addView(TextView(activity).apply {
            text = "Choose an action. The app will collect its values and build the firmware command for you."
            setPadding(0, 0, 0, activity.dp(8))
        })
        val search = EditText(activity).apply {
            hint = "Search commands"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        content.addView(search, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val categories = listOf("All categories") + commands.map(GuidedCommand::category).distinct().sorted()
        val category = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, categories)
        }
        content.addView(category, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val commandList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply {
            addView(commandList, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(470)))

        val dialog = AlertDialog.Builder(activity)
            .setTitle("${firmware.displayName()} commands")
            .setView(content)
            .setNegativeButton("Close", null)
            .create()

        fun rebuild() {
            val query = search.text.toString().trim().lowercase()
            val selectedCategory = categories[category.selectedItemPosition]
            val matches = commands.filter { command ->
                (selectedCategory == "All categories" || command.category == selectedCategory) &&
                    (query.isBlank() || listOf(
                        command.title,
                        command.summary,
                        command.template,
                        command.category,
                    ).any { query in it.lowercase() })
            }
            commandList.removeAllViews()
            matches.forEach { command ->
                commandList.addView(Button(activity).apply {
                    isAllCaps = false
                    text = "${command.title}\n${command.summary}"
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    val statusDot = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(
                            when (command.risk) {
                                GuidedCommandRisk.SAFE -> Color.rgb(52, 199, 89)
                                GuidedCommandRisk.REVIEW -> Color.rgb(255, 204, 0)
                                GuidedCommandRisk.ACTIVE -> Color.rgb(255, 149, 0)
                            },
                        )
                        setSize(activity.dp(10), activity.dp(10))
                    }
                    setCompoundDrawables(statusDot, null, null, null)
                    compoundDrawablePadding = activity.dp(12)
                    setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
                    setOnClickListener {
                        showForm(activity, command) { rendered ->
                            dialog.dismiss()
                            onRun(command, rendered)
                        }
                    }
                }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            if (matches.isEmpty()) {
                commandList.addView(TextView(activity).apply {
                    text = "No commands match this search."
                    setPadding(0, activity.dp(16), 0, activity.dp(16))
                })
            }
        }

        search.addTextChangedListener(SimpleTextWatcher(::rebuild))
        category.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = rebuild()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        dialog.setOnShowListener { rebuild() }
        dialog.show()
    }

    private fun showForm(
        activity: Activity,
        command: GuidedCommand,
        onRun: (String) -> Unit,
    ) {
        val values = mutableMapOf<String, String>()
        val editors = mutableMapOf<String, EditText>()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(4), activity.dp(20), activity.dp(4))
        }
        content.addView(TextView(activity).apply {
            text = command.summary
            setPadding(0, 0, 0, activity.dp(8))
        })
        content.addView(TextView(activity).apply {
            text = "Firmware usage\n${command.template}"
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, 0, 0, activity.dp(10))
        })

        var preview: TextView? = null
        fun updatePreview() {
            preview?.text = command.preview(values)
        }

        command.parameters.forEach { parameter ->
            content.addView(TextView(activity).apply {
                text = parameter.label + if (parameter.required) " *" else " (optional)"
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, activity.dp(6), 0, 0)
            })
            if (parameter.choices.isNotEmpty()) {
                val choices = if (parameter.required) parameter.choices else listOf("Not set") + parameter.choices
                content.addView(Spinner(activity).apply {
                    adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, choices)
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            values[parameter.token] = if (!parameter.required && position == 0) "" else choices[position]
                            updatePreview()
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    }
                }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                val editor = EditText(activity).apply {
                    hint = parameter.label
                    isSingleLine = !parameter.multiline
                    minLines = if (parameter.multiline) 2 else 1
                    inputType = when {
                        parameter.secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        parameter.id.numericLike() -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                        parameter.multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        else -> InputType.TYPE_CLASS_TEXT
                    }
                    addTextChangedListener(SimpleTextWatcher {
                        values[parameter.token] = text.toString()
                        updatePreview()
                    })
                }
                editors[parameter.token] = editor
                content.addView(editor, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }

        content.addView(TextView(activity).apply {
            text = when (command.risk) {
                GuidedCommandRisk.SAFE -> "Read-only or passive action"
                GuidedCommandRisk.REVIEW -> "This action changes device, storage, or connection state."
                GuidedCommandRisk.ACTIVE -> "This action can transmit, affect nearby systems, erase data, or restart the device. A separate confirmation follows."
            }
            setPadding(0, activity.dp(12), 0, activity.dp(4))
        })
        val previewView = TextView(activity).apply {
            text = command.preview(values)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(activity.dp(10), activity.dp(8), activity.dp(10), activity.dp(8))
        }
        preview = previewView
        content.addView(previewView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val scroll = ScrollView(activity).apply {
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(command.title)
            .setView(scroll)
            .setNegativeButton("Back", null)
            .setPositiveButton("Run", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val rendered = command.render(values)
                    confirmRun(activity, command, rendered) {
                        dialog.dismiss()
                        onRun(rendered)
                    }
                } catch (error: IllegalArgumentException) {
                    val missing = command.parameters.firstOrNull {
                        it.required && values[it.token].isNullOrBlank()
                    }
                    missing?.let { editors[it.token]?.error = "Required" }
                    Toast.makeText(activity, error.message ?: "Complete the required fields", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun confirmRun(
        activity: Activity,
        command: GuidedCommand,
        rendered: String,
        run: () -> Unit,
    ) {
        when (command.risk) {
            GuidedCommandRisk.SAFE -> run()
            GuidedCommandRisk.REVIEW -> AlertDialog.Builder(activity)
                .setTitle("Review command")
                .setMessage("This action changes device, storage, or connection state:\n\n$rendered")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run") { _, _ -> run() }
                .show()
            GuidedCommandRisk.ACTIVE -> {
                val confirmation = EditText(activity).apply {
                    hint = "RUN"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    isSingleLine = true
                    setPadding(activity.dp(48), activity.dp(12), activity.dp(48), activity.dp(12))
                }
                val dialog = AlertDialog.Builder(activity)
                    .setTitle("Confirm active command")
                    .setMessage(
                        "This action can transmit, affect nearby systems, erase data, or restart the device:\n\n$rendered\n\nUse it only where you have permission. Type RUN to continue.",
                    )
                    .setView(confirmation)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Run", null)
                    .create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (confirmation.text.toString().trim() == "RUN") {
                            dialog.dismiss()
                            run()
                        } else {
                            confirmation.error = "Type RUN exactly"
                        }
                    }
                }
                dialog.show()
            }
        }
    }

    private fun GuidedFirmware.displayName(): String = when (this) {
        GuidedFirmware.BRUCE -> "Bruce"
        GuidedFirmware.GHOSTESP -> "GhostESP"
        GuidedFirmware.MARAUDER -> "Marauder"
    }

    private fun String.numericLike(): Boolean = listOf(
        "count", "duration", "seconds", "timeout", "channel", "index", "offset", "length",
        "size", "frequency", "pin", "depth", "page", "percent", "level", "hops", "baud",
    ).any { contains(it) }

    private fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class SimpleTextWatcher(private val changed: () -> Unit) : TextWatcher {
        override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(value: Editable?) = changed()
    }
}
