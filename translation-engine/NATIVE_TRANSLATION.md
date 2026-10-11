# 本地 GGUF 翻译调用

## 请求与批量

所有模型继续使用 `NativeTranslator.buildNumberedMessages` 的完整漫画翻译规则和编号原文。单区域兼容重试不切换为设置页面的短句试译指令。

本地适配器按当前 GGUF 分词器计量原文，每批最多 8 个区域、约 512 个原文 token（另为每个区域留 8 token 编号开销）。超过上限的单个区域仍进入已有上下文检查，不能被丢弃。`begin` 按完整模板输入和原有输出预算做最终上下文检查；失败后仍可二分。

当前模型和语言方向的适配器保留批量学习状态。多区域请求的上下文、输出预算或编号失败将区域上限降至失败批次的一半。有效的主请求连续成功后逐步恢复：单区域需要 8 次，其他批量需要 3 次，上限每次加 1，最多 8。单区域失败重置成功计数；兼容重试不算批量成功。状态跨模型卸载保留，适配器关闭/语言身份变化后重置，不写入用户设置。

歧义编号前缀仍全部重试；截断和非连续遗漏仍按原有可靠区域边界恢复。原始索引贯穿分块、二分、空白/OCR 候选过滤及请求内检查点。

## 采样

采样按模型内容摘要识别，文件名不能触发预设：

| 模型 | temperature | top-k | top-p | repeat penalty |
|---|---:|---:|---:|---:|
| 已校验 HY-MT1.5 / Hy-MT2 1.8B Q4_K_M | 0.7 | 20 | 0.6 | 1.05 |
| 已校验 Hy-MT2 日语→简中漫画微调 Q4_K_M | 0.15 | 20 | 0.6 | 1.05 |
| 其他导入模型 | 确定性贪心 | — | — | — |

已知模型的采样链为 penalties → top-k → top-p → temperature → seeded distribution，不加 min-p。重复惩罚窗口为最后 64 个生成 token，频率/存在惩罚为 0。随机种子固定为 42，每次请求重置采样器；固定种子不代表不同设备必定产生相同译文。原有重复生成检测仍有效。

参数来源：[HY-MT1.5](https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF)、[Hy-MT2](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF)、[漫画微调模型](https://huggingface.co/fumetodev/Hy-MT2-1.8B-JP-Manga-Finetune-chinese-zh-Hans-v1-GGUF)。

## 模板、缓存和流式返回

直接编译固定 llama.cpp 提交中的独立 Jinja 解释器，不启用 HTTP/server/common 整体库。Jinja 元数据按实际模板执行；只有内置模板名元数据使用旧命名模板入口。模板探针确认 user 文本被保留，并检查 system 是受支持、抛错还是静默丢弃。后两者由已有 user-only 路径拼接完整系统规则；模板未支持的思考控制不会被凭空改写。

模板变量包含 `enable_thinking=false`，支持此变量的模型关闭思考生成。处理 Jinja 已输出与分词器自动添加的 BOS/EOS 重复。模板时间固定为该模型实例创建时的真实时间。原有 `<think>` 清理保留，以兼容强制思考的模板。

固定前缀缓存继续按实际 token 序列匹配，最多两项/32 MiB。不支持空 user 消息的模板可正常推理，但不保存这种探针的快照。

生产生成每个 JNI 返回最多 4 token，原生每次 decode 后检查 32 ms 时间窗。因此取消检查的额外间隔最多为这个窗口加一次不可中断的 decode；没有承诺单次 decode 的耗时上限。预填充仍保持原有 32/128 token 取消边界。token 数通过复用的状态数组随片段返回，不为进度再跨 JNI 查询。协议检查按生成 token 计量（每约 16 token），不按片段计量。

EOS 或输出预算在片段中间出现时先返回已生成字节，再返回结束/抛预算异常，防止丢掉片段尾部的可靠区域。逐 token `next` 保留用于诊断和字节对照。结果缓存身份升至 v17；OCR/预处理身份未因本轮调用改动升级。

## 验证入口

- JVM：`NativeAdaptiveBatchTest`、`NativeTranslationProtocolTest`、`NativeTranslationCheckpointTest`。
- 原生模板：`src/test/cpp/translation_chat_template_test.cpp`，链接固定 llama.cpp 的 `common/jinja/{lexer,parser,runtime,value,string}.cpp`、`common/json.cpp`、`common/unicode.cpp`；include 路径为 `common`、`vendor`、`ggml/include`。
- 真机：`NativeGenerationChunkDeviceTest`（显式参数 `testNativeLlm=true`），比较逐 token / 分片的完整字节、EOS、1/5 token 预算截断及最多 4 token 边界。使用设备已导入模型，不下载或替换用户模型。

这些边界验证不等同于模型质量或端到端提速对照；后者需要同一组漫画页、同模型和同设备的完整评测。

2026-10-11 验证记录：应用 JVM 435 项、引擎 JVM 18 项全部通过；原生 Jinja 主机测试通过；Debug、测试和 Release APK 构建及 Release 16 KiB/Android 26 静态检查通过。vivo V2227A / Android 16 的 HY-MT1.5 1.8B Q4_K_M 真机检查 8 项通过，1 项外部导入样本测试因未提供路径跳过；Hy-MT2 权重未在该设备上评测。

AndroidJUnitRunner 默认在测试之间清理 Activity，可能表现为回到桌面；vivo 随后可能冻结后台进程。运行上述真机测试时须保持调试版前台，不把后台冻结耗时作为推理耗时或提速依据。
