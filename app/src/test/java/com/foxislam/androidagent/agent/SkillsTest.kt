package com.foxislam.androidagent.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillsTest {

    private fun skill(trigger: String) = Skill("1", "Spotify", trigger, "body")

    @Test
    fun `a dotted or starred trigger matches the foreground package`() {
        assertTrue(skill("com.spotify.*").matches("com.spotify.music", prompt = ""))
        assertTrue(skill("com.spotify.music").matches("com.spotify.music", prompt = ""))
        assertFalse(skill("com.spotify.*").matches("com.apple.music", prompt = ""))
    }

    @Test
    fun `a plain word is matched against the task instead`() {
        assertTrue(skill("groceries").matches(packageName = null, prompt = "order the groceries"))
        assertFalse(skill("groceries").matches(packageName = null, prompt = "text Dan"))
    }

    @Test
    fun `triggers are comma-separated and either kind can fire`() {
        val both = skill("com.spotify.*, playlist")
        assertTrue(both.matches("com.spotify.music", prompt = "anything"))
        assertTrue(both.matches("com.android.settings", prompt = "make me a playlist"))
        assertFalse(both.matches("com.android.settings", prompt = "turn on wifi"))
    }
}
