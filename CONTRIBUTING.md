# Contributing to Deep Stellar Browsing

Thanks for wanting to help. Deep Stellar is a privacy browser first, so every change is judged by one question before anything else:

> **Does this send anything off the phone, or keep anything on it, that the user didn't explicitly ask for?**

If the answer is yes, it needs to be opt-in, off by default, and explained in the UI.

## The ground rules

1. **No telemetry, analytics, crash uploaders or "phone home" of any kind.** Not even anonymous. (On-device crash reports that the user chooses to share are fine.)
2. **No proprietary dependencies.** No Google Play Services, Firebase, ML Kit or closed SDKs. Every library must be under an open-source license compatible with MIT (Apache-2.0, BSD, MIT, LGPL for dynamically linked native libs).
3. **Nothing about browsing is written to disk by default.** Anything that does persist (bookmarks, opt-in history) is either explicitly requested or encrypted.
4. **Network calls the app itself makes** (filter list updates, etc.) only happen when the user taps something or on a schedule the user turned on, and go through `NetUtil` so they respect the Tor/Orbot setting.
5. **Be honest in the UI.** If a feature is best-effort (e.g. CPU/RAM limits, reader mode), say so.

## Getting set up

You don't need Android Studio — GitHub Actions builds every push. If you want to build locally:

```bash
# JDK 17 + Android SDK (platform 35) required
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

The debug build installs alongside a release build (`dev.deepstellar.browser.debug`).

## Making a change

1. Open an issue first for anything bigger than a bug fix, so we can agree on the approach.
2. Fork, branch from `main` (`feature/short-name` or `fix/short-name`).
3. Keep pull requests focused — one feature or fix each.
4. Make sure the **Build APK** workflow passes on your PR.
5. Fill in the PR template, including what you tested on a real device or emulator.

## Code style

- Kotlin, official style (`kotlin.code.style=official`), 4-space indents, ~120-char lines.
- Prefer the platform and AndroidX over new libraries. Every new dependency needs a reason in the PR.
- UI is built in code with the helpers in `ui/Ui.kt` and `ui/Scaffold.kt`. Use `Ui.color(ctx, R.color.…)` for palette colors so all themes work — never hard-code a hex color in UI code.
- Long-running work stays off the main thread (executors in each engine).
- Comments explain *why*, especially anything privacy-related.

## Testing a release

Before tagging, run through [docs/TESTING.md](docs/TESTING.md) on a real phone.

## Where things live

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for a map of the code and how a page load flows through it.

## Reporting security or privacy problems

If you find a way the browser leaks data (requests that bypass the shield or Tor setting, data surviving the exit wipe, etc.), please open an issue titled **[privacy]** with steps to reproduce. If it's sensitive, say so in the issue and we'll arrange a private channel before details are posted.

## License

By contributing you agree your work is released under the project's [MIT license](LICENSE).
