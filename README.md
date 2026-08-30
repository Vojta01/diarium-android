# Diarium Android

Nativní obal pro Diarium (https://diarium-two.vercel.app) — WebView + nativní
most pro přesné statistiky používání telefonu z `UsageStatsManager`
(stejná data, která ukazuje Android Digitální rovnováha).

## Proč

Screen-time data sbíraná přes Home Assistant (senzory `interactive` /
`last_used_app`) se systematicky liší od Android `UsageStatsManager`:
HA měří dobu svícení obrazovky a „poslední aplikaci", ne skutečný čas
strávený v aplikacích. Tento wrapper čte statistiky **přímo u zdroje**
a posílá je do Diaria přes `/api/save-entry` — bez HA v cestě.

## Funkce

- ✅ WebView s celým Diariem (stejné UI, auth, Supabase)
- ✅ `UsageStatsProvider` — per-app čas, celkový čas, odemknutí (UsageStatsManager)
- ✅ Automatický denní sync: **21:00** snapshot dneška, **07:00** backfill včera,
      po instalaci backfill posledních 7 dní
- ✅ OAuth přes Chrome Custom Tabs + deep link `diarium://auth-callback`
- 🔜 FCM push notifikace (vyžaduje `google-services.json`, viz níže)

## Build

```bash
# lokální konfigurace (nepovinná — default je /opt/android-sdk)
# echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Instalace (sideload)

1. Zkopíruj APK do telefonu a otevři ho (nebo `adb install app-debug.apk`).
2. Povol „neznámé zdroje" pro instalátor.
3. Po prvním spuštění: **Nastavení → Aplikace → Speciální přístup →
   Přístup k využití → Diarium → povol**. Bez toho nemůže číst statistiky.
4. Přihlas se přes Google (Custom Tab → vrátí se do app).

## Povinné konfigurace v Supabase

V **Authentication → URL Configuration → Redirect URLs** musí být:

```
diarium://auth-callback
```

## Firebase (FCM push — volitelné, fáze 2)

1. Založ projekt na https://console.firebase.google.com (bez Analytics)
2. Přidej Android app s package name `cz.digitalnivedomi.diarium`
3. Stáhni `google-services.json` → umísti do `app/`
4. Stáhni service account JSON → nastav na Vercelu jako
   `FIREBASE_SERVICE_ACCOUNT` (server jím posílá notifikace)
5. Odkomentuj Firebase dependency v `app/build.gradle.kts` a service v manifestu

## Server (Vercel)

Po nasazení nativního zdroje **zastav starý HA cron** (Hermes job
„Diarium screen time sync", e8085d86665e), aby se data nepřepisovala.

## Licence

MIT