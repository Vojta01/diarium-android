# Diarium APK (průběžná distribuce)

Tato větev **není zdrojový kód** — slouží jen jako stabilní odkaz ke stažení
nejnovější testovací verze aplikace Diarium pro Android.

| Co | Kde |
|---|---|
| Nejnovější APK | [`diarium.apk`](../../raw/apk/diarium.apk) |
| Zmrazená verze | [`diarium-2.0.0-alpha16.apk`](../../raw/apk/diarium-2.0.0-alpha16.apk) |
| Verze / datum | viz `version.txt` |
| Starší verze | `diarium-2.0.0-alpha12 … -alpha15.apk` v této větvi |

Zdrojový kód: [`main`](../../tree/main).

## Instalace
1. Soubor otevřít v telefonu (Chrome).
2. Android se zeptá na povolení instalace z tohoto zdroje → povolit.
3. Nainstalovat. **Od alpha11 je APK podepsaný stabilním klíčem (CN=Diarium),
   takže další verze se aktualizují přímo přes sebe.**

## Co je v alpha16

**Nové: přepracovaný vzhled celé aplikace.** Přibyl jednotný design systém —
skleněné karty s gradientním okrajem a jemným svitem, akcentové ikonové štítky,
typografická škála, sjednocené rozestupy a sbírka animovaných prvků (počítadla,
která dopočítají čísla, skeleton při načítání, nástup obsahu po částech).

- **Spodní lišta** je animovaná — indikátor se plynule posouvá mezi ikonami,
  klepnutí doprovází jemná haptická odezva. Obsah jde přes celý displej
  (edge-to-edge) se správným odsazením od systémových lišt.
- **Pozadí** (tmavý gradient s jemnou mřížkou) teď kreslí jen jednou shell,
  takže se obrazovky nepřekrývají dvěma vrstvami.
- **Odznaky**: skleněné karty, odemčené odznaky svítí a mají gradient, zamčené
  jsou ztlumené s obrysem; místo spinneru skeleton, při chybě jasná hláška
  s tlačítkem „Zkusit znovu“.
- **Podobrazovky** (Cíle, Odznaky, Škály, Šablony, Export do CSV, AI Přehledy,
  Nastavení notifikací) už netisknou svůj název dvakrát — titulek zůstal jen
  v horní liště se šipkou zpět.
- **Údaje o využití**: hláška uživatele neposílá k reinstalaci aplikace, ale vede
  k přepínači v systémovém nastavení (přesně jak to na Androidu funguje).

### Z alpha15 (pokud jsi přeskočil)

- **AI Přehledy** (Nastavení → 🤖 AI Přehledy): týdenní a měsíční report
  s datem období, tlačítkem „Vygenerovat nový“ a formátovaným textem.

### Z alpha14 (pokud jsi přeskočil)

- **Karty na Přehledu se ptají na poslední zaznamenaný den**, ne na dnešek
  (ranní synchronizace bez nálady den nezaloží) a datum píšou do popisku karty.
- **Graf je rozdělený**: Přehled má tři karty (📱 čas na obrazovce, 🔓 odemknutí,
  🏆 aplikace), Statistiky dva samostatné grafy s vlastní stupnicí a průměrem.
- **Export do CSV** (Nastavení → Data → 📤 Export do CSV): sloupce 1:1 s webem.

## Technické
- Balíček: `cz.digitalnivedomi.diarium`, `minSdk 26`.
- Podpis: stabilní klíč `CN=Diarium` (SHA-256 `0804b65b…`).
- Testy: 592/592 zelených (`testDebugUnitTest`).
