// Thinking-budget tests. The governor is pure (the caller tokenizes the end tag), so the policy is
// checked here without a model, and the span detection against the REAL vendored templates, which
// is where the marker strings come from. The engine names no marker for any family.
//
// Assertions are explicit (not <cassert>) because the Release gates build with NDEBUG.

#include "think_budget.h"
#include "thinking_control.h"

#include "chat.h"
#include "common.h"

#include <algorithm>
#include <cstdio>
#include <exception>
#include <fstream>
#include <sstream>
#include <string>

#if !defined(BMOE_TMPL_QWEN3) || !defined(BMOE_TMPL_GEMMA4) || !defined(BMOE_TMPL_LFM2)
#error "template paths must be defined by the build"
#endif

using bmoe::detail::ThinkGovernor;
using bmoe::detail::ThinkSpan;

static int failures = 0;

static void expect(const char * name, bool ok, const std::string & detail = {}) {
    if (ok) {
        std::printf("[PASS] %s\n", name);
    } else {
        std::printf("[FAIL] %s%s%s\n", name, detail.empty() ? "" : "\n  ", detail.c_str());
        ++failures;
    }
}

static common_chat_templates_ptr load(const char * path) {
    std::ifstream in(path, std::ios::binary);
    std::ostringstream ss;
    ss << in.rdbuf();
    return common_chat_templates_init(/*model*/ nullptr, ss.str());
}

static common_chat_params render(const common_chat_templates * tmpls, bool think) {
    common_chat_msg user;
    user.role = "user";
    user.content = "hi";
    common_chat_templates_inputs inputs;
    inputs.messages = {user};
    inputs.add_generation_prompt = true;
    inputs.use_jinja = true;
    inputs.enable_thinking = think;
    inputs.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
    return common_chat_templates_apply(const_cast<common_chat_templates *>(tmpls), inputs);
}

// A small hand-made span, so the policy cases do not depend on any template's wording.
static ThinkSpan span(bool open_at_start) {
    ThinkSpan s;
    s.start = "<t>";
    s.ends = {"</t>"};
    s.open_at_start = open_at_start;
    return s;
}

static const std::vector<llama_token> FORCED = {901, 902};

// Feeds `n` one-character reasoning pieces, asking the governor at each sampling point.
static int run_until_cut(ThinkGovernor & g, int n, bool end_requested = false) {
    llama_token out = 0;
    for (int i = 0; i < n; ++i) {
        if (g.override_token(end_requested, out)) return i;
        g.feed("x");
    }
    return -1;
}

int main() {
    try {
        // The span comes from the template: the tags and whether the prompt already opened the span.
        {
            auto t = load(BMOE_TMPL_QWEN3);
            const ThinkSpan s = bmoe::detail::think_span_from(render(t.get(), true));
            expect("qwen3: span found", s.valid(), "start='" + s.start + "'");
            expect("qwen3: the bare closer is recognised too, the template's spelling is forced first",
                   s.ends.front().find("</think>") != std::string::npos &&
                       std::find(s.ends.begin(), s.ends.end(), "</think>") != s.ends.end());
            expect("qwen3: the prompt opens the span (the model resumes inside it)", s.open_at_start);
            const ThinkSpan off = bmoe::detail::think_span_from(render(t.get(), false));
            expect("qwen3: thinking off closes the span in the prompt", !off.open_at_start);
        }
        {
            auto t = load(BMOE_TMPL_GEMMA4);
            const ThinkSpan s = bmoe::detail::think_span_from(render(t.get(), true));
            expect("gemma4: span found", s.valid(), "start='" + s.start + "'");
            expect("gemma4: the model opens its own span", !s.open_at_start);
        }
        {
            auto t = load(BMOE_TMPL_LFM2);
            const ThinkSpan s = bmoe::detail::think_span_from(render(t.get(), true));
            // Whatever LFM2 declares, an invalid span must leave a governor inert.
            ThinkGovernor g(s, FORCED, 0);
            llama_token out = 0;
            expect("lfm2: a model without a usable span is never overridden",
                   s.valid() || (!g.active() && !g.override_token(true, out)));
        }

        // Policy.
        {
            ThinkGovernor g(span(true), FORCED, 5);
            const int at = run_until_cut(g, 20);
            expect("open at start: cut after exactly the budget", at == 5, "cut at " + std::to_string(at));
            expect("open at start: marked cut", g.cut());
        }
        {
            ThinkGovernor g(span(false), FORCED, 3);
            g.feed("<t>");
            const int at = run_until_cut(g, 20);
            expect("model-opened span: counting starts after the opener", at == 3, "cut at " + std::to_string(at));
        }
        {
            ThinkGovernor g(span(false), FORCED, 3);
            llama_token out = 0;
            bool any = false;
            for (int i = 0; i < 30; ++i) {
                any |= g.override_token(false, out);
                g.feed("y");
            }
            expect("no span opened, nothing forced", !any && !g.cut());
        }
        {
            ThinkGovernor g(span(true), FORCED, 5);
            g.feed("a");
            g.feed("b");
            g.feed("</t>");
            expect("natural end before the budget: no force", run_until_cut(g, 50) < 0 && !g.cut());
            expect("natural end: tokens counted up to and including the closer", g.span_tokens() == 3);
        }
        {
            ThinkGovernor g(span(true), FORCED, 5);
            g.feed("<");
            g.feed("/t");
            g.feed(">");
            expect("a closer split across pieces is recognised", run_until_cut(g, 20) < 0 && !g.cut());
        }
        {
            ThinkGovernor g(span(true), FORCED, -1);
            expect("no budget: never cut on its own", run_until_cut(g, 1000) < 0);
            llama_token out = 0;
            expect("no budget: end_thinking still cuts", g.override_token(true, out) && out == FORCED[0]);
        }
        {
            ThinkGovernor g(span(true), FORCED, 100);
            g.feed("a");
            llama_token out = 0;
            const bool first = g.override_token(true, out);
            expect("end_thinking cuts at once", first && out == FORCED[0] && g.cut());
            expect("the whole tag is delivered, one token per step",
                   g.forcing() && g.override_token(false, out) && out == FORCED[1]);
            expect("then the governor lets go", !g.forcing() && !g.override_token(true, out));
            g.feed("</t>");
            expect("and does not cut again", !g.override_token(true, out));
        }
        {
            ThinkGovernor g(span(false), FORCED, 100);
            llama_token out = 0;
            expect("end_thinking before the span opens waits", !g.override_token(true, out));
            g.feed("<t>");
            expect("then cuts as soon as it opens", g.override_token(true, out) && out == FORCED[0]);
        }
        {
            // Never leave half a character before the forced tag.
            ThinkGovernor g(span(true), FORCED, 1);
            g.feed("\xE2\x82"); // two bytes of a three-byte character
            llama_token out = 0;
            expect("waits for the end of a multi-byte character", !g.override_token(false, out));
            g.feed("\xAC");
            expect("then cuts", g.override_token(false, out));
        }
        {
            ThinkGovernor g;
            llama_token out = 0;
            expect("default governor is inert", !g.active() && !g.override_token(true, out));
        }
    } catch (const std::exception & e) {
        std::printf("[FAIL] exception: %s\n", e.what());
        ++failures;
    }
    std::printf("%d failure(s)\n", failures);
    return failures == 0 ? 0 : 1;
}
