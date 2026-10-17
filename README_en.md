# Last.fm Scrobbler for Salt Player

[简体中文](README.md)

A personal-use Last.fm scrobbling plugin for Salt Player for Windows, built on
[spw-workshop-api](https://github.com/Moriafly/spw-workshop-api) `0.1.0-dev23`.

Plugin version: `0.2.0`. Requires Salt Player for Windows `1.19.0` or later.

## Features

- Automatically sends Now Playing updates.
- Optional library-only scrobbling, disabled by default. Changes take effect immediately
  and affect Scrobbles only, not Now Playing. Library membership is determined solely
  through the player's `library.getTrackById` API, without checking files or paths.
  Tracks outside the library do not generate new listening records. Existing queued
  records are checked again before submission; excluded records and older records
  without a track ID remain stored locally. Disabling the option allows those queued
  records to be submitted again. A failed library query never triggers an alternative lookup.
- A track must be longer than 30 seconds and actually played for at least half its
  duration or four minutes, whichever comes first, to qualify for scrobbling.
- Pauses and seeks do not count toward listening time.
- Uses the UTC timestamp of when playback actually started for each Scrobble.
- Supports Last.fm desktop authorization and persists the Session Key.
- Uses bounded exponential backoff for temporary Scrobble failures, service downtime,
  and rate limits. Failed records are stored locally and survive restarts.
- Now Playing requests are not retried. The plugin checks Last.fm's filtering result
  and cancels unfinished requests when playback pauses, the track changes, or the plugin is disabled.
- Track metadata and duration come exclusively from the player API. The plugin does
  not read or parse audio files.

## Installation

Download the `.spmod` file from [Releases](https://github.com/akikiri/LastFmForSaltPlayer/releases)
and import it into the player. Grant the **Library read** (`library-read`) permission
when enabling the plugin. Startup fails if permission is not granted.

Tracks without an ID, title, or artist are not submitted. If the duration is missing,
the plugin can send Now Playing but cannot scrobble the track. Failed API queries
never fall back to audio-file inspection or another data source.

## Settings translation

The settings interface and plugin notifications are currently in Simplified Chinese.
The settings group is **Last.fm 账号与提交 — Last.fm account and submissions**.
The controls appear in this order, from top to bottom:

| Order | Chinese label | English translation | Description |
| --- | --- | --- | --- |
| 1 | 仅提交音乐库中的音乐 | Only scrobble tracks in the music library | Only submit listening records for tracks found through the library API. Does not affect Now Playing. Disabled by default. |
| 2 | 登录 / 打开授权页 | Sign in / Open authorization page | Authorize the plugin in your browser; it checks for successful sign-in automatically. |
| 3 | 检查授权 | Check authorization | Check the result immediately after completing authorization in your browser. |
| 4 | 重新授权 | Reauthorize | Clear the locally saved sign-in state and reopen the Last.fm authorization page. |
| 5 | 查看登录与提交状态 | View sign-in and submission status | Show the current sign-in state and number of pending records. |
| 6 | 重试待提交记录 | Retry pending records | Resend the original failed records. Records already confirmed as successful are not sent again. |

## First-time authorization

Enable the plugin, then click **登录 / 打开授权页 — Sign in / Open authorization page**
in its settings and authorize it on Last.fm in your browser.

The plugin checks the authorization result every five seconds for up to about two
minutes. On success, it displays your username and automatically resumes the current
track's Now Playing update and pending Scrobbles; changing tracks is not necessary.

If you close the browser page, you can reopen the same authorization link. If automatic
checking times out, check manually or reopen the page. After a restart, a pending
authorization token causes the page to reopen and checking to resume.

If Last.fm explicitly invalidates the session, the plugin starts reauthorization and
retains pending records. An ordinary network outage does not clear your sign-in state.
Records permanently rejected by Last.fm remain stored for manual handling rather than
being retried automatically. After resolving the problem, you can retry the original
records. Later tag edits do not change metadata already saved in the queue.

Last.fm tokens are valid for 60 minutes. Session Keys normally remain valid until
you revoke the application's access in your Last.fm settings.

## Building

1. Install JDK 25 and point `JAVA_HOME` to it. The project uses Kotlin 2.4.20 and Gradle 9.8.0.
2. Copy `local.properties.example` to `local.properties` and enter your Last.fm API key and shared secret.
3. Run:

   ```powershell
   .\gradlew.bat clean test plugin
   ```

The official Workshop Gradle plugin creates:

`build/libs/plugin-akikiri_LastFm_scrobble-0.2.0.spmod`

Credentials can also be supplied through the Gradle properties `lastfm.api.key` and
`lastfm.api.secret`, or the environment variables `LASTFM_API_KEY` and `LASTFM_API_SECRET`.
Real credentials are not tracked by Git, but the final plugin package still contains
them so it can call the Last.fm API.

## Implementation notes

- `PlaybackTracker` handles playback state and Scrobble eligibility without network access.
- `LastFmApiClient` handles request signing, HTTP, response validation, and retries.
- `AuthManager` manages the desktop authorization state machine.
- `FileSessionStore` saves authentication state using a temporary file and atomic replacement.
- `PluginRuntime` creates an independent `ScrobblerEngine` for each activation. Disabling
  the plugin cancels background tasks and HTTP requests and releases connections.
- `PlayerTrackReader` queries the current track in the background through
  `WorkshopApi.playback.getCurrentMediaItem()`. It queries on startup, refreshes on lyrics
  and playback-state events, and checks every two seconds. Loading lyrics does not itself
  mark a new playback session.
- `PlaybackTracker` preserves progress by track ID and playback round, using a monotonic
  clock together with position changes to count actual listening time. Reloading lyrics
  for the same track neither resets progress nor creates duplicate Scrobbles.
- `PendingScrobbles` atomically saves eligible records to `Last_Fm_Pending.json` and removes
  them after success is confirmed. `ScrobbleSubmitter` sends them in timestamp order,
  independently of Now Playing requests.
- `LoginController` handles settings actions and time-limited authorization checks;
  playback is not responsible for triggering sign-in.
- Sign-in and queue files are stored in
  `%APPDATA%/Salt Player for Windows/workshop/data/`, retaining the previous session-file location.
