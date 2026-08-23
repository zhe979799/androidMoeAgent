package io.bigmoeonedge.example

import android.content.Context

/** Persists the user-authored Agent system message without exposing it to other app features. */
object AgentPreferences {
    private const val PREFS = "agent_preferences"
    private const val SYSTEM_MESSAGE = "system_message"
    const val MAX_SYSTEM_MESSAGE_CHARS = 16 * 1024

    fun load(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(SYSTEM_MESSAGE, "")
        .orEmpty()

    fun normalize(value: String): String = value.trim().take(MAX_SYSTEM_MESSAGE_CHARS)

    fun save(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(SYSTEM_MESSAGE, normalize(value))
            .apply()
    }
}
