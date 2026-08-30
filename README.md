# Diarium Android

**Native Android wrapper for the [Diarium](https://diarium-two.vercel.app) web app — with accurate per-app usage statistics read directly from the device.**

Diarium is a daily check-in / mood & habit tracking app. This Android application wraps its web UI in a native WebView and adds a small bridge that reads the phone's real usage statistics (per-app screen time, total screen time, unlock count) from `UsageStatsManager` — the same source Android's Digital Wellbeing uses.

## Why a native wrapper?

A plain PWA running in a browser **cannot** read per-app usage data: the `PACKAGE_USAGE_STATS` permission is only granted to real installed apps. This wrapper:

- loads the existing Diarium web app in a WebView (same UI, same Supabase, no rebuild of the web app),
- exposes a JavaScript bridge (`window.AndroidBridge`) to the web app,
- reads exact usage statistics at the source and pushes them to the Diarium API — no Home Assistant or third-party telemetry in the path.

## Features

| Feature | How |
|---|---|
| Web UI | Full Diarium web app inside a WebView (JS, DOM storage, file picker) |
| Per-app screen time | `UsageStatsManager.queryUsageStats(...)` — foreground time per package, app labels via `PackageManager` |
| Total screen time | Sum of per-app foreground time (matches Android Digital Wellbeing, launcher/system apps excluded) |
| Unlock count | `UsageEvents` — `EVENT_SCREEN_INTERACTIVE` |
| Daily sync | 21:00 snapshot of today + 07:00 backfill of yesterday (WorkManager) + 7-day backfill on install + sync right after login |
| Push out | Data pushed to `/api/save-entry` with the user's own JWT (`sub` from token as `user_id`) — no server secrets in the APK |
| Sign-in | OAuth via Chrome Custom Tab + deep link back to the app (Google blocks OAuth inside embedded WebViews) |
| Notifications | Native FCM push (reminders, AI reports) with token registration to the backend |

## Architecture

```
Diarium Android (Kotlin)
 ├── WebView ──► Diarium web app (existing UI, auth, Supabase)
 ├── UsageStatsProvider ──► UsageStatsManager (per-app time, unlocks)
 ├── BridgeJavaScriptInterface = window.AndroidBridge
 │     readUsageStats(date) · getUsageAccess() · openUsageAccessSettings() · getSession()
 ├── SyncScheduler / UsageSyncWorker (WorkManager)
 │     21:00 today snapshot · 07:00 yesterday backfill · install backfill
 ├── AuthManager (Chrome Custom Tab + diarium://auth-callback deep link)
 └── FCM service (registered tokens → /api/push/subscribe)
            │
            ▼
     Diarium API → /api/save-entry (user JWT) → Supabase
```

## Requirements / build

- JDK 17+, Android SDK (platform 34)
- `local.properties` with `sdk.dir=...` (or standard `ANDROID_HOME`)

```bash
git clone git@github.com:Vojta01/diarium-android.git
cd diarium-android
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Install (sideload)

1. Copy the APK to your phone and open it (allow unknown sources when prompted; or `adb install app-debug.apk`).
2. Grant usage access: **Settings → Apps → Special access → Usage access → Diarium → On**.
   Without this the app can't read statistics (the web app simply won't fill the screen-time chart).
3. Sign in with Google — a Custom Tab opens and returns the session to the app.
4. After login the app syncs the last 7 days; afterwards it syncs daily automatically.

## Configuration

All values are compiled in at build time in `app/build.gradle.kts` (`buildConfigField`):

| Field | Purpose |
|---|---|
| `DIARIUM_URL` | Web app URL loaded in the WebView |
| `AUTH_SCHEME`, `AUTH_HOST` | Deep-link scheme/host for the OAuth callback (e.g. `diarium://auth-callback`) |
| `SUPABASE_REF` | Supabase project ref (used for the web app's localStorage session key) |
| `SUPABASE_URL` | Supabase URL used to open the OAuth authorize flow |
| `SAVE_ENTRY_URL` | API endpoint that receives the usage stats push |

If the web app uses Supabase Google OAuth with a custom redirect scheme, add the scheme (e.g. `diarium://auth-callback`) to the Supabase **Allowed Redirect URLs** — otherwise sign-in can't return to the app.

## Push notifications (FCM)

- Add your `google-services.json` under `app/` and uncomment the Firebase plugin/dependencies in `app/build.gradle.kts` and the service in `AndroidManifest.xml`.
- The app registers its FCM token at `/api/push/subscribe` (platform `android`) and the backend sends native notifications via the FCM HTTP v1 API.

## Known limitations

- Web-push (browser push API) does not work inside a WebView; native FCM covers notifications instead.
- Usage statistics require Android 8+ (API 26).

## License

MIT