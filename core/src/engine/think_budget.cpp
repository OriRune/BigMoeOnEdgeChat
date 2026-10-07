#include "think_budget.h"

#include <algorithm>

namespace bmoe::detail {

ThinkSpan think_span_from(const common_chat_params & cp) {
    ThinkSpan span;
    if (cp.thinking_start_tag.empty() || cp.thinking_end_tags.empty()) return span;
    span.start = cp.thinking_start_tag;
    for (const std::string & e : cp.thinking_end_tags)
        if (!e.empty()) span.ends.push_back(e);
    if (span.ends.empty()) return ThinkSpan{};

    // A template's closer may carry the newline it renders before it ("\n</think>"), but a model does
    // not always write that newline. Recognise the bare tag as well; the first entry, as the template
    // spells it, stays the one that is forced.
    for (size_t i = 0, n = span.ends.size(); i < n; ++i) {
        const std::string & e = span.ends[i];
        const size_t a = e.find_first_not_of(" \t\r\n");
        const size_t b = e.find_last_not_of(" \t\r\n");
        if (a == std::string::npos) continue;
        const std::string bare = e.substr(a, b - a + 1);
        if (std::find(span.ends.begin(), span.ends.end(), bare) == span.ends.end()) span.ends.push_back(bare);
    }

    // The prompt leaves the model inside the span when its last opener has no closer after it.
    const size_t open_at = cp.generation_prompt.rfind(span.start);
    if (open_at != std::string::npos) {
        bool closed = false;
        for (const std::string & e : span.ends)
            if (cp.generation_prompt.find(e, open_at) != std::string::npos) closed = true;
        span.open_at_start = !closed;
    }
    return span;
}

ThinkGovernor::ThinkGovernor(ThinkSpan span, std::vector<llama_token> forced, int budget)
    : active_(span.valid() && !forced.empty()), span_(std::move(span)), forced_(std::move(forced)), budget_(budget) {
    if (active_ && span_.open_at_start) state_ = State::Open;
}

void ThinkGovernor::feed(const std::string & piece) {
    if (!active_ || state_ == State::Closed) return;
    tail_ += piece;

    // Keep just enough text to find a tag split across pieces and to judge the last character.
    auto trim = [&] {
        size_t keep = std::max(span_.start.size(), (size_t) 4);
        for (const std::string & e : span_.ends)
            keep = std::max(keep, e.size());
        if (tail_.size() > keep) tail_.erase(0, tail_.size() - keep);
    };
    auto closes = [&] {
        for (const std::string & e : span_.ends)
            if (tail_.find(e) != std::string::npos) return true;
        return false;
    };

    if (state_ == State::Idle) {
        const size_t at = tail_.find(span_.start);
        if (at == std::string::npos) {
            trim();
            return;
        }
        // The piece that opened the span may carry reasoning (even its end) after the tag; that rest
        // is not counted as a token of its own.
        tail_.erase(0, at + span_.start.size());
        state_ = State::Open;
        tokens_ = 0;
        if (closes()) {
            state_ = State::Closed;
            tail_.clear();
        } else {
            trim();
        }
        return;
    }

    // Open: this piece is one token of reasoning, the last one if it closes the span.
    ++tokens_;
    if (closes()) {
        state_ = State::Closed;
        tail_.clear();
        return;
    }
    trim();
}

bool ThinkGovernor::utf8_complete() const {
    // A forced tag after half a character would leave broken bytes in the reasoning text.
    const size_t n = tail_.size();
    for (size_t back = 1; back <= 3 && back <= n; ++back) {
        const unsigned char c = (unsigned char) tail_[n - back];
        if ((c & 0xC0) == 0x80) continue; // a continuation byte: keep looking for its lead
        const size_t need = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
        return back >= need;
    }
    return true;
}

bool ThinkGovernor::override_token(bool end_requested, llama_token & out) {
    if (!active_) return false;
    if (forcing_) {
        out = forced_[next_++];
        if (next_ >= forced_.size()) {
            forcing_ = false;
            // The span is over from here on, whether or not the tag's text is recognised on the way back.
            state_ = State::Closed;
        }
        return true;
    }
    if (cut_ || state_ != State::Open) return false;
    const bool due = end_requested || (budget_ >= 0 && tokens_ >= budget_);
    if (!due || !utf8_complete()) return false;
    cut_ = true;
    forcing_ = true;
    next_ = 0;
    return override_token(end_requested, out);
}

} // namespace bmoe::detail
