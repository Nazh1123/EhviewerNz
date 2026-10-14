# 可选 PP-OCRv5 识别组件

APK 保留 ONNX Runtime 1.24.3 的 Java API（约 121 KB），不再包含它的两份原生库。日语 NCNN OCR 不依赖此组件。中文、英文、韩文在运行库和对应语言模型都安装后使用 PP-OCR；缺少任一文件时保持原有 NCNN 回退行为。

“翻译设置 → 模型管理 → 漫画模型”提供独立组件卡片，可下载、导入或删除。下载语言模型会先补齐缺少的运行库。组件卸载保留语言模型；已加载的原生代码无法从进程卸载，但后续识别不再选择未安装的组件。进程退出后释放已加载代码。

语言识别模型的 `.onnx` 可选择复制导入或直接引用外部文件。引用模式验证固定大小、SHA-256、随机访问与只读映射能力，持久保存读取授权，并通过映射缓冲区创建会话；不会生成私有模型副本。源文件被移动、删除、修改或授权失效后需要重新选择，移除引用保留源文件。此选项仅用于权重文件；运行组件 ZIP 仍须解压、校验并安装到应用私有目录。

## 下载与安装

默认下载 Maven Central 的固定版本官方 AAR（40,948,335 字节），不依赖尚未发布的项目 Release。完整 AAR 的大小和 SHA-256 验证成功后，只解压 arm64-v8a 下的 `libonnxruntime.so`、`libonnxruntime4j_jni.so`。两份库再单独校验；暂存文件在打开写入描述符后立即设为只读，通过校验后在模型维护锁内替换。安装目录位于应用私有、不参与备份的目录。下载取消或校验失败会清理暂存文件，不覆盖已有组件。

`src/main/assets/ppocr-runtime.json` 固定版本、ABI、AAR 和原生库校验值。APK 中的 Java API 来自同一 AAR，Gradle 只提取 `classes.jar` 并排除上游 `OnnxRuntime.class`。`src/main/java/ai/onnxruntime/OnnxRuntime.java` 保留 v1.24.3 上游源文件及 MIT 声明，仅修改 Android 加载分支：存在显式运行库目录时，先从该目录加载核心库和 JNI 库。上游 Android 分支忽略 native path/skip 属性，因此不能只配置属性而继续使用原始加载器。

升级运行库时必须同步更新 Gradle 版本、上游 Java 文件和 manifest，重新生成组件包并执行真机测试。保留原生库 16 KB 页对齐及依赖检查。

## 生成可导入的组件 ZIP

```sh
./gradlew :translation-engine:copyPpOcrRuntimeArchive
python tools/package_ppocr_runtime.py translation-engine/build/onnx-archive/onnxruntime-android-1.24.3.aar
```

输出：`artifacts/ppocr-runtime/ehviewer-ppocr-runtime-1.24.3-arm64-v8a.zip`，9,480,775 字节；安装后共 25,943,608 字节。ZIP 根目录只有两份固定库，附带独立 SHA-256 文件及许可声明。用户导入此 ZIP，无需导入 AAR。CI 的翻译发布验证流程同时生成和上传此组件构件。

单元测试覆盖官方下载、非 arm64 内容过滤、完整包和单库校验、缺失文件、路径穿越、取消、失败后保留已有安装，以及模型管理入口。发布校验拒绝 APK 内出现 ONNX Runtime 原生库。

本地发布包由 37,105,514 字节降为 27,638,249 字节，减少 9,467,265 字节。相关 app 回归测试 24 项和 translation-engine 单元测试通过；发布 APK 的 ABI、原生依赖、16 KB 页对齐及编译边界检查通过。连接设备拒绝安装测试 APK，因此本次未完成真机推理验证。

真机 OCR 测试需先导入组件，再运行 `PpOcrDeviceTest`；也可通过 instrumentation 参数 `ppOcrRuntimeArchive` 指定设备上组件 ZIP 路径。原有 `testPpOcr=true` 与 `ppOcrDirectory` 参数保持不变。
