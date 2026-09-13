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
- Daily screen time, per-app time, and unlock count — measured exactly the same way
  Android Digital Wellbeing measures it (display-on time, per-app foreground time)
- Screens show a 7-day window with an interactive daily breakdown: tap any day to see
  which apps you used and for how long
- Data lives on your device and syncs to your Diarium account automatically at times
  you choose (default: evening snapshot of today + morning backfill of yesterday)

### 📈 Insights
- Mood trends — one bar per day with a 7-day moving average, and for the year a monthly
  breakdown (twelve columns, the mean of each month's answered days) with a 3-month
  moving average and the strongest/weakest month called out under the chart
- Activity and habit correlations (what actually improves your mood)
- Screen-time analysis, unlock patterns, yearly pixel calendar
- **AI reflections**: a short weekly and monthly summary written by AI from your data —
  patterns you might not notice yourself, one tap away on the dashboard (AI Přehledy)
  as well as in Settings → Přehledy

### 🔔 Reminders & automation — everything runs on your phone
No server-side crons are involved. The app schedules everything locally and lets you
set the times in its Settings screen (opened from the web UI via the notification
settings button):
- **Daily check-in reminder** — pick the time and days of week; the smart reminder
  skips days you already filled in
- **Weekly AI reflection** — pick the day and time; the app generates the report
  on the spot and notifies you when it's ready
- **Monthly AI reflection** — pick the time on the 1st of the month
- **Screen time sync** — pick evening and morning times, or switch it off
  (on-open backfill of the last 7 days always runs so charts self-heal)

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

1. Download the APK (see Releases) and open it
2. Allow installation from unknown sources when prompted
3. Sign in with Google
4. Grant usage access when asked (or later in Settings)
5. Done — Diarium syncs the last 7 days right away and then keeps your stats fresh daily

> Sideloading from GitHub is for personal builds. For distribution through a store the
> app can be signed and published normally.

## Versioning

Each tagged release (`v1.2.3`) is built by GitHub Actions and published as
`diarium-1.2.3.apk`. The app's real version (shown in Settings → O aplikaci and in
Android's App info) is derived from the tag, so you can always tell which build you
have installed. Screenshots of the app in this repo may lag behind the latest version.

## Build from source

```bash
# Requirements: JDK 17+, Android SDK (platform 34)
echo "sdk.dir=/path/to/android-sdk" > local.properties   # or set ANDROID_HOME

# Optional: set the version (defaults to 1.0.0)
APP_VERSION_NAME=1.2.3 APP_VERSION_CODE=10203 ./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions builds the APK on every push to `main` (see the Actions tab) and
attaches it to tagged releases.

## Privacy

- Your check-ins and statistics belong to you and are stored in your Diarium account
- Screen-time data is read on-device and synced to your account — never sent anywhere else
- No ads, no trackers

## Tech notes (for contributors)

The app is a Kotlin/Android project with a **native Jetpack Compose UI** (the WebView
wrapper of the early milestones is gone — only the plumbing it carried remains):
Supabase auth via Chrome Custom Tabs, usage statistics read on-device through
`UsageStatsManager`, AlarmManager + WorkManager-based scheduling (all times
user-configurable — no server crons), and local notifications. The statistics maths lives
in `core/stats/StatsMath.kt`, separate from the screens that draw it. See
`app/build.gradle.kts` for configurable values (API endpoints, auth scheme,
Supabase project ref).

### Known limitations
- Browser-style web push is not available inside a WebView; notifications are shown
  natively by the app itself (reminders + AI report alerts), scheduled locally
- Usage statistics require Android 8+ (API 26)

## License

MIT