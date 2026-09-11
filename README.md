# Diarium APK (průběžná distribuce)

Tato větev **není zdrojový kód** — slouží jen jako stabilní odkaz ke stažení
nejnovější testovací verze aplikace Diarium pro Android.

| Co | Kde |
|---|---|
| Nejnovější APK | [`diarium.apk`](../../raw/apk/diarium.apk) |
| Zmrazená verze | [`diarium-2.0.0-alpha13.apk`](../../raw/apk/diarium-2.0.0-alpha13.apk) |
| Verze / datum | viz `version.txt` |
| Starší verze | `diarium-2.0.0-alpha11.apk`, `-alpha12.apk` v této větvi |

Zdrojový kód: [`main`](../../tree/main).

## Instalace
1. Soubor otevřít v telefonu (Chrome).
2. Android se zeptá na povolení instalace z tohoto zdroje → povolit.
3. Nainstalovat. **Od alpha11 je APK podepsaný stabilním klíčem (CN=Diarium),
   takže další verze se aktualizují přímo přes sebe** — už není nutné aplikaci
   před každou aktualizací odinstalovat (jednorázově jen při přechodu z
   předchozích debug buildů).

## Co je v alpha13 (M6)
Notifikace, screen time a sync:
- obrazovka **Nastavení notifikací** (Nastavení i dlaždice na Přehledu) — časy
  připomenutí, týdenní/měsíční reflexe, sběr času na obrazovce,
- řádky oprávnění s reálným stavem: oznámení, **Údaje o využití** (čas na
  obrazovce), přesné alarmy,
- sběr času na obrazovce se posílá přímo do Supabase (uživatelským tokenem),
- registrace zařízení pro push notifikace.
