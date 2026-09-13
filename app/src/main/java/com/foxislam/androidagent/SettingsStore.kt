package com.foxislam.androidagent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.foxislam.androidagent.agent.Approvals
import com.foxislam.androidagent.agent.Reasoning

/** The API key goes nowhere but the configured endpoint. There is no backend, no telemetry */
class SettingsStore private constructor(context: Context) {

    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "agent_settings",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var apiUrl: String
        get() = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_API_URL
        set(value) = prefs.edit().putString(KEY_URL, value.trim()).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    /** One model slug per line; this is what the chat screen's picker offers */
    var modelsRaw: String
        get() = prefs.getString(KEY_MODELS, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODELS
        set(value) = prefs.edit().putString(KEY_MODELS, value).apply()

    val models: List<String> get() = modelsFrom(modelsRaw)

    var selectedModel: String
        get() = prefs.getString(KEY_SELECTED, null)?.takeIf { it in models } ?: models.firstOrNull().orEmpty()
        set(value) = prefs.edit().putString(KEY_SELECTED, value).apply()

    var approvalMode: Approvals.Mode
        get() = runCatching { Approvals.Mode.valueOf(prefs.getString(KEY_APPROVALS, null) ?: "") }
            .getOrDefault(Approvals.Mode.RISKY)
        set(value) {
            prefs.edit().putString(KEY_APPROVALS, value.name).apply()
            Approvals.mode = value
        }

    /**
     * The chosen model's context window, which nothing reports automatically. It decides
     * when a long run starts compacting itself instead of hitting the provider's limit
     */
    var contextLimit: Int
        get() = prefs.getInt(KEY_CONTEXT, DEFAULT_CONTEXT_LIMIT)
        set(value) = prefs.edit().putInt(KEY_CONTEXT, value.coerceIn(8_000, 2_000_000)).apply()

    /**
     * The model used for the mechanical steps, and for the summaries compaction writes.
     * Blank means there is no cheap tier and everything goes to the chosen model
     */
    var fastModel: String
        get() = prefs.getString(KEY_FAST, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_FAST, value.trim()).apply()

    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

    var reasoning: Reasoning
        get() = runCatching { Reasoning.valueOf(prefs.getString(KEY_REASONING, null) ?: "") }
            // A stored OFF from an older version meant "send nothing", which is DEFAULT
            // here, so it maps across to the behaviour that phone already had
            .getOrDefault(Reasoning.DEFAULT)
        set(value) = prefs.edit().putString(KEY_REASONING, value.name).apply()

    fun resetApiUrl() = prefs.edit().remove(KEY_URL).apply()

    companion object {
        @Volatile
        private var instance: SettingsStore? = null

        fun modelsFrom(raw: String): List<String> =
            raw.lines().map(String::trim).filter { it.isNotEmpty() }.distinct()

        /**
         * Must be a singleton. EncryptedSharedPreferences keeps its own cache and keyset per
         * instance, and a second instance over the same file read back blanks for settings a
         * previous instance had already written - losing the API key on every Activity restart
         */
        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext).also { instance = it }
            }

        const val DEFAULT_API_URL = "https://openrouter.ai/api/v1/chat/completions"

        /** Every entry must support both tool calling and image input */
        val DEFAULT_MODELS = listOf(
            "google/gemini-2.5-flash",
            "anthropic/claude-sonnet-5",
            "anthropic/claude-opus-5",
            "openai/gpt-5-nano",
        ).joinToString("\n")

        private const val KEY_URL = "api_url"
        private const val KEY_API = "api_key"
        private const val KEY_MODELS = "models"
        private const val KEY_SELECTED = "selected_model"
        private const val KEY_REASONING = "reasoning"
        private const val KEY_APPROVALS = "approval_mode"
        private const val KEY_CONTEXT = "context_limit"
        private const val KEY_FAST = "fast_model"
        private const val KEY_ONBOARDED = "onboarded"

        const val DEFAULT_CONTEXT_LIMIT = 128_000
    }
}
