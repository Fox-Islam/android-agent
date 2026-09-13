package com.foxislam.androidagent.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import com.foxislam.androidagent.control.ControlService
import com.foxislam.androidagent.mcp.McpServers
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.io.ByteArrayOutputStream

/**
 * The agent's whole surface on the device. Kept small: a bigger tool list costs tokens on
 * every turn and gives the model more ways to go sideways
 */
class Tools(
    private val service: ControlService,
    private val context: Context,
    private val threadId: String = "",
) {

    suspend fun call(name: String, input: Map<String, Any?>): Outcome = when (name) {
        "ui_tree" -> uiTree()
        "screenshot" -> screenshot()
        "tap" -> tap(input)
        "swipe" -> swipe(input)
        "type_text" -> typeText(input)
        "press" -> Outcome.Text(service.press(input.str("key") ?: "back"))
        "open_app" -> openApp(input)
        "list_apps" -> listApps()
        "wait" -> waitFor(input)
        "skill" -> skill(input)
        "ask_user_question" -> askUser(input)
        "todo_write" -> todos(input)
        "session_search" -> sessionSearch(input)
        "session_read" -> sessionRead(input)
        "save_script" -> saveScript(input)
        "remember" -> Outcome.Text(Memory.remember(input.str("note").orEmpty()))
        "forget" -> Outcome.Text(Memory.forget(input.str("note").orEmpty()))
        "done" -> Outcome.Done(input.str("summary") ?: "Finished.")
        else -> if (McpServers.isMcpTool(name)) McpServers.call(name, input)
        else Outcome.Text("Unknown tool '$name'.")
    }

    /** Hands the question to the user and waits, with the run left where it is */
    private suspend fun askUser(input: Map<String, Any?>): Outcome {
        val question = input.str("question") ?: return Outcome.Text("ask_user_question needs a question.")
        val options = input.strings("options")
        return Outcome.Text(Questions.ask(context, threadId, question, options))
    }

    /**
     * The run's checklist. A thirty-step task is unreadable as a wall of taps, and a small
     * model halfway through one has usually forgotten what it set out to do
     */
    private fun todos(input: Map<String, Any?>): Outcome {
        val raw = input["items"] as? JSONArray ?: return Outcome.Text("todo_write needs items.")
        val items = (0 until raw.length()).mapNotNull { index ->
            val item = raw.optJSONObject(index) ?: return@mapNotNull null
            val text = item.optString("text").trim().ifEmpty { return@mapNotNull null }
            TodoItem(text, item.optString("state").ifBlank { TodoItem.PENDING })
        }
        if (items.isEmpty()) return Outcome.Text("No usable items - each needs a text field.")

        Chats.record(threadId, SessionEvent.TodosWritten(Chats.nextEventId(), items))
        val done = items.count { it.state == TodoItem.DONE }
        return Outcome.Text("Noted ${items.size} steps, $done done.")
    }

    /**
     * Looking things up in its own history, instead of carrying all of it. The cheaper half
     * of compaction: what was dropped from the context is still on disk
     */
    private fun sessionSearch(input: Map<String, Any?>): Outcome {
        val query = input.str("query") ?: return Outcome.Text("session_search needs a query.")
        val limit = (input.int("limit") ?: 10).coerceIn(1, 40)
        val hits = lines()
            .filter { it.second.contains(query, ignoreCase = true) }
            .takeLast(limit)
        if (hits.isEmpty()) return Outcome.Text("Nothing in this chat mentions \"$query\".")
        return Outcome.Text(hits.joinToString("\n") { "[${it.first}] ${it.second.take(LINE_CHARS)}" })
    }

    private fun sessionRead(input: Map<String, Any?>): Outcome {
        val all = lines()
        val count = (input.int("count") ?: 20).coerceIn(1, 60)
        val from = input.int("from")
        val window = if (from == null) all.takeLast(count) else all.filter { it.first >= from }.take(count)
        if (window.isEmpty()) return Outcome.Text("Nothing recorded there.")
        return Outcome.Text(window.joinToString("\n") { "[${it.first}] ${it.second.take(LINE_CHARS)}" })
    }

    /** The log as numbered lines, so the model can cite one back */
    private fun lines(): List<Pair<Long, String>> =
        Chats.thread(threadId)?.events.orEmpty().mapNotNull { event ->
            val text = when (event) {
                is SessionEvent.UserMessage -> "user: ${event.text}"
                is SessionEvent.AssistantMessage -> event.text?.let { "agent: $it" }
                is SessionEvent.ToolStarted -> "did: ${event.name} ${event.detail}"
                is SessionEvent.ToolFinished -> "result: ${event.result}"
                is SessionEvent.Finished -> "finished: ${event.text}"
                is SessionEvent.Failed -> "failed: ${event.text}"
                is SessionEvent.QuestionAsked -> "asked: ${event.text}"
                is SessionEvent.QuestionAnswered -> "answered: ${event.answer}"
                is SessionEvent.AskRaised -> "approval: ${event.tool} ${event.detail}"
                is SessionEvent.AskDecided -> "approval ${event.decision}"
                is SessionEvent.PlanProposed -> "plan: ${event.steps.joinToString("; ")}"
                else -> null
            }
            text?.let { event.id to it }
        }

    /**
     * Keeps a script under a name. Refusing one that will not compile keeps the failure from
     * surfacing days later, in a call that was relying on it. Saving takes no action on the
     * phone - what the script *does* is gated when it runs, every time it runs - so this
     * needs no approval of its own
     */
    private fun saveScript(input: Map<String, Any?>): Outcome {
        val name = input.str("name") ?: return Outcome.Text("save_script needs a name.")
        val source = input.str("script") ?: return Outcome.Text("save_script needs a script.")
        val description = input.str("description")
            ?: return Outcome.Text("save_script needs a description - it is how you will choose it later.")
        if (source.length > Scripts.MAX_SOURCE_CHARS) {
            return Outcome.Text("That script is longer than ${Scripts.MAX_SOURCE_CHARS} characters.")
        }
        Scripts.check(source)?.let { return Outcome.Text("Not saved - it would not compile: $it") }

        val replaced = SavedScripts.save(name, description, source, params(input))
        val verb = if (replaced) "Replaced" else "Saved"
        return Outcome.Text("$verb '$name'. Run it later with call_script, without writing it again.")
    }

    /**
     * The values the script wants, declared. This is what turns "run it yourself" from typing
     * JSON into filling in a form, so the tool description presses the model to declare them
     */
    private fun params(input: Map<String, Any?>): List<ScriptParam> {
        val raw = input["params"] as? JSONArray ?: return emptyList()
        return (0 until raw.length()).mapNotNull { index ->
            val item = raw.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name").trim().ifEmpty { return@mapNotNull null }
            ScriptParam(
                name = name,
                type = item.optString("type").trim().lowercase().ifEmpty { ScriptParam.TEXT },
                label = item.optString("label").trim(),
                default = item.optString("default").trim(),
            )
        }
    }

    /** Loads one app playbook on demand, so its text is only paid for when it is wanted */
    private fun skill(input: Map<String, Any?>): Outcome {
        val name = input.str("name") ?: return Outcome.Text("skill needs a name.")
        val found = Skills.byName(name)
            ?: return Outcome.Text(
                "No playbook called '$name'. Available: " +
                    Skills.all.value.joinToString(", ") { it.name }.ifBlank { "none" },
            )
        return Outcome.Text(found.body)
    }

    private fun uiTree(): Outcome {
        disconnected()?.let { return it }
        val nodes = service.snapshot()
        if (nodes.isEmpty()) {
            return Outcome.Text(
                "The accessibility tree is empty. This screen is probably canvas-rendered " +
                    "or marked secure - take a screenshot and work from coordinates.",
            )
        }
        return Outcome.Text(nodes.joinToString("\n") { it.render() })
    }

    private suspend fun screenshot(): Outcome {
        disconnected()?.let { return it }
        val bitmap = service.capture()
        if (bitmap.width <= 1) {
            return Outcome.Text(
                "Screen capture failed. The foreground window is probably FLAG_SECURE " +
                    "(banking or DRM), which cannot be captured. Use ui_tree, or ask the user.",
            )
        }
        return Outcome.Image(bitmap.toScaledJpegBase64(), "Screen is ${bitmap.width}x${bitmap.height}.")
    }

    /** Distinguishes "nothing to see" from "we cannot see at all" */
    private fun disconnected(): Outcome? =
        if (ControlService.isConnected) {
            null
        } else {
            Outcome.Text(
                "The accessibility service is not connected, so nothing on screen can be read " +
                    "or tapped. Ask the user to re-enable it in Settings → Accessibility.",
            )
        }

    private suspend fun tap(input: Map<String, Any?>): Outcome {
        input.int("node")?.let { return Outcome.Text(service.tapNode(it)) }
        val x = input.int("x") ?: return Outcome.Text("tap needs either node, or both x and y.")
        val y = input.int("y") ?: return Outcome.Text("tap needs either node, or both x and y.")
        val ok = service.tap(x, y)
        return Outcome.Text(if (ok) "Tapped $x,$y." else "Gesture was cancelled - is the screen on?")
    }

    private suspend fun swipe(input: Map<String, Any?>): Outcome {
        val x1 = input.int("x1") ?: return Outcome.Text("swipe needs x1, y1, x2, y2.")
        val y1 = input.int("y1") ?: return Outcome.Text("swipe needs x1, y1, x2, y2.")
        val x2 = input.int("x2") ?: return Outcome.Text("swipe needs x1, y1, x2, y2.")
        val y2 = input.int("y2") ?: return Outcome.Text("swipe needs x1, y1, x2, y2.")
        val duration = input.int("duration_ms")?.toLong() ?: 300L
        val ok = service.swipe(x1, y1, x2, y2, duration)
        return Outcome.Text(if (ok) "Swiped $x1,$y1 to $x2,$y2." else "Swipe was cancelled.")
    }

    private fun typeText(input: Map<String, Any?>): Outcome {
        val text = input.str("text") ?: return Outcome.Text("type_text needs text.")
        return Outcome.Text(service.typeText(text, input.int("node")))
    }

    private fun openApp(input: Map<String, Any?>): Outcome {
        val query = input.str("app") ?: return Outcome.Text("open_app needs an app name or package.")
        val match = launchable().firstOrNull { (pkg, label) ->
            pkg.equals(query, ignoreCase = true) || label.equals(query, ignoreCase = true)
        } ?: launchable().firstOrNull { (pkg, label) ->
            label.contains(query, ignoreCase = true) || pkg.contains(query, ignoreCase = true)
        } ?: return Outcome.Text("No installed app matches '$query'. Call list_apps to see what is available.")

        val intent = context.packageManager.getLaunchIntentForPackage(match.first)
            ?: return Outcome.Text("'${match.second}' has no launcher entry.")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
        return Outcome.Text("Launched ${match.second} (${match.first}).")
    }

    private fun listApps(): Outcome =
        Outcome.Text(launchable().joinToString("\n") { (pkg, label) -> "$label - $pkg" })

    private fun launchable(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    private suspend fun waitFor(input: Map<String, Any?>): Outcome {
        val ms = (input.int("ms") ?: 1000).coerceIn(100, 15_000)
        delay(ms.toLong())
        return Outcome.Text("Waited ${ms}ms.")
    }

    /**
     * Long edge capped and re-encoded as JPEG. A full-resolution phone screenshot is a
     * large image-token bill on every turn, and the agent does not need the pixels
     */
    private fun Bitmap.toScaledJpegBase64(): String {
        val longest = maxOf(width, height)
        val scaled = if (longest <= MAX_IMAGE_EDGE) this else {
            val ratio = MAX_IMAGE_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(this, (width * ratio).toInt(), (height * ratio).toInt(), true)
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun Map<String, Any?>.strings(key: String): List<String> {
        val array = this[key] as? JSONArray ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }

    private fun Map<String, Any?>.str(key: String): String? =
        (this[key] as? String)?.takeIf { it.isNotBlank() }

    private fun Map<String, Any?>.int(key: String): Int? = when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.trim().toDoubleOrNull()?.toInt()
        else -> null
    }

    companion object {
        private const val MAX_IMAGE_EDGE = 1100
        private const val JPEG_QUALITY = 70
        private const val LINE_CHARS = 160

        private fun tool(
            name: String,
            description: String,
            properties: Map<String, Map<String, Any>> = emptyMap(),
            required: List<String> = emptyList(),
        ) = ToolSpec(name, description, properties, required)

        private val INT = mapOf("type" to "integer")
        private val STR = mapOf("type" to "string")

        /** Declared once and reused so a provider's cached tool prefix stays byte-identical */
        val definitions: List<ToolSpec> = listOf(
            tool(
                "ui_tree",
                "List the interactive and labelled elements on screen, with a stable index, " +
                    "centre point and bounds for each. Cheap. Prefer this over screenshot.",
            ),
            tool(
                "screenshot",
                "Capture the screen as an image. Use when ui_tree is empty or ambiguous, or " +
                    "when you need to judge layout or visual state. Expensive - avoid per-step use.",
            ),
            tool(
                "tap",
                "Tap an element. Pass node (an index from ui_tree) when you have one - it is " +
                    "much more reliable. Otherwise pass x and y screen coordinates.",
                mapOf("node" to INT, "x" to INT, "y" to INT),
            ),
            tool(
                "swipe",
                "Drag from one point to another. Scroll a list by swiping from low y to high y " +
                    "(or the reverse), well inside the list bounds.",
                mapOf("x1" to INT, "y1" to INT, "x2" to INT, "y2" to INT, "duration_ms" to INT),
                listOf("x1", "y1", "x2", "y2"),
            ),
            tool(
                "type_text",
                "Replace the contents of the focused text field. Pass node to focus a specific " +
                    "field first. Password and some custom fields will refuse this.",
                mapOf("text" to STR, "node" to INT),
                listOf("text"),
            ),
            tool(
                "press",
                "Press a system key: back, home, recents or notifications.",
                mapOf("key" to STR),
                listOf("key"),
            ),
            tool(
                "open_app",
                "Launch an app by visible name or package name.",
                mapOf("app" to STR),
                listOf("app"),
            ),
            tool(
                "list_apps",
                "List every launchable app with its package name.",
            ),
            tool(
                "wait",
                "Pause for ms milliseconds to let an animation or load settle.",
                mapOf("ms" to INT),
            ),
            tool(
                "remember",
                "Save a durable note about this phone or this user - something worth knowing " +
                    "in a later, unrelated chat. Not for the details of the task in hand.",
                mapOf("note" to STR),
                listOf("note"),
            ),
            tool(
                "forget",
                "Remove saved notes containing this text, when something you remembered turns " +
                    "out to be wrong or out of date.",
                mapOf("note" to STR),
                listOf("note"),
            ),
            tool(
                "done",
                "Call this when the task is complete or you cannot continue. summary is what " +
                    "the user reads.",
                mapOf("summary" to STR),
                listOf("summary"),
            ),
        )

        private val STRINGS = mapOf("type" to "array", "items" to mapOf("type" to "string"))

        /** Tools that exist because a phone run is long, blocking and easy to lose track of */
        val session: List<ToolSpec> = listOf(
            tool(
                "ask_user_question",
                "Ask the user one short question and wait for the answer. Use it when you " +
                    "genuinely cannot tell - which of two accounts, which of three people - " +
                    "instead of guessing or giving up. Offer options when there is a short " +
                    "list of sensible answers.",
                mapOf("question" to STR, "options" to STRINGS),
                listOf("question"),
            ),
            tool(
                "todo_write",
                "Record or update the checklist for this task. Rewrite the whole list each " +
                    "time, with each item's state as pending, doing or done. Worth doing for " +
                    "anything longer than a few steps; the user sees it as it changes.",
                mapOf(
                    "items" to mapOf(
                        "type" to "array",
                        "items" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "text" to mapOf("type" to "string"),
                                "state" to mapOf(
                                    "type" to "string",
                                    "enum" to listOf("pending", "doing", "done"),
                                ),
                            ),
                            "required" to listOf("text"),
                        ),
                    ),
                ),
                listOf("items"),
            ),
            tool(
                "session_search",
                "Search everything already said and done in this chat. Use it when something " +
                    "you need has scrolled out of the conversation - a value you read earlier, " +
                    "what a screen said, why something failed.",
                mapOf("query" to STR, "limit" to INT),
                listOf("query"),
            ),
            tool(
                "session_read",
                "Read back a window of this chat's history. from is an entry number from " +
                    "session_search; without it you get the most recent entries.",
                mapOf("from" to INT, "count" to INT),
            ),
            tool(
                "run_steps",
                "Do several device actions in one go, in order, stopping at the first one " +
                    "that fails. Each step is {tool, args} using tap, swipe, type_text, press, " +
                    "wait or ui_tree. Use it for a run of actions you are sure about - it " +
                    "saves a round trip each - not for anything you need to look at first.",
                mapOf(
                    "steps" to mapOf(
                        "type" to "array",
                        "items" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "tool" to mapOf("type" to "string"),
                                "args" to mapOf("type" to "object"),
                            ),
                            "required" to listOf("tool"),
                        ),
                    ),
                ),
                listOf("steps"),
            ),
        )

        /**
         * Declared separately from [session] so the description can carry the API. It is
         * long, and the model needs it to write a working script first time
         */
        val runScript = tool(
            "run_script",
            "Run a Lua script on the phone, when what to do next depends on what is on " +
                "screen. Every action inside is approved and recorded exactly as if you had " +
                "called the tool yourself.\n" +
                "Available: phone.look() -> array of nodes, each {index, class, text, desc, " +
                "id, clickable, editable, scrollable, checkable, checked, focused, x, y, " +
                "left, top, right, bottom}. phone.find(q) -> first matching node or nil. " +
                "phone.all(q) -> every match. phone.waitFor(q, ms) -> polls until it appears " +
                "or nil. phone.ensure(q, true/false) -> taps a toggle only if it is not " +
                "already in that state. q is a table: {text=, desc=, id=, class=, exact=, " +
                "checked=, checkable=, clickable=, editable=}; text and desc match " +
                "case-insensitive " +
                "substrings unless exact=true.\n" +
                "phone.tap(node or index or x, y), phone.type(text, node), " +
                "phone.swipe(x1, y1, x2, y2, ms), phone.press(key), phone.open(app), " +
                "phone.apps(), phone.wait(ms), phone.log(text).\n" +
                "Match on text or id, never on a saved index - the tree is renumbered every " +
                "look. Call phone.log to say what it found and what it decided; that log is " +
                "all you will see of a script that ran while you were not looking. Limits: " +
                "${Scripts.MAX_ACTIONS} actions, ${Scripts.MAX_MILLIS / 1000}s. No file, " +
                "network or system access - it is only these functions.",
            mapOf("script" to STR),
            listOf("script"),
        )

        val saveScript = tool(
            "save_script",
            "Keep a script you have got working, under a name, so it can be run again " +
                "without being written again. description says what it does and what it " +
                "expects in args - it is all you will see of it when choosing later, so make " +
                "it specific. Read values out of the args table instead of hard-coding them: " +
                "args.on, args.names, args.query.\n" +
                "Declare every value the script reads out of args in params, each " +
                "{name, type, label, default} with type text, boolean or number. That is what " +
                "the user is shown when they run it themselves - a boolean becomes a switch, " +
                "the rest become fields - so a script that reads args and declares nothing is " +
                "one they cannot comfortably run.",
            mapOf(
                "name" to STR,
                "description" to STR,
                "script" to STR,
                "params" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "name" to mapOf("type" to "string"),
                            "type" to mapOf(
                                "type" to "string",
                                "enum" to listOf("text", "boolean", "number"),
                            ),
                            "label" to mapOf("type" to "string"),
                            "default" to mapOf("type" to "string"),
                        ),
                        "required" to listOf("name"),
                    ),
                ),
            ),
            listOf("name", "description", "script"),
        )

        val callScript = tool(
            "call_script",
            "Run a script you saved earlier, by name. args is handed to the script as a " +
                "table, so pass whatever its description asks for.",
            mapOf("name" to STR, "args" to mapOf("type" to "object")),
            listOf("name"),
        )

        /** Tools that block on a person, and so keep their own clock */
        val blocking = setOf("ask_user_question")

        /** What run_steps is allowed to contain. Anything that needs judgement is not here */
        val batchable = setOf("tap", "swipe", "type_text", "press", "wait", "ui_tree")

        /**
         * What a script may call. Wider than [batchable] because a script decides where it
         * is going, but still only the tools that act on this phone - no MCP server, nothing
         * that blocks on a person, and nothing that ends the run
         */
        val scriptable = batchable + setOf("open_app", "list_apps")

        /** The skill tool is only offered when there are playbooks to load */
        val skill = tool(
            "skill",
            "Load the full text of one of the app playbooks listed below, by name. Do this " +
                "before working in an app you have a playbook for.",
            mapOf("name" to STR),
            listOf("name"),
        )

        val proposePlan = tool(
            "propose_plan",
            "Describe what you intend to do, as an ordered list of short steps, and stop. " +
                "The user approves or rejects it before anything is touched.",
            mapOf(
                "steps" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                "note" to STR,
            ),
            listOf("steps"),
        )

        /** Tools that only look. What plan mode is allowed before the plan is approved */
        val readOnly = setOf(
            "ui_tree", "screenshot", "list_apps", "wait", "skill", "done",
            "ask_user_question", "session_search", "session_read",
        )

        /**
         * The tool list for one turn. Plan mode hands over a looking-only set plus the way to
         * propose, so "plan first" is enforced by the harness, not asked for in the prompt
         */
        fun available(planning: Boolean, withSkills: Boolean, remote: List<ToolSpec>): List<ToolSpec> {
            val device = definitions + session + listOf(runScript, saveScript) +
                (if (SavedScripts.all.value.isNotEmpty()) listOf(callScript) else emptyList()) +
                (if (withSkills) listOf(skill) else emptyList())
            if (planning) {
                return device.filter { it.name in readOnly } + proposePlan
            }
            return device + remote
        }
    }
}
