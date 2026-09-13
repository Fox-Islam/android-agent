package com.foxislam.androidagent.agent

import com.foxislam.androidagent.mcp.McpServers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The merge rules, which are the part that can lose someone's data. The file format itself
 * is exercised on a device, where there is a filesystem and a settings store to read
 */
class BackupMergeTest {

    @AfterTest
    fun clear() {
        SavedScripts.all.value.forEach { SavedScripts.delete(it.id) }
        SavedPrompts.all.value.forEach { SavedPrompts.delete(it.id) }
    }

    @Test
    fun `merging adds what is new`() {
        SavedScripts.save("here", "d", "return 1")
        val incoming = listOf(SavedScript("other-id", "imported", "d", "return 2"))
        SavedScripts.merge(incoming)
        assertEquals(setOf("here", "imported"), SavedScripts.all.value.map { it.name }.toSet())
    }

    /** Import merges so that opening a backup does not delete local work */
    @Test
    fun `merging never drops what was only on this phone`() {
        SavedScripts.save("local-only", "d", "return 1")
        SavedScripts.merge(listOf(SavedScript("x", "from-backup", "d", "return 2")))
        assertTrue(SavedScripts.all.value.any { it.name == "local-only" })
    }

    @Test
    fun `an entry with the same id is updated, not duplicated`() {
        SavedScripts.save("toggles", "first", "return 1")
        val id = SavedScripts.byName("toggles")!!.id
        SavedScripts.merge(listOf(SavedScript(id, "toggles", "second", "return 2")))
        assertEquals(1, SavedScripts.all.value.size)
        assertEquals("second", SavedScripts.byName("toggles")!!.description)
    }

    @Test
    fun `merging nothing changes nothing`() {
        SavedPrompts.add("a task")
        SavedPrompts.merge(emptyList())
        assertEquals(1, SavedPrompts.all.value.size)
    }


    @Test
    fun `memory and rules are joined instead of replaced`() {
        val out = Backup.merged("local note", "backed-up note")
        assertContains(out, "local note")
        assertContains(out, "backed-up note")
    }

    @Test
    fun `a line already present is not added twice`() {
        val out = Backup.merged("same line\nlocal only", "same line")
        assertEquals(1, out.lines().count { it.trim() == "same line" })
        assertContains(out, "local only")
    }

    @Test
    fun `nothing here means take what arrived`() {
        assertEquals("incoming", Backup.merged("", "incoming"))
        assertEquals("existing", Backup.merged("existing", ""))
    }
}

class RestoredSummaryTest {

    @Test
    fun `the summary reads as a sentence`() {
        assertEquals(
            "Restored 2 chats, 1 script and your settings.",
            Restored(chats = 2, scripts = 1, prompts = 0, skills = 0, servers = 0, settings = true).summary,
        )
    }

    @Test
    fun `one of something is singular`() {
        assertEquals(
            "Restored 1 chat.",
            Restored(chats = 1, scripts = 0, prompts = 0, skills = 0, servers = 0, settings = false).summary,
        )
    }

    @Test
    fun `an empty backup is reported instead of counted as a success`() {
        val summary = Restored(0, 0, 0, 0, 0, false).summary
        assertContains(summary, "nothing new")
        assertFalse(summary.startsWith("Restored"))
    }
}
