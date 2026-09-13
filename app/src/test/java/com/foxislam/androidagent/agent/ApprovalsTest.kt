package com.foxislam.androidagent.agent

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ApprovalsTest {

    @Test
    fun `words that mean something irreversible are risky`() {
        assertNotNull(Approvals.riskOf("tap", "node 4 (\"Send\")", "com.whatsapp"))
        assertNotNull(Approvals.riskOf("tap", "node 9 (\"Confirm payment\")", "com.deliveroo"))
        assertNotNull(Approvals.riskOf("tap", "node 2 (\"Delete\")", "com.google.photos"))
    }

    @Test
    fun `ordinary navigation is not`() {
        assertNull(Approvals.riskOf("tap", "node 4 (\"Settings\")", "com.spotify.music"))
        assertNull(Approvals.riskOf("swipe", "100,900 -> 100,300", "com.spotify.music"))
    }

    @Test
    fun `a money app makes everything risky`() {
        assertNotNull(Approvals.riskOf("tap", "node 1 (\"Home\")", "com.monzo.android"))
        assertNotNull(Approvals.riskOf("tap", "node 1 (\"Home\")", "com.bitwarden.app"))
    }

    @Test
    fun `a remote tool is risky wherever it is called from`() {
        assertNotNull(Approvals.riskOf("mcp__github__create_issue", "title=x", "com.android.settings"))
    }

    @Test
    fun `substrings do not count - "sendai" is not "send"`() {
        assertNull(Approvals.riskOf("tap", "node 4 (\"Sendai\")", "com.google.maps"))
    }
}
