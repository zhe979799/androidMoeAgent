package io.bigmoeonedge.example

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AutotuneScreen(context: Context, settings: AppSettings, model: File?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val coordinator = remember(scope) { AutotuneCoordinator(scope) }
    val state by coordinator.state.collectAsState()
    DisposableEffect(coordinator) { onDispose { coordinator.cancel() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Performance tune") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Runs four bounded, output-preserving settings variants twice in balanced order. Each trial starts a fresh session and writes its own engine CSV.")
            Text("Quality-changing settings are held at output-preserving values for this comparison: model top-k, dropping, route-ahead, prefetch and speculation are disabled for every trial. Your saved settings are not changed.")
            Button(
                onClick = { model?.let { coordinator.start(context, it, settings) } },
                enabled = model != null && !state.running,
            ) { Text("Start comparison") }
            if (state.running) Button(onClick = coordinator::cancel) { Text("Cancel") }
            Text(state.status)
            state.results.forEach { result ->
                Text("${result.order + 1}. ${result.candidate.id}: ${if (result.status == "complete") String.format("%.2f tok/s", result.tokensPerSecond) else result.status}" +
                    if (!result.outputMatches) " (output differed)" else "")
            }
            state.recommendation?.let { Text("Recommendation: ${it.id}. Settings were not changed; apply it manually after reviewing the CSV.") }
            state.report?.let { Text("Report: ${it.name}") }
        }
    }
}
