package com.unkl3errl.helteccontroller.marauder

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MarauderCommandGuidanceTest {
    @Test
    fun incompletePortScansExplainTheRequiredTarget() {
        assertNotNull(MarauderCommandGuidance.validationError("portscan"))
        assertNotNull(MarauderCommandGuidance.validationError("portscan -a"))
        assertNotNull(MarauderCommandGuidance.validationError("portscan -a -t nope"))
        assertNotNull(MarauderCommandGuidance.validationError("portscan -s"))
        assertNotNull(MarauderCommandGuidance.validationError("portscan -s unknown"))
    }

    @Test
    fun completePortScansAndOtherCommandsPassThrough() {
        assertNull(MarauderCommandGuidance.validationError("portscan -a -t 3"))
        assertNull(MarauderCommandGuidance.validationError("portscan -s https"))
        assertNull(MarauderCommandGuidance.validationError("help"))
        assertNull(MarauderCommandGuidance.validationError("futurecommand -x"))
    }
}
