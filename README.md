# Last.fm Scrobbler for Salt Player

一个供个人使用的 Salt Player for Windows Last.fm Scrobble 插件，基于
[spw-workshop-api](https://github.com/Moriafly/spw-workshop-api) `0.1.0-dev20`。

## 功能

- 自动发送 Now Playing。
- 按 Last.fm 规则提交 Scrobble：歌曲必须长于 30 秒，并实际播放至少一半或
  4 分钟（取较早者）。
- 暂停和跳转不会被计作实际收听时间。
- 使用歌曲开始播放的 UTC 时间作为 Scrobble 时间戳。
- 自动执行 Last.fm 桌面授权流程并持久化 Session Key。
- 对临时网络错误、服务离线和限流进行有限指数退避。

## 构建

1. 复制 `local.properties.example` 为 `local.properties`。
2. 填入 Last.fm API key 和 shared secret。
3. 运行：

   ```powershell
   .\gradlew.bat clean test plugin
   ```

插件包生成在 `build/libs/plugin-akikiri_LastFm_scrobble-0.1.0.zip`。

也可以通过 Gradle 属性 `lastfm.api.key`、`lastfm.api.secret`，或者环境变量
`LASTFM_API_KEY`、`LASTFM_API_SECRET` 提供凭据。真实凭据不会被 Git 跟踪，但
最终插件包为了调用 Last.fm API 仍会包含它们。

## 首次授权

首次开始播放歌曲时，插件会请求授权 Token 并打开 Last.fm 页面。授权完成后，
切换到下一首歌曲；插件会交换并保存 Session Key，之后无需重复授权。

Last.fm Token 有效期为 60 分钟。Session Key 默认长期有效，除非用户在 Last.fm
设置中撤销应用权限。

## 实现说明

- `PlaybackTracker` 只负责播放状态和 Scrobble 条件，不访问网络。
- `LastFmApiClient` 负责签名、HTTP、响应校验和重试。
- `AuthManager` 负责桌面授权状态机。
- `FileSessionStore` 使用临时文件和原子替换保存完整认证状态。
- `PluginRuntime` 提供插件级协程生命周期，停止插件时取消后台任务。
- dev20 的 `MediaItem` 尚未提供歌曲时长；插件会从本地音频文件读取时长，
  读取成功后将其用于 Now Playing 和 Scrobble 判断。网络流或无法解析的文件
  因无法可靠判断 Last.fm 阈值而不会提交 Scrobble。
