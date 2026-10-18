package cz.digitalnivedomi.diarium.core.data

import org.json.JSONObject
import java.util.Locale

/**
 * One `entries` row, reduced to exactly what the report prompt reads.
 *
 * A nullable number means "the column had no value" — `null` renders the same
 * way the web's template literal renders a SQL NULL (`nálada null/5`), which is
 * deliberate: this builder is a 1:1 port and the model already sees that shape
 * from the server route today.
 */
data class ReportEntry(
    val date: String,
    val mood: Int? = null,
    val sleepQuality: Int? = null,
    val stress: Int? = null,
    val activities: List<String> = emptyList(),
    /**
     * Habit key → ticked? Since the 2026-10-13 owner decision a stored `true`
     * means "splněno" for EVERY habit — for a negative (avoid) habit the tick
     * means the user avoided it, which is a good day too.
     */
    val habits: Map<String, Boolean> = emptyMap(),
    val gratitude: List<String> = emptyList(),
    val note: String? = null,
    /** Seconds, as stored; null/≤0 means "no data". */
    val phoneScreenTime: Int? = null,
    /** `scales.id` (uuid) → value; the ids are mapped through a name map. */
    val scaleValues: Map<String, Int> = emptyMap(),
)

/** One habit as the prompt names it: label, direction and display order. */
data class ReportHabit(
    val label: String,
    val negative: Boolean,
    val order: Int,
)

/**
 * The on-device port of the web's `buildPeriodPrompt` +
 * `SYSTEM_PROMPTS` (src/app/api/cron/ai-report/route.ts, current
 * habit-dynamic version). The prompt strings are transcribed verbatim on
 * purpose — the phone must produce the same input the server route does, or the
 * reports would drift the moment both paths exist side by side.
 *
 * Pure by design: no Android types, no I/O. Everything is driven from
 * [entries], [scaleNameMap] and [habitMap], so unit tests pin the exact text
 * (period windows are the caller's job — see `ReportGenerator`).
 */
object ReportPromptBuilder {

    /** `SYSTEM_PROMPTS.weekly`, verbatim. */
    val WEEKLY_SYSTEM = """Jsi Diarium AI — asistent pro týdenní reflexi.

Napiš shrnutí uplynulého týdne (3-5 odstavců v češtině):

1. Přehled — shrň náladu za týden (průměr, trend, nejlepší/nejhorší den)
2. Vzorce — co ovlivňovalo náladu? Které aktivity pomáhaly? Souvislosti mezi spánkem, cvičením, screen timem a náladou
3. Návyky — jak šly návyky? Je tam progres?
4. Vděčnost — opakující se témata
5. Doporučení — jedna konkrétní věc na příští týden

Tón: přátelský, jako kamarád. Používej emoji a krátké odstavce. Max 800 znaků."""

    /** `SYSTEM_PROMPTS.monthly`, verbatim. */
    val MONTHLY_SYSTEM = """Jsi Diarium AI — asistent pro měsíční reflexi.

Napiš shrnutí uplynulého měsíce (4-6 odstavců v češtině):

1. Celkový obrázek — průměrná nálada, trend, porovnání
2. Klíčové momenty — nejlepší a nejhorší dny
3. Hlubší vzorce — co ovlivňuje náladu dlouhodobě?
4. Návyky a cíle — progres, úspěchy
5. Vhled — co ses o sobě naučil/a
6. Výhled — co zkusit příští měsíc jinak?

Tón: reflektivní, optimistický. Max 1200 znaků."""

    /** The system prompt for [type] — anything but `monthly` is the weekly one. */
    fun systemPrompt(type: String): String =
        if (type == "monthly") MONTHLY_SYSTEM else WEEKLY_SYSTEM

    /** DeepSeek `max_tokens`, same split as the route (3072 weekly / 4096 monthly). */
    fun maxTokens(type: String): Int = if (type == "monthly") 4096 else 3072

    /**
     * The `user` message — a line-for-line port of `buildPeriodPrompt`. Every
     * branch, label and separator matches the TypeScript; the only additions are
     * explicit types. Output joins with `\n`, and the first line carries its own
     * trailing `\n` exactly like the template literal does.
     *
     * [userName]/[birthYear] prepend the route's `👤 Uživatel` header (omitted
     * while the name is blank); [goals] add the `🎯 Cíle:` section right after
     * the habits line. Both are optional personalisation — no profile and no
     * goals produce exactly the pre-personalisation prompt.
     */
    fun buildPeriodPrompt(
        entries: List<ReportEntry>,
        type: String,
        scaleNameMap: Map<String, String>,
        habitMap: Map<String, ReportHabit>,
        userName: String? = null,
        birthYear: Int? = null,
        goals: List<GoalProgress> = emptyList(),
    ): String {
        val lines = mutableListOf<String>()
        if (!userName.isNullOrBlank()) {
            lines.add("👤 Uživatel: $userName${birthYear?.let { " (narozen $it)" } ?: ""}")
        }
        lines.add("Data za období: ${entries.size} dní\n")

        val moods = entries.mapNotNull { it.mood }.filter { it > 0 }
        val avgMood = if (moods.isNotEmpty()) format1(moods.sum().toDouble() / moods.size) else "?"

        val moodLabels = linkedMapOf(
            5 to "skvěle 😄",
            4 to "dobře 🙂",
            3 to "neutrálně 😐",
            2 to "špatně 😟",
            1 to "hrozně 😡",
        )
        val moodCounts = LinkedHashMap<String, Int>()
        moods.forEach { m ->
            val label = moodLabels[m] ?: "?"
            moodCounts[label] = (moodCounts[label] ?: 0) + 1
        }

        lines.add("Průměrná nálada: $avgMood/5")
        lines.add("Rozložení: " + moodCounts.entries.joinToString(", ") { "${it.key}: ${it.value}×" })

        val stresses = entries.mapNotNull { it.stress }.filter { it > 0 }
        if (stresses.isNotEmpty()) {
            lines.add("Průměrný stres: ${format1(stresses.sum().toDouble() / stresses.size)}/5")
        }

        val sleeps = entries.mapNotNull { it.sleepQuality }.filter { it > 0 }
        if (sleeps.isNotEmpty()) {
            val sleepLabels = linkedMapOf(3 to "skvělý", 2 to "normální", 1 to "špatný")
            val sleepCounts = LinkedHashMap<String, Int>()
            sleeps.forEach { s ->
                val label = sleepLabels[s] ?: "?"
                sleepCounts[label] = (sleepCounts[label] ?: 0) + 1
            }
            lines.add("Spánek: " + sleepCounts.entries.joinToString(", ") { "${it.key}: ${it.value}×" })
        }

        // Scales (Energie, Produktivita) — from the scale_values JSONB; ids are
        // mapped to names through the scales table.
        val scaleSums = LinkedHashMap<String, Pair<Double, Int>>()
        entries.forEach { entry ->
            entry.scaleValues.forEach { (scaleId, value) ->
                val name = scaleNameMap[scaleId] ?: return@forEach
                val current = scaleSums[name] ?: (0.0 to 0)
                scaleSums[name] = (current.first + value) to (current.second + 1)
            }
        }
        if (scaleSums.isNotEmpty()) {
            lines.add(
                "Škály: " + scaleSums.entries.joinToString(", ") { (name, sum) ->
                    "$name: průměr ${format1(sum.first / sum.second)}/5"
                },
            )
        }

        val screenTimes = entries.mapNotNull { it.phoneScreenTime }.filter { it > 0 }
        if (screenTimes.isNotEmpty()) {
            val avgSt = screenTimes.sum().toDouble() / screenTimes.size
            lines.add("Průměrný screen time: ${Math.round(avgSt / 60.0)} min/den")
        }

        val activityCounts = LinkedHashMap<String, Int>()
        entries.forEach { entry ->
            entry.activities.forEach { activity ->
                activityCounts[activity] = (activityCounts[activity] ?: 0) + 1
            }
        }
        val top = activityCounts.entries.sortedByDescending { it.value }.take(10)
        if (top.isNotEmpty()) {
            lines.add("\nNejčastější aktivity: " + top.joinToString(", ") { "${it.key} (${it.value}×)" })
        }

        // Habits: only the user's effective active set (catalogue defaults merged
        // with the active user rows — an inactive row hides the habit), and only
        // those with recorded days. 2026-10-13 owner decision: value=true means
        // "fulfilled" for EVERY habit — a ticked negative (avoid) habit means the
        // user avoided it, which is a good day too. "Splněno" is therefore always
        // the number of `true` days; polarity only adds the "(nežádoucí)" marker.
        val habitCounts = LinkedHashMap<String, Pair<Int, Int>>() // key -> (splněno, nesplněno)
        entries.forEach { entry ->
            entry.habits.forEach { (key, value) ->
                if (!habitMap.containsKey(key)) return@forEach
                val (good, bad) = habitCounts[key] ?: (0 to 0)
                habitCounts[key] = if (value) (good + 1) to bad else good to (bad + 1)
            }
        }
        val habitParts = habitMap.entries
            .sortedBy { it.value.order }
            .filter { habitCounts.containsKey(it.key) }
            .map { (key, info) ->
                val (good, bad) = habitCounts.getValue(key)
                "${info.label}${if (info.negative) " (nežádoucí)" else ""}: splněno ${good}d / nesplněno ${bad}d"
            }
        if (habitParts.isNotEmpty()) lines.add("\nNávyky: " + habitParts.joinToString("; "))

        // Goals — the goals repository's live progress, one line per goal right
        // after the habits. The period label ("den"/"týden"/"měsíc") and the 🔥
        // streak come from [GoalProgress]; without goals the section is skipped.
        if (goals.isNotEmpty()) {
            lines.add("\n🎯 Cíle:")
            goals.forEach { goal ->
                lines.add(
                    "- ${goal.goal.name}: ${goal.count}/${goal.goal.targetCount} (${periodLabelCs(goal.goal.frequency)}) — " +
                        "${if (goal.targetMet) "splněno ✅" else "zbývá ${goal.remaining}"}" +
                        "${if (goal.streak > 0) "; série 🔥${goal.streak}" else ""}",
                )
            }
        }

        val allGratitude = mutableListOf<String>()
        entries.forEach { entry ->
            entry.gratitude.forEach { g -> if (g.trim().isNotEmpty()) allGratitude.add(g.trim()) }
        }
        if (allGratitude.isNotEmpty()) {
            lines.add("\nVděčnost: " + allGratitude.take(15).joinToString("; "))
        }

        val notes = entries.mapNotNull { it.note?.trim()?.takeIf { n -> n.isNotEmpty() } }
        if (notes.isNotEmpty()) {
            lines.add("\nPoznámky: " + notes.take(10).joinToString(" | "))
        }

        if (type == "weekly") {
            lines.add("\n─── Denní přehled ───")
            entries.forEach { entry ->
                val parts = mutableListOf("${entry.date}: nálada ${entry.mood?.toString() ?: "null"}/5")
                entry.sleepQuality?.takeIf { it > 0 }?.let { parts.add("spánek $it/3") }
                entry.stress?.takeIf { it > 0 }?.let { parts.add("stres $it/5") }
                if (entry.activities.isNotEmpty()) {
                    parts.add("aktivity: ${entry.activities.joinToString(", ")}")
                }
                entry.note?.takeIf { it.trim().isNotEmpty() }?.let { parts.add("poznámka: \"$it\"") }
                lines.add(parts.joinToString(" | "))
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * The route's habit merge, in the same order:
     *  1. every `habit_catalog` row flagged `is_default` becomes a habit (its
     *     `key` falling back to nothing when blank — the route assumes a key);
     *  2. every `user_habits` row overrides its key — an **inactive** row deletes
     *     the habit (a hide-override), an active one replaces label/negative and
     *     keeps the catalogue's slot, and a key the catalogue does not know is
     *     appended after the defaults.
     *
     * The result is a `LinkedHashMap` whose iteration order already is the
     * prompt's display order; `order` additionally survives map re-insertion
     * (matching the route's explicit `habitOrder` counter).
     */
    fun mergeHabits(
        catalog: List<JSONObject>,
        userRows: List<JSONObject>,
    ): Map<String, ReportHabit> {
        val map = LinkedHashMap<String, ReportHabit>()
        var habitOrder = 0
        catalog.forEach { h ->
            val key = h.plainString("key")
            if (key.isBlank()) return@forEach
            map[key] = ReportHabit(
                label = h.plainString("label").ifBlank { key },
                negative = h.optBoolean("is_negative", false),
                order = habitOrder++,
            )
        }
        userRows.forEach { h ->
            val key = h.plainString("key")
            if (key.isBlank()) return@forEach
            if (!h.optBoolean("is_active", false)) {
                map.remove(key)
                return@forEach
            }
            val known = map[key]
            map[key] = ReportHabit(
                label = h.plainString("label").ifBlank { key },
                negative = h.optBoolean("is_negative", false),
                order = known?.order ?: habitOrder++,
            )
        }
        return map
    }

    /** JS `toFixed(1)` with a dot decimal separator (Czech prompt, fixed format). */
    private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
}
