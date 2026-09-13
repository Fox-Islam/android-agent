package com.foxislam.androidagent.agent

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedScriptsTest {

    @AfterTest
    fun clear() {
        SavedScripts.all.value.forEach { SavedScripts.delete(it.id) }
    }

    @Test
    fun `a saved script comes back by name`() {
        SavedScripts.save("toggles", "turns switches on or off", "return args.on")
        val found = SavedScripts.byName("toggles")
        assertEquals("return args.on", found?.source)
        assertEquals("turns switches on or off", found?.description)
    }

    @Test
    fun `the name is matched loosely, the way the model will ask for it`() {
        SavedScripts.save("morning routine", "d", "return 1")
        assertEquals("morning routine", SavedScripts.byName("Morning Routine")?.name)
        assertEquals("morning routine", SavedScripts.byName("morning")?.name)
        assertNull(SavedScripts.byName("evening"))
    }

    /** The second attempt at a script is nearly always the first one corrected */
    @Test
    fun `saving the same name again replaces it instead of duplicating`() {
        assertFalse(SavedScripts.save("toggles", "first", "return 1"))
        assertTrue(SavedScripts.save("toggles", "second", "return 2"))
        assertEquals(1, SavedScripts.all.value.size)
        assertEquals("return 2", SavedScripts.byName("toggles")?.source)
    }

    @Test
    fun `the id survives a replacement, so anything holding one still resolves`() {
        SavedScripts.save("toggles", "first", "return 1")
        val id = SavedScripts.byName("toggles")!!.id
        SavedScripts.save("toggles", "second", "return 2")
        assertEquals(id, SavedScripts.byName("toggles")!!.id)
    }

    /**
     * Only names and descriptions - the bodies stay on disk. A catalogue that carried the
     * scripts themselves would put every one of them in the prompt on every turn
     */
    @Test
    fun `the catalogue lists what exists without the source`() {
        SavedScripts.save("toggles", "turns switches on or off", "phone.press('back')")
        val catalogue = SavedScripts.catalogue()
        assertContains(catalogue, "toggles: turns switches on or off")
        assertFalse(catalogue.contains("phone.press"))
    }

    @Test
    fun `nothing saved means nothing in the prompt`() {
        assertEquals("", SavedScripts.catalogue())
        assertEquals("none", SavedScripts.names())
    }

    @Test
    fun `deleting takes it out of both the list and the catalogue`() {
        SavedScripts.save("toggles", "d", "return 1")
        SavedScripts.delete(SavedScripts.byName("toggles")!!.id)
        assertNull(SavedScripts.byName("toggles"))
        assertEquals("", SavedScripts.catalogue())
    }
}

class ScriptParamsTest {

    @Test
    fun `declared params are what the form shows`() {
        val script = SavedScript(
            id = "1", name = "toggle", description = "d",
            source = "phone.ensure(q, args.on)",
            params = listOf(
                ScriptParam("label", ScriptParam.TEXT, "Which row"),
                ScriptParam("on", ScriptParam.BOOLEAN),
            ),
        )
        assertEquals(listOf("label", "on"), script.fields().map { it.name })
        assertEquals("Which row", script.fields()[0].title)
        // No label given, so the name has to do
        assertEquals("on", script.fields()[1].title)
    }

    /**
     * A script saved before params existed, or by a model that skipped them, still has to be
     * runnable by hand - and the source declares what it wants
     */
    @Test
    fun `undeclared params are read out of the source`() {
        val script = SavedScript(
            id = "1", name = "toggle", description = "d",
            source = """
                phone.tap(phone.waitFor({ text = args.label }, 8000))
                phone.ensure(q, args.on)
                phone.log(args["note"])
                phone.log(args.label)
            """.trimIndent(),
        )
        assertEquals(listOf("label", "on", "note"), script.fields().map { it.name })
        assertTrue(script.fields().all { it.type == ScriptParam.TEXT })
    }

    @Test
    fun `a script that reads nothing asks for nothing`() {
        val script = SavedScript("1", "n", "d", "phone.press('back')")
        assertTrue(script.fields().isEmpty())
    }
}

class ArgsTest {

    @Test
    fun `one name and value per line, which is what a phone keyboard can manage`() {
        val args = SavedScripts.parseArgs("label = Adaptive connectivity\non = true")
        assertEquals("Adaptive connectivity", args["label"])
        assertEquals(true, args["on"])
    }

    @Test
    fun `values are read the way they look`() {
        val args = SavedScripts.parseArgs("a=false\nb=12\nc=1.5\nd=Wi-Fi")
        assertEquals(false, args["a"])
        assertEquals(12L, args["b"])
        assertEquals(1.5, args["c"])
        assertEquals("Wi-Fi", args["d"])
    }

    @Test
    fun `a value containing an equals sign keeps it`() {
        assertEquals("a=b", SavedScripts.parseArgs("q = a=b")["q"])
    }

    @Test
    fun `nothing typed means no arguments, not a failure`() {
        assertTrue(SavedScripts.parseArgs("").isEmpty())
        assertTrue(SavedScripts.parseArgs("   \n  ").isEmpty())
    }

    @Test
    fun `lines that are not assignments are skipped instead of guessed at`() {
        val args = SavedScripts.parseArgs("just a note\nlabel = Bluetooth\n= nameless")
        assertEquals(1, args.size)
        assertEquals("Bluetooth", args["label"])
    }
}
