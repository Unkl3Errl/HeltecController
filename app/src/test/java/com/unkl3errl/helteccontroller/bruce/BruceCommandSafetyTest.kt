package com.unkl3errl.helteccontroller.bruce

import org.junit.Assert.assertEquals
import org.junit.Test

class BruceCommandSafetyTest {
    @Test
    fun readOnlyDiagnosticsAreSafe() {
        assertEquals(BruceCommandRisk.SAFE, BruceCommandSafety.classify("help"))
        assertEquals(BruceCommandRisk.SAFE, BruceCommandSafety.classify("storage read /config.conf"))
        assertEquals(BruceCommandRisk.SAFE, BruceCommandSafety.classify("sd status"))
    }

    @Test
    fun stateChangingCommandsRequireTypedAuthorization() {
        assertEquals(BruceCommandRisk.ACTIVE, BruceCommandSafety.classify("wifi off"))
        assertEquals(BruceCommandRisk.ACTIVE, BruceCommandSafety.classify("rm /config.conf"))
        assertEquals(BruceCommandRisk.ACTIVE, BruceCommandSafety.classify("js /test.js"))
    }

    @Test
    fun unknownCommandsRequireReview() {
        assertEquals(BruceCommandRisk.CONFIRM, BruceCommandSafety.classify("future_command"))
    }
}
