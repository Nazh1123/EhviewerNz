# EhViewerNz 2.0.2.4.1

#### 基于 [EhViewer[xiaojieonly] 2.0.2.4](https://github.com/xiaojieonly/Ehviewer_CN_SXJ/releases/tag/2.0.2.4)

## Fork 更新内容

### 画廊更新

- 画廊更新逻辑现在使用画廊的最初版本画廊gid (first_gid)作为版本链依据 (原逻辑通过检查依次检查各个父画廊gid)。
- 新增“更新已下载画廊的版本信息”按钮，扫描还未添加first_gid信息的已下载画廊并网络请求信息。
- 新增“删除存在已下载新版本的旧版本画廊”按钮，扫描拥有相同first_gid的画廊并只保留可阅读的完整最新版本画廊，即删除同版本链旧画廊。

### 杂项
