package io.bigmoeonedge.example

import android.content.Context

/** Persists the user-authored Agent system message without exposing it to other app features. */
object AgentPreferences {
    private const val PREFS = "agent_preferences"
    private const val SYSTEM_MESSAGE = "system_message"
    private const val REQUIRE_INITIAL_TOOL = "require_initial_tool"
    fun load(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(SYSTEM_MESSAGE, "")
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

    fun save(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(SYSTEM_MESSAGE, normalize(value))
            .apply()
    }
}
