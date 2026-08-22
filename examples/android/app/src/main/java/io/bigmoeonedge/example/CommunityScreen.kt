package io.bigmoeonedge.example

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Model community discovery page. Ranking metadata is kept separate from the install catalog. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(onBack: () -> Unit, onOpenAgent: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var ranking by remember { mutableStateOf<CommunityRanking?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh) {
        loading = true
        error = null
        CommunityRankingRepository.fetch(query = query)
            .onSuccess { ranking = it }
            .onFailure { error = it.message ?: "加载社区榜单失败" }
        loading = false
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型社区榜单") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = { TextButton(onClick = onOpenAgent) { Text("Agent") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("从 ModelScope 获取公开模型热度数据。榜单只是发现入口，不会自动下载或替换本地模型。")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(80) },
                        label = { Text("搜索模型（可选）") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Button(onClick = { refresh++ }, enabled = !loading) { Text(if (loading) "加载中" else "刷新") }
                }
                error?.let { Text("$it（可继续重试）") }
                ranking?.let { Text("${it.source} · ${it.models.size} 条 · 刷新时间 ${it.fetchedAtMs}") }
            }
            items(ranking?.models.orEmpty(), key = { it.url }) { model ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("#${model.rank}  ${model.name}")
                    Text(
                        "下载 ${formatCommunityCount(model.downloads)} · 喜欢 ${formatCommunityCount(model.likes)}" +
                            (model.updatedAt?.let { " · 更新 $it" } ?: ""),
                    )
                    model.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    Text(model.url, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
