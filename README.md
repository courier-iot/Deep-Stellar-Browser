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
| **Clean links** | Strips tracking tags (`utm_*`, `fbclid`, `gclid`, `msclkid` …) and unwraps Google / Facebook / DuckDuckGo / YouTube / Reddit click-through redirects before a page loads. |
| **Find in page** | Live match count, next/previous. |
| **Reader mode** | Strips a page down to the article and re-lays it out in your theme, with reading time. Back or the menu returns to the page. |
| **Themes** | Nebula (violet & blue), Event Horizon (pure black for OLED, accretion-disk orange), Supernova (coral & gold), Aurora (green & teal). The start page follows the theme. Switching keeps your open tabs. |
| **Under the hood** | Menu → Under the hood. *Who's watching*: every outside company the page contacted, what was blocked and why, with one-tap blocking. *Network*: every request (type, 1st/3rd-party, verdict). *Console*: the page's JS logs and errors. *Certificate*: issuer, validity, SHA-256 fingerprint. *Source*: highlighted page source; JSON is pretty-printed, XML re-indented, and `.md` files can be rendered. |
| **QR share & scan** | Show the current page as a QR code for another device — no sync account. Scanner has continuous autofocus, **tap-to-focus**, pinch/slider zoom and torch, and can read codes from a saved image. Decoding is on-device (ZXing); links open inside Deep Stellar. |
| **Encrypted backup** | Settings → Backup. One `.dsbackup` file with settings, bookmarks, your block list, and optionally history and the learned ad model — AES-256-GCM with a PBKDF2 password key. Restore on any phone; history is re-encrypted with the new phone's hardware key. **Automatic backups** run daily / every 3 days / weekly (optionally only while charging) to a folder you pick, keeping the newest 3–20. Your password isn't stored — only a key made from it, locked in the phone's secure hardware — and every automatic file still opens with that password. |
| **Start page** | Two sections, each switchable: *Recommended* (the built-in tiles) and *Your shortcuts* (add with ＋, Menu → Add to start page, or long-press a tile; edit/reorder in Settings). Tiles show a letter — no icons are fetched, so the start page never contacts those sites. |
| **Projects page** | A built-in page (first tile in Recommended) listing the developer's repos from `app/src/main/assets/projects.json`. Optionally checks GitHub's public API every 6 hours for new repos, pushes and releases, shows NEW / UPDATED / NEW RELEASE badges, and can notify. Off by default; uses your Tor setting. |
| **App lock** | Optional. Fingerprint, face or screen-lock PIN to open the browser — right away or after 1 / 5 / 15 minutes away. File pickers and share sheets the browser opens don't count as leaving. Blanks the Recents preview (Android 13+). |
| **Panic wipe tile** | A Quick Settings button: stops downloads and torrents, wipes cookies, cache and site data (optionally history), removes the app from Recents and ends it — works from the lock screen and skips the app lock. Saved downloads stay. |
| **Filter list updates** | Manual, or automatic weekly / every 2 weeks / monthly (Wi-Fi only by default). Uses your Tor setting. |
| **Crash reports** | Saved only on the phone, with web addresses blanked out. View, share or delete them in Settings — nothing is ever sent automatically. |
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

## Contributing

Pull requests welcome — read [CONTRIBUTING.md](CONTRIBUTING.md) (privacy ground rules, setup, style) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (code map and how a page load flows).

## License

MIT. Want forks to have to stay open source too? Swap `LICENSE` for GPL-3.0 — the torrent library (libtorrent4j, MIT) and AndroidX (Apache 2.0) are compatible with it.
