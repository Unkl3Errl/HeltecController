package com.unkl3errl.helteccontroller.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeltecBridgeResponseCollectorTest {
    @Test
    fun ignoresEchoedFormCommandBeforeJsonResponse() {
        val id = 1_000_000_001L

        assertFalse(
            HeltecBridgeWireProtocol.isResponseFor(
                "@HELTEC-BRIDGE $id logger-read name=session-000001-000.ndjson&offset=0&length=384",
                id,
            ),
        )
        assertTrue(
            HeltecBridgeWireProtocol.isResponseFor(
                "@HELTEC-BRIDGE $id OK {\"name\":\"session-000001-000.ndjson\"}",
                id,
            ),
        )
        assertTrue(
            HeltecBridgeWireProtocol.isResponseFor(
                "@HELTEC-BRIDGE $id ERROR {\"error\":\"not found\"}",
                id,
            ),
        )
    }
}
