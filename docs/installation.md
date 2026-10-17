# Install, build and contribute

Everything technical about Ondes lives here: the full feature list, the stack, how to get or build the APK, and how the code is laid out. For the tour of the app, see the [README](../README.md).

## Features

- 🎧 **Background playback** with lock-screen & notification controls (Media3 / ExoPlayer + MediaSession)
- 🚗 **Android Auto** — browse Continue listening / Subscriptions / Downloads and play hands-free in the car
- ⏯️ Play / pause, **configurable skip intervals**, scrub
- ▶️ **Auto-play the next episode** — continuous playback through your list
- ⏩ **Variable speed** (0.8×–3×), remembered as your default, with **per-podcast speed** overrides
- 🤫 **Skip silence** & **volume boost** for clearer speech over road/train noise
- 🔖 **Chapters** — tap to jump (Podcasting 2.0 `podcast:chapters`)
- 📝 **Rich show notes** — formatted HTML with tappable links & `mm:ss` timestamps that seek
- 😴 **Sleep timer** — fixed durations or **stop at end of episode**
- 💾 **Offline downloads** (WorkManager — survives app being closed), optional **Wi-Fi-only** and **auto-delete when finished**
- 🔄 **Background refresh + new-episode notifications** for your subscriptions, with optional **per-podcast auto-download**
- 🔖 **Resume where you left off** — playback positions saved per episode
- ✅ Auto mark-as-played, "Continue listening" on the home screen
- 🔍 **Discover** podcasts (iTunes search) or paste any RSS feed URL, with a first-run **interest picker**
- 📚 Subscriptions library with pull-to-refresh, plus **filter episodes** within a show
- ♿ **Accessibility** — merged TalkBack list items with custom actions for every episode operation
- 🧾 **Up-Next queue** — a persistent, reorderable play queue ("Play next" / "Add to queue")
- 💼 **Own your data** — OPML import/export and a full local backup/restore (subscriptions, progress & settings), all on-device
- 🌍 **Localized in 5 languages** — English, French, German, Spanish & Portuguese
- ⚙️ **Settings** for playback, downloads, updates, your data and appearance
- 🎨 **Material You** dynamic theming, light/dark/system theme, edge-to-edge
- 🚫 No ads, no analytics, no login

## Tech stack

| Concern        | Choice |
|----------------|--------|
| Language       | Kotlin 2.0 |
| UI             | Jetpack Compose + Material 3 |
| Playback       | AndroidX **Media3** (ExoPlayer + Session) |
| Persistence    | **Room** |
| DI             | **Hilt** |
| Async          | Coroutines + Flow |
| Background work| WorkManager |
| Networking     | OkHttp + platform XmlPullParser (RSS) |
| Images         | Coil |

`minSdk 26` (Android 8.0) · `targetSdk 35` · single-activity, MVVM.

## Get the APK on your phone

> **Free to build, fair to buy.** Ondes is open source — clone it, build it, and
> sideload it for free, forever (the steps below). The **Google Play** version is
> a **one-time purchase** (no subscription, no ads, no in-app purchases) for
> people who'd rather tap *Install* than run Gradle — it's the same app, and it
> funds the work. Try it free here first; buy it on Play if it earns a spot on
> your phone.

**Easiest — one tap from a GitHub Release** (public download, no account):

| Build | Download | What it is |
|-------|----------|------------|
| **Stable** | [**app-release.apk**](https://github.com/Wifsimster/ondes/releases/latest/download/app-release.apk) | The latest tagged version — start here |
| Nightly | [app-release.apk](https://github.com/Wifsimster/ondes/releases/download/latest/app-release.apk) · [app-debug.apk](https://github.com/Wifsimster/ondes/releases/download/latest/app-debug.apk) | Tip of `main`, rebuilt on every push |

Open the link on your phone, allow *install from unknown sources* when Android
asks, then tap the downloaded file. Each release also ships a `SHA256SUMS.txt`,
so you can check what you downloaded is what CI built:
`sha256sum -c SHA256SUMS.txt`.

**Testing a branch or a PR:** every push — on any branch — uploads
`ondes-release-apk` and `ondes-debug-apk` artifacts to its **Actions** run.
Downloading an artifact does require being signed in to GitHub; the release
links above do not.

**Build it yourself:**
```bash
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```
Requires JDK 17 and the Android SDK (platform 35).

> **Signing:** the **APK** distributed here (CI artifact / GitHub Release) is
> **debug-signed** so it installs without extra setup — it is for sideloading
> only, not Play. The **Play Store AAB** is built and **upload-signed** with the
> real upload key (supplied via `keystore.properties` locally or CI env vars; see
> `keystore.properties.example`). Configure your own upload key the same way for
> store distribution.

## Project layout

```
app/src/main/java/ovh/battistella/ondes/
├─ data/        Room (local) · RSS + iTunes (remote) · repository · settings (DataStore) · opml + backup (data ownership)
├─ playback/    Media3 service, controller bridge, sleep timer
├─ download/    WorkManager episode downloader
├─ sync/        Periodic feed refresh worker + new-episode notifications
├─ ui/          Compose screens, theme, navigation, components
├─ di/          Hilt modules
├─ OndesApp     Application — WorkManager + notification channel setup
└─ MainActivity
```

## Notes

- Ondes is an independent player. It streams the publicly published RSS feeds
  you subscribe to and is not affiliated with any podcast or publisher.
- The internal package/applicationId remains `ovh.battistella.ondes` for
  historical reasons; the user-facing name is **Ondes**.

