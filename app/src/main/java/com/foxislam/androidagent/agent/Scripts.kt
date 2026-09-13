package com.foxislam.androidagent.agent

import com.foxislam.androidagent.control.UiNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.json.JSONArray
import org.json.JSONObject
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.MathLib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import java.io.StringReader

/**
 * What a script is allowed to reach.
 *
 * [tool] is the ordinary tool path - gated by the rules and the approval mode, recorded in
 * the chat - so a script decides which actions are attempted, and cannot take one the rules
 * would refuse. [nodes] is what the last look found, because a script wants to test
 * `checked` and read `text`, and the rendered lines the model reads are the wrong shape
 * for that
 */
interface ScriptHost {
    suspend fun tool(name: String, args: Map<String, Any?>): String
    fun nodes(): List<UiNode>
}

/**
 * Lua, sandboxed, with the device tools bound into it.
 *
 * A flat list of steps cannot express "tap it only if it is off", which is most of what a real
 * task needs - so the alternative to this is a round trip to the model between every pair of
 * taps. A script here cannot reach the filesystem, the classloader, the network or the VM's
 * own internals, cannot run longer than [MAX_MILLIS], and cannot act more times than
 * [MAX_ACTIONS] however long it loops
 */
object Scripts {

    /** Enough for real work, far short of a runaway loop. Roughly a second of interpreting */
    const val MAX_INSTRUCTIONS = 4_000_000L

    /** Things that change the device. A loop that taps forever stops here */
    const val MAX_ACTIONS = 120

    /** Everything, looks included, so polling cannot spin without bound either */
    const val MAX_CALLS = 500

    const val MAX_MILLIS = 90_000L
    const val MAX_SOURCE_CHARS = 20_000
    private const val MAX_LOG_LINES = 120
    private const val MAX_LINE_CHARS = 300

    /** Tools a script may call. Anything needing judgement is not here */
    private val MUTATING = setOf("tap", "swipe", "type_text", "press", "open_app")

    /**
     * Stops a script dead. An [Error], not a [LuaError]: `pcall` catches LuaError and
     * Exception, so anything less would let a script swallow its own budget
     */
    private class Halted(val why: String) : Error(why)

    suspend fun run(
        source: String,
        host: ScriptHost,
        args: Map<String, Any?> = emptyMap(),
    ): String {
        if (source.isBlank()) return "run_script needs a script."
        if (source.length > MAX_SOURCE_CHARS) {
            return "Script is ${source.length} characters; the limit is $MAX_SOURCE_CHARS. " +
                "Do it in stages instead."
        }
        return withContext(Dispatchers.IO) {
            val run = Run(host, currentCoroutineContext()[Job])
            execute(source, run, args)
        }
    }

    /**
     * Whether it would compile, without running a line of it. Saving a script that cannot
     * parse only produces a confusing failure later, at the point where someone is relying
     * on it. Compilation does not resolve globals, so this needs no sandbox and no device
     */
    fun check(source: String): String? {
        if (source.isBlank()) return "the script is empty"
        val globals = Globals()
        LuaC.install(globals)
        return try {
            globals.load(StringReader(source), "script")
            null
        } catch (e: LuaError) {
            e.message?.clean() ?: "syntax error"
        }
    }

    private fun execute(source: String, run: Run, args: Map<String, Any?>): String {
        val globals = sandbox(run, args)

        val chunk = try {
            globals.load(StringReader(source), "script")
        } catch (e: LuaError) {
            return "Script would not compile: ${e.message?.clean() ?: "syntax error"}"
        }

        return try {
            val result = chunk.call()
            run.report(ending = "Finished.", returned = result)
        } catch (e: Halted) {
            run.report(ending = e.why, returned = null)
        } catch (e: LuaError) {
            run.report(ending = "Failed: ${e.message?.clean() ?: "error"}", returned = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            run.report(ending = "Failed: ${e.message ?: e.javaClass.simpleName}", returned = null)
        }
    }

    /**
     * Only the four libraries a task needs. `os`, `io`, `package`, `coroutine` and `luajava`
     * are never loaded - luajava in particular is reflection over the whole runtime, which in
     * this process means the accessibility service. The handful that arrive with base and
     * debug have to be taken back out by hand
     */
    private fun sandbox(run: Run, args: Map<String, Any?>): Globals {
        val globals = Globals()
        globals.load(BaseLib())
        // Every other library registers itself in package.loaded as it loads, so this has
        // to be here even though nothing is allowed to use it. `package` and `require` are
        // taken back out below, once the libraries that need them are in
        globals.load(PackageLib())
        globals.load(StringLib())
        globals.load(TableLib())
        globals.load(MathLib())
        // Installs itself as globals.debuglib, which is what the interpreter calls per
        // instruction. The `debug` table it also defines is removed below; the hook stays
        globals.load(Budget(run))
        LuaC.install(globals)

        // No LoadState: without an undumper a precompiled binary chunk cannot be loaded at
        // all, which removes a whole class of malformed-bytecode problem
        for (name in FORBIDDEN) globals.set(name, LuaValue.NIL)

        globals.set("phone", device(run))
        // Always a table, never nil: a saved script reading args.name should get nil for a
        // missing value, not fail on indexing nil
        globals.set("args", args.toLua())
        globals.set("print", run.logFunction())
        globals.load(StringReader(PRELUDE), "prelude").call()
        return globals
    }

    private val FORBIDDEN = listOf(
        "dofile", "loadfile", "load", "loadstring", "collectgarbage", "debug", "require",
        "package", "module",
    )

    /** The device surface. Everything here goes out through [ScriptHost.tool] */
    private fun device(run: Run): LuaTable = LuaTable().apply {
        set("look", fn { _ -> run.look() })
        set("tap", fn { args -> run.tap(args) })
        set("type", fn { args -> run.call("type_text", mapOf("text" to args.arg(1).checkjstring(), "node" to args.nodeArg(2))) })
        set("swipe", fn { args ->
            run.call(
                "swipe",
                mapOf(
                    "x1" to args.arg(1).checkint(), "y1" to args.arg(2).checkint(),
                    "x2" to args.arg(3).checkint(), "y2" to args.arg(4).checkint(),
                    "duration_ms" to args.arg(5).optint(300),
                ),
            )
        })
        set("press", fn { args -> run.call("press", mapOf("key" to args.arg(1).checkjstring())) })
        set("open", fn { args -> run.call("open_app", mapOf("app" to args.arg(1).checkjstring())) })
        set("apps", fn { _ -> run.call("list_apps", emptyMap()) })
        set("wait", fn { args -> run.call("wait", mapOf("ms" to args.arg(1).optint(500))) })
        set("log", fn { args -> run.log(args.arg(1).tojstring()); LuaValue.NIL })
    }

    /**
     * The convenience layer, written in Lua because it is easier to read and change there.
     * `find` and `waitFor` are what a task reaches for - an accessibility tree is never in
     * the same order twice, so matching by index is the one thing a script must not do
     */
    private val PRELUDE = """
        local function holds(node, q)
          if q.id and node.id ~= q.id then return false end
          if q.class and node.class ~= q.class then return false end
          if q.checked ~= nil and node.checked ~= q.checked then return false end
          if q.checkable and not node.checkable then return false end
          if q.clickable and not node.clickable then return false end
          if q.editable and not node.editable then return false end
          if q.text then
            if q.exact then if node.text ~= q.text then return false end
            else if not string.find(node.text:lower(), q.text:lower(), 1, true) then return false end end
          end
          if q.desc then
            if not string.find(node.desc:lower(), q.desc:lower(), 1, true) then return false end
          end
          return true
        end

        -- Every node matching the query, in tree order.
        function phone.all(q)
          local hits = {}
          for _, node in ipairs(phone.look()) do
            if holds(node, q) then hits[#hits + 1] = node end
          end
          return hits
        end

        -- The first match, or nil. Looks once.
        function phone.find(q)
          return phone.all(q)[1]
        end

        -- Waits for something to appear, polling. Returns the node, or nil on timeout.
        function phone.waitFor(q, timeout)
          local deadline = (timeout or 5000)
          repeat
            local node = phone.find(q)
            if node then return node end
            phone.wait(400)
            deadline = deadline - 400
          until deadline <= 0
          return nil
        end

        -- A toggle, done properly: tapping one that is already on turns it off.
        function phone.ensure(q, on)
          local node = phone.find(q)
          if not node then return false, "not on screen" end
          if not node.checkable then return false, "not a toggle" end
          if node.checked == on then return true, "already" end
          phone.tap(node)
          return true, "tapped"
        end
    """.trimIndent()

    /** One script's budget, counters and log */
    private class Run(private val host: ScriptHost, private val job: Job?) {

        private val started = System.nanoTime()
        private val lines = mutableListOf<String>()
        private var instructions = 0L
        private var actions = 0
        private var calls = 0
        private var dropped = 0

        fun tick() {
            if (++instructions > MAX_INSTRUCTIONS) {
                throw Halted("Ran out of steps after $MAX_INSTRUCTIONS instructions. It was probably looping.")
            }
            // Cheap enough per instruction, but there is no reason to read the clock or
            // touch the job on every one of four million
            if (instructions % CHECK_EVERY != 0L) return
            if (elapsed() > MAX_MILLIS) throw Halted("Took longer than ${MAX_MILLIS / 1000}s and was stopped.")
            if (job != null && !job.isActive) throw Halted("Stopped.")
        }

        fun call(name: String, args: Map<String, Any?>): LuaValue {
            if (++calls > MAX_CALLS) throw Halted("Made more than $MAX_CALLS calls and was stopped.")
            if (name in MUTATING && ++actions > MAX_ACTIONS) {
                throw Halted("Tried to act more than $MAX_ACTIONS times and was stopped.")
            }
            val result = try {
                // The tool path is suspending and the interpreter is not, so this thread
                // waits. Parenting it to the run's job means stopping the run also cancels
                // whatever tool is in flight, instead of only the script around it
                runBlocking(job ?: kotlin.coroutines.EmptyCoroutineContext) {
                    host.tool(name, args.filterValues { it != null })
                }
            } catch (e: CancellationException) {
                // pcall catches Exception, and CancellationException is one. Becoming a
                // Halted here stops a script carrying on after a stop
                throw Halted("Stopped.")
            }
            return LuaValue.valueOf(result)
        }

        fun look(): LuaValue {
            call("ui_tree", emptyMap())
            val table = LuaTable()
            host.nodes().forEachIndexed { at, node -> table.set(at + 1, node.toLua()) }
            return table
        }

        fun tap(args: Varargs): LuaValue {
            val first = args.arg(1)
            // `phone.tap(phone.find {...})` is the shape everything is written in, so a tap
            // given nil means the find above it matched nothing, and the error names that
            // instead of the nil argument
            if (first.isnil()) {
                throw LuaError(
                    "tap was given nothing. The find or waitFor before it matched no node. " +
                        "Look at the screen before assuming it is there.",
                )
            }
            val target = when {
                first.istable() -> mapOf("node" to first.get("index").checkint())
                args.narg() >= 2 && first.isnumber() && args.arg(2).isnumber() ->
                    mapOf("x" to first.checkint(), "y" to args.arg(2).checkint())
                first.isnumber() -> mapOf("node" to first.checkint())
                else -> throw LuaError("tap wants a node from find/look, or x and y.")
            }
            return call("tap", target)
        }

        fun log(text: String) {
            if (lines.size >= MAX_LOG_LINES) {
                dropped++
                return
            }
            lines += text.take(MAX_LINE_CHARS)
        }

        fun logFunction(): LuaValue = fn { args ->
            log((1..args.narg()).joinToString(" ") { args.arg(it).tojstring() })
            LuaValue.NIL
        }

        /**
         * What the model reads. The log is the only account of what the script decided
         * while the model was not watching
         */
        fun report(ending: String, returned: LuaValue?): String = buildString {
            appendLine(ending)
            if (lines.isNotEmpty()) {
                appendLine()
                lines.forEach { appendLine(it) }
                if (dropped > 0) appendLine("[$dropped more log lines dropped]")
            }
            returned?.takeUnless { it.isnil() }?.let {
                appendLine()
                appendLine("Returned: ${it.tojstring().take(MAX_LINE_CHARS)}")
            }
            appendLine()
            append("$actions actions, $calls calls, ${elapsed()}ms.")
        }

        private fun elapsed(): Long = (System.nanoTime() - started) / 1_000_000

        private companion object {
            const val CHECK_EVERY = 2048L
        }
    }

    /**
     * The per-instruction hook. Subclassing makes the budget inescapable: the interpreter
     * calls this from inside the loop, so it applies to a script that never calls anything,
     * which a check in the tool bindings would not
     */
    private class Budget(private val run: Run) : DebugLib() {
        override fun onInstruction(pc: Int, v: Varargs, top: Int) {
            run.tick()
            super.onInstruction(pc, v, top)
        }
    }

    private fun UiNode.toLua(): LuaTable = LuaTable().apply {
        set("index", index)
        set("class", className)
        set("text", text.orEmpty())
        set("desc", contentDescription.orEmpty())
        set("id", viewId.orEmpty())
        set("clickable", LuaValue.valueOf(clickable))
        set("editable", LuaValue.valueOf(editable))
        set("scrollable", LuaValue.valueOf(scrollable))
        set("checkable", LuaValue.valueOf(checkable))
        set("checked", LuaValue.valueOf(checked))
        set("focused", LuaValue.valueOf(focused))
        set("x", centerX)
        set("y", centerY)
        set("left", bounds.left)
        set("top", bounds.top)
        set("right", bounds.right)
        set("bottom", bounds.bottom)
    }

    /**
     * Tool arguments arrive as org.json values, so this is what turns `{"names": ["Wi-Fi"],
     * "on": true}` into something a script can index. Arrays become 1-based tables, which is
     * what `ipairs` expects
     */
    private fun Map<String, Any?>.toLua(): LuaTable = LuaTable().also { table ->
        forEach { (key, value) -> table.set(key, value.toLua()) }
    }

    private fun Any?.toLua(): LuaValue = when (this) {
        null, JSONObject.NULL -> LuaValue.NIL
        is Boolean -> LuaValue.valueOf(this)
        is Int -> LuaValue.valueOf(this)
        is Long -> LuaValue.valueOf(toDouble())
        is Number -> LuaValue.valueOf(toDouble())
        is String -> LuaValue.valueOf(this)
        is JSONObject -> LuaTable().also { table ->
            keys().forEach { key -> table.set(key, opt(key).toLua()) }
        }
        is JSONArray -> LuaTable().also { table ->
            for (index in 0 until length()) table.set(index + 1, opt(index).toLua())
        }
        // Tool input arrives as org.json, but a caller holding plain collections should not
        // have to wrap them to be understood
        is Map<*, *> -> LuaTable().also { table ->
            forEach { (key, value) -> table.set(key.toString(), value.toLua()) }
        }
        is List<*> -> LuaTable().also { table ->
            forEachIndexed { index, value -> table.set(index + 1, value.toLua()) }
        }
        else -> LuaValue.valueOf(toString())
    }

    private fun Varargs.nodeArg(at: Int): Int? {
        val value = arg(at)
        return when {
            value.istable() -> value.get("index").optint(-1).takeIf { it >= 0 }
            value.isnumber() -> value.checkint()
            else -> null
        }
    }

    private fun fn(body: (Varargs) -> LuaValue): LuaValue = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs = body(args)
    }

    /** LuaJ prefixes its own chunk name; the model does not need to read it twice */
    private fun String.clean(): String = removePrefix("script:").trim()
}
