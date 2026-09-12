# Diarium APK (průběžná distribuce)

Tato větev **není zdrojový kód** — slouží jen jako stabilní odkaz ke stažení
nejnovější testovací verze aplikace Diarium pro Android.

| Co | Kde |
|---|---|
| Nejnovější APK | https://raw.githubusercontent.com/Vojta01/diarium-android/apk/diarium.apk |
| Verze | `version.txt` |
| Zdrojový kód | větev `main` tohoto repa |

**Stažení:** otevři odkaz v telefonu a potvrď instalaci. Od verze alpha11 je podpis
stejný, takže se nová verze **instaluje přes předchozí** (data zůstanou).

> Pokud prohlížeč nabídne „otevřít v aplikaci / stáhnout", vyber stažení —
> a v prohlížeči používej `raw.githubusercontent.com`, ne `github.com/.../raw/`
> (ten vrací zkrácený soubor).

---

## alpha20 — Přehled a Statistiky na dotek

- **Návrat zpět je okamžitý a vrátí tě tam, kde jsi byl** — i se zaskrolováním.
  Stav obrazovek se teď drží nad navigací, takže se Přehled při návratu gestem
  **nenačítá znovu od začátku** a nebliknou šedé kostry; ty se ukážou jen při
  úplně prvním načtení (nebo když se obnovují data na pozadí, čísla zůstanou).
- **Tendence u čísel na Přehledu**: u *Času na obrazovce*, *Odemknutí* i *Nálady*
  je šipka nahoru/dolu s **procentem změny proti předchozímu týdnu** a krátká věta,
  co číslo znamená (např. kolik minut denně v průměru, kolik odemknutí denně,
  jaká známka nálady to je a kolik dní ze 7 má záznam).
- **Klepnutí na den v pruhu nálady** (posledních 7 dní) otevře **celý den** —
  náladu, vděčnosti, AI reflexi, kvalitu spánku, energii i čas na obrazovce
  a odemknutí. Funguje i pro dny bez záznamu (řekne, že záznam chybí).
- **Karty Čas na obrazovce / Odemknutí / Nálada jsou klikatelné** — otevřou
  okno s rozkladem: den po dni, průměr, nejlepší a nejhorší den.
- **Statistiky: *Nejlepší den* a *Nejhorší den*** jsou teď karty s datem, dnem
  v týdnu, náladou a kontextem (čas na obrazovce · odemknutí · aktivity).
  Klepnutím se otevře **celý záznam toho dne**; dlouhý seznam aktivit se vejde
  do okna a dá se v něm posouvat.

## alpha19 — grafy a heatmapa

- **Sloupce v grafech** mají gradient (světlejší nahoře) a **kulaté konce** místo
  hranatých a při změně období nebo metriky **plynule narostou** z nulové výšky
- **Průměrná linka** je indigo a tenká — čte se jako orientační čára, ne jako
  druhá datová řada (dřív byla tlustá bílá)
- **Klepnutí na den** v grafu i **na buňku v „Rok v pixelech"** zabliká haptikou;
  vybraná buňka má gradientní rámeček a je plně sytá
- buňky heatmapy mají kulatější rohy (4 dp)
- barevné mapování (bucket podle času na obrazovce, barva podle nálady) zůstalo
  **beze změny** — kvůli paritě s webem

## alpha18 — hlavičky sekcí a klávesnice

- **Hlavičky sekcí v Check-inu** jsou teď prémiové: místo nenápadné tečky mají
  indigo **gradientní proužek** a leží na jemném skleněném pásu, takže je na první
  pohled vidět, kde začíná „Nálada", „Kvalita spánku", „Aktivity"…
- **Šipka sekce se plynule otáčí** (místo skoku mezi dvěma znaky) a klepnutí na
  hlavičku **zabliká haptikou** — je poznat, že se sekce sbalila
- **Klávesnice už nezakrývá pole**: psaní poznámky a reflexe má `imePadding`, takže
  se obsah odscrolluje nad klávesnici (appka kreslí přes celý displej)
- název sekce je polotučný (lepší orientace při projíždění formuláře)

## alpha17 — doladění vzhledu a použitelnosti

**Nejvíc je to vidět na Check-inu** (obrazovka, kterou otevíráš nejčastěji):

- výběr nálady se při klepnutí **plynule zvětší**, orámuje gradientem a **zabliká haptikou**
- chipy a přepínače dostaly gradientní rámeček s akcentem; přepínač má knoflík,
  který se plynule posouvá (ne skokem)
- tlačítko **„Uložit check-in"** je indigo gradient se svitem, uložení potvrdí
  **haptika** a karta „✓ Uloženo"

**Přehled** a **Statistiky s grafy** prošly vizuálním kolem — hero karty s animovanými
čísly, skeleton místo spinneru při načítání, plynulé nástupy prvků, sjednocené
prázdné stavy s jasnou další akcí.

**Odznaky**: odemčené odznaky svítí (glow + gradient), zamčené jsou ztlumené;
načítání kreslí skeleton, chyba má tlačítko „Zkusit znovu".

**Podobrazovky** (Cíle, Odznaky, Škály, Šablony, Notifikace, Export, AI Přehledy)
už **netisknou svůj název dvakrát** — název zůstal jen v horní liště se šipkou zpět,
pod ním je jen kontextový podtitulek.

**Login**: svatozář kolem loga, indigo CTA, chyba v kartě místo holého červeného textu,
jemný nástup prvků.

**AI Přehledy**: tlačítko generování jako indigo CTA s haptikou, nadpisy v reportu
s akcentovým proužkem. **Export do CSV**: průběh jako kroková osa se skeletonem,
tlačítko uložení je výrazné CTA (haptika i při úspěchu).

**Kalendář v Historii**: buňky dne mají stisk s animací, vybraný den gradientní
rámeček, dnešek jemný svit.

**Údaje o využití**: hláška uživatele neposílá k reinstalaci, ale vede přímo
k přepínači v systémovém nastavení (viz níže).

### Z alpha16 (pokud jsi přeskočil)
Nový sdílený design systém (skleněné karty s gradientním okrajem, akcentové ikonové
štítky, animace, haptiky), spodní lišta s posuvným indikátorem a okraje přes celý
displej. Smazaná mrtvá obrazovka „Připravujeme".

### Z alpha15
Obrazovka **Nastavení → 🤖 AI Přehledy** (týdenní/měsíční report, historie období).

---

## Zapnutí „Údaje o využití" (screen time)

1. **Nastavení telefonu → Aplikace → Diarium → Údaje o využití** (nebo
   Nastavení → Aplikace → Speciální přístup → Údaje o využití → Diarium) a zapni přepínač.
2. Pokud je přepínač šedý: **Nastavení → Aplikace → Diarium → ⋮ → Povolit omezená nastavení**.
3. Z PC: `adb shell appops set --uid cz.digitalnivedomi.diarium GET_USAGE_STATS allow`

> Omezená nastavení se **netýkají** „Údajů o využití" — ty patří mezi systémová
> oprávnění (app-op) a u aplikací mimo Google Play se udělují normálně.

---

## Technické údaje (alpha19)

- verze `2.0.0-alpha19` (versionCode `2000019`), minSdk 26
- APK: **14 522 729 B**, sha256 `61fa37f9c688dc3ad9950698e40f12ed2a744f2aec55fac7185883c8bbd95752`
- unit testy: **592 / 592** hotových, 0 chyb
- podpis: stabilní klíč (od alpha11 se verze instalují přes sebe)
- klíče (DeepSeek, Supabase service_role, VAPID, FCM) jsou **jen na serveru**, v APK nejsou
