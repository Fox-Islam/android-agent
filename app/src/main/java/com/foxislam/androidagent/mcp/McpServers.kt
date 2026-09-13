package com.foxislam.androidagent.mcp

import android.content.Context
import android.util.Log
import com.foxislam.androidagent.agent.Outcome
import com.foxislam.androidagent.agent.ToolSpec
import com.foxislam.androidagent.agent.JsonListStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val token: String = "",
    val enabled: Boolean = true,
)

@Serializable
data class CachedTool(val name: String, val description: String, val schema: String)

/**
 * The MCP servers this phone knows about, and the tools they last reported.
 *
 * Tools are cached on disk: a run should not have to wait on a network round trip before it
 * can start, and a server that is briefly down costs one failed tool call instead of every
 * remote tool at once
 */
object McpServers {

    const val PREFIX = "mcp__"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val store = JsonListStore("mcp_servers.json", McpServer.serializer(), "servers") { it.id }

    val all: StateFlow<List<McpServer>> get() = store.all

    private val _tools = MutableStateFlow<Map<String, List<CachedTool>>>(emptyMap())
    val tools: StateFlow<Map<String, List<CachedTool>>> = _tools.asStateFlow()

    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()

    private val clients = mutableMapOf<String, McpClient>()
    private var toolFile: File? = null

    fun load(context: Context) {
        if (toolFile != null) return
        store.load(context)
        val dir = context.applicationContext.filesDir
        toolFile = File(dir, "mcp_tools.json")

        toolFile?.takeIf { it.exists() }?.let { file ->
            runCatching {
                json.decodeFromString(
                    MapSerializer(String.serializer(), ListSerializer(CachedTool.serializer())),
                    file.readText(),
                )
            }
                .onSuccess { _tools.value = it }
                .onFailure { Log.w(TAG, "could not read cached tools", it) }
        }
    }

    fun add(name: String, url: String, token: String): String {
        val server = McpServer(UUID.randomUUID().toString(), name.trim(), url.trim(), token.trim())
        store.value = store.value + server
        return server.id
    }

    fun update(id: String, name: String, url: String, token: String) {
        store.value = store.value.map {
            if (it.id == id) it.copy(name = name.trim(), url = url.trim(), token = token.trim()) else it
        }
        clients.remove(id)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        store.value = store.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }

    fun delete(id: String) {
        store.delete(id)
        _tools.value = _tools.value - id
        _status.value = _status.value - id
        clients.remove(id)
        persistTools()
    }

    /** Asks a server what it can do now. Returns what to show the user, success or failure */
    suspend fun refresh(id: String): String {
        val server = store.value.firstOrNull { it.id == id } ?: return "No such server."
        return try {
            val discovered = client(server).listTools()
            _tools.value = _tools.value + (id to discovered.map {
                CachedTool(it.name, it.description, it.inputSchema)
            })
            persistTools()
            report(id, "${discovered.size} tools")
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed for ${server.name}", e)
            clients.remove(id)
            report(id, e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun refreshAll() {
        store.value.filter { it.enabled }.forEach { refresh(it.id) }
    }

    private fun report(id: String, message: String): String {
        _status.value = _status.value + (id to message)
        return message
    }


    /**
     * Remote tools, named so a collision with a device tool is impossible, so the model
     * can see which server it is reaching into
     */
    fun definitions(): List<ToolSpec> = store.value.filter { it.enabled }.flatMap { server ->
        _tools.value[server.id].orEmpty().map { tool ->
            ToolSpec(
                name = qualify(server, tool.name),
                description = "[${server.name}] ${tool.description}",
                rawSchema = tool.schema,
            )
        }
    }

    fun isMcpTool(name: String): Boolean = name.startsWith(PREFIX)

    fun serverFor(qualified: String): McpServer? = resolve(qualified)?.first

    suspend fun call(qualified: String, arguments: Map<String, Any?>): Outcome {
        val (server, tool) = resolve(qualified)
            ?: return Outcome.Text("No MCP server offers '$qualified'. It may have been removed.")

        return try {
            val result = client(server).callTool(tool, arguments)
            val note = if (result.isError) "The tool reported an error: ${result.text}" else result.text
            result.images.firstOrNull()
                ?.let { Outcome.Image(it.base64, note, it.mime) }
                ?: Outcome.Text(note)
        } catch (e: Exception) {
            Log.w(TAG, "call failed: $qualified", e)
            clients.remove(server.id)
            Outcome.Text("${server.name} could not run '$tool': ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun resolve(qualified: String): Pair<McpServer, String>? {
        if (!isMcpTool(qualified)) return null
        return store.value.filter { it.enabled }.firstNotNullOfOrNull { server ->
            _tools.value[server.id].orEmpty()
                .firstOrNull { qualify(server, it.name) == qualified }
                ?.let { server to it.name }
        }
    }

    private fun client(server: McpServer): McpClient =
        clients.getOrPut(server.id) { McpClient(server.url, server.token) }

    /**
     * Providers only accept `[A-Za-z0-9_-]{1,64}` as a tool name, and a server called
     * "GitHub (work)" has to survive that intact enough to still be recognisable
     */
    private fun qualify(server: McpServer, tool: String): String =
        (PREFIX + slug(server.name) + "__" + slug(tool)).take(64)

    private fun slug(raw: String): String =
        raw.lowercase().map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .trim('_')


    fun merge(incoming: List<McpServer>) = store.merge(incoming)

    private fun persistTools() {
        val target = toolFile ?: return
        runCatching {
            target.writeText(
                json.encodeToString(
                    MapSerializer(String.serializer(), ListSerializer(CachedTool.serializer())),
                    _tools.value,
                ),
            )
        }.onFailure { Log.w(TAG, "could not write cached tools", it) }
    }

    private const val TAG = "McpServers"
}
