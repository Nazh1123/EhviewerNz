# 本地 GGUF 翻译调用

## GGUF 导入与存储

模型管理的“选择 GGUF 文件”先选择“复制到应用私有目录”或“直接调用外部文件”，再打开系统文件选择器。选择方式随 Fragment 状态保存，文件选择器取消不改变模型。

复制模式沿用 `noBackupFilesDir/translation-llm/<SHA-256>.gguf`。引用模式保存系统返回的 URI 和持久读取权限，不复制权重；首次导入仍完整计算 SHA-256 并实际加载校验。预设模型身份、采样和翻译结果缓存继续使用内容摘要，不依赖文件名或存储方式。

每次加载引用时重新打开只读文件描述符，JNI 复制该描述符并通过 `fdopen`、`llama_model_load_from_file_ptr` 加载。保留默认 mmap 策略，原生 Session 在释放模型后关闭它拥有的 FILE；Kotlin 打开的描述符在加载完成或失败后关闭。不支持普通文件、随机读取或 mmap 的提供者导入失败，用户可以改用复制模式，不做隐式复制。

引用保存文件大小和数据修改时间，每次检查或重载时验证。原文件被删除、权限丢失或检测到修改后需重新选择。重启可能改变设备号或 inode，共享存储的权限/扩展属性更新会改变 ctime，因此这些信息不作为持久引用的有效性条件；原生前缀缓存仍用实际文件身份区分会话。正在使用的原文件不可改写或截断。

同一内容切换为引用时，验证和保存成功后移除已有私有副本；切回复制时释放不再使用的 URI 读取权限。移除引用仅删除登记和权限，不删除用户原始 GGUF。界面标记“外部文件”，应用文件占用只统计私有副本。

验证：`NativeModelReferenceTest` 覆盖权限、模式切换、源文件改变/丢失、取消和失败保留；`NativeTranslationDeviceTest.referencesAndReloadsExternalFixture` 用显式 `nativeModelPath` 验证真实 GGUF 推理、重载及原文件保留；`nativeLoaderRejectsNonSeekableExternalDescriptor` 验证管道拒绝；`ModelManagementUiDeviceTest.choosesStorageMethodBeforeOpeningDocumentPicker` 检查两种方式先弹选择框再打开系统选择器。

2026-10-11 本轮验证：上述存储与既有协议/持久化相关 JVM 测试 77 项通过；vivo V2227A / Android 16 的导入方式界面检查、管道拒绝、Hy-MT2 与漫画微调两个预设的引用/推理/重载检查通过。Debug、测试和 Release APK（含 release vital lint）构建通过。两份预设直接从固定 Hugging Face 修订下载到设备共享目录，文件大小及 SHA-256 与模型目录一致。

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

## 设置页单句试译

本地 GGUF 和 API 的设置页试译共用 `LlmSampleTranslation` 的单条 user 提示词及响应校验，不走漫画页编号协议。因此服务直接返回纯译文时不会误报 `Parsed 0/1 regions`。语言方向、繁体约束、多行文本、思考块清理和完整代码块包装共用同一处理；也接受有效的单个编号 `<|1|>`。

空译文、未闭合思考块或代码块、错误/重复/未完成编号仍返回失败。API 的 HTTP、认证、温度兼容和取消机制沿用原传输；服务报告 `length` 或 `content_filter` 时不接受截断文本。本地试译保留输出预算和重复生成检查，在完整响应后由共用解析器校验编号；漫画页仍执行原有流式编号检查。此改动只涉及设置页诊断，不变更阅读结果缓存身份。

## 验证入口

- JVM：`NativeAdaptiveBatchTest`、`NativeTranslationProtocolTest`、`NativeTranslationCheckpointTest`。
- 原生模板：`src/test/cpp/translation_chat_template_test.cpp`，链接固定 llama.cpp 的 `common/jinja/{lexer,parser,runtime,value,string}.cpp`、`common/json.cpp`、`common/unicode.cpp`；include 路径为 `common`、`vendor`、`ggml/include`。
- 真机：`NativeGenerationChunkDeviceTest`（显式参数 `testNativeLlm=true`），比较逐 token / 分片的完整字节、EOS、1/5 token 预算截断及最多 4 token 边界。使用设备已导入模型，不下载或替换用户模型。

这些边界验证不等同于模型质量或端到端提速对照；后者需要同一组漫画页、同模型和同设备的完整评测。

2026-10-11 验证记录：应用 JVM 435 项、引擎 JVM 18 项全部通过；原生 Jinja 主机测试通过；Debug、测试和 Release APK 构建及 Release 16 KiB/Android 26 静态检查通过。vivo V2227A / Android 16 的 HY-MT1.5 1.8B Q4_K_M 真机检查 8 项通过，1 项外部导入样本测试因未提供路径跳过；Hy-MT2 权重未在该设备上评测。

AndroidJUnitRunner 默认在测试之间清理 Activity，可能表现为回到桌面；vivo 随后可能冻结后台进程。运行上述真机测试时须保持调试版前台，不把后台冻结耗时作为推理耗时或提速依据。
