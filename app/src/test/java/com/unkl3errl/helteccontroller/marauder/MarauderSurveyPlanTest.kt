package com.unkl3errl.helteccontroller.marauder

import org.junit.Assert.assertEquals
import org.junit.Test

class MarauderSurveyPlanTest {
    @Test
    fun surveyStopsExistingWorkBeforeClearingAndScanning() {
        assertEquals(
            listOf("stopscan", "clearlist -a", "scanall"),
            MarauderSurveyPlan.startupSteps.map(MarauderSurveyStep::command),
        )
        assertEquals(
            listOf(0L, 750L, 500L),
            MarauderSurveyPlan.startupSteps.map(MarauderSurveyStep::delayAfterPreviousMs),
        )
        assertEquals(
            "Scanning every 2.4 GHz channel until STOP is pressed…",
            MarauderSurveyPlan.startupSteps.last().status,
        )
    }
}
