# Release testing checklist

Run through this on a real phone before tagging a release (two different phones/Android versions if you can). Tick each box; anything that fails becomes an issue.

**Setup:** install the signed release APK from the GitHub Release. Note the phone, Android version and Deep Stellar version at the top of your notes.

---

## 1. First launch & basics
- [ ] App opens to the starfield start page, no crash
- [ ] Typing a search goes to the chosen engine; typing `example.com` opens the site
- [ ] Back / forward / reload / home buttons work
- [ ] Rotate the phone on a page — nothing reloads or breaks
- [ ] A video goes fullscreen and back

## 2. Start page
- [ ] **Recommended** and **Your shortcuts** both show by default
- [ ] Tap **＋ Add**, add `codeberg.org` with no name → tile shows "Codeberg" with a C
- [ ] Long-press your tile → Edit and Remove both work
- [ ] Long-press a Recommended tile → "Add to my shortcuts" works
- [ ] On any site: Menu → **Add to start page** → it appears
- [ ] Settings → Start page: turn off Recommended, then Your shortcuts → each section disappears when you return
- [ ] Settings → Manage your shortcuts: move up / down / remove work

## 2b. Projects page
- [ ] The **Projects** tile opens the built-in page; each card opens the right repo
- [ ] "Check every 6 hours" turns on; after a few seconds the page says "Checked GitHub … ago"
- [ ] Push a commit or publish a release, tap **Check now**, reload → UPDATED / NEW RELEASE badge; it clears after you've seen it
- [ ] Switch theme → the page recolours

## 3. Tabs & limits
- [ ] Open tabs until the cap (Settings → Maximum tabs = 4) → the 5th is refused with a message
- [ ] Tabs sheet: switch, close, close all others
- [ ] CPU = Ultra-eco, open 3 tabs → background tabs show "sleeping" and reload when opened
- [ ] Resource monitor shows sensible numbers

## 4. Privacy
- [ ] Log in to a site, **Exit & wipe**, reopen → logged out
- [ ] Force-stop the app from Android settings, reopen → still logged out
- [ ] `http://neverssl.com` → HTTPS-only offers "Load insecure"
- [ ] A link with `?utm_source=x&fbclid=y` loads without those parts
- [ ] Turn on Tor with Orbot **off** → pages fail to load (no leak); with Orbot on → https://check.torproject.org says you're on Tor

## 5. Ad shield
- [ ] A news site: shield count goes up
- [ ] Menu → Under the hood → **Who's watching** lists companies, some blocked
- [ ] Network tab filters (Blocked / Third-party / Scripts) work
- [ ] Long-press an ad image → Block as ad → it's gone after reload
- [ ] Shield sheet → Allow ads on this site → amber shield, page reloads unfiltered
- [ ] Settings → Update filter lists → summary shows "updated <date>"
- [ ] Settings → Update lists automatically = Weekly (check it saves; the run itself happens in the background)

## 6. Under the hood
- [ ] Console shows messages on a JS-heavy site
- [ ] Certificate shows issuer, dates, fingerprint; on an `http://` page it says not encrypted
- [ ] Source on a normal page is highlighted; on `https://api.github.com` it's pretty JSON
- [ ] A raw `.md` file (e.g. a GitHub "Raw" README) → Menu → Render Markdown

## 7. Reader, find, themes
- [ ] Reader mode on a long article; Back returns to the page
- [ ] Find in page: count, next/previous, close
- [ ] Switch each theme with 3 tabs open → all tabs survive, start page recolours

## 8. Bookmarks, history, suggestions
- [ ] Bookmark a page (★), export, import into another browser (or re-import here)
- [ ] Import a bookmarks file from Firefox/Chrome → folders preserved
- [ ] Turn on encrypted history → visit pages → History screen asks for fingerprint, lists them
- [ ] Address bar suggests bookmarks (★) and history (◷) while typing
- [ ] Turn history off → confirm → History menu item disappears

## 9. Downloads & torrents
- [ ] Download a file, pause, resume, open it
- [ ] Open a magnet link (e.g. a Linux ISO) → metadata found, progress moves
- [ ] Notification shows while transfers run; disappears when done

## 9b. Torrents & downloads (advanced)
- [ ] Add a multi-file torrent paused → Details → untick some files → resume: only ticked files download
- [ ] Details → Download in order on a video → tap the file at ~5% → it plays
- [ ] Close the app (not panic) mid-torrent and mid-download → reopen → both are back; download is paused and resumes
- [ ] Settings: torrents on Wi-Fi only → switch to mobile data → "Waiting for Wi-Fi"
- [ ] VPN only with VPN off → "Waiting for VPN"
- [ ] Downloading at once = 1 with two torrents → second shows "Queued"
- [ ] Remove → "delete files" removes them from storage
- [ ] Ask where to save → save dialog appears, file lands there
- [ ] A site that generates a file (e.g. export to CSV in a web app) → blob download saves

## 9c. Privacy extras
- [ ] https://browserleaks.com/webrtc → no local/public IP shown (relay mode)
- [ ] A video call site (e.g. meet.jit.si) → asks for camera/mic → Allow → call works
- [ ] Site settings: block camera for that site → it can't ask again; reset brings the prompt back
- [ ] Site settings: JavaScript off for a site → page reloads without JS; other sites unaffected
- [ ] A site with a cookie banner (most news sites in the EU) → banner gone, page scrolls
- [ ] Settings → Update filter lists → summary shows "+ N rules"
- [ ] `!gh deep stellar` and `!w nebula` go to GitHub / Wikipedia
- [ ] Close a tab → Undo in the snackbar brings it back with its back history
- [ ] Swipe the address bar left/right → switches tabs; pull down at top → reloads
- [ ] Address bar at the bottom → bars move; still usable with the keyboard open
- [ ] Menu → Print / save as PDF → PDF saved

## 10. QR
- [ ] Menu → QR code for this page → another phone's camera opens it
- [ ] Scan: tap-to-focus ring appears and focuses; pinch + slider zoom; torch
- [ ] Scan a QR from a saved image (menu)

## 11. Backups
- [ ] Manual backup with history + model → restore it (wrong password is refused, right one works, app restarts with everything back)
- [ ] Turn on automatic backups → a file appears in the chosen folder within a minute
- [ ] "Back up now" works; delete the folder → next run shows a warning + notification

## 12. App lock & panic
- [ ] Turn on App lock (Right away) → leave and return → lock screen; Back on it leaves the app
- [ ] Pick a file in a web form / share a link → returning does **not** ask to unlock
- [ ] "After 1 minute" → returning after 20 s doesn't lock; after 70 s it does
- [ ] Recents preview is blank while App lock is on (Android 13+)
- [ ] Settings → Panic wipe tile → add it; start a download, tap the tile → app closes, gone from Recents, download stopped, logged out on reopen
- [ ] Tap the tile from the lock screen → same result, no unlock needed

## 13. Crash reports
- [ ] Settings → View crash reports says "None" (or lists any) — open one and check there are no web addresses in it
- [ ] Share a report → share sheet opens with plain text

## 14. Update path
- [ ] Install the previous release, then this one over it → keeps bookmarks/settings (same signing key)
