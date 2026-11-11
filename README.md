# Diarium

**Diarium is a daily journal and mood-tracking app.** This repository contains the web app built with Next.js; the native Android app is maintained separately.

- [Open the web app](https://diarium.digitalni-vedomi.cz)
- [Web source](https://github.com/Vojta01/diarium)
- [Android source](https://github.com/Vojta01/diarium-android)
- [Download the Android APK](https://raw.githubusercontent.com/Vojta01/diarium-android/apk/diarium.apk)

## Features

| Area | Web app (this repository) | Native Android |
|---|---|---|
| Journal | Daily mood, stress, sleep, activities, habits, gratitude, notes, weather, custom scales and photos | Native daily check-in with mood, activities, habits, optional stress/sleep/gratitude/notes, scales, templates and photos |
| History and insights | Dashboard, calendar, Year in Pixels, activity/habit analysis, goals, achievements and photo gallery | Native insights, mood trends, activity/habit correlations and yearly pixel calendar |
| Screen time | Displays data synced from Android only when `NEXT_PUBLIC_FEATURES=personal`; the browser cannot collect device-usage statistics | Collects device usage statistics with Android's Usage Access permission and syncs them to the account |
| AI | Daily reflection and on-demand weekly, monthly and yearly summaries through the server | Can schedule weekly and monthly report requests locally |
| Notifications | Browser Web Push can deliver notifications; the web app does not schedule reminders | Schedules reminders and report notifications on the device |
| Export | CSV and a reduced-summary PDF | CSV; web CSV formatting is covered by a parity test |
| App type | Installable Next.js PWA | Separate native Kotlin/Jetpack Compose app, not a WebView wrapper |

See the [web/Android parity notes](./docs/PARITY.md) for details and limits. Photos in the web app are selected from the device and uploaded to Supabase Storage; the Google Photos picker is not implemented.

## Stack and data flow

- **Web:** Next.js 16 App Router, React 19, TypeScript and Tailwind CSS 4.
- **Identity and data:** Google OAuth through Supabase Auth; journal data is stored in Supabase Postgres and photos in Supabase Storage.
- **AI:** server routes call the DeepSeek API. The model can be configured at runtime.
- **Browser push:** Web Push/VAPID subscriptions are stored in Upstash Redis.
- **Hosting:** the live web app is hosted on Vercel.

The client reads and writes user data using Supabase; selected server routes use the Supabase service-role key. Do not expose that key in a `NEXT_PUBLIC_*` variable or commit it.

## Getting started

### Requirements

- Node.js **22.18 or newer** (the project declares this minimum; `npm test` uses Node's built-in TypeScript support).
- npm.
- A Supabase project for authentication and database/storage features.
- A DeepSeek API key for AI features. AI use is allowlisted; configure `AI_GUARD_ALLOWED_USER_ID` for the intended user UUIDs instead of relying on the code's owner-only fallback.
- Upstash Redis and VAPID keys are needed only if enabling browser Web Push.

### Configure Supabase and Google sign-in

1. Configure the Google provider in the Supabase project under **Authentication → Providers → Google**.
2. Add the callback URL to Supabase's allowed redirect URLs. By default the app uses `https://<your-host>/auth-callback.html`; for local development, add `http://localhost:3000/auth-callback.html`.
3. Use the matching Supabase project URL and public anon key in the web environment. The Google OAuth client ID/secret belong in the Supabase provider configuration; the web app does not read a separate Google client ID from its runtime environment.
4. Review the SQL files under [`database/`](./database/) before applying anything. There is no Supabase CLI migration directory or one-size-fits-all fresh-install migration sequence; see [database setup notes](./docs/DATABASE.md).

### Run locally

```bash
git clone https://github.com/Vojta01/diarium.git
cd diarium
npm ci
cp .env.example .env.local
# Fill in the environment values below; keep .env.local out of Git.
npm run dev
```

Open [http://localhost:3000](http://localhost:3000). `NEXT_PUBLIC_AUTH_CALLBACK_URL` may override the default callback with an absolute HTTP(S) URL. If using a different local port or host, allow that callback URL in Supabase as well.

## Environment variables

Set values in `.env.local` for local development and in the hosting provider's environment settings for deployment. Use placeholders only in templates; never put live credentials in this README or in Git.

| Variable | Needed for | Notes |
|---|---|---|
| `NEXT_PUBLIC_SUPABASE_URL` | App | Supabase project URL. |
| `NEXT_PUBLIC_SUPABASE_ANON_KEY` | App | Public Supabase anon key. |
| `SUPABASE_SERVICE_ROLE_KEY` | Server routes | Secret; server-side only. |
| `DEEPSEEK_API_KEY` | AI | Required to generate AI reflections/reports. |
| `AI_GUARD_ALLOWED_USER_ID` | AI access | Comma-separated Supabase user UUIDs allowed to use AI. Configure explicitly for your deployment; `ALLOWED_AI_USERS` is a legacy fallback. |
| `NEXT_PUBLIC_AUTH_CALLBACK_URL` | OAuth (optional) | Absolute callback URL override; defaults to the current origin plus `/auth-callback.html`. |
| `NEXT_PUBLIC_FEATURES` | Feature mode (optional) | Defaults to `core`; use `personal` to show personal-only features such as synced screen-time data and sensitive habits. |
| `DEEPSEEK_MODEL` | AI (optional) | Environment fallback for model selection; `public.app_settings.deepseek_model` takes precedence when configured. |
| `CRON_SECRET` | Server-to-server report calls (optional) | Non-empty secret for the report route; keep private. See [authorization below](#reports-and-scheduling). |
| `VAPID_PRIVATE_KEY` | Browser Web Push | Secret VAPID private key. |
| `NEXT_PUBLIC_VAPID_PUBLIC_KEY` | Browser Web Push (optional) | Public key corresponding to the private key; a development default exists in the app. |
| `VAPID_EMAIL` | Browser Web Push (optional) | VAPID contact URI. |
| `UPSTASH_REDIS_REST_URL` | Browser Web Push | Upstash Redis REST URL for browser push subscriptions. |
| `UPSTASH_REDIS_REST_TOKEN` | Browser Web Push | Secret Upstash Redis REST token. |

The checked-in [`.env.example`](./.env.example) contains placeholders, not production credentials. It is a template and may include entries not required by every deployment.

## PWA and offline behavior

The app registers a service worker. For GET requests it is **network-first**, with an 8-second timeout and a cached-response fallback; a failed navigation with no cached response gets a minimal offline page. This is not an offline-first app: writes are not queued for later synchronization, and saving a journal entry requires a network connection. Unsaved check-in drafts are retained locally in the browser while editing.

## Reports and scheduling

`vercel.json` declares an empty `crons` list: this repository does not configure scheduled Vercel jobs. The web UI can generate reports on demand. The Android app schedules its reminders and weekly/monthly report calls locally.

`/api/cron/ai-report` accepts either a verified user's bearer JWT (for that user's report) or, for a server-to-server call, a matching `CRON_SECRET` in the bearer Authorization header or `?secret=` query parameter. Prefer the header because query strings can appear in logs. The route does **not** trust an `x-vercel-cron` header. Do not configure a Vercel cron based on the endpoint name alone.

## Tests and checks

Run from the repository root with Node.js 22.18 or newer:

```bash
npm test        # Node's built-in test runner; tests/*.test.ts
npm run lint    # ESLint
npm run build   # Next.js production build
```

During this documentation update, `npm test` passed (418 tests) and `npm run build` succeeded. `npm run lint` currently exits with existing ESLint errors in application and test source; it is not a passing check in this checkout.

Database SQL checks are separate from `npm test`; see [database setup notes](./docs/DATABASE.md) and review a SQL test script before running it against any database.

## Repository layout

```text
database/        Manual Supabase SQL scripts and database test SQL
docs/            Current setup/parity notes and archived historical documents
public/          PWA manifest, service worker and static OAuth callback
src/app/         Next.js pages and API route handlers
src/components/  Reusable web UI
src/lib/         Auth, Supabase, check-in, data and analysis helpers
tests/           Node test-runner tests
```

## License

MIT. See [`LICENSE`](./LICENSE).
