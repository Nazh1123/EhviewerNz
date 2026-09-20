# 数据库版本与导入兼容性

本 fork 的数据库版本从 `9` 调整为 `1_000_009`，合并 upstream 地点标签后递增为
`1_000_010`，生成器与 `DaoMaster` 使用相同版本。
后续 fork 迁移继续在此区间递增，不再与 upstream 共用小整数序列。

核对基准：upstream `BiLi_PC_Gamer` 的 `2c451bf3`（2026-09-03），数据库版本为 `8`。
upstream 的 `8` 新增 `Gallery_Tags.LOCATION`，旧 fork 的 `8` 新增
`QUICK_SEARCH.SUBSCRIBED`，旧 fork 的 `9` 新增 `DOWNLOADS.FIRST_GID`。
因此只比较版本大小不能判断字段是否已经存在。

## 迁移和导入

- 本地旧 fork 8/9/1,000,009 升级时保留原有数据；按实际字段补齐扩展列，
  包括 `Gallery_Tags.LOCATION`，默认值为 `NULL`。
- upstream 7/8 的导出数据库可导入。缺少的 `SUBSCRIBED` 默认填 `0`，
  `FIRST_GID` 默认填 `NULL`，并提示补充画廊版本信息。
- 旧导入流程可能将 upstream 8 改为版本 9 却遗漏 `SUBSCRIBED`，现在也能修复。
- 导入先复制备份，在临时副本的事务中迁移并检查 DAO 所需的表和列，成功后才开始合并。
  失败时关闭连接并删除临时文件，不改写用户原始备份。
- 保留旧版本 2–6 的历史迁移路径；未知版本（例如 10 或高于当前 fork 的版本）拒绝导入，
  不能因为其版本小于 `1_000_010` 就认为兼容。未来升级须补充迁移和回归用例。
- 结构检查可在合并前发现缺表、缺列；现有数据合并流程并非整体事务，
  运行中遇到其他数据或存储错误仍可能留下部分已导入数据。

upstream 8 的 `LOCATION` 现在会正常导入；如果目标中已有相同 GID 的标签缓存，
只补入缺失的地点标签，保留已有的其他标签，重复导入不会重复插入该缓存。
`BOOKMARKS` 表的导入仍是既有的 TODO；
界面书签使用的 `QUICK_SEARCH` 会正常导入。

## LOCATION 的含义

`Gallery_Tags.LOCATION` 是 E-Hentai 的 `location` 标签命名空间，
例如 `location:beach`（沙滩），短前缀为 `loc:`；多个标签以逗号分隔缓存。
它用于标签同步、翻译、搜索建议、标签选择，以及下载列表的标签信息。

这个字段不表示本地存储路径。本地下载仍通过下载根目录设置和
`DOWNLOAD_DIRNAME` 定位；本地导入的目录/压缩包通过 `ARCHIVE_URI`、
`LocalFolderGallerySource` 中的目录 URI 与相对路径定位，因此不复用地点标签。

## 导出方向

普通导出使用 fork 版本号并保留 fork 字段。交给 upstream 时使用“导出数据（兼容）”：
该选项现在输出版本 8 的结构，去掉 `SUBSCRIBED` 和 `FIRST_GID`，保留 `LOCATION`。
普通导出和兼容导出均复制 `Gallery_Tags` 中的标签缓存。
兼容导出面向 upstream 8，数据库版本仍为 7 的旧 upstream 不能读取此版本的备份。
普通导出仍不可直接交给 upstream。

## 验证

`EhDBCompatibilityTest` 使用从上述 upstream 提取的真实建表 SQL，
13 项测试覆盖 upstream 7/8 的实际导入、原备份不变、旧 fork 8/9/1,000,009 的升级、
重复迁移、旧流程遗留缺列、未知版本拒绝、迁移回滚、普通与兼容导出的地点标签保留、
兼容导出与 upstream 8 表结构的一致性，以及已有标签缓存的地点补全。
`EhTagDatabaseTest` 的 3 项测试覆盖字典读取、`location`/`loc:` 映射、翻译与搜索建议。

```powershell
.\gradlew.bat :app:testAppReleaseDebugUnitTest --tests com.hippo.ehviewer.EhDBCompatibilityTest --tests com.hippo.ehviewer.client.EhTagDatabaseTest
```

测试依赖升级至 Robolectric 4.16，按
[官方 Java 17+ 配置](https://robolectric.org/getting-started/)添加 JVM 参数以支持项目使用的 Java 21。
主机单元测试使用桌面版 Conscrypt，排除仅适用于 Android 的 JNI 依赖。

本次合并验证：上述 16 项测试全部通过，应用 Java/Kotlin 和 DAO 生成器编译通过。
未重跑完整单元测试；此前完整测试有其他用例在生产 Application 初始化时因
Windows 缺少 `image.dll` 失败。这两个测试类使用轻量 Application，避免启动图像原生库。
