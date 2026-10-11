#pragma once

#include "jinja/lexer.h"
#include "jinja/parser.h"
#include "jinja/runtime.h"
#include "json.h"
#include <functional>
#include <stdexcept>
#include <string>
#include <vector>

namespace ehnz {
struct ChatMessage {
    std::string role, content;
};

/** Execute the model's Jinja, rather than guessing a built-in template by substrings. */
class TranslationChatTemplate {
    std::string source, bos, eos;
    jinja::program program;
    std::time_t template_time = std::time(nullptr);
    std::function<std::string(const std::vector<ChatMessage> &)> alias_renderer;
public:
    TranslationChatTemplate(std::string text, std::string bosToken, std::string eosToken,
        std::function<std::string(const std::vector<ChatMessage> &)> aliasRenderer = {})
        : source(std::move(text)), bos(std::move(bosToken)), eos(std::move(eosToken)) {
        if (source.find("{{") == std::string::npos && source.find("{%") == std::string::npos && aliasRenderer) {
            // Preserve metadata containing a built-in name such as "chatml". Jinja never takes this route.
            alias_renderer = std::move(aliasRenderer);
        } else {
            jinja::lexer lexer;
            program = jinja::parse_from_tokens(lexer.tokenize(source));
        }
        // A syntactically valid template can silently omit messages.
        const std::string probe = "EHNZ_USER_PROBE_4d712e";
        if (render({{"user", probe}}).find(probe) == std::string::npos)
            throw std::runtime_error("GGUF chat template discards the user message");
    }

    bool supportsSystem() const {
        const std::string probe = "EHNZ_SYSTEM_PROBE_4d712e";
        try {
            return render({{"system", probe}, {"user", "EHNZ_USER_PROBE"}}).find(probe) != std::string::npos;
        } catch (const std::exception &) { return false; }
    }

    std::string render(const std::vector<ChatMessage> &messages) const {
        if (alias_renderer) return alias_renderer(messages);
        auto list = common_json::array();
        for (const auto &message : messages)
            list.push_back(common_json{{"role", message.role}, {"content", message.content}});
        jinja::context ctx(source);
        // Stable during this model load; never replace a real date with a synthetic template date.
        ctx.current_time = template_time;
        jinja::global_from_json(ctx, common_json{
            {"messages", list}, {"bos_token", bos}, {"eos_token", eos},
            {"add_generation_prompt", true}, {"enable_thinking", false},
        }, true);
        jinja::runtime runtime(ctx);
        auto parts = jinja::runtime::gather_string_parts(runtime.execute(program));
        return parts->as_string().str();
    }
};
} // namespace ehnz
