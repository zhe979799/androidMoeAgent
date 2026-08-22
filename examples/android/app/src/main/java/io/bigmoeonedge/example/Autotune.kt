package io.bigmoeonedge.example

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/** Candidate and order policy for the foreground, output-preserving Android tuner. */
object AutotunePlanner {
    const val MAX_CANDIDATES = 4
    const val REPETITIONS = 2
    const val MAX_TOKENS = 48

    data class Candidate(val id: String, val settings: AppSettings)
    data class Trial(val candidate: Candidate, val repetition: Int, val order: Int)

    fun candidates(base: AppSettings): List<Candidate> {
        val fixed = base.copy(
            nExpertUsed = 0, dropColdPct = 0, routeAhead = 0,
            prefetchLayers = 0, predictPrefetch = false, predictSpecMax = 0,
            spec = AppSettings.SPEC_OFF,
        )
        return listOf(
            Candidate("current", fixed),
            Candidate("cache-3000", fixed.copy(cacheMb = 3000).takeIf { !it.mmap } ?: fixed),
            Candidate("io-2", fixed.copy(ioThreads = 2).takeIf { !it.mmap } ?: fixed),
            Candidate("io-8", fixed.copy(ioThreads = 8).takeIf { !it.mmap } ?: fixed),
        ).distinctBy { it.settings.sessionSignature("") }.take(MAX_CANDIDATES)
    }

    /** Two shuffled blocks put every candidate once in each half, reducing thermal/order bias. */
    fun schedule(base: AppSettings, seed: Int): List<Trial> {
        val ids = candidates(base)
        if (ids.isEmpty()) return emptyList()
        val first = ids.shuffled(Random(seed))
        val second = first.asReversed()
        return (first + second).mapIndexed { index, candidate ->
            Trial(candidate, repetition = if (index < ids.size) 1 else 2, order = index)
        }
    }

    fun recommend(results: List<AutotuneResult>): Candidate? {
        val eligible = results.filter { it.status == "complete" && it.outputMatches && it.tokensPerSecond > 0 }
        return eligible.groupBy { it.candidate.id }
            .filterValues { values -> values.size >= REPETITIONS }
            .mapNotNull { (_, values) ->
                val median = values.map { it.tokensPerSecond }.sorted()[values.size / 2]
                values.first().candidate to median
            }.maxWithOrNull(compareBy<Pair<Candidate, Double>> { it.second }.thenBy { it.first.settings.cacheMb })?.first
    }
}

data class AutotuneResult(
    val candidate: AutotunePlanner.Candidate,
    val repetition: Int,
    val order: Int,
    val csv: String?,
    val outputMatches: Boolean,
    val tokensPerSecond: Double,
    val temperatureStart: Double?,
    val temperatureEnd: Double?,
    val status: String,
    val error: String? = null,
)

data class AutotuneState(
    val running: Boolean = false,
    val status: String = "",
    val results: List<AutotuneResult> = emptyList(),
    val report: File? = null,
    val recommendation: AutotunePlanner.Candidate? = null,
)

/** Runs only bounded, greedy, non-lossy performance variants and reports rather than applies one. */
class AutotuneCoordinator(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(AutotuneState())
    val state: StateFlow<AutotuneState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(context: Context, model: File, base: AppSettings) {
        if (job?.isActive == true) return
        job = scope.launch {
            val schedule = AutotunePlanner.schedule(base, seed = (System.currentTimeMillis() and 0x7fffffff).toInt())
            val results = ArrayList<AutotuneResult>()
            var reference: String? = null
            _state.value = AutotuneState(running = true, status = "Preparing ${schedule.size} balanced trials…")
            try {
                schedule.forEachIndexed { index, trial ->
                    _state.value = _state.value.copy(status = "Trial ${index + 1}/${schedule.size}: ${trial.candidate.id}")
                    if (index > 0) coolDown()
                    val result = runTrial(context, model, trial, reference)
                    if (reference == null && result.status == "complete") reference = RunBus.state.value.lastCompletedText
                    results += result
                    _state.value = _state.value.copy(results = results.toList())
                    ContextCompat.startForegroundService(
                        context, Intent(context, RunService::class.java).setAction(RunService.ACTION_SHUTDOWN),
                    )
                }
                val recommendation = AutotunePlanner.recommend(results)
                val report = writeReport(context, model, base, results, recommendation)
                _state.value = AutotuneState(false, if (recommendation == null) "No output-matching winner" else "Recommendation ready", results, report, recommendation)
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(running = false, status = "Cancelled")
                throw e
            } catch (e: Throwable) {
                _state.value = _state.value.copy(running = false, status = "Failed: ${e.message}")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun runTrial(context: Context, model: File, trial: AutotunePlanner.Trial, reference: String?): AutotuneResult {
        val settings = trial.candidate.settings.copy(metricsCsv = true, nPredict = AutotunePlanner.MAX_TOKENS)
        val csv = uniqueCsv(context)
        val before = RunBus.state.value.generationId
        val startTemp = RunBus.state.value.cpuTempC
        launchPrompt(context, model, AUTOTUNE_PROMPT, settings, null, true, csvPath = csv)
        val terminal = RunBus.state.first { it.generationId > before || (!it.busy && it.error != null) }
        val text = terminal.lastCompletedText
        val match = reference == null || text == reference
        val ok = terminal.error == null && text.isNotEmpty()
        return AutotuneResult(trial.candidate, trial.repetition, trial.order, csv, match,
            terminal.telemetry.avgTokensPerSecond, startTemp, terminal.cpuTempC,
            if (ok) "complete" else "failed", terminal.error)
    }

    private suspend fun coolDown() {
        val limit = System.currentTimeMillis() + 15_000
        val initial = RunBus.state.value.cpuTempC
        while (System.currentTimeMillis() < limit) {
            val current = RunBus.state.value.cpuTempC
            if (initial == null || current == null || current <= initial + 2.0) return
            delay(500)
        }
    }

    private fun uniqueCsv(context: Context): String? {
        val first = AppSettings.newMetricsCsvPath(context) ?: return null
        var file = File(first)
        var suffix = 1
        while (file.exists()) file = File(first.removeSuffix(".csv") + "-$suffix.csv").also { suffix++ }
        return file.absolutePath
    }

    private fun writeReport(context: Context, model: File, base: AppSettings, results: List<AutotuneResult>, recommendation: AutotunePlanner.Candidate?): File {
        val root = File(context.getExternalFilesDir(null) ?: error("app storage unavailable"), "metrics")
        check(root.isDirectory || root.mkdirs())
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val report = File(root, "autotune-$stamp.csv")
        report.printWriter().use { out ->
            out.println("# bmoe_autotune v1")
            out.println("# model=${model.name} prompt_sha256=${sha256(AUTOTUNE_PROMPT)} seed_schedule=balanced repetitions=${AutotunePlanner.REPETITIONS} max_tokens=${AutotunePlanner.MAX_TOKENS}")
            out.println("# quality_knobs=normalized_to_output_preserving_values n_expert_used=0 drop_cold=0 route_ahead=0 prefetch=0 predict_prefetch=0 spec=off")
            out.println("# baseline=${argv(AutotunePlanner.candidates(base).first().settings, model)}")
            out.println("# recommendation=${recommendation?.id ?: "none"}")
            out.println("candidate,repetition,order,csv,output_matches,tok_s,temp_start_c,temp_end_c,status,error,settings")
            results.forEach { r ->
                out.println(listOf(r.candidate.id, r.repetition, r.order, r.csv?.let(::File)?.name ?: "", r.outputMatches,
                    r.tokensPerSecond, r.temperatureStart ?: "", r.temperatureEnd ?: "", r.status, r.error ?: "", argv(r.candidate.settings, model)).joinToString(",") { csvEscape(it.toString()) })
            }
        }
        return report
    }

    private fun argv(settings: AppSettings, model: File): String = settings.sessionArgv("bmoe-cli", model.name, null).joinToString(" ") { if (it == model.name) "<model>" else it }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun csvEscape(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' }) "\"${value.replace("\"", "\"\"")}\"" else value

    companion object { const val AUTOTUNE_PROMPT = "Reply with exactly: BMOE autotune reference 7." }
}
