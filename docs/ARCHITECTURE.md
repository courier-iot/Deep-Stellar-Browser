# Architecture

Deep Stellar is a single-module Android app (Kotlin, minSdk 29, targetSdk 35). Pages render in Android System WebView; everything else — tabs, the ad shield, downloads, torrents, history — is the app's own code.

## Map

```
dev.deepstellar.browser
├── DeepStellarApp        App start: prefs, startup wipe, stores, notification channel
├── MainActivity          The browser screen: address bar, tabs sheet, menus, find bar,
│                         suggestions, long-press actions, fullscreen video, back handling
├── BrowserEngine         Creates and configures each WebView: privacy settings, request
│                         filtering, link cleaning, HTTPS-only, CPU throttle script,
│                         console + network logging, start page asset loader
├── TabManager / Tab      Tabs, the tab cap, sleep/wake (WebView destroyed, back/forward
│                         list kept in RAM), carrying tabs across a theme change
├── ResourceGovernor      CPU & RAM limiter (samples /proc/self/stat + PSS, puts tabs to sleep,
│                         throttles torrents)
├── PrivacyWiper          Wipes cookies, cache, storage at exit and at launch
├── UrlCleaner            Tracking-parameter stripping and redirect unwrapping
├── Reader                Reader-mode script (themed)
├── AppLock / ui.LockActivity  Optional app lock (activity-lifecycle tracking, biometric prompt)
├── Panic + PanicTileService  Quick Settings panic wipe (runs without opening the UI, then ends the process)
├── CrashLog          On-device crash reports with addresses scrubbed
├── StartShortcuts    Start-page sections + your shortcuts, served to start.html at /data/start.json
├── projects/Projects  Built-in Projects page data (assets/projects.json) + optional 6-hourly GitHub check (WorkManager, ETag)
├── PageScripts       WebRTC relay-only, fingerprint noise, referrer trimming, cookie-banner script
├── SitePrefs         Per-site permissions, JavaScript, desktop mode, text zoom
├── Bangs             Search shortcuts
├── SafeMode          Crash-loop detection: 3 crashes within 20 s of start → next start skips risky parts and offers resets
├── Migrations        Numbered, run-once settings upgrades
├── NetUtil               The only place the app opens its own HTTP connections (respects Tor)
├── Prefs                 Typed access to settings (SharedPreferences)
│
├── adblock/   AdBlocker (lists, user list, cosmetic CSS), FilterEngine (EasyList syntax, keyword-indexed),
│              AdLearner (hashed logistic regression), FilterUpdates (WorkManager),
│              DomainUtil (registrable domain, first/third party)
├── inspect/   PageLogs (per-page request + console logs, tracker report), Inspector (source capture,
│              pretty JSON/XML, highlighting, certificate), MarkdownView
├── history/   HistoryStore (opt-in, AES-GCM with an Android Keystore key), SuggestionEngine
├── bookmarks/ BookmarkStore, NetscapeBookmarks (standard import/export format)
├── downloads/ DownloadEngine (HTTP with pause/resume), TransferService (foreground service)
├── torrent/   TorrentEngine (libtorrent4j): resume index in filesDir/torrents, file priorities, queue, network rules
├── backup/    BackupManager (PBKDF2 + AES-GCM encrypted .dsbackup files), AutoBackup + AutoBackupWorker
│              (WorkManager schedule; derived key wrapped by an Android Keystore key; DocumentFile folder)
├── qr/        QrCodec (ZXing encode/decode)
└── ui/        Settings, Bookmarks, History, Transfers, Inspector, QR screens;
               Themes, Ui helpers, Scaffold
assets/
├── start.html     Starfield start page (theme passed as ?theme=)
├── projects.html  Projects page; reads /data/projects.json from the app
├── projects.json  The built-in project list (edit this to add repos)
├── theme.js       Theme palettes shared by the built-in pages
└── blocklist.txt  Bundled seed list of ad/tracker domains
```

## How a page load flows

1. **You type or tap** → `MainActivity.navigate()` → `UrlCleaner.clean()` → `WebView.loadUrl()` with GPC/DNT headers.
2. **Link clicks and redirects** → `BrowserEngine…shouldOverrideUrlLoading()`:
   cleans the URL, upgrades to HTTPS, hands `magnet:`/`intent:` links to the app or other apps.
3. **Every request** → `shouldInterceptRequest()` (background thread):
   - the main-frame request resets the page's logs and sets `tab.pageHost`;
   - `AdBlocker.check()` returns a verdict (list / your list / learned / allow) and trains the model;
   - the request is recorded in `tab.net` for the Under-the-hood screen;
   - blocked requests get an empty response.
4. **Page events** → `onPageStarted/Finished`, `onReceivedTitle`, `onConsoleMessage` update the tab, cosmetic filtering, history (if on) and the console log.
5. **Exit** (menu, double back, or the activity finishing) → `PrivacyWiper.wipeWebData()`. At the next launch `DeepStellarApp` wipes the engine's storage folders again, in case the app was killed.

## Threading

- WebView callbacks run on the main thread, except `shouldInterceptRequest` (WebView's IO threads) — keep it fast and thread-safe.
- Each engine owns an executor: `AdBlocker.io`, `HistoryStore.io`, `DownloadEngine.pool`, `TorrentEngine.exec`, `ResourceGovernor` (HandlerThread).
- UI updates from background work go through `runOnUiThread` / a main-thread `Handler`.

## Theming

Colors are theme attributes (`res/values/attrs.xml`: `dsBg`, `dsText`, `dsAccent` …) defined per theme in `themes.xml`. Layouts and drawables use `?attr/…`; Kotlin uses `Ui.color(ctx, R.color.x)`, which maps the palette color ids to the active theme. Every Activity calls `Themes.apply(this)` before `super.onCreate()`. The start page receives the theme key and sets matching CSS variables.

## Privacy invariants (please keep them true)

- No history list exists unless the user turns on encrypted history.
- Request/console logs, tab back-stacks, the downloads and torrent lists are memory-only.
- The ad model stores only hashed feature weights, never URLs.
- The app's own network calls use `NetUtil` (Tor-aware) and only happen on a user action or a schedule the user enabled (filter updates).
- Crash reports stay on the phone and are scrubbed of addresses; they leave only via the user's own Share.
- Safe Browsing lookups and WebView metrics are disabled in the manifest.

## Build & release

`.github/workflows/build.yml` builds debug + release APKs on every push, uploads them as artifacts, and publishes a GitHub Release for `v*` tags. Release signing is enabled automatically when the four keystore secrets exist (see README).
