#include "../../main/cpp/translation_chat_template.h"
#include <cassert>
#include <cstdlib>
#include <iostream>

// common/json.cpp uses GGML_ASSERT; host template tests need no GGML backend.
extern "C" void ggml_abort(const char *, int, const char *, ...) { std::abort(); }

int main() {
    using ehnz::TranslationChatTemplate;
    const std::string basic = "{{ bos_token }}{% for m in messages %}"
        "[{{ m.role }}]{{ m.content }}{% endfor %}"
        "{% if add_generation_prompt %}[assistant]{% endif %}";
    TranslationChatTemplate full(basic, "<s>", "</s>");
    assert(full.supportsSystem());
    assert(full.render({{"system", "完整规则"}, {"user", "<|1|>你好😀"}}) ==
        "<s>[system]完整规则[user]<|1|>你好😀[assistant]");

    const std::string userOnly = "{% for m in messages %}{% if m.role == 'user' %}"
        "{{ m.content }}{% endif %}{% endfor %}";
    TranslationChatTemplate silentDrop(userOnly, "", "");
    assert(!silentDrop.supportsSystem());
    assert(silentDrop.render({{"user", "规则\n\n<|1|>原文"}}) == "规则\n\n<|1|>原文");

    TranslationChatTemplate strict("{% for m in messages %}{% if m.role == 'system' %}"
        "{{ raise_exception('system unsupported') }}{% endif %}{{ m.content }}{% endfor %}", "", "");
    assert(!strict.supportsSystem());

    TranslationChatTemplate thinking(basic + "{% if enable_thinking %}<think>{% else %}"
        "<think></think>{% endif %}", "", "");
    assert(thinking.render({{"user", "原文"}}) == "[user]原文[assistant]<think></think>");

    bool rejected = false;
    try { TranslationChatTemplate missingUser("{% if true %}assistant{% endif %}", "", ""); }
    catch (const std::runtime_error &) { rejected = true; }
    assert(rejected);
    std::cout << "Translation chat template tests passed\n";
}
