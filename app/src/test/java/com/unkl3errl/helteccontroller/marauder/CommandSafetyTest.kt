package com.unkl3errl.helteccontroller.marauder

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandSafetyTest {
    @Test fun passiveCommandsDoNotRequireConfirmation() {
        assertEquals(CommandRisk.SAFE, CommandSafety.classify("gps -g fix"))
        assertEquals(CommandRisk.SAFE, CommandSafety.classify("sniffbt"))
        assertEquals(CommandRisk.SAFE, CommandSafety.classify("STOPSCAN"))
    }

    @Test fun transmitAndStateChangingCommandsPassThroughToFirmware() {
        assertEquals(CommandRisk.SAFE, CommandSafety.classify("attack -t deauth"))
        assertEquals(CommandRisk.SAFE, CommandSafety.classify("blespam -t all"))
        assertEquals(CommandRisk.SAFE, CommandSafety.classify("reboot"))
    }

    @Test fun unknownCommandsPassThroughToFirmware() {
        assertEquals(CommandRisk.SAFE, CommandSafety.classify("futurecommand -x"))
        assertEquals(CommandRisk.SAFE, CommandSafety.classify(""))
    }
}
