package com.foxislam.androidagent.agent

import android.graphics.Rect
import com.foxislam.androidagent.control.UiNode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeHost(private val screen: List<UiNode> = emptyList()) : ScriptHost {
    val calls = mutableListOf<Pair<String, Map<String, Any?>>>()

    override suspend fun tool(name: String, args: Map<String, Any?>): String {
        calls += name to args
        return "ok"
    }

    override fun nodes(): List<UiNode> = screen
}

private fun node(
    index: Int,
    text: String? = null,
    id: String? = null,
    checkable: Boolean = false,
    checked: Boolean = false,
) = UiNode(
    index = index,
    className = "Switch",
    text = text,
    contentDescription = null,
    viewId = id,
    bounds = Rect(),
    clickable = true,
    editable = false,
    scrollable = false,
    checkable = checkable,
    checked = checked,
    focused = false,
    node = null,
)

private fun run(source: String, host: ScriptHost = FakeHost()): String =
    runBlocking { Scripts.run(source, host) }

private fun runWith(source: String, args: Map<String, Any?>, host: ScriptHost = FakeHost()): String =
    runBlocking { Scripts.run(source, host, args) }

class ScriptsTest {

    @Test
    fun `a script runs and reports what it returned`() {
        val out = run("return 2 + 3")
        assertContains(out, "Finished.")
        assertContains(out, "Returned: 5")
    }

    @Test
    fun `logging is what the model reads back`() {
        val out = run("phone.log('found three') print('and this')")
        assertContains(out, "found three")
        assertContains(out, "and this")
    }


    @Test
    fun `nothing that reaches the machine is in scope`() {
        val reachable = listOf(
            "os", "io", "package", "require", "module", "luajava", "coroutine",
            "load", "loadfile", "dofile", "loadstring", "debug", "collectgarbage",
        )
        for (name in reachable) {
            val out = run("if $name == nil then return 'gone' else return 'PRESENT' end")
            assertContains(out, "Returned: gone", message = "$name is reachable from a script")
        }
    }

    @Test
    fun `a runaway loop is stopped`() {
        val out = run("while true do end")
        assertContains(out, "looping")
    }

    /**
     * pcall catches LuaError and Exception, so a budget that raised either could be
     * swallowed by a script that wrapped its own infinite loop
     */
    @Test
    fun `a script cannot catch its own budget running out`() {
        val out = run("pcall(function() while true do end end) return 'ESCAPED'")
        assertFalse(out.contains("ESCAPED"), "pcall swallowed the instruction budget")
        assertContains(out, "looping")
    }

    @Test
    fun `nor by catching it in a loop of its own`() {
        val out = run(
            """
            while true do
              pcall(function() while true do end end)
            end
            return 'ESCAPED'
            """.trimIndent(),
        )
        assertFalse(out.contains("ESCAPED"))
    }

    @Test
    fun `acting forever is stopped even when the script is small`() {
        val host = FakeHost()
        val out = run("while true do phone.press('back') end", host)
        assertContains(out, "act more than")
        assertEquals(Scripts.MAX_ACTIONS, host.calls.size)
    }

    @Test
    fun `looking forever is stopped too`() {
        val host = FakeHost()
        val out = run("while true do phone.look() end", host)
        assertContains(out, "calls and was stopped")
        assertTrue(host.calls.size <= Scripts.MAX_CALLS)
    }

    @Test
    fun `a script that will not compile is rejected instead of run`() {
        val out = run("this is not lua")
        assertContains(out, "would not compile")
    }

    @Test
    fun `a script longer than the limit is refused`() {
        val out = run("-- " + "x".repeat(Scripts.MAX_SOURCE_CHARS))
        assertContains(out, "limit is")
    }


    @Test
    fun `every action goes out through the host`() {
        val host = FakeHost()
        run("phone.open('Settings') phone.tap(4) phone.type('hello') phone.press('back')", host)
        assertEquals(
            listOf("open_app", "tap", "type_text", "press"),
            host.calls.map { it.first },
        )
        assertEquals(mapOf("node" to 4), host.calls[1].second)
        assertEquals("hello", host.calls[2].second["text"])
    }

    @Test
    fun `tap takes a node from find, a bare index, or a point`() {
        val host = FakeHost(listOf(node(0, text = "Wi-Fi")))
        run("phone.tap(phone.find { text = 'wi-fi' }) phone.tap(2) phone.tap(30, 40)", host)
        val taps = host.calls.filter { it.first == "tap" }.map { it.second }
        assertEquals(mapOf("node" to 0), taps[0])
        assertEquals(mapOf("node" to 2), taps[1])
        assertEquals(mapOf("x" to 30, "y" to 40), taps[2])
    }

    @Test
    fun `find matches on a case-insensitive substring and on id`() {
        val host = FakeHost(listOf(node(0, text = "Bluetooth", id = "bt_switch")))
        assertContains(run("return phone.find { text = 'BLUE' }.index", host), "Returned: 0")
        assertContains(run("return phone.find { id = 'bt_switch' }.index", host), "Returned: 0")
        assertContains(run("if phone.find { text = 'Wi-Fi' } then return 'y' else return 'n' end", host), "Returned: n")
        assertContains(run("if phone.find { text = 'Blue', exact = true } then return 'y' else return 'n' end", host), "Returned: n")
    }

    /**
     * The reason a recorded list of taps is not good enough: tapping a toggle that is
     * already on turns it off
     */
    @Test
    fun `ensure taps a toggle that is off and leaves one that is on alone`() {
        val off = FakeHost(listOf(node(0, text = "Bluetooth", checkable = true, checked = false)))
        run("phone.ensure({ text = 'bluetooth' }, true)", off)
        assertEquals(listOf("ui_tree", "tap"), off.calls.map { it.first })

        val on = FakeHost(listOf(node(0, text = "Bluetooth", checkable = true, checked = true)))
        run("phone.ensure({ text = 'bluetooth' }, true)", on)
        assertEquals(listOf("ui_tree"), on.calls.map { it.first })
    }

    @Test
    fun `a missing toggle is reported instead of tapped blindly`() {
        val host = FakeHost()
        val out = run("local ok, why = phone.ensure({ text = 'nfc' }, true) return why", host)
        assertContains(out, "not on screen")
        assertFalse(host.calls.any { it.first == "tap" })
    }


    @Test
    fun `a script reads the values it was called with`() {
        val out = runWith("return args.name .. '/' .. tostring(args.on)", mapOf("name" to "Wi-Fi", "on" to true))
        assertContains(out, "Returned: Wi-Fi/true")
    }

    @Test
    fun `a list argument arrives as something ipairs can walk`() {
        val out = runWith(
            "local out = '' for _, n in ipairs(args.names) do out = out .. n .. ',' end return out",
            mapOf("names" to listOf("Wi-Fi", "NFC")),
        )
        assertContains(out, "Returned: Wi-Fi,NFC,")
    }

    @Test
    fun `a nested argument keeps its shape`() {
        val out = runWith("return args.where.app", mapOf("where" to mapOf("app" to "Settings")))
        assertContains(out, "Returned: Settings")
    }

    @Test
    fun `args is a table even when nothing was passed`() {
        val out = run("if args.missing == nil then return 'nil-ok' end return 'wrong'")
        assertContains(out, "Returned: nil-ok")
    }


    @Test
    fun `check passes a script that compiles and names the fault in one that does not`() {
        assertNull(Scripts.check("return 1 + 1"))
        assertNotNull(Scripts.check("this is not lua"))
        assertNotNull(Scripts.check(""))
    }

    /** Compiling must not run it: a check that acted would change the phone */
    @Test
    fun `check does not execute the script`() {
        val host = FakeHost()
        Scripts.check("phone.press('back')")
        assertTrue(host.calls.isEmpty())
    }

    /** The commonest way a script goes wrong, so it has to report what happened */
    @Test
    fun `tapping the result of a find that matched nothing is reported`() {
        val out = run("phone.tap(phone.find { text = 'nowhere' })")
        assertContains(out, "matched no node")
    }

    @Test
    fun `an error names the line it happened on`() {
        val out = run("local t = nil\nreturn t.x")
        assertContains(out, "Failed:")
        assertContains(out, "2:")
    }
}
