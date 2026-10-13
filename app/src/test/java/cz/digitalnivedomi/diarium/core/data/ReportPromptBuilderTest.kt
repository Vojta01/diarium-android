package cz.digitalnivedomi.diarium.core.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device prompt must produce the **same text** the web route's
 * `buildPeriodPrompt` produces — the reports would drift the moment the two
 * builders disagree. The golden strings in this file are transcribed by hand
 * from `src/app/api/cron/ai-report/route.ts` (checked line by line against the
 * current, habit-dynamic version) and the fixture is kept deliberately small so
 * every number in the expected output can be verified by eye.
 */
class ReportPromptBuilderTest {

    // ── golden prompt ───────────────────────────────────────────────────────

    @Test
    fun `the weekly prompt is a verbatim port of the route's builder`() {
        val prompt = ReportPromptBuilder.buildPeriodPrompt(
            entries = weeklyFixture(),
            type = "weekly",
            scaleNameMap = scaleNames,
            habitMap = habitMap,
        )

        assertEquals(expectedWeeklyPrompt, prompt)
    }

    @Test
    fun `the monthly prompt is the same data without the daily overview`() {
        val prompt = ReportPromptBuilder.buildPeriodPrompt(
            entries = weeklyFixture(),
            type = "monthly",
            scaleNameMap = scaleNames,
            habitMap = habitMap,
        )

        assertEquals(expectedPromptWithoutDailyBlock, prompt)
        assertFalse("monthly must not carry the weekly block", prompt.contains("Denní přehled"))
    }

    @Test
    fun `a day without a mood renders null, exactly like the route`() {
        val entries = listOf(
            ReportEntry(date = "2026-10-05", mood = 4),
            ReportEntry(date = "2026-10-06", mood = null),
        )

        val prompt = ReportPromptBuilder.buildPeriodPrompt(entries, "weekly", emptyMap(), emptyMap())

        assertTrue(prompt.contains("2026-10-06: nálada null/5"))
    }

    @Test
    fun `empty sections are omitted, averages fall back to a question mark`() {
        val entries = listOf(
            ReportEntry(date = "2026-10-05", mood = 0),
            ReportEntry(date = "2026-10-06", mood = null),
        )

        val prompt = ReportPromptBuilder.buildPeriodPrompt(entries, "weekly", emptyMap(), emptyMap())

        // No mood > 0 → avg "?", no stress/sleep/scales/screen/activities/habits/
        // gratitude/notes lines at all; the daily block still prints every day.
        assertTrue(prompt.contains("Průměrná nálada: ?/5"))
        assertTrue("an empty distribution renders without entries", prompt.contains("Rozložení: \n"))
        assertFalse(prompt.contains("Průměrný stres"))
        assertFalse(prompt.contains("Škály"))
        assertFalse(prompt.contains("Nejčastější aktivity"))
        assertTrue(prompt.contains("2026-10-05: nálada 0/5"))
        assertTrue(prompt.contains("2026-10-06: nálada null/5"))
    }

    // ── personalisation: profile header + goals ─────────────────────────────

    @Test
    fun `a profile name and birth year prepend the user header`() {
        val prompt = ReportPromptBuilder.buildPeriodPrompt(
            entries = weeklyFixture(),
            type = "weekly",
            scaleNameMap = scaleNames,
            habitMap = habitMap,
            userName = "Vojta",
            birthYear = 1990,
        )

        assertTrue(prompt.startsWith("👤 Uživatel: Vojta (narozen 1990)\n"))
        assertTrue("the period line keeps its own trailing newline", prompt.contains("\nData za období: 2 dní\n"))
    }

    @Test
    fun `the header omits the birth year when the profile has none`() {
        val prompt = ReportPromptBuilder.buildPeriodPrompt(
            entries = weeklyFixture(),
            type = "weekly",
            scaleNameMap = scaleNames,
            habitMap = habitMap,
            userName = "Vojta",
        )

        assertTrue(prompt.startsWith("👤 Uživatel: Vojta\n"))
        assertFalse(prompt.contains("narozen"))
    }

    @Test
    fun `a blank or missing name omits the header entirely`() {
        val blank = ReportPromptBuilder.buildPeriodPrompt(
            weeklyFixture(), "weekly", scaleNames, habitMap, userName = "   ",
        )
        val missing = ReportPromptBuilder.buildPeriodPrompt(
            weeklyFixture(), "weekly", scaleNames, habitMap,
        )

        assertFalse(blank.contains("👤"))
        assertFalse(missing.contains("👤"))
        assertTrue(blank.startsWith("Data za období: 2 dní\n"))
        assertTrue(missing.startsWith("Data za období: 2 dní\n"))
    }

    @Test
    fun `goals render as a Czech section right after the habits`() {
        val goals = listOf(
            GoalProgress(
                goal = Goal(
                    id = "goal-1",
                    activityKey = "beh",
                    name = "Běhání",
                    targetCount = 3,
                    frequency = "weekly",
                    isActive = true,
                ),
                count = 2,
                streak = 4,
            ),
            GoalProgress(
                goal = Goal(
                    id = "goal-2",
                    activityKey = "cteni",
                    name = "Čtení",
                    targetCount = 2,
                    frequency = "daily",
                    isActive = true,
                ),
                count = 2,
                streak = 0,
            ),
        )

        val prompt = ReportPromptBuilder.buildPeriodPrompt(
            entries = weeklyFixture(),
            type = "weekly",
            scaleNameMap = scaleNames,
            habitMap = habitMap,
            goals = goals,
        )

        assertTrue(prompt.contains("\n🎯 Cíle:"))
        // One line per goal: unmet goals count down, met ones get the check —
        // and only a live streak carries the 🔥 suffix.
        assertEquals(
            listOf(
                "- Běhání: 2/3 (týden) — zbývá 1; série 🔥4",
                "- Čtení: 2/2 (den) — splněno ✅",
            ),
            prompt.lines().filter { it.startsWith("- ") },
        )
        assertTrue("goals follow the habits line", prompt.indexOf("Návyky:") < prompt.indexOf("🎯 Cíle:"))
    }

    // ── system prompts + token budgets ──────────────────────────────────────

    @Test
    fun `system prompts and max tokens match the route`() {
        assertTrue(ReportPromptBuilder.WEEKLY_SYSTEM.startsWith("Jsi Diarium AI — asistent pro týdenní reflexi."))
        assertTrue(ReportPromptBuilder.WEEKLY_SYSTEM.endsWith("Max 800 znaků."))
        assertTrue(ReportPromptBuilder.MONTHLY_SYSTEM.startsWith("Jsi Diarium AI — asistent pro měsíční reflexi."))
        assertTrue(ReportPromptBuilder.MONTHLY_SYSTEM.endsWith("Max 1200 znaků."))

        assertEquals(ReportPromptBuilder.WEEKLY_SYSTEM, ReportPromptBuilder.systemPrompt("weekly"))
        assertEquals(ReportPromptBuilder.MONTHLY_SYSTEM, ReportPromptBuilder.systemPrompt("monthly"))
        assertEquals(3072, ReportPromptBuilder.maxTokens("weekly"))
        assertEquals(4096, ReportPromptBuilder.maxTokens("monthly"))
    }

    // ── habit merge ─────────────────────────────────────────────────────────

    @Test
    fun `catalogue defaults keep their slot, an override replaces label and flag`() {
        val merged = ReportPromptBuilder.mergeHabits(
            catalog = listOf(
                habitRow("alkohol", "Alkohol", negative = true),
                habitRow("spanek", "Spánek", negative = false),
            ),
            userRows = listOf(
                userHabitRow("alkohol", "Alkohol (nově)", negative = false, active = true),
            ),
        )

        assertEquals(listOf("alkohol", "spanek"), merged.keys.toList())
        val alkohol = merged.getValue("alkohol")
        assertEquals("Alkohol (nově)", alkohol.label)
        assertFalse(alkohol.negative)
        assertEquals("the override keeps the catalogue's order slot", 0, alkohol.order)
    }

    @Test
    fun `an inactive user row hides its habit and an active custom one is appended`() {
        val merged = ReportPromptBuilder.mergeHabits(
            catalog = listOf(
                habitRow("alkohol", "Alkohol", negative = true),
                habitRow("spanek", "Spánek", negative = false),
            ),
            userRows = listOf(
                userHabitRow("spanek", "Spánek", negative = false, active = false),
                userHabitRow("koureni", "Kouření", negative = true, active = true),
            ),
        )

        assertEquals(listOf("alkohol", "koureni"), merged.keys.toList())
        assertEquals(2, merged.getValue("koureni").order)
    }

    @Test
    fun `a blank label falls back to the key like the route's fallback chain`() {
        val merged = ReportPromptBuilder.mergeHabits(
            catalog = listOf(habitRow("spanek", "", negative = false, labelMissing = true)),
            userRows = emptyList(),
        )

        assertEquals("spanek", merged.getValue("spanek").label)
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun weeklyFixture(): List<ReportEntry> = listOf(
        ReportEntry(
            date = "2026-10-05",
            mood = 4,
            sleepQuality = 2,
            stress = 3,
            activities = listOf("práce", "sport"),
            habits = linkedMapOf("alkohol" to false, "spanek" to true),
            gratitude = listOf("rodina"),
            note = " dobrý den ",
            phoneScreenTime = 3600,
            scaleValues = linkedMapOf("id-energie" to 4),
        ),
        ReportEntry(
            date = "2026-10-06",
            mood = 5,
            sleepQuality = 3,
            stress = 0,
            activities = listOf("sport"),
            habits = linkedMapOf("alkohol" to true, "spanek" to true),
            gratitude = emptyList(),
            note = null,
            phoneScreenTime = null,
            scaleValues = linkedMapOf("id-energie" to 3, "id-produkt" to 2),
        ),
    )

    private val scaleNames = mapOf("id-energie" to "Energie", "id-produkt" to "Produktivita")

    private val habitMap = linkedMapOf(
        "alkohol" to ReportHabit("Alkohol", negative = true, order = 0),
        "spanek" to ReportHabit("Spánek", negative = false, order = 1),
    )

    private fun habitRow(
        key: String,
        label: String,
        negative: Boolean,
        labelMissing: Boolean = false,
    ): JSONObject = JSONObject().apply {
        put("key", key)
        if (!labelMissing) put("label", label)
        put("is_negative", negative)
    }

    private fun userHabitRow(key: String, label: String, negative: Boolean, active: Boolean): JSONObject =
        JSONObject().apply {
            put("key", key)
            put("label", label)
            put("is_negative", negative)
            put("is_active", active)
        }

    private companion object {

        /**
         * Hand-written expected output of the route's `buildPeriodPrompt` for
         * [weeklyFixture]. Line by line (the first element carries the template's
         * own trailing newline, so the prompt starts with a blank line after it):
         *
         *  Data za období: 2 dní
         *  (blank)
         *  Průměrná nálada: 4.5/5        ← (4+5)/2, toFixed(1)
         *  Rozložení: dobře 🙂: 1×, skvěle 😄: 1×
         *  Průměrný stres: 3.0/5
         *  Spánek: normální: 1×, skvělý: 1×
         *  Škály: Energie: průměr 3.5/5, Produktivita: průměr 2.0/5
         *  Průměrný screen time: 60 min/den   ← 3600 s / 60
         *  (blank) Nejčastější aktivity: sport (2×), práce (1×)
         *  (blank) Návyky: Alkohol (nežádoucí): splněno 1d / nesplněno 1d; Spánek: splněno 2d / nesplněno 0d
         *            ← true = "splněno" for EVERY habit (2026-10-13 decision); alkohol: false 10-05,
         *              true 10-06 (the "(nežádoucí)" marker is polarity's only trace); spánek: true both days
         *  (blank) Vděčnost: rodina
         *  (blank) Poznámky: dobrý den      ← trimmed; the daily block keeps the raw note
         *  (blank) ─── Denní přehled ───
         *  2026-10-05: nálada 4/5 | spánek 2/3 | stres 3/5 | aktivity: práce, sport | poznámka: " dobrý den "
         *  2026-10-06: nálada 5/5 | spánek 3/3 | aktivity: sport
         */
        val expectedPromptLines = listOf(
            "Data za období: 2 dní\n",
            "Průměrná nálada: 4.5/5",
            "Rozložení: dobře 🙂: 1×, skvěle 😄: 1×",
            "Průměrný stres: 3.0/5",
            "Spánek: normální: 1×, skvělý: 1×",
            "Škály: Energie: průměr 3.5/5, Produktivita: průměr 2.0/5",
            "Průměrný screen time: 60 min/den",
            "\nNejčastější aktivity: sport (2×), práce (1×)",
            "\nNávyky: Alkohol (nežádoucí): splněno 1d / nesplněno 1d; Spánek: splněno 2d / nesplněno 0d",
            "\nVděčnost: rodina",
            "\nPoznámky: dobrý den",
        )

        val dailyBlockLines = listOf(
            "\n─── Denní přehled ───",
            "2026-10-05: nálada 4/5 | spánek 2/3 | stres 3/5 | aktivity: práce, sport | poznámka: \" dobrý den \"",
            "2026-10-06: nálada 5/5 | spánek 3/3 | aktivity: sport",
        )

        val expectedWeeklyPrompt: String = (expectedPromptLines + dailyBlockLines).joinToString("\n")

        val expectedPromptWithoutDailyBlock: String = expectedPromptLines.joinToString("\n")
    }
}
