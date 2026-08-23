package io.bigmoeonedge.example

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

/** Dedicated model-agent workspace. The user explicitly starts each diagnostic run. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    context: Context,
    models: List<File>,
    modelIdx: Int,
    settings: AppSettings,
    toolkitIds: Set<String>,
    onSelectModel: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val coordinator = remember(scope) { NetworkAgentCoordinator(scope) }
    val ui by RunBus.state.collectAsStateWithLifecycle()
    var request by rememberSaveable { mutableStateOf("请自动检查当前网络状态，并用中文给出最可能的原因和下一步建议。") }
    var selectedLog by rememberSaveable { mutableStateOf("") }
    var systemMessage by rememberSaveable { mutableStateOf(AgentPreferences.load(context)) }
    var savedSystemMessage by rememberSaveable { mutableStateOf(systemMessage) }
    val allowedTools = ToolkitCatalog.toolsFor(toolkitIds, selectedLog.isNotBlank())
    val selected = models.getOrNull(modelIdx.coerceIn(0, (models.size - 1).coerceAtLeast(0)))
    val listState = rememberLazyListState()
    val templates = listOf(
        "网络无法访问" to "请检查当前网络状态，并判断是连接、DNS、HTTPS 还是服务端问题。请列出事实、可能原因、置信度和下一步。",
        "模型生成很慢" to "请观察当前模型的生成速度、缓存、I/O、内存和温度，判断瓶颈并给出一个安全建议。",
        "设备发热" to "请检查当前设备的电池、热状态、系统内存、进程内存和屏幕状态，指出可能的风险。",
        "检查模型空间" to "请查看本地模型目录、应用存储和进程内存，说明空间是否可能影响加载。",
    )

    DisposableEffect(coordinator, context) {
        onDispose {
            coordinator.cancel()
            context.startService(Intent(context, RunService::class.java).setAction(RunService.ACTION_CANCEL))
        }
    }
    // Agent entry is intentionally explicit: opening a page must never trigger a model load or
    // network observation before the user confirms the task.

    LaunchedEffect(ui.agentTranscript.size, ui.agentTools.size, ui.agentStatus, ui.agentActive) {
        if (ui.agentActive || ui.agentTools.isNotEmpty() || ui.agentTranscript.isNotEmpty()) {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last >= 0) listState.animateScrollToItem(last)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent 工作台") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("当前模型：${selected?.name ?: "未选择"}", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text(
                    if (toolkitIds.isEmpty()) "未启用工具集，Agent 只会基于模型知识回答。"
                    else "已启用 ${toolkitIds.size} 个工具集，开始后每次读取都会在下方显示。",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("选择模型并点击开始诊断。模型、工具调用和结果都留在设备上。", fontSize = 13.sp)
                Text(
                    if (allowedTools.isEmpty()) "当前没有可用观测工具，Agent 不会读取设备或网络数据。"
                    else "日志工具仅在粘贴内容后才会授权。",
                    fontSize = 12.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.tertiary,
                )
                if (models.isEmpty()) {
                    Text("还没有可用的 MoE 模型，请先回到首页下载或导入模型。")
                } else {
                    LabeledDropdown(
                        label = "Agent 模型",
                        options = models.map { it.name },
                        selected = modelIdx.coerceIn(0, models.lastIndex),
                        enabled = !ui.busy && !ui.agentActive,
                        onSelect = {
                            coordinator.cancel()
                            context.startService(Intent(context, RunService::class.java).setAction(RunService.ACTION_CANCEL))
                            onSelectModel(it)
                        },
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    templates.forEach { (label, value) ->
                        androidx.compose.material3.AssistChip(
                            onClick = { request = value },
                            label = { Text(label, maxLines = 1, softWrap = false) },
                        )
                    }
                }
                OutlinedTextField(
                    value = request,
                    onValueChange = { request = it.take(32 * 1024) },
                    label = { Text("任务（中文）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                OutlinedTextField(
                    value = systemMessage,
                    onValueChange = { systemMessage = it.take(AgentPreferences.MAX_SYSTEM_MESSAGE_CHARS) },
                    label = { Text("SystemMessage（可选）") },
                    supportingText = { Text("留空使用内置 Agent 规则；工具注入会默认追加在后面。") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    minLines = 4,
                    maxLines = 10,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            systemMessage = ""
                            AgentPreferences.save(context, "")
                            savedSystemMessage = ""
                        },
                        enabled = systemMessage.isNotEmpty() || savedSystemMessage.isNotEmpty(),
                    ) { Text("恢复默认") }
                    TextButton(
                        onClick = {
                            val normalized = AgentPreferences.normalize(systemMessage)
                            systemMessage = normalized
                            AgentPreferences.save(context, normalized)
                            savedSystemMessage = normalized
                        },
                        enabled = AgentPreferences.normalize(systemMessage) != savedSystemMessage,
                    ) { Text("保存 SystemMessage") }
                }
                OutlinedTextField(
                    value = selectedLog,
                    onValueChange = { selectedLog = it.take(32 * 1024) },
                    label = { Text("可选：粘贴一段日志") },
                    supportingText = { Text("仅本次 Agent 调用可读取") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    minLines = 2,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = {
                            selected?.let {
                                val normalized = AgentPreferences.normalize(systemMessage)
                                systemMessage = normalized
                                AgentPreferences.save(context, normalized)
                                savedSystemMessage = normalized
                                coordinator.start(context, it, settings, request, selectedLog, allowedTools, normalized)
                            }
                        },
                        enabled = selected != null && !ui.busy && !ui.agentActive,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (ui.agentTranscript.isEmpty()) "开始诊断" else "重新诊断") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                coordinator.cancel()
                                RunBus.update {
                                    it.copy(
                                        agentTranscript = emptyList(),
                                        agentTools = emptyList(),
                                        agentAllowedTools = emptySet(),
                                        agentPromptPreview = "",
                                        agentStatus = null,
                                        agentError = null,
                                        error = null,
                                    )
                                }
                            },
                            enabled = !ui.agentActive && ui.agentTranscript.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) { Text("清除结果") }
                        TextButton(
                            onClick = {
                                coordinator.cancel()
                                context.startService(Intent(context, RunService::class.java).setAction(RunService.ACTION_CANCEL))
                            },
                            enabled = ui.agentActive || ui.generating || ui.loading,
                            modifier = Modifier.weight(1f),
                        ) { Text("停止") }
                    }
                }
            }
            if (ui.agentActive || ui.agentTools.isNotEmpty() || ui.agentStatus != null) {
                item { AgentToolsCard(ui.agentStatus, ui.agentTools, ui, toolkitIds) }
            }
            items(ui.agentTranscript) { turn ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (turn.role == "user") "用户" else "Agent")
                    if (turn.text.isNotEmpty()) {
                        if (turn.role == "assistant") MarkdownText(turn.text) else Text(turn.text)
                    }
                }
            }
            if (ui.agentError != null) item { Text("错误：${ui.agentError}", color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        }
    }
}
