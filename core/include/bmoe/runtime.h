// The engine entry point: compose model + streaming + generation from a RunConfig.
//
// run() loads the model with the layout the streamer requires (mmap on, no weight
// repack), discovers the MoE expert tensors through a one-token capture warm-up, binds
// them to the expert source, then greedily generates n_predict tokens — reporting each
// token to the optional callback/sink and returning a RunSummary. Greedy sampling makes
// the output a deterministic function of the graph, which is what the byte-identity
// gates rely on — true of every configuration except MoeStreamConfig::drop_cold_frac, which
// decides from live cache state and so is reproducible only within a single run.
#pragma once

#include "bmoe/config.h"
#include "bmoe/metrics.h"

#include <functional>
#include <string>

namespace bmoe {

class IRouteTraceSink;
class IComputeTraceSink;
class IIoTraceSink;

struct RunResult {
    bool ok = false;
    bool cancelled = false; // generation was interrupted by Session::cancel() (ok stays true)
    std::string error;
    std::string generated_text;
    // Exact text rendered by the model's chat template before tokenization. This is diagnostic
    // evidence only; it includes template-generated role/channel metadata and may be large.
    std::string rendered_prompt;
    // The reasoning span, when a thinking model's chat template separated it from the answer.
    // Empty otherwise (chat off, non-reasoning model, harmony no-think). Display-only; the answer
    // in generated_text already has it stripped. See TokenMetrics::reasoning.
    std::string reasoning_text;
    // OpenAI-compatible tool calls emitted by a structured chat template, encoded as a JSON array.
    // Empty for ordinary final answers and legacy prompt-only generations.
    std::string tool_calls_json;
    RunSummary summary;
    explicit operator bool() const { return ok; }
};

// Run one generation. `on_token` (nullable) is invoked once per generated token before
// the next decode; `sink` (nullable) receives the same per-token metrics plus the final
// summary. The trace sinks (all nullable) are diagnostics that perturb what they measure — see
// bmoe/route_trace.h and bmoe/decode_trace.h. Blocks until generation completes or errors.
RunResult run(const RunConfig & cfg,
              const std::function<void(const TokenMetrics &)> & on_token = nullptr,
              IMetricsSink * sink = nullptr,
              IRouteTraceSink * route_trace = nullptr,
              IComputeTraceSink * compute_trace = nullptr,
              IIoTraceSink * io_trace = nullptr);

} // namespace bmoe
