package io.bigmoeonedge.example

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Every tunable the engine exposes, grouped by what it is for. Changes apply to [current] live and
 * the caller persists them.
 *
 * Each category shows the recommended configuration first and folds the rest into an
 * [ExperimentalGroup]: the levers measured on one device, measured once, or still owed a
 * measurement. They stay in the release build because testing them on other hardware is what this
 * app is for, and a lever nobody can reach is a lever nobody can refute.
 *
 * Labels keep the flag's own vocabulary, so a setting here can be matched against the CLI, the CSV
 * preamble and the docs. Descriptions say what the knob does in one or two lines and carry no
 * measured figures: a number would need the device, the model and the day beside it to mean
 * anything, and the docs are where that fits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(current: AppSettings, onChange: (AppSettings) -> Unit, onBack: () -> Unit) {
    // Reported by the loaded session at BMOE_READY. "none" means this model reasons no matter what
    // it is asked, so the Thinking switch is shown disabled with the reason rather than left there
    // pretending to work (#82). Null = nothing loaded yet, so nothing is claimed either way.
    val ui by RunBus.state.collectAsStateWithLifecycle()
    val thinkingLocked = ui.thinkControl == "none"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val stream = !current.mmap
        val cacheOn = current.cacheMb == AppSettings.CACHE_AUTO || current.cacheMb > 0

        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("流式加载") {
                // mmap is the no-streaming baseline. When on, every streaming knob below is
                // inert (the CLI omits --moe-stream and all sub-flags), so they are disabled.
                SwitchRow(
                    "mmap 基线（不流式加载）",
                    "按普通方式加载模型，作为性能对照基线。",
                    current.mmap,
                ) { onChange(current.copy(mmap = it)) }

                IntSetting(
                    "专家缓存（MiB）", AppSettings.CACHE_CHOICES, current.cacheMb,
                    format = {
                        when (it) {
                            AppSettings.CACHE_AUTO -> "自动"
                            0 -> "关闭"
                            else -> "$it MiB"
                        }
                    },
                    enabled = stream,
                ) { onChange(current.copy(cacheMb = it)) }
                Hint(
                    "常驻专家无需重复读取，缓存越大，等待闪存的时间越少，但会占用更多内存。自动模式只在加载时计算一次。过小的档位低于引擎下限，只会反复抖动。"
                )
                IntSetting(
                    "自动缓存上限（MiB）", AppSettings.CACHE_CEIL_CHOICES, current.cacheCeilMb,
                    format = { if (it == 0) "不限制" else "$it MiB" },
                    enabled = stream && current.cacheMb == AppSettings.CACHE_AUTO,
                ) { onChange(current.copy(cacheCeilMb = it)) }
                Hint(
                    "限制自动模式可以申请的缓存大小。系统会把应用自己映射的权重计为可用空间，不设上限时可能申请超过实际余量。"
                )
                IntSetting("并行 I/O 通道", AppSettings.IO_CHOICES, current.ioThreads, enabled = stream) {
                    onChange(current.copy(ioThreads = it))
                }
                Hint("同时进行的专家读取数量，直到闪存带宽饱和后便不会继续加速。")
                SwitchRow(
                    "直接 I/O（O_DIRECT）",
                    "绕过页缓存，避免系统为缓存中的内容保留第二份副本；不支持时会自动回退。",
                    current.oDirect, enabled = stream,
                ) { onChange(current.copy(oDirect = it)) }
                SwitchRow(
                    "I/O 与计算重叠",
                    "当前层计算时提前发起下一批读取，让闪存延迟隐藏在计算过程之后。",
                    current.overlap, enabled = stream,
                ) { onChange(current.copy(overlap = it)) }
                LabeledDropdown(
                    "稠密权重",
                    DenseWeights.values().map { it.label },
                    current.denseWeights.ordinal,
                    enabled = stream,
                ) { onChange(current.copy(denseWeights = DenseWeights.values()[it])) }
                Hint(current.denseWeights.blurb)

                ExperimentalGroup {
                    IntSetting(
                        "时序预取（层数）", AppSettings.PREFETCH_CHOICES, current.prefetchLayers,
                        format = { if (it == 0) "关闭" else "$it" },
                        // Mutually exclusive with predictive prefetch: two predictors would speculate
                        // the same future twice, and the engine refuses the pair.
                        enabled = stream && cacheOn && !current.predictPrefetch && current.routeAhead == 0,
                    ) { onChange(current.copy(prefetchLayers = it)) }
                    Hint(
                        "假设当前层会复用上一个 token 的专家，并在空闲通道提前读取；需要开启缓存。"
                    )
                    SwitchRow(
                        "预测式预取",
                        "提前运行下一层自己的路由器，并预取它选中的专家；比简单猜测更准确，会替代上面的时序预取。需要开启缓存。",
                        current.predictPrefetch,
                        enabled = stream && cacheOn && current.prefetchLayers == 0 && current.routeAhead == 0,
                    ) { onChange(current.copy(predictPrefetch = it)) }
                    if (current.predictPrefetch) {
                        IntSetting(
                            "预测缺失专家的提前读取数量", AppSettings.PREDICT_SPEC_CHOICES,
                            current.predictSpecMax,
                            format = { if (it == 0) "仅保留" else "$it" },
                            enabled = stream && cacheOn,
                        ) { onChange(current.copy(predictSpecMax = it)) }
                        Hint(
                            "仅保留模式不会读取数据，只保护预测到的专家不被驱逐；这是更安全的设置，因为提前读取会和当前 token 争抢 I/O。"
                        )
                    }
                }
            }

            Section("速度 / 质量") {
                IntSetting(
                    "丢弃冷专家（均分比例）", AppSettings.DROP_COLD_CHOICES, current.dropColdPct,
                    format = {
                        when (it) {
                            0 -> "关闭"
                            50 -> "50%（影响较小）"
                            75 -> "75%（推荐）"
                            100 -> "100%（最快，质量损失最大）"
                            else -> "$it%"
                        }
                    },
                    // Needs the streamer and a live cache: it asks the source what is resident.
                    enabled = stream && cacheOn,
                ) { onChange(current.copy(dropColdPct = it)) }
                Hint(
                    "只有当路由到的专家不在缓存中，且路由器对它的偏好很低时才跳过；质量损失只发生在需要闪存读取的地方。常驻专家始终执行，最高权重的专家不会被丢弃。该设置会改变回答，而且结果取决于缓存当时的内容。"
                )
                // The threshold is a share of the even split, so a narrow routing changes what the
                // same percentage means. Only shown once a model reports its width.
                val topk = ui.nExpertUsed
                if (current.dropColdPct > 0 && topk != null && topk in 1..4) {
                    Text(
                        "该模型每个 token 路由的专家很少，因此相同比例会影响更多输出。请检查回答，或在此关闭该选项。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                    )
                }
                IntSetting(
                    "活动专家数（top-k）", AppSettings.N_EXPERT_CHOICES, current.nExpertUsed,
                    format = { if (it == 0) "模型默认" else "$it" },
                ) { onChange(current.copy(nExpertUsed = it)) }
                Hint(
                    "每个 token 使用的专家少于模型原本请求的数量，同时减少计算和读取，但会改变回答。"
                )

                ExperimentalGroup {
                    LabeledDropdown(
                        "提前猜测",
                        listOf("关闭", "模型自带预测头（MTP）", "重复文本（n-gram）"),
                        AppSettings.SPEC_CHOICES.indexOf(current.spec).coerceAtLeast(0),
                        // Excluded by route-ahead, which declines to commit across a wider verify
                        // pass while still paying for its prediction.
                        enabled = current.routeAhead == 0,
                    ) { onChange(current.copy(spec = AppSettings.SPEC_CHOICES[it])) }
                    Hint(
                        "先草拟接下来的 token，再一次性验证，只保留模型本来会输出的内容。无损，但更宽的验证会让每层触碰更多专家；预测头只在部分模型可用，n-gram 查找免费且适用于所有模型，但只在文本重复时生效。"
                    )
                    if (current.spec != AppSettings.SPEC_OFF) {
                        IntSetting(
                            "每轮猜测 token 数", AppSettings.MTP_DRAFT_CHOICES, current.mtpDraft,
                        ) { onChange(current.copy(mtpDraft = it)) }
                        Hint(
                            "每轮猜得越多，单次解码处理的 token 越多，但草稿可靠性会下降，错误草稿仍然需要付出成本；最佳值通常不是最大值。"
                        )
                    }
                    if (current.spec == AppSettings.SPEC_MTP) {
                        IntSetting(
                            "仅在有把握时猜测", AppSettings.MTP_P_MIN_CHOICES, current.mtpPMinPct,
                            format = { if (it == 0) "始终猜测" else "$it%以上" },
                        ) { onChange(current.copy(mtpPMinPct = it)) }
                        Hint(
                            "预测头不确定时停止草拟。没有草拟就能保持验证批次更窄，从而减少读取的专家数量。"
                        )
                    }
                    IntSetting(
                        "提前路由（层数）", AppSettings.ROUTE_AHEAD_CHOICES, current.routeAhead,
                        format = { if (it == 0) "关闭" else "$it" },
                        // Excludes both prefetchers and speculation; needs the streamer, and the cache
                        // is what turns a committed selection into early reads.
                        enabled = stream && current.prefetchLayers == 0 && !current.predictPrefetch &&
                            current.spec == AppSettings.SPEC_OFF,
                    ) { onChange(current.copy(routeAhead = it)) }
                    Hint(
                        "提前提交每层路由，让专家更早开始读取且不会浪费读取结果。该设置有损，部分槽位会改变路由；不能和预取、提前猜测同时使用。"
                    )
                }
            }

            Section("计算") {
                IntSetting("计算线程", AppSettings.THREAD_CHOICES, current.threads) {
                    onChange(current.copy(threads = it))
                }
                IntSetting("生成 token 数", AppSettings.NPREDICT_CHOICES, current.nPredict) {
                    onChange(current.copy(nPredict = it))
                }
                IntSetting("上下文（token）", AppSettings.CTX_CHOICES, current.sessionCtx) {
                    onChange(current.copy(sessionCtx = it))
                }
                Hint(
                    "会话可以容纳的提示词和回答长度，也决定 KV cache 的内存大小。对于已经占满内存的模型，缩短上下文会把空间让给专家缓存和稠密权重。修改后会重新打开会话。"
                )
            }

            Section("提示词") {
                SwitchRow(
                    "思考",
                    if (thinkingLocked)
                        "该模型始终进行思考且没有关闭方式，因此开关会被禁用而不是假装生效。思考内容仍会显示在回答上方。"
                    else
                        "让支持思考的模型先进行推理，推理内容会显示在回答上方。不支持思考的模型不受影响。",
                    // Locked reads ON, not OFF: the model reasons on every turn, and that is what
                    // the switch should be showing whatever the stored preference says.
                    checked = current.thinking || thinkingLocked,
                    enabled = !thinkingLocked,
                ) { onChange(current.copy(thinking = it)) }
            }

            Section("诊断") {
                SwitchRow(
                    "指标 CSV",
                    "每个会话生成一个 CSV，记录每 token 的耗时、缺页、缓存预算和内存驻留位置。下次会话生效，可从菜单分享。",
                    current.metricsCsv,
                ) { onChange(current.copy(metricsCsv = it)) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
