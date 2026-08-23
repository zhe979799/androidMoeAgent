package io.bigmoeonedge.example

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Choose optional capability groups before entering the Agent workspace. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ToolkitScreen(context: Context, onBack: () -> Unit, onOpenAgent: () -> Unit) {
    var enabled by remember { mutableStateOf(ToolkitPreferences.load(context)) }
    var exaApiKey by remember { mutableStateOf(SearchPreferences.loadExaApiKey(context)) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工具集") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = { TextButton(onClick = onOpenAgent) { Text("进入 Agent") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("当前已启用 ${enabled.size}/${ToolkitCatalog.entries.size} 个工具集", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "进入 Agent 工作台后，确认任务并点击开始诊断。已启用的能力会保存到本机。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    enabled = ToolkitCatalog.allIds
                                    ToolkitPreferences.save(context, enabled)
                                },
                            ) { Text("全部启用") }
                            TextButton(
                                onClick = {
                                    enabled = emptySet()
                                    ToolkitPreferences.save(context, enabled)
                                },
                            ) { Text("全部停用") }
                        }
                    }
                }
            }
            if ("web_search" in enabled) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Exa API Key（可选）", style = MaterialTheme.typography.titleSmall)
                            androidx.compose.material3.OutlinedTextField(
                                value = exaApiKey,
                                onValueChange = {
                                    exaApiKey = it.take(256)
                                    SearchPreferences.saveExaApiKey(context, exaApiKey)
                                },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "百度和 Bing 不需要密钥；Exa 只有在此处填写后才会执行。密钥不会注入提示词。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            ToolkitCatalog.entries.forEach { toolkit ->
                item(key = toolkit.id) {
                    Surface(
                        color = if (toolkit.id in enabled) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = if (toolkit.id in enabled) 1.dp else 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = toolkit.id in enabled,
                                enabled = toolkit.tools.isNotEmpty(),
                                role = Role.Switch,
                            ) {
                                enabled = if (toolkit.id in enabled) enabled - toolkit.id else enabled + toolkit.id
                                ToolkitPreferences.save(context, enabled)
                            },
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(toolkit.title, style = MaterialTheme.typography.titleMedium)
                                    Text(toolkit.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = toolkit.id in enabled,
                                    onCheckedChange = null,
                                    enabled = toolkit.tools.isNotEmpty(),
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                toolkit.tools.forEach { tool ->
                                    Text(
                                        "${ToolkitCatalog.toolTitle(tool)} · ${ToolkitCatalog.toolSummary(tool)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                Button(onClick = onOpenAgent, modifier = Modifier.fillMaxWidth()) {
                    Text("进入 Agent 工作台")
                }
            }
        }
    }
}
