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
    fun stateChangingCommandsPassThroughToFirmware() {
        assertEquals(BruceCommandRisk.SAFE, BruceCommandSafety.classify("wifi off"))
        assertEquals(BruceCommandRisk.SAFE, BruceCommandSafety.classify("rm /config.conf"))
        assertEquals(BruceCommandRisk.SAFE, BruceCommandSafety.classify("js /test.js"))
    }

    @Test
    fun unknownCommandsPassThroughToFirmware() {
        assertEquals(BruceCommandRisk.SAFE, BruceCommandSafety.classify("future_command"))
    }
}
