package com.foxislam.androidagent.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HooksTest {

    private fun rules(text: String) = Hooks.also { it.replace(text) }

    @Test
    fun `first matching rule wins`() {
        rules(
            """
            allow tap in com.android.settings
            deny tap
            """.trimIndent(),
        )
        assertEquals(Hooks.Action.ALLOW, Hooks.decide("tap", "OK", "com.android.settings")?.action)
        assertEquals(Hooks.Action.DENY, Hooks.decide("tap", "OK", "com.whatsapp")?.action)
    }

    @Test
    fun `a star matches any tool and any package fragment`() {
        rules("deny * in com.*bank*")
        assertEquals(Hooks.Action.DENY, Hooks.decide("tap", "Pay", "com.monzobank.app")?.action)
        assertEquals(Hooks.Action.DENY, Hooks.decide("type_text", "1234", "com.mybanking")?.action)
        assertNull(Hooks.decide("tap", "Pay", "com.spotify.music"))
    }

    @Test
    fun `matching applies a regex to the detail`() {
        rules("ask type_text matching (?i)password|otp")
        assertEquals(Hooks.Action.ASK, Hooks.decide("type_text", "my PASSWORD is", null)?.action)
        assertNull(Hooks.decide("type_text", "hello there", null))
    }

    @Test
    fun `a rule needing a package never matches when there is no package`() {
        rules("deny tap in com.x")
        assertNull(Hooks.decide("tap", "", null))
    }

    @Test
    fun `comments and nonsense lines are ignored, not obeyed`() {
        rules(
            """
            # deny everything
            wibble tap
            deny press
            """.trimIndent(),
        )
        assertNull(Hooks.decide("tap", "", null))
        assertEquals(Hooks.Action.DENY, Hooks.decide("press", "back", null)?.action)
    }

    @Test
    fun `always-allow writes a rule scoped to the app it was granted in`() {
        rules("")
        Hooks.allowAlways("tap", "com.spotify.music")
        assertEquals(Hooks.Action.ALLOW, Hooks.decide("tap", "", "com.spotify.music")?.action)
        assertNull(Hooks.decide("tap", "", "com.monzo.app"))

        // Answering "always" twice must not write the same line twice
        Hooks.allowAlways("tap", "com.spotify.music")
        assertEquals(1, Hooks.text.value.lines().count { it.isNotBlank() })
    }
}
