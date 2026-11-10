package cz.digitalnivedomi.diarium.ui.screenshots

import cz.digitalnivedomi.diarium.core.data.HttpRequest
import cz.digitalnivedomi.diarium.core.data.HttpResponse
import cz.digitalnivedomi.diarium.core.data.HttpTransport
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * An in-process stand-in for the Supabase/PostgREST reads the eight documentation
 * screens make. It is a real [HttpTransport], so the screens run through their
 * production repositories, state holders and Compose code — only the network is
 * replaced by deterministic, synthetic fixture data.
 *
 * The fixture is anchored to [today] — the pinned screenshot date (2026-11-07 when
 * run through `:app:screenshotEn`) — and covers 1 Jan 2026 … today. No personal data,
 * no OAuth, no real request. Report bodies are Czech because the real generator asks
 * DeepSeek for Czech prose; only the app chrome is translated.
 */
class FakeDiariumBackend(private val today: LocalDate = LocalDate.now()) : HttpTransport {

    private val start: LocalDate = LocalDate.of(2026, 1, 1)
    private val entries: List<JSONObject> = buildEntries()
    private val goals: List<JSONObject> = buildGoals()
    private val scales: List<JSONObject> = buildScales()
    private val activities: List<JSONObject> = buildActivities()
    private val habits: List<JSONObject> = buildHabits()
    private val achievements: List<JSONObject> = buildAchievements()
    private val reports: List<JSONObject> = buildReports()

    /** The ISO date the fixture is anchored to, also used by the test's wait markers. */
    val todayIso: String = today.toString()

    override fun execute(request: HttpRequest): HttpResponse {
        if (request.method != "GET") {
            // The only writes on these screens are achievement syncs; accept them and
            // leave the fixture untouched, exactly like a successful upsert.
            return HttpResponse(201, "[]")
        }
        val uri = URI(request.url)
        val path = uri.path.substringAfter("/rest/v1/").trim('/')
        val query = parseQuery(uri.rawQuery)
        val body = when (path) {
            "entries" -> JSONArray(filterEntries(query)).toString()
            "goals" -> JSONArray(goals).toString()
            "scales" -> JSONArray(scales).toString()
            "activity_catalog" -> JSONArray(activities).toString()
            "habit_catalog" -> JSONArray(habits).toString()
            "achievements" -> JSONArray(achievements).toString()
            "ai_reports" -> JSONArray(filterReports(query)).toString()
            else -> "[]"
        }
        return HttpResponse(200, body)
    }

    // ── query handling ───────────────────────────────────────────────────────

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty()
            .split('&')
            .mapNotNull { part ->
                val equals = part.indexOf('=')
                if (equals <= 0) null
                else URLDecoder.decode(part.substring(0, equals), StandardCharsets.UTF_8) to
                    URLDecoder.decode(part.substring(equals + 1), StandardCharsets.UTF_8)
            }
            .toMap()

    private fun filterEntries(query: Map<String, String>): List<JSONObject> {
        var rows = entries
        query["date"]?.let { spec ->
            rows = when {
                spec.startsWith("eq.") -> {
                    val date = spec.removePrefix("eq.")
                    rows.filter { it.optString("date") == date }
                }
                spec.startsWith("in.(") -> {
                    val dates = spec.removePrefix("in.(").removeSuffix(")").split(',').toSet()
                    rows.filter { it.optString("date") in dates }
                }
                spec.startsWith("gte.") -> {
                    val from = spec.removePrefix("gte.")
                    rows.filter { it.optString("date") >= from }
                }
                spec.startsWith("lte.") -> {
                    val to = spec.removePrefix("lte.")
                    rows.filter { it.optString("date") <= to }
                }
                else -> rows
            }
        }
        query["and"]?.let { spec ->
            Regex("date\\.gte\\.([^,)]+)").find(spec)?.groupValues?.getOrNull(1)?.let { from ->
                rows = rows.filter { it.optString("date") >= from }
            }
            Regex("date\\.lte\\.([^,)]+)").find(spec)?.groupValues?.getOrNull(1)?.let { to ->
                rows = rows.filter { it.optString("date") <= to }
            }
        }
        val sorted = rows.sortedBy { it.optString("date") }
        return if (query["order"]?.contains("desc") == true) sorted.reversed() else sorted
    }

    private fun filterReports(query: Map<String, String>): List<JSONObject> {
        val type = query["type"]?.removePrefix("eq.")
        return if (type.isNullOrBlank()) reports else reports.filter { it.optString("type") == type }
    }

    // ── fixture builders ─────────────────────────────────────────────────────

    private fun buildEntries(): List<JSONObject> {
        if (today.isBefore(start)) return listOf(todayEntry(today, 0))
        val days = ChronoUnit.DAYS.between(start, today).toInt()
        val rows = mutableListOf<JSONObject>()
        for (index in 0..days) {
            val date = start.plusDays(index.toLong())
            // A rest day every nine days keeps the calendar from looking mechanically
            // full; today is always present so the dashboard and check-in have data.
            if (date != today && index % 9 == 4) continue
            rows += dayEntry(date, index)
        }
        return rows
    }

    private fun dayEntry(date: LocalDate, index: Int): JSONObject {
        val isToday = date == today
        val mood = when {
            isToday -> 4
            index % 23 == 0 -> 2
            index % 5 == 0 -> 3
            index % 3 == 0 -> 5
            else -> 4
        }
        val activityLabels = buildList {
            add("Walking")
            if (index % 3 == 0) add("Reading")
            if (index % 7 != 0) add("Work")
            if (index % 4 == 0) add("Cooking")
            if (index % 10 == 0) add("Friends")
        }.distinct()
        val habitValues = linkedMapOf(
            "exercise" to (index % 2 == 0),
            "reading" to (index % 3 == 0),
            "water" to true,
        )
        val note = if (isToday) {
            "A steady day. Finished the important work before lunch, walked in the afternoon " +
                "and kept the evening quiet."
        } else {
            "A calm, ordinary day. One deep block of work, a walk outside and an early night."
        }
        val reflection = when {
            isToday -> "You kept a steady pace today. The morning walk and the reading block " +
                "gave the day a clear shape, and your mood held above the weekly average."
            index % 17 == 0 -> "A balanced day with room to breathe. The small routines are " +
                "starting to look like a pattern."
            else -> null
        }
        return JSONObject().apply {
            put("user_id", USER_ID)
            put("date", date.toString())
            put("mood", mood)
            put("mood_emoji", moodEmoji(mood))
            put("sleep_quality", 2 + (index % 3))
            put("stress", 1 + (index % 4))
            put("activities", JSONArray(activityLabels))
            put("habits", JSONObject(habitValues))
            put("gratitude", JSONArray(listOf("A quiet coffee before work", "A long walk after lunch", "")))
            put("note", note)
            put("scale_values", JSONObject(mapOf("scale-energy" to 2 + index % 4, "scale-focus" to 1 + index % 5)))
            put("weather", JSONArray(listOf("☀️", "18°C")))
            put("phone_screen_time", 10_800 + (index % 6) * 900)
            put("phone_unlocks", 52 + index % 35)
            put("phone_top_apps", JSONArray(listOf(
                JSONObject().put("app", "Messages").put("minutes", 48.0),
                JSONObject().put("app", "Maps").put("minutes", 21.5),
                JSONObject().put("app", "Photos").put("minutes", 12.0),
            )))
            if (reflection != null) put("ai_reflection", reflection)
            put("created_at", "${date}T19:30:00+00:00")
        }
    }

    private fun todayEntry(date: LocalDate, index: Int): JSONObject = dayEntry(date, index)

    private fun buildGoals(): List<JSONObject> = listOf(
        goal("goal-morning-walk", "walking", "Morning walk", 5, "weekly", "2026-01-02T08:00:00+00:00"),
        goal("goal-read-daily", "reading", "Read every day", 20, "monthly", "2026-01-03T08:00:00+00:00"),
        goal("goal-cook-home", "cooking", "Cook at home", 4, "weekly", "2026-01-04T08:00:00+00:00"),
    )

    private fun goal(id: String, key: String, name: String, target: Int, frequency: String, created: String) =
        JSONObject().apply {
            put("id", id)
            put("user_id", USER_ID)
            put("activity_key", key)
            put("name", name)
            put("target_count", target)
            put("frequency", frequency)
            put("is_active", true)
            put("created_at", created)
        }

    private fun buildScales(): List<JSONObject> = listOf(
        scale("scale-energy", "Energy", "⚡", 0),
        scale("scale-focus", "Focus", "🎯", 1),
    )

    private fun scale(id: String, name: String, emoji: String, order: Int) = JSONObject().apply {
        put("id", id)
        put("user_id", USER_ID)
        put("name", name)
        put("emoji", emoji)
        put("min_value", 1)
        put("max_value", 5)
        put("color", "#6366f1")
        put("sort_order", order)
    }

    private fun buildActivities(): List<JSONObject> = listOf(
        activity("walking", "Walking", "🚶", "sport"),
        activity("reading", "Reading", "📖", "volný čas"),
        activity("cooking", "Cooking", "🍲", "domácí práce"),
        activity("friends", "Friends", "👥", "sociální"),
        activity("work", "Work", "💼", "práce"),
        activity("music", "Music", "🎵", "volný čas"),
        activity("swimming", "Swimming", "🏊", "sport"),
        activity("shopping", "Shopping", "🛒", "domácí práce"),
    )

    private fun activity(key: String, label: String, icon: String, category: String) = JSONObject().apply {
        put("key", key)
        put("label", label)
        put("icon", icon)
        put("category", category)
        put("color", "#6366f1")
        put("is_active", true)
    }

    private fun buildHabits(): List<JSONObject> = listOf(
        habit("exercise", "Exercise", "🏋️", "pohyb", false, 1),
        habit("reading", "Reading", "📖", "mysl", false, 2),
        habit("water", "Drinking water", "💧", "zdraví", false, 3),
        habit("meditation", "Meditation", "🧘", "mysl", false, 4),
        habit("alcohol", "Alcohol", "🍺", "zdraví", true, 5),
        habit("sweets", "Sweets", "🍬", "zdraví", true, 6),
    )

    private fun habit(key: String, label: String, icon: String, category: String, negative: Boolean, order: Int) =
        JSONObject().apply {
            put("key", key)
            put("label", label)
            put("icon", icon)
            put("category", category)
            put("color", "#6366f1")
            put("is_negative", negative)
            put("is_default", true)
            put("sort_order", order)
        }

    private fun buildAchievements(): List<JSONObject> = listOf(
        unlocked("first_entry", 1, "2026-01-01T09:00:00+00:00"),
        unlocked("streak_7", 7, "2026-01-08T20:00:00+00:00"),
        unlocked("streak_30", 30, "2026-01-31T20:00:00+00:00"),
        unlocked("entries_10", 10, "2026-01-10T20:00:00+00:00"),
        unlocked("entries_100", 100, "2026-04-10T20:00:00+00:00"),
        unlocked("use_scale", 1, "2026-01-03T20:00:00+00:00"),
        unlocked("create_goal", 1, "2026-01-04T20:00:00+00:00"),
        unlocked("complete_goal", 1, "2026-01-11T20:00:00+00:00"),
        unlocked("all_moods_week", 1, "2026-02-01T20:00:00+00:00"),
        unlocked("early_bird", 1, "2026-01-02T07:40:00+00:00"),
        unlocked("weekend_warrior", 1, "2026-01-04T20:00:00+00:00"),
    )

    private fun unlocked(key: String, progress: Int, at: String) = JSONObject().apply {
        put("id", "achievement-$key")
        put("user_id", USER_ID)
        put("achievement_key", key)
        put("progress", progress)
        put("unlocked_at", at)
    }

    private fun buildReports(): List<JSONObject> {
        val monthEnd = today.withDayOfMonth(1).minusDays(1)
        val monthStart = monthEnd.withDayOfMonth(1)
        val weekStart = today.minusDays(6)
        return listOf(
            report(
                id = "report-weekly-fixture",
                type = "weekly",
                start = weekStart.toString(),
                end = today.toString(),
                created = "${today}T07:00:00+00:00",
                content = "Tento týden měl jasný rytmus. Zapsal ses většinu dní, ranní procházku " +
                    "jsi udržel a čtecí blok sis ochránil i v nabitějších dnech.\n\n" +
                    "Nálada zůstala v dobrém pásmu, s malým poklesem uprostřed týdne, který " +
                    "sedl na nejkratší noc. Spánek a stres se hýbaly spolu, jak už to bývá.\n\n" +
                    "Jedna věc stojí za to si nechat: tichý večer po procházce. Ukazuje se ve " +
                    "dnech, které hodnotíš nejvýš.",
            ),
            report(
                id = "report-monthly-fixture",
                type = "monthly",
                start = monthStart.toString(),
                end = monthEnd.toString(),
                created = "${monthEnd}T08:00:00+00:00",
                content = "Měsíc byl klidnější než ten předchozí. Psal jsi častěji a zápisy byly " +
                    "delší ve dnech, kdy jsi byl venku.\n\n" +
                    "Energie a soustředění rostly spolu v druhé polovině měsíce. Cíle, které " +
                    "sis nastavil v lednu, pořád fungují: procházka je návyk, který nese zbytek.\n\n" +
                    "Na další měsíc si drž stejné tempo a nepřidávej další rutinu.",
            ),
        )
    }

    private fun report(
        id: String,
        type: String,
        start: String,
        end: String,
        created: String,
        content: String,
    ) = JSONObject().apply {
        put("id", id)
        put("user_id", USER_ID)
        put("type", type)
        put("period_start", start)
        put("period_end", end)
        put("content", content)
        put("created_at", created)
    }

    private fun moodEmoji(mood: Int): String = when (mood) {
        5 -> "😄"
        4 -> "🙂"
        3 -> "😐"
        2 -> "😟"
        else -> "😡"
    }

    companion object {
        /** A synthetic account id — never a real Supabase user. */
        const val USER_ID = "00000000-0000-4000-8000-000000000001"
    }
}
