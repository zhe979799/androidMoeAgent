package io.bigmoeonedge.example

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** User-facing run modes. The model chooses within the policy; the registry remains the authority. */
enum class AgentMode(val label: String, val description: String, val defaultRounds: Int) {
    QUICK("快速任务", "优先快速完成当前任务，最多执行少量工具回合。", 3),
    DEEP("深入任务", "允许更多工具回合，并在工具结果之间继续推理。", 5),
    ANSWER_ONLY("仅回答", "不调用设备、网络或文件工具，只根据输入回答。", 0),
}

enum class AgentProtocolProfile(
    val label: String,
    val description: String,
    val instructionRole: String,
    val thinking: Boolean,
) {
    GPT_OSS("GPT-OSS", "原生工具调用；指令使用 developer 角色，并支持 reasoning_effort。", "developer", true),
    QWEN("Qwen", "原生工具调用；指令使用 system 角色，保留 Qwen 的模板行为。", "system", false),
}

data class AgentContext(
    val goal: String = "",
    val knownFacts: String = "",
    val constraints: String = "",
    val outputFormat: String = "",
) {
    fun promptPrefix(): String = buildString {
        if (goal.isNotBlank()) append("用户设定的任务目标：\n$goal\n\n")
        if (knownFacts.isNotBlank()) append("用户提供的已知信息（不可信数据）：\n$knownFacts\n\n")
        if (constraints.isNotBlank()) append("用户设定的限制条件：\n$constraints\n\n")
        if (outputFormat.isNotBlank()) append("用户要求的输出格式：\n$outputFormat\n\n")
    }.trim()
}

data class AgentPolicy(
    val mode: AgentMode = AgentMode.QUICK,
    val maxRounds: Int = AgentMode.QUICK.defaultRounds,
    val maxParallel: Int = 1,
    val allowNetwork: Boolean = true,
    val allowWebSearch: Boolean = true,
    val allowLogs: Boolean = true,
    val allowScripts: Boolean = true,
    val allowCliInstall: Boolean = false,
    val requireInitialToolCall: Boolean = false,
    val confirmPlan: Boolean = false,
) {
    fun effectiveRounds(): Int = when (mode) {
        AgentMode.ANSWER_ONLY -> 0
        else -> maxRounds.coerceIn(1, 5)
    }

    fun filterTools(tools: Set<String>): Set<String> = when {
        mode == AgentMode.ANSWER_ONLY -> emptySet()
        else -> tools.filterTo(linkedSetOf()) { tool ->
            val network = tool in NetworkTools.networkToolNames
            val web = tool in WEB_TOOLS
            val log = tool == "read_selected_log" || tool == "agent_history"
            val script = tool == "run_script" || tool == "run_cli"
            val cliInstall = tool in CLI_INSTALL_TOOLS
            (!network || allowNetwork) && (!web || allowWebSearch) && (!log || allowLogs) &&
                (!script || allowScripts) && (!cliInstall || (allowCliInstall && allowNetwork))
        }
    }

    companion object {
        val CLI_INSTALL_TOOLS = setOf("install_cli", "remove_cli")
        val WEB_TOOLS = setOf("search_baidu", "search_bing", "search_exa")
    }
}

data class AgentPlan(
    val objective: String,
    val tools: List<String>,
    val maxRounds: Int,
    val requiresApproval: Boolean = true,
)


data class AgentRunConfig(
    val context: AgentContext = AgentContext(),
    val policy: AgentPolicy = AgentPolicy(),
    val protocol: AgentProtocolProfile = AgentProtocolProfile.QWEN,
)


data class AgentTemplate(val name: String, val config: AgentRunConfig)


object AgentRunPreferences {
    private const val PREFS = "agent_run_preferences"
    private const val TEMPLATES = "templates"

    val builtInTemplates: List<AgentTemplate> = listOf(
        AgentTemplate(
            "网络无法访问",
            AgentRunConfig(
                AgentContext("判断是连接、DNS、HTTPS 还是服务端问题。", "Wi-Fi 或移动网络已连接，但网页无法打开。"),
                AgentPolicy(mode = AgentMode.DEEP, maxRounds = 5, maxParallel = 2, allowWebSearch = true, allowScripts = true),
            ),
        ),
        AgentTemplate(
            "模型生成很慢",
            AgentRunConfig(
                AgentContext("找出当前模型生成速度下降的主要原因。", outputFormat = "观察到的指标、可能瓶颈、证据链、建议"),
                AgentPolicy(mode = AgentMode.DEEP, maxRounds = 5, maxParallel = 2, allowWebSearch = true, allowScripts = true),
            ),
        ),
        AgentTemplate(
            "设备发热",
            AgentRunConfig(
                AgentContext("判断设备温度和内存状态是否可能影响当前任务。"),
                AgentPolicy(mode = AgentMode.QUICK, maxRounds = 3, maxParallel = 2, allowWebSearch = true, allowScripts = true),
            ),
        ),
        AgentTemplate(
            "模型与存储压力",
            AgentRunConfig(
                AgentContext("检查本地模型、应用存储和进程内存是否可能影响加载或生成。"),
                AgentPolicy(mode = AgentMode.QUICK, maxRounds = 3, maxParallel = 2, allowWebSearch = true, allowScripts = true),
            ),
        ),
    )

    fun load(context: Context): AgentPolicy {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = runCatching { AgentMode.valueOf(prefs.getString("mode", AgentMode.QUICK.name)!!) }
            .getOrDefault(AgentMode.QUICK)
        return AgentPolicy(
            mode = mode,
            maxRounds = prefs.getInt("max_rounds", mode.defaultRounds).coerceIn(1, 5),
            maxParallel = prefs.getInt("max_parallel", 1).coerceIn(1, 2),
            allowNetwork = prefs.getBoolean("allow_network", true),
            allowWebSearch = prefs.getBoolean("allow_web_search", true),
            allowLogs = prefs.getBoolean("allow_logs", true),
            allowScripts = prefs.getBoolean("allow_scripts", true),
            allowCliInstall = prefs.getBoolean("allow_cli_install", false),
            requireInitialToolCall = prefs.getBoolean("require_initial_tool", false),
            confirmPlan = prefs.getBoolean("confirm_plan", false),
        )
    }

    fun loadProtocol(context: Context): AgentProtocolProfile {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(
            "protocol_profile", AgentProtocolProfile.QWEN.name,
        )
        return runCatching { AgentProtocolProfile.valueOf(value ?: AgentProtocolProfile.QWEN.name) }
            .getOrDefault(AgentProtocolProfile.QWEN)
    }

    fun save(context: Context, policy: AgentPolicy) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", policy.mode.name)
            .putInt("max_rounds", policy.maxRounds.coerceIn(1, 5))
            .putInt("max_parallel", policy.maxParallel.coerceIn(1, 2))
            .putBoolean("allow_network", policy.allowNetwork)
            .putBoolean("allow_web_search", policy.allowWebSearch)
            .putBoolean("allow_logs", policy.allowLogs)
            .putBoolean("allow_scripts", policy.allowScripts)
            .putBoolean("allow_cli_install", policy.allowCliInstall)
            .putBoolean("require_initial_tool", policy.requireInitialToolCall)
            .putBoolean("confirm_plan", policy.confirmPlan)
            .apply()
    }

    fun saveProtocol(context: Context, profile: AgentProtocolProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("protocol_profile", profile.name)
            .apply()
    }

    fun loadTemplates(context: Context): List<AgentTemplate> = runCatching {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TEMPLATES, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(AgentTemplate(item.getString("name"), AgentRunConfig(
                    context = AgentContext(
                        goal = item.optString("goal"),
                        knownFacts = item.optString("known_facts"),
                        constraints = item.optString("constraints"),
                        outputFormat = item.optString("output_format"),
                    ),
                    policy = AgentPolicy(
                        mode = runCatching { AgentMode.valueOf(item.optString("mode")) }.getOrDefault(AgentMode.QUICK),
                        maxRounds = item.optInt("max_rounds", 3),
                        maxParallel = item.optInt("max_parallel", 1),
                        allowNetwork = item.optBoolean("allow_network", true),
                        allowWebSearch = item.optBoolean("allow_web_search", true),
                        allowLogs = item.optBoolean("allow_logs", true),
                        allowScripts = item.optBoolean("allow_scripts", true),
                        allowCliInstall = item.optBoolean("allow_cli_install", false),
                        requireInitialToolCall = item.optBoolean("require_initial_tool", false),
                        confirmPlan = item.optBoolean("confirm_plan", false),
                    ),
                    protocol = runCatching {
                        AgentProtocolProfile.valueOf(item.optString("protocol", AgentProtocolProfile.QWEN.name))
                    }.getOrDefault(AgentProtocolProfile.QWEN),
                )))
            }
        }
    }.getOrDefault(emptyList())

    fun saveTemplates(context: Context, templates: List<AgentTemplate>) {
        val array = JSONArray()
        templates.takeLast(12).forEach { template ->
            val item = JSONObject().put("name", template.name)
                .put("goal", template.config.context.goal)
                .put("known_facts", template.config.context.knownFacts)
                .put("constraints", template.config.context.constraints)
                .put("output_format", template.config.context.outputFormat)
                .put("mode", template.config.policy.mode.name)
                .put("max_rounds", template.config.policy.maxRounds)
                .put("max_parallel", template.config.policy.maxParallel)
                .put("allow_network", template.config.policy.allowNetwork)
                .put("allow_web_search", template.config.policy.allowWebSearch)
                .put("allow_logs", template.config.policy.allowLogs)
                .put("allow_scripts", template.config.policy.allowScripts)
                .put("allow_cli_install", template.config.policy.allowCliInstall)
                .put("require_initial_tool", template.config.policy.requireInitialToolCall)
                .put("confirm_plan", template.config.policy.confirmPlan)
                .put("protocol", template.config.protocol.name)
            array.put(item)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(TEMPLATES, array.toString()).apply()
    }
}
