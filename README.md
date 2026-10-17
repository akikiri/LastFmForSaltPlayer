# Last.fm Scrobbler for Salt Player

[English](README_en.md)

一个供个人使用的 Salt Player for Windows Last.fm Scrobble 插件，基于
[spw-workshop-api](https://github.com/Moriafly/spw-workshop-api) `0.1.0-dev23`。

插件版本 `0.2.0`，需要 Salt Player for Windows `1.19.0` 或更新版本。

## 功能

- 自动发送 Now Playing。
- 设置中可开启“仅提交音乐库中的音乐”（默认关闭），仅限制 Scrobble，不影响 Now Playing。
  开关即时生效，曲库归属仅按播放器 `library.getTrackById` 查询结果判断，不检查文件或路径。
  未入库歌曲不新增收听记录；已有待提交记录会在发送前重新检查，不符合条件或没有歌曲 ID 的旧记录暂留本地。
  关闭开关后这些待提交记录可继续发送；曲库查询失败时不发送，不尝试其他判断方法。
- 按 Last.fm 规则提交 Scrobble：歌曲必须长于 30 秒，并实际播放至少一半或
  4 分钟（取较早者）。
- 暂停和跳转不会被计作实际收听时间。
- 使用歌曲开始播放的 UTC 时间作为 Scrobble 时间戳。
- 自动执行 Last.fm 桌面授权流程并持久化 Session Key。
- Scrobble 对临时网络错误、服务离线和限流进行有限指数退避；失败记录保存在本地待提交队列，重启后继续。
- Now Playing 不重试，并检查 Last.fm 的过滤结果；暂停、换曲和停用时取消尚未完成的请求。
- 歌曲元数据和时长仅通过播放器曲库 API 获取，不读取或解析音频文件。

## 安装

将 `.spmod` 文件导入播放器，启用时授予“音乐库读取”（`library-read`）权限。
没有权限时插件会报告启动失败。歌曲 ID、标题或艺术家缺失时不提交该曲；
时长缺失时可发送 Now Playing，但不提交 Scrobble。API 查询失败后不读取音频文件或尝试其他数据来源。

## 构建

1. 安装 JDK 25，并将 `JAVA_HOME` 指向该 JDK。项目使用 Kotlin 2.4.20 和 Gradle 9.8.0。
2. 复制 `local.properties.example` 为 `local.properties`，填入 Last.fm API key 和 shared secret。
3. 运行：

   ```powershell
   .\gradlew.bat clean test plugin
   ```

插件包由官方 Workshop Gradle 插件生成在
`build/libs/plugin-akikiri_LastFm_scrobble-0.2.0.spmod`。

也可以通过 Gradle 属性 `lastfm.api.key`、`lastfm.api.secret`，或者环境变量
`LASTFM_API_KEY`、`LASTFM_API_SECRET` 提供凭据。真实凭据不会被 Git 跟踪，但
最终插件包为了调用 Last.fm API 仍会包含它们。

## 首次授权

启用插件后，在插件设置中点击“登录 / 打开授权页”，在浏览器完成 Last.fm 授权。
插件每 5 秒检查一次授权结果，最多等待约 2 分钟，成功后显示已登录的用户名，
自动继续发送当前歌曲的 Now Playing 和待提交的 Scrobble，不需要换歌。

设置还提供“检查授权”“重新授权”“查看登录与提交状态”和“重试待提交记录”。
关闭了网页可重新打开同一个授权链接；等待超时后可以手动检查或重新打开。
重启时若仍有待授权 Token，会重新打开授权页并继续检查。

Session 被服务器明确判定失效后，会进入重新授权流程，待提交记录保留。
普通断网不会清除登录状态。被 Last.fm 永久拒收的记录保留为待处理，
不会自动反复发送；排查问题后可手动重试原记录，队列中的元数据不会随之后的标签编辑而改变。

Last.fm Token 有效期为 60 分钟。Session Key 默认长期有效，除非用户在 Last.fm
设置中撤销应用权限。

## 实现说明

- `PlaybackTracker` 只负责播放状态和 Scrobble 条件，不访问网络。
- `LastFmApiClient` 负责签名、HTTP、响应校验和重试。
- `AuthManager` 负责桌面授权状态机。
- `FileSessionStore` 使用临时文件和原子替换保存完整认证状态。
- `PluginRuntime` 管理每次启用独立的 `ScrobblerEngine`，停止时取消后台任务、HTTP 请求并释放连接。
- `PlayerTrackReader` 在后台通过 `WorkshopApi.playback.getCurrentMediaItem()` 查询当前歌曲；
  启用时主动查询，歌词和状态事件触发刷新，并每 2 秒核对一次。歌词加载本身不代表新的一次播放。
- `PlaybackTracker` 按歌曲 ID 与播放轮次保留进度，以单调时钟结合播放位置统计实际收听时间，
  从实际播放开始记录时间戳；同一首歌重载歌词不会清零，也不会重复提交。
- `PendingScrobbles` 将达到条件的记录原子保存到 `Last_Fm_Pending.json`，收到成功确认后移除；
  `ScrobbleSubmitter` 按时间顺序发送，网络重试与 Now Playing 相互独立。
- `LoginController` 独立处理配置按钮和有时限的授权检查，歌曲播放不负责触发登录。
- 登录和队列文件位于 `%APPDATA%/Salt Player for Windows/workshop/data/`，沿用旧版 Session 文件路径。
