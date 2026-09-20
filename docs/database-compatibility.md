# 数据库版本与导入兼容性

本 fork 的数据库版本从 `9` 调整为 `1_000_009`，生成器与 `DaoMaster` 使用相同版本。
后续 fork 迁移继续在此区间递增，不再与 upstream 共用小整数序列。

核对基准：upstream `BiLi_PC_Gamer` 的 `2c451bf3`（2026-09-03），数据库版本为 `8`。
upstream 的 `8` 新增 `Gallery_Tags.LOCATION`，旧 fork 的 `8` 新增
`QUICK_SEARCH.SUBSCRIBED`，旧 fork 的 `9` 新增 `DOWNLOADS.FIRST_GID`。
因此只比较版本大小不能判断字段是否已经存在。

## 迁移和导入

- 本地旧 fork 8/9 升级到新区间时保留原有数据；按实际字段补齐 fork 扩展列。
- upstream 7/8 的导出数据库可导入。缺少的 `SUBSCRIBED` 默认填 `0`，
  `FIRST_GID` 默认填 `NULL`，并提示补充画廊版本信息。
- 旧导入流程可能将 upstream 8 改为版本 9 却遗漏 `SUBSCRIBED`，现在也能修复。
- 导入先复制备份，在临时副本的事务中迁移并检查 DAO 所需的表和列，成功后才开始合并。
  失败时关闭连接并删除临时文件，不改写用户原始备份。
- 保留旧版本 2–6 的历史迁移路径；未知版本（例如 10 或高于当前 fork 的版本）拒绝导入，
  不能因为其版本小于 `1_000_009` 就认为兼容。未来升级须补充迁移和回归用例。
- 结构检查可在合并前发现缺表、缺列；现有数据合并流程并非整体事务，
  运行中遇到其他数据或存储错误仍可能留下部分已导入数据。

导入兼容针对双方共有的数据模型。upstream 8 的 `LOCATION` 暂无 fork 对应属性，
不会合并到目标库；原始备份中仍保留它。`BOOKMARKS` 表的导入仍是既有的 TODO；
界面书签使用的 `QUICK_SEARCH` 会正常导入。

## 导出方向

普通导出使用 fork 版本号并保留 fork 字段。交给 upstream 时使用“导出数据（兼容）”：
该选项继续输出版本 7 的结构，去掉 `SUBSCRIBED` 和 `FIRST_GID`，
由 upstream 8 自行执行 7→8 的迁移。大版本号本身不意味着普通导出可反向导入 upstream。

## 验证

`EhDBCompatibilityTest` 使用从上述 upstream 提取的真实建表 SQL，
10 项测试覆盖 upstream 7/8 的实际导入、原备份不变、旧 fork 8/9 的本地升级、
重复迁移、旧流程遗留缺列、未知版本拒绝、迁移回滚和兼容导出的往返迁移。

```powershell
.\gradlew.bat :app:testAppReleaseDebugUnitTest --tests com.hippo.ehviewer.EhDBCompatibilityTest
```

测试依赖升级至 Robolectric 4.16，按
[官方 Java 17+ 配置](https://robolectric.org/getting-started/)添加 JVM 参数以支持项目使用的 Java 21。
主机单元测试使用桌面版 Conscrypt，排除仅适用于 Android 的 JNI 依赖。

本次验证：上述 10 项数据库测试全部通过。完整单元测试共 150 项，122 项通过，
另外 28 项在生产 Application 初始化时因 Windows 缺少 `image.dll` 失败，未进入测试逻辑。
