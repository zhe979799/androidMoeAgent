package io.bigmoeonedge.example

import android.content.Context

/** A selectable group of local tools. Registry rows are the only source of truth for the Agent UI. */
data class ToolkitDefinition(
    val id: String,
    val title: String,
    val description: String,
    val tools: Set<String>,
)

object ToolkitCatalog {
    val entries: List<ToolkitDefinition> = listOf(
        ToolkitDefinition(
            id = "network",
            title = "网络诊断",
            description = "网络状态、DNS、公共地址连通性和 HTTPS 响应信息。",
            tools = setOf("network_state", "network_capabilities", "network_addresses", "dns_lookup", "ping_host", "http_probe"),
        ),
        ToolkitDefinition(
            id = "device",
            title = "设备探索",
            description = "Android 版本、构建信息、电池状态和应用可见存储。",
            tools = setOf("device_info", "app_info", "battery_state", "thermal_state", "memory_state", "display_state", "device_storage", "app_files"),
        ),
        ToolkitDefinition(
            id = "logs",
            title = "日志分析",
            description = "让 Agent 读取本次由用户主动粘贴的日志文本。",
            tools = setOf("read_selected_log", "agent_history"),
        ),
        ToolkitDefinition(
            id = "performance",
            title = "性能观测",
            description = "读取当前会话的生成、缓存、I/O 和温度摘要。",
            tools = setOf("runtime_metrics", "process_memory"),
        ),
        ToolkitDefinition(
            id = "models",
            title = "模型目录",
            description = "查看本地可用模型和内置社区目录的元数据。",
            tools = setOf("model_catalog"),
        ),
    )

    private val toolTitles = mapOf(
        "network_state" to "网络状态",
        "network_capabilities" to "网络能力",
        "network_addresses" to "网络地址",
        "dns_lookup" to "DNS 查询",
        "ping_host" to "公共地址连通性",
        "http_probe" to "HTTPS 响应探测",
        "device_info" to "设备与版本信息",
        "app_info" to "应用信息",
        "battery_state" to "电池状态",
        "thermal_state" to "温度状态",
        "memory_state" to "系统内存",
        "display_state" to "屏幕状态",
        "device_storage" to "可用存储统计",
        "app_files" to "应用文件元数据",
        "runtime_metrics" to "当前运行指标",
        "process_memory" to "进程内存",
        "model_catalog" to "本地模型目录",
        "read_selected_log" to "用户粘贴日志",
        "agent_history" to "Agent 历史",
    )

    fun toolTitle(id: String): String = toolTitles[id] ?: id

    fun toolSummary(id: String): String = when (id) {
        "network_state" -> "连接、验证状态、DNS 和路由"
        "network_capabilities" -> "计费、漫游、带宽和 VPN 能力"
        "network_addresses" -> "当前网络接口的地址和前缀"
        "dns_lookup" -> "查询公开域名的 A/AAAA 记录"
        "ping_host" -> "对已验证的公开地址做有限次数探测"
        "http_probe" -> "读取公开 HTTPS 服务响应头"
        "device_info" -> "Android、设备和应用版本信息"
        "app_info" -> "包名、版本、目标 SDK 和应用目录状态"
        "battery_state" -> "电量、充电状态和温度"
        "thermal_state" -> "系统热状态和电池温度"
        "memory_state" -> "系统可用内存和低内存状态"
        "display_state" -> "分辨率、密度、方向和字体缩放"
        "device_storage" -> "应用可见存储总量与可用空间"
        "app_files" -> "应用私有目录的文件名和大小"
        "runtime_metrics" -> "生成速度、缓存、I/O 和温度"
        "process_memory" -> "当前应用进程的 PSS 和堆内存"
        "model_catalog" -> "本地 MoE 模型文件名和大小"
        "read_selected_log" -> "仅读取用户本次粘贴的日志文本"
        "agent_history" -> "仅查看历史诊断文件的大小和时间，不读取内容"
        else -> ""
    }

    val allIds: Set<String> get() = entries.mapTo(linkedSetOf()) { it.id }
    val allTools: Set<String> get() = entries.flatMapTo(linkedSetOf()) { it.tools }

    fun toolsFor(ids: Set<String>): Set<String> = entries
        .filter { it.id in ids }
        .flatMapTo(linkedSetOf()) { it.tools }

    fun toolsFor(ids: Set<String>, selectedLogAvailable: Boolean): Set<String> = toolsFor(ids).let {
        if (selectedLogAvailable) it else it - "read_selected_log"
    }

    fun definitionsFor(ids: Set<String>): List<ToolkitDefinition> = entries.filter { it.id in ids }
}

/** Persists only toolkit IDs; tool implementation and arguments remain code-owned. */
object ToolkitPreferences {
    private const val PREFS = "agent_toolkits"
    private const val ENABLED = "enabled"

    fun load(context: Context): Set<String> {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(ENABLED, null)
            ?.toSet()
        // An explicitly empty set is meaningful: it lets a user run a model-only Agent turn.
        // Only a missing preference means "enable the built-in defaults" on first launch.
        return stored?.intersect(ToolkitCatalog.allIds) ?: ToolkitCatalog.allIds
    }

    fun save(context: Context, ids: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(ENABLED, ids.intersect(ToolkitCatalog.allIds))
            .apply()
    }
}
