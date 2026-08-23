package io.bigmoeonedge.example

import android.content.Context

/** Local configuration for optional search providers. Secrets never enter the model prompt. */
object SearchPreferences {
    private const val PREFS = "agent_search"
    private const val EXA_API_KEY = "exa_api_key"

    fun loadExaApiKey(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(EXA_API_KEY, "")
        .orEmpty()

    fun saveExaApiKey(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(EXA_API_KEY, value.trim().take(256))
            .apply()
    }
}
