# Diarium for Android

**Your daily check-in: mood, habits, screen time — all in one place.**

Diarium is a private daily journal and habit tracker for Android. Every evening you
take a minute to check in: how was your day, what did you do, how do you feel. Diarium
turns those small daily records into trends, correlations and AI-written reflections so
you can actually see what makes your days better.

## What it does

### 📝 Daily check-in
- Mood on a 1–5 scale with emoji, plus optional stress, sleep quality, gratitude, and a note
- Pick activities (cooking, family, reading, exercise…) and habits (sleep 7h+, no alcohol…)
- Scales for energy and productivity (custom scales supported)
- Templates for common entries — quick one-tap check-ins
- Photos + notes — your day, your way

### 📊 Screen time & phone usage
- Daily screen time, per-app time, and unlock count — measured the same way
  Android Digital Wellbeing measures it (foreground time per app)
- Screens show a 7-day window with an interactive daily breakdown: tap any day to see
  which apps you used and for how long
- Data lives on your device and syncs to your Diarium account automatically

### 📈 Insights
- Mood trends with a 7-day moving average
- Activity and habit correlations (what actually improves your mood)
- Screen-time analysis, unlock patterns, yearly pixel calendar
- **AI reflections**: a short weekly and monthly summary written by AI from your data —
  patterns you might not notice yourself

### 🔔 Reminders
- Daily check-in reminder so you never lose your streak
- Native notifications: tap to open the app and fill in your day

## Why Android-native

Phone usage statistics (`PACKAGE_USAGE_STATS`) can only be read by a real installed
app — a browser page cannot. Diarium is a native Android app precisely so it can read
exact per-app screen time directly on your device and show you accurate numbers,
without any third-party services in the path. Your usage data goes straight to your
Diarium account, nowhere else.

## Requirements

- Android 8.0+ (API 26)
- Google login (used only to identify your Diarium account)
- Usage access permission (Settings → Special access → Usage access → Diarium) — required
  for screen-time statistics; everything else works without it

## Install

1. Download the APK (see Releases / GitHub Actions artifacts) and open it
2. Allow installation from unknown sources when prompted
3. Sign in with Google
4. Grant usage access when asked (or later in Settings)
5. Done — Diarium syncs the last 7 days right away and then keeps your stats fresh daily

> Sideloading from GitHub is for personal builds. For distribution through a store the
> app can be signed and published normally.

## Build from source

```bash
# Requirements: JDK 17+, Android SDK (platform 34)
echo "sdk.dir=/path/to/android-sdk" > local.properties   # or set ANDROID_HOME

./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions builds the APK on every push to `main` (see the Actions tab) and
attaches it to tagged releases.

## Privacy

- Your check-ins and statistics belong to you and are stored in your Diarium account
- Screen-time data is read on-device and synced to your account — never sent anywhere else
- No ads, no trackers

## Tech notes (for contributors)

The app is a Kotlin/Android project: the UI is delivered as a web app inside a WebView
(same codebase as Diarium's web version), with a native bridge (`window.AndroidBridge`)
for usage statistics, OAuth via Chrome Custom Tabs, WorkManager-based daily sync, and
optional FCM push. See `app/build.gradle.kts` for configurable values (API endpoints,
auth scheme, Supabase project ref).

### Known limitations
- Browser-style web push is not available inside a WebView; native FCM notifications
  cover reminders and reports instead
- Usage statistics require Android 8+ (API 26)

## License

MIT