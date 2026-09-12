# Diarium APK (průběžná distribuce)

Tato větev **není zdrojový kód** — slouží jen jako stabilní odkaz ke stažení
nejnovější testovací verze aplikace Diarium pro Android.

| Co | Kde |
|---|---|
| Nejnovější APK | [`diarium.apk`](../../raw/apk/diarium.apk) |
| Zmrazená verze | [`diarium-2.0.0-alpha15.apk`](../../raw/apk/diarium-2.0.0-alpha15.apk) |
| Verze / datum | viz `version.txt` |
| Starší verze | `diarium-2.0.0-alpha12.apk`, `-alpha13.apk`, `-alpha14.apk` v této větvi |

Zdrojový kód: [`main`](../../tree/main).

## Instalace
1. Soubor otevřít v telefonu (Chrome).
2. Android se zeptá na povolení instalace z tohoto zdroje → povolit.
3. Nainstalovat. **Od alpha11 je APK podepsaný stabilním klíčem (CN=Diarium),
   takže další verze se aktualizují přímo přes sebe** — už není nutné aplikaci
   před každou aktualizací odinstalovat (jednorázově jen při přechodu z
   předchozích debug buildů).

## Co je v alpha15

**Nové: AI Přehledy** (Nastavení → 🤖 AI Přehledy)

Reporty se v aplikaci dosud jen generovaly na pozadí, ale nebyly nikde k přečtení.
Nová obrazovka je ukazuje:

- **Týdenní a měsíční přehled** — přepínač v horní části, u každého je datum
  období („1. 9. – 8. 9. 2026“) a kdy byl vygenerován (v českém čase).
- **Tlačítko „Vygenerovat nový“** — vyžádá nový report; aplikace pak čeká na
  výsledek a rovnou ho zobrazí. Během generování je vidět stav.
- Text je formátovaný (nadpisy, odstavce, odrážky) a čte se pohodlně na mobilu.
- Bez připojení / při chybě se místo reportu ukáže srozumitelná hláška s
  tlačítkem „Zkusit znovu“ (+ když už je načtený starší report, zůstane na
  obrazovce).
- Aplikace posílá **jen přihlašovací token uživatele** — žádný serverový klíč
  v APK není.

### Z alpha14 (pokud jsi přeskočil)

- **Karty na Přehledu se ptají na poslední zaznamenaný den**, ne na dnešek:
  ranní synchronizace z telefonu (bez vyplněné nálady) den nezaloží, takže
  „AI reflexe“ i „Nejpoužívanější aplikace“ ukážou poslední plná data a datum
  napíšou do popisku karty. Když poslední zaznamenaný den reflexi ještě nemá,
  zůstane na obrazovce poslední existující reflexe s vlastním datem.
- **Graf je rozdělený**: Přehled má tři karty (📱 čas na obrazovce,
  🔓 počet odemknutí, 🏆 aplikace), Statistiky dva samostatné grafy — každý
  s vlastní stupnicí a průměrem. Screen time a odemknutí se už nemíchají do
  jednoho grafu.
- **Export do CSV** (Nastavení → Data → 📤 Export do CSV): uloží se přes
  systémové okno „kam uložit“, sloupce jsou 1:1 s webem, žádné nové oprávnění.

## Technické
- Balíček: `cz.digitalnivedomi.diarium`, `minSdk 26`.
- Podpis: stabilní klíč `CN=Diarium` (SHA-256 `0804b65b…`).
- Testy: 592/592 zelených (`testDebugUnitTest`).
