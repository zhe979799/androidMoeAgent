package io.bigmoeonedge.example

import android.content.Context
import java.util.Locale

/** Persists one user-authored Agent system message per protocol profile. */
object AgentPreferences {
    private const val PREFS = "agent_preferences"
    private const val SYSTEM_MESSAGE = "system_message"
    private const val REQUIRE_INITIAL_TOOL = "require_initial_tool"

    private fun systemMessageKey(profile: AgentProtocolProfile): String =
        "${SYSTEM_MESSAGE}_${profile.name.lowercase(Locale.ROOT)}"

    fun load(context: Context, profile: AgentProtocolProfile): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(systemMessageKey(profile), "")
        .orEmpty()

    fun loadRequireInitialTool(context: Context): Boolean = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(REQUIRE_INITIAL_TOOL, false)

    fun saveRequireInitialTool(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(REQUIRE_INITIAL_TOOL, value)
            .apply()
    }


    fun normalize(value: String): String = value.trim()

    fun save(context: Context, profile: AgentProtocolProfile, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(systemMessageKey(profile), normalize(value))
            .apply()
    }
}
