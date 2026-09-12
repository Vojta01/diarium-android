# Diarium APK (průběžná distribuce)

Tato větev **není zdrojový kód** — slouží jen jako stabilní odkaz ke stažení
nejnovější testovací verze aplikace Diarium pro Android.

| Co | Kde |
|---|---|
| Nejnovější APK | [`diarium.apk`](../../raw/apk/diarium.apk) |
| Zmrazená verze | [`diarium-2.0.0-alpha14.apk`](../../raw/apk/diarium-2.0.0-alpha14.apk) |
| Verze / datum | viz `version.txt` |
| Starší verze | `diarium-2.0.0-alpha12.apk`, `-alpha13.apk` v této větvi |

Zdrojový kód: [`main`](../../tree/main).

## Instalace
1. Soubor otevřít v telefonu (Chrome).
2. Android se zeptá na povolení instalace z tohoto zdroje → povolit.
3. Nainstalovat. **Od alpha11 je APK podepsaný stabilním klíčem (CN=Diarium),
   takže další verze se aktualizují přímo přes sebe** — už není nutné aplikaci
   před každou aktualizací odinstalovat (jednorázově jen při přechodu z
   předchozích debug buildů).

## Co je v alpha14

Přehled, grafy a export:
- **Karty na Přehledu se už neptají na dnešek, ale na poslední zaznamenaný den.**
  Ranní synchronizace z telefonu (bez vyplněné nálady) den nezaloží, takže
  „AI reflexe“ i „Nejpoužívanější aplikace“ ukážou včerejší plná data a datum
  napíšou do popisku karty. Když poslední zaznamenaný den reflexi ještě nemá,
  zůstane na obrazovce poslední existující reflexe s vlastním datem.
- **Graf času na obrazovce je rozdělený na tři samostatné karty** — 📱 čas na
  obrazovce, 🔓 počet odemknutí a 🏆 nejpoužívanější aplikace. Každá má vlastní
  stupnici, takže odemknutí nezaniknou vedle šestihodinového sloupce, a hodnota
  je vypsaná pod sloupcem.
- **Ve Statistikách jsou z toho dva grafy** (čas na obrazovce v sekundách a
  počet odemknutí), každý s vlastními pásmy, průměrem a součty. Čísla jsou
  pořád 1:1 s webem.
- **Nově Export do CSV** (Nastavení → Data → 📤 Export do CSV): všechny zápisy
  do souboru, který si uložíš přes systémové okno. Stejné sloupce, pořadí i
  escapování jako u exportu na webu, diakritika v UTF-8. Aplikace k tomu
  nepotřebuje žádné nové oprávnění a nikam neposílá žádný klíč — data čte
  přímo z tvého účtu tvým přihlašovacím tokenem.
