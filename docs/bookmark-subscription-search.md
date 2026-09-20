# 书签订阅搜索优化

后台更新检查和书签订阅列表共用 `BookmarkSubscriptionPlanner`，每个调度器最多同时执行 8 个请求。保存的书签内容和排列顺序保持不变。

## 合并规则

- 共同条件相同、仅上传者不同：`l:english uploader:"Alice"` 与 `l:english uploader:"Bob"` 合并为 `l:"english" uploader:"alice" uploader:"bob"`。上传者字段本身支持并集，不加 `~`。
- 共同条件相同、仅一个正向标签不同：保留共同条件，用 `~` 连接标签。例如 `l:english o:artbook$` 与 `l:english o:"full color$"` 合并为 `l:"english" ~o:"artbook$" ~o:"full color$"`。
- 关键词和其他条件相同、仅显式分类不同：包含分类位掩码取 OR。账号默认分类单独保留。
- 每组只允许一个变化维度，避免多条件交叉展开。查询最多使用 200 个 UTF-8 字节，超长分组继续拆分。无法保守识别的查询仍独立请求。
- `/tag/namespace:value` 按精确标签转换；上传者、标签路径原本忽略的高级筛选不会在转换时被激活。

上传者与分类归属由返回字段判断。标签强度、别名等由服务器决定，因此标签合并不靠本地标签猜测归属：用户点击“搜索对应书签”时，才使用原书签和 `next=gid+1` 验证目标 GID，验证请求也计入列表的 8 个并发槽位。失败时保留候选，允许再次操作。

## 元数据与缓存

- API 元数据按搜索上下文、GID 和 token 去重；重叠的在途查询等待同一份结果。继续使用原有每批最多 25 条的 API 请求。
- 元数据缓存有效期 30 秒、最多 512 条。成功结果共享，失败不进入缓存；每个使用方拿到独立的可变数组。
- “更新订阅”的自动检查和手动点击结果均可在 30 秒内被书签、全局订阅入口复用，按每个源首次请求时间判断新鲜度。检查中途进入列表也会保留已完整成功的源，缺失或失败的源重新请求。独立 EH 订阅页保持原有网络请求逻辑；列表拿到独立副本，避免收藏和布局修改污染缓存。
- 缓存上下文包含站点、Cookie、EH 配置、本地过滤规则及元数据显示设置，使用摘要作为键。手动刷新仍请求网络。
- 没有强制使用 `inline_set` 修改网页显示模式；减少补全请求采用共享元数据方案。

## 进度与失败处理

每个完整成功的源独立持久化单调递增的 GID 进度并提交未读项。其他源失败不会丢弃已成功源的进度；下一轮仍检查第一页，但不必重复扫描其已检查历史分页。失败或取消的源不推进进度，全组游标仅在全组成功时推进。

网络错误以及 HTTP 408、500、502、503、504 最多重试两次，延迟 2 秒、5 秒，保持当前分页位置。语法错误、权限错误和 HTTP 429 不进行这种短间隔重试。无 watched 标签是合法空订阅；损坏页面和查询语法警告不能当作成功的空结果。

## 验证

官方依据：[搜索语法](https://ehwiki.org/wiki/Gallery_Searching)、[My Tags](https://ehwiki.org/wiki/My_Tags)、[API](https://ehwiki.org/wiki/API)。之前的匿名搜索已验证共同条件、标签 OR 和分类并集；本轮另用 4 次匿名搜索验证 GID 定位的匹配、非匹配和标签路径。

针对性单元测试覆盖合并限制、归属、稳定分组、长度拆分、元数据并发去重与过期、失败恢复、缓存副本和独立进度。`SubscriptionCheckReuseTest` 另覆盖手动及自动更新后的跨入口请求去重、取消和部分失败、30 秒边界、上下文变化以及独立 EH 订阅入口不复用。

```powershell
.\gradlew.bat :app:testAppReleaseDebugUnitTest --tests '*BookmarkSubscriptionPlannerTest' --tests '*MergedUploaderSearchParserTest' --tests '*SubscriptionMetadataCacheTest' --tests '*SubscriptionProgressStoreTest' --tests '*SubscriptionCheckReuseTest' --tests '*GalleryApiParserTest' --no-daemon
```

尚未在手机上使用用户的真实书签集合测量请求节省比例及首屏时间。
