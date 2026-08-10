package com.unkl3errl.helteccontroller.marauder

internal data class MarauderSurveyStep(
    val delayAfterPreviousMs: Long,
    val command: String,
    val status: String,
    val buttonLabel: String,
)

internal object MarauderSurveyPlan {
    val startupSteps = listOf(
        MarauderSurveyStep(
            delayAfterPreviousMs = 0L,
            command = "stopscan",
            status = "Stopping any existing OLED or CLI scan…",
            buttonLabel = "RESETTING…",
        ),
        MarauderSurveyStep(
            delayAfterPreviousMs = 750L,
            command = "clearlist -a",
            status = "Clearing the old AP list…",
            buttonLabel = "RESETTING…",
        ),
        MarauderSurveyStep(
            delayAfterPreviousMs = 500L,
            command = "scanall",
            status = "Scanning every 2.4 GHz channel until STOP is pressed…",
            buttonLabel = "SCANNING…",
        ),
    )
}
