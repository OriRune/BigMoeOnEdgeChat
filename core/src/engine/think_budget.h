#pragma once

// A budget for the reasoning span, enforced at the sampling point.
//
// A thinking model can spend an entire reply budget inside its reasoning and never answer. The
// templates have no "low effort" level to ask for (Qwen3.x and Gemma 4 read one boolean), so the
// limit has to be applied to the stream: once the span has run for N tokens, or the caller asks for
// the answer now, the span's end tag is forced and the model continues past it.
//
// The marker strings are never spelled out here. They come from the rendered chat template
// (`thinking_start_tag` / `thinking_end_tags` of common_chat_params), the same source
// thinking_control.cpp reads, so a new model family needs no change.
//
// Internal header: includes llama.cpp's `common` (not the stable public API). See docs/seam.md.

#include "chat.h"

#include "llama.h"

#include <string>
#include <vector>

namespace bmoe::detail {

// Where a template's reasoning span starts and ends, and whether the prompt has already opened it.
struct ThinkSpan {
    std::string start;
    std::vector<std::string> ends; // any of these closes the span; the first is the one that is forced
    bool open_at_start = false;    // the generation prompt ends inside the span (the model resumes inside it)

    bool valid() const { return !start.empty() && !ends.empty(); }
};

// Read the span off an applied template. Not valid (so nothing is enforced) for a model that
// declares no span, such as one whose reasoning is a structural channel.
ThinkSpan think_span_from(const common_chat_params & cp);

// A model can write the closing tag more than once in a row (a pruned Gemma 4 writes it three times
// after an empty span), and the chat parser then reads none of the turn: no reasoning, no answer. This
// keeps the first closer and drops the ones that follow it back to back, in the text handed to the
// parser only; generation, the KV cache and the sampler never see the change. No span, or no repeat,
// returns the text as it is.
std::string collapse_repeated_ends(const std::string & raw, const ThinkSpan & span);

// Follows one generation's text, counts the tokens spent inside the span, and says when to take over
// the sampling. Pure: the caller tokenizes the end tag and passes the ids in, so the policy is
// unit-tested without a model.
class ThinkGovernor {
public:
    ThinkGovernor() = default; // inactive: never overrides anything
    // `forced` is the end tag as tokens; `budget` < 0 means no token limit (end_thinking may still
    // cut the span).
    ThinkGovernor(ThinkSpan span, std::vector<llama_token> forced, int budget);

    bool active() const { return active_; }

    // The piece of every emitted token, in order.
    void feed(const std::string & piece);

    // At each sampling point: true when `out` must replace the sampled token. `end_requested` is the
    // caller's "answer now". Once started, the whole end tag is delivered, one token per call.
    bool override_token(bool end_requested, llama_token & out);

    // True while forced tokens are still waiting, so a drafting loop knows not to run ahead of them.
    bool forcing() const { return forcing_; }

    bool in_span() const { return state_ == State::Open; }
    bool cut() const { return cut_; }
    int span_tokens() const { return tokens_; }

private:
    enum class State { Idle, Open, Closed };

    bool utf8_complete() const;

    bool active_ = false;
    ThinkSpan span_;
    std::vector<llama_token> forced_;
    int budget_ = -1;

    State state_ = State::Idle;
    int tokens_ = 0;
    bool cut_ = false;
    bool forcing_ = false;
    size_t next_ = 0;  // index into forced_ while forcing
    std::string tail_; // the last few bytes seen, to find tags that straddle pieces
};

} // namespace bmoe::detail
