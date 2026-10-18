package cz.digitalnivedomi.diarium.ui.i18n

import cz.digitalnivedomi.diarium.core.i18n.AppLanguage

/**
 * Czech -> English translation for UI chrome.
 *
 * Lookup order:
 *  1. [EnStrings.exact] — the 680 static strings, keyed by the exact Czech literal.
 *  2. [RULES] — ~55 patterns for strings built at runtime (counts, dates, names, plurals).
 *  3. Fallback — return the Czech text unchanged. A missed string shows Czech in the English
 *     UI (visible, not broken) instead of crashing or rendering an empty label.
 *
 * The rules exist because Czech and English pluralise differently: "1 den / 3 dny / 5 dní"
 * all collapse to "1 day / 3 days / 5 days" in English. Matching the finished string is more
 * reliable than trying to parameterise every one of the 80 dynamic call sites.
 */
fun translate(text: String): String {
    if (!AppLanguage.isEnglish()) return text
    EnStrings.exact[text]?.let { return it }
    for (rule in RULES) {
        val match = rule.regex.matchEntire(text) ?: continue
        return rule.build(match)
    }
    return text
}

/** Alias for non-composable code (notifications, workers, repositories). */
fun tr(text: String): String = translate(text)

private fun days(n: Int): String = if (n == 1) "1 day" else "$n days"

private fun entries(n: Int): String = if (n == 1) "1 entry" else "$n entries"

private fun unlocks(n: Int): String = if (n == 1) "1 unlock" else "$n unlocks"

private fun periods(n: Int): String = if (n == 1) "1 period" else "$n periods"

private fun moodWord(cs: String): String = when (cs) {
    "hrozná" -> "terrible"
    "špatná" -> "bad"
    "průměrná" -> "average"
    "dobrá" -> "good"
    "skvělá" -> "great"
    else -> cs
}

private fun periodWord(cs: String): String = when (cs) {
    "týden" -> "week"
    "měsíc" -> "month"
    "den" -> "day"
    else -> cs
}

private class Rule(val regex: Regex, val build: (MatchResult) -> String)

private fun rule(pattern: String, build: (MatchResult) -> String) =
    Rule(Regex(pattern), build)

private val RULES: List<Rule> = listOf(
    // --- goals / periods -------------------------------------------------------------
    rule("""^Odemčeno (\d+)/(\d+)$""") { m ->
        "Unlocked ${m.groupValues[1]}/${m.groupValues[2]}"
    },
    rule("""^Odemčeno (.+)$""") { m -> "Unlocked ${m.groupValues[1]}" },
    rule("""^(\d+)/(\d+) období ✓ \((\d+) %\)$""") { m ->
        "${m.groupValues[1]}/${m.groupValues[2]} periods ✓ (${m.groupValues[3]} %)"
    },
    rule("""^cíl (\d+)× za (.+)$""") { m ->
        "goal ${m.groupValues[1]}× per ${periodWord(m.groupValues[2])}"
    },
    rule("""^Probíhá — zbývají (\d+) dny\.$""") { m ->
        "In progress — ${days(m.groupValues[1].toInt())} left."
    },
    rule("""^Probíhá — zbývá (\d+) dní\.$""") { m ->
        "In progress — ${days(m.groupValues[1].toInt())} left."
    },
    rule("""^Týden (.+)$""") { m -> "Week ${m.groupValues[1]}" },
    rule("""^V měsíci (.+)$""") { m -> "In ${m.groupValues[1]}" },
    rule("""^Za (.+) ji ještě nemáš\.$""") { m ->
        "You don't have it for ${m.groupValues[1]} yet."
    },
    rule("""^Některé cíle se nepodařilo uložit \((.+)\)\. Zkus to prosím znovu\.$""") { m ->
        "Some goals could not be saved (${m.groupValues[1]}). Please try again."
    },
    rule("""^(\d+) dní s aktivitou$""") { m ->
        "${days(m.groupValues[1].toInt())} with activity"
    },
    rule("""^série 🔥(\d+)$""") { m -> "🔥${m.groupValues[1]} streak" },
    rule("""^streak 🔥 (\d+) dní$""") { m ->
        "🔥 ${days(m.groupValues[1].toInt())} streak"
    },
    rule("""^(\d+) (?:den|dny|dní) v řadě$""") { m ->
        "${days(m.groupValues[1].toInt())} in a row"
    },
    rule("""^Nejdelší série \(za (\d+) dní\)$""") { m ->
        "Longest streak (over ${days(m.groupValues[1].toInt())})"
    },
    rule("""^· (\d+) dní$""") { m -> "· ${days(m.groupValues[1].toInt())}" },
    rule("""^(\d+) dnů$""") { m -> days(m.groupValues[1].toInt()) },
    rule("""^Cíle (\d+)/(\d+)$""") { m ->
        "Goals ${m.groupValues[1]}/${m.groupValues[2]}"
    },
    rule("""^🎯 Cíle · (\d+)/(\d+) splněno$""") { m ->
        "🎯 Goals · ${m.groupValues[1]}/${m.groupValues[2]} met"
    },

    // --- dashboard / mood ------------------------------------------------------------
    rule("""^(.+) (.+) nálada$""") { m ->
        "${m.groupValues[1]} ${moodWord(m.groupValues[2])} mood"
    },
    rule("""^(\d+) záznam(?:y|ů)?$""") { m -> entries(m.groupValues[1].toInt()) },
    rule("""^V tomto měsíci máš (\d+) (.+)\.$""") { m ->
        "You have ${entries(m.groupValues[1].toInt())} this month."
    },
    rule("""^U (\d+) máš jen automatická data bez nálady — jako zápisy se nepočítají\.$""") { m ->
        "For ${m.groupValues[1]}, there is automatic data but no mood — it does not count as an entry."
    },
    rule("""^Celkem za posledních (\d+) dní$""") { m ->
        "Total over the last ${days(m.groupValues[1].toInt())}"
    },
    rule("""^za posledních (\d+) dní$""") { m ->
        "over the last ${days(m.groupValues[1].toInt())}"
    },
    rule("""^Ø (\d+) odemknutí na den$""") { m ->
        "Ø ${unlocks(m.groupValues[1].toInt())} per day"
    },
    rule("""^(\d+) odemknutí$""") { m -> unlocks(m.groupValues[1].toInt()) },
    rule("""^Skryté \((\d+)\)$""") { m -> "Hidden (${m.groupValues[1]})" },

    // --- check-in dialog / history ---------------------------------------------------
    rule("""^Den (.+) \((.+)\) už má vyplněný zápis\.$""") { m ->
        "The day ${m.groupValues[1]} (${m.groupValues[2]}) already has an entry."
    },
    rule("""^Přesunout sem zápis z (.+) \((.+)\)$""") { m ->
        "Move the entry here from ${m.groupValues[1]} (${m.groupValues[2]})"
    },
    rule("""^(\d+)\. věc\.\.\.$""") { m -> "${m.groupValues[1]}. thing…" },
    rule("""^Jeden řádek na den, od nejstaršího\. Hlavička: (.+)$""") { m ->
        "One row per day, oldest first. Header: ${m.groupValues[1]}"
    },
    rule("""^Sloupce = denní nálada, indigo čára = (.+)\. Klepni na den pro detail\.$""") { m ->
        "Bars = daily mood, indigo line = ${m.groupValues[1]}. Tap a day for detail."
    },
    rule("""^Sloupce = průměrná nálada za měsíc, indigo čára = (.+)\.$""") { m ->
        "Bars = average mood per month, indigo line = ${m.groupValues[1]}."
    },
    rule("""^Nejlepší: (.+) (\d+\.\d+) / 5 ·$""") { m ->
        "Best: ${m.groupValues[1]} ${m.groupValues[2]} / 5 ·"
    },
    rule("""^nejslabší: (.+) (\d+\.\d+) / 5$""") { m ->
        "weakest: ${m.groupValues[1]} ${m.groupValues[2]} / 5"
    },
    rule("""^(\d+)měsíční klouzavý průměr$""") { m ->
        "${m.groupValues[1]}-month moving average"
    },
    rule("""^(\d+)denní klouzavý průměr$""") { m ->
        "${m.groupValues[1]}-day moving average"
    },
    rule("""^Posledních (\d+) dní$""") { m ->
        "Last ${days(m.groupValues[1].toInt())}"
    },
    rule("""^Zobrazuju posledních (\d+) dní, takže starší dny roku$""") { m ->
        "Showing the last ${days(m.groupValues[1].toInt())}, so older days of the year"
    },
    rule("""^(\d+)/(\d+) sledovaných dní$""") { m ->
        "${m.groupValues[1]}/${m.groupValues[2]} tracked days"
    },
    rule("""^(.+) — bez odpovědi$""") { m -> "${m.groupValues[1]} — no answer" },
    rule("""^Naposledy zapsaná (.+)\.$""") { m -> "Last recorded ${m.groupValues[1]}." },
    rule("""^Za posledních (.+) žádný zápis$""") { m ->
        "No entry over the last ${m.groupValues[1]}"
    },

    // --- screen time / unlocks -------------------------------------------------------
    rule("""^Chvilku strpení, stahuju posledních (\d+) dní\.$""") { m ->
        "One moment, loading the last ${days(m.groupValues[1].toInt())}."
    },
    rule("""^Za posledních (\d+) dní, podle času na obrazovce\.$""") { m ->
        "Over the last ${days(m.groupValues[1].toInt())}, by screen time."
    },
    rule("""^Za posledních (\d+) dní nemám žádná data o času na obrazovce\.$""") { m ->
        "I have no screen-time data for the last ${days(m.groupValues[1].toInt())}."
    },
    rule("""^Za posledních (\d+) dní nemám žádná data o odemknutích\.$""") { m ->
        "I have no unlock data for the last ${days(m.groupValues[1].toInt())}."
    },
    rule("""^Posledních (\d+) dní \((.+) – (.+)\)\. Sloupec = jeden den, přerušovaná čára = průměr\.$""") { m ->
        "Last ${days(m.groupValues[1].toInt())} (${m.groupValues[2]} – ${m.groupValues[3]}). One bar = one day, dashed line = average."
    },
    rule("""^Posledních (\d+) dní \((.+) – (.+)\), stejné období jako čas na obrazovce\.$""") { m ->
        "Last ${days(m.groupValues[1].toInt())} (${m.groupValues[2]} – ${m.groupValues[3]}), the same period as screen time."
    },

    // --- notifications ---------------------------------------------------------------
    rule("""^Kanál: (.+)$""") { m -> "Channel: ${m.groupValues[1]}" },
    rule("""^Pošle testovací připomenutí teď, na kanálu (.+)\.$""") { m ->
        "Sends a test reminder now, on the ${m.groupValues[1]} channel."
    },
    rule("""^Testovací notifikace odeslána na kanál (.+)\.$""") { m ->
        "Test notification sent on the ${m.groupValues[1]} channel."
    },

    // --- AI / reports / templates ----------------------------------------------------
    rule("""^Vygenerováno (.+) v (.+)$""") { m ->
        "Generated ${m.groupValues[1]} at ${m.groupValues[2]}"
    },
    rule("""^Vytvořeno (.+)$""") { m -> "Created ${m.groupValues[1]}" },
    rule("""^Opravdu chceš šablonu „(.+)“ smazat\? Nedá se to vrátit\.$""") { m ->
        "Do you really want to delete the template \"${m.groupValues[1]}\"? This cannot be undone."
    },
    rule("""^Škála „(.+)“ se smaže\. Tuto akci nelze vrátit\.$""") { m ->
        "The scale \"${m.groupValues[1]}\" will be deleted. This cannot be undone."
    },
    rule("""^Rozsah (\d+)–(\d+) je příliš široký pro graf\.$""") { m ->
        "The range ${m.groupValues[1]}–${m.groupValues[2]} is too wide for a chart."
    },
    rule("""^(.+) \((.+)\)\. Zkus to za chvíli znovu\.$""") { m ->
        "${m.groupValues[1]} (${m.groupValues[2]}). Try again in a moment."
    },
    rule("""^(.+)\. Zkus to za chvíli znovu\.$""") { m ->
        "${m.groupValues[1]}. Try again in a moment."
    },
    rule("""^průměr · (\d+)×$""") { m -> "average · ${m.groupValues[1]}×" },
    rule("""^(.+) denně$""") { m -> "${m.groupValues[1]} daily" },

    // --- network errors --------------------------------------------------------------
    rule("""^Načtení \((.+)\) selhalo \(HTTP (\d+)\)\.$""") { m ->
        "Loading (${m.groupValues[1]}) failed (HTTP ${m.groupValues[2]})."
    },
    rule("""^Načtení přehledu se nezdařilo \((.+)\)\.?$""") { m ->
        "Failed to load the overview (${m.groupValues[1]})."
    },
    rule("""^Načtení zápisů se nezdařilo \((.+)\)\.?$""") { m ->
        "Failed to load entries (${m.groupValues[1]})."
    },
    rule("""^Uložení se nezdařilo \((.+)\)$""") { m ->
        "Saving failed (${m.groupValues[1]})"
    },
    rule("""^Původní den se nepodařilo smazat \((.+)\)\.$""") { m ->
        "Could not delete the original day (${m.groupValues[1]})."
    },
    rule("""^Uloženo: (.+) \((.+)\)\.$""") { m ->
        "Saved: ${m.groupValues[1]} (${m.groupValues[2]})."
    },
    rule("""^Neplatné období \((\d+)/(\d+)\)\.$""") { m ->
        "Invalid period (${m.groupValues[1]}/${m.groupValues[2]})."
    },
    rule("""^zbývá (\d+)$""") { m -> "${m.groupValues[1]} left" },
)
