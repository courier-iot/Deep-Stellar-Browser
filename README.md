# ✦ Deep Stellar Browsing

A private, deep-space themed Android browser. Free and open source, no telemetry, no history — ever.

## Features

| | |
|---|---|
| **No history by default** | Out of the box no history list exists. Cookies, cache, local storage, IndexedDB, service workers and form data are wiped on every exit *and* at every launch (in case the app was killed). Keyboard runs in incognito mode. No cloud backup. |
| **Optional encrypted history** | Off unless you turn it on in Settings. Saved as an AES-256-GCM encrypted file whose key lives in the Android Keystore (secure hardware, can't be exported), decrypted into memory at launch. Fingerprint/PIN lock, auto-expiry (1 day to forever), paused during Tor, and switching it off deletes the file *and* destroys the key. |
| **Privacy defaults** | DuckDuckGo search (or Brave, Startpage, Mojeek, DDG Lite, your own SearXNG), HTTPS-only mode, third-party cookies blocked, Global Privacy Control, Google Safe Browsing lookups and WebView metrics turned off, camera/mic/location always refused. |
| **Tor** | Optional "Route through Tor (Orbot)" — no direct fallback, so if Orbot is off pages simply won't load instead of leaking. |
| **Tabs** | Tab cap slider: 4 · 8 · 12 · 16 · 32 · 64 · Unlimited. Pop-ups without a tap are dropped. |
| **CPU limiter** | Full power · Balanced · Eco · Ultra-eco. Caps how many background tabs stay awake, throttles page animation/timers (30 or 15 fps), and slows torrents when the app runs hot. |
| **RAM limiter** | 256 MB → 2 GB or Unlimited. Background tabs go to sleep (history kept in RAM, page reloads when you return) when the estimate exceeds the budget or Android is low on memory. |
| **Self-learning ad shield** | Filter lists + a small on-device learning model that spots ads no list has seen. Long-press an ad → **Block as ad** to teach it. Per-site "allow" for broken pages. The model stores only hashed numbers, never URLs. |
| **Downloads** | Built-in manager with pause / resume / cancel. Saved to `Download/DeepStellar`. |
| **Torrents** | Built-in BitTorrent client (libtorrent). Magnet links and `.torrent` files, speed limits, optional seeding. Saved to `Download/DeepStellar/Torrents`. |
| **Bookmarks** | Import/export in the standard bookmark HTML format — works with Firefox, Chrome, Brave, Edge, Tor Browser, DuckDuckGo, Vivaldi, Safari. |
| **Address bar suggestions** | Matches your bookmarks (★) and, if history is on, your history (◷) as you type, ranked by how well it matches, how often you visit and how recently. Matched on the phone — nothing you type goes to a search engine until you press Go. Tap ↖ to copy a suggestion into the bar and edit it. |
| **Desktop mode** | Per tab, from the menu. Or on by default in Settings. |

## Build it (GitHub only, no Android Studio)

1. Create a new repo on GitHub (e.g. `deep-stellar-browsing`).
2. Unzip this project and push **the contents** (so `app/`, `.github/`, `gradlew` sit at the repo root):
   ```bash
   cd deep-stellar-browsing
   git init && git add . && git commit -m "Deep Stellar Browsing"
   git branch -M main
   git remote add origin https://github.com/<you>/deep-stellar-browsing.git
   git push -u origin main
   ```
   (Uploading through the GitHub website also works — make sure the hidden `.github` folder comes along.)
3. Open the **Actions** tab → *Build APK*. When it finishes, download the artifact.

### Releases

Push a tag and the workflow publishes a GitHub Release with the APKs attached:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

Each build produces:
- `DeepStellar-<ver>-debug.apk` — installable right away (debug-signed, app id `dev.deepstellar.browser.debug`)
- `DeepStellar-<ver>-release-unsigned.apk` — until you add a signing key

### Signing later

Add four repository secrets (Settings → Secrets and variables → Actions) and the release APK becomes `DeepStellar-<ver>-release.apk`, signed:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 your.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

## Honest limits

- **Engine:** pages render with Android System WebView (Chromium). That's what makes the app small, but it means no extensions and fingerprint resistance isn't at Tor Browser level. For real anonymity use Tor Browser; this is a privacy browser, not an anonymity tool.
- **CPU/RAM limiters:** Android doesn't allow an app to hard-cap its own CPU or memory, and the page renderer runs in a sandboxed process the app can't measure. The limiters work by putting tabs to sleep, throttling pages, and slowing torrents — the same way desktop browsers manage background tabs. Memory per page is an estimate (~70 MB per awake tab).
- **Torrents never go through Tor**, even when the browser does — the app warns you before starting one in Tor mode.
- `blob:` downloads (files a site builds in-page) aren't supported yet.

## Project layout

```
app/src/main/java/dev/deepstellar/browser/
  MainActivity.kt        UI, tabs sheet, menus, long-press actions
  BrowserEngine.kt       WebView setup, privacy settings, request filtering, CPU throttle
  TabManager.kt          tabs, cap, sleep/wake
  ResourceGovernor.kt    CPU/RAM limiter
  PrivacyWiper.kt        wipe on exit/launch
  adblock/               filter lists + self-learning model
  downloads/             download manager + background service
  torrent/               BitTorrent engine
  bookmarks/             store + import/export
  history/               optional encrypted history
  ui/                    settings, bookmarks, transfers screens
app/src/main/assets/start.html   the starfield start page
```

## License

MIT. Want forks to have to stay open source too? Swap `LICENSE` for GPL-3.0 — the torrent library (libtorrent4j, MIT) and AndroidX (Apache 2.0) are compatible with it.
