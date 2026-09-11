package cz.digitalnivedomi.diarium.core.achievements

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Odznaky (achievements) — the catalogue and the arithmetic, in pure Kotlin.
 *
 * No Compose, no Android, no network, no org.json: everything here is callable
 * from a plain JVM unit test, which is the point. The repository
 * (`core/data/AchievementsRepository.kt`) only reads rows and maps them onto
 * [AchievementEntry]/[AchievementGoal]; every threshold, count, streak and
 * unlock comparison lives in this file.
 *
 * SOURCE OF TRUTH: the web app's `src/lib/achievements.ts` — the 17 definitions
 * below are that array 1:1 (key, Czech name, description, emoji, target,
 * category, in the same order). The web file is the definition set; the
 * `achievements` DB table additionally holds a **stale `use_template` row** for
 * an achievement that no longer exists anywhere in the web source, so the panel
 * is built from [ACHIEVEMENTS] and the stored rows are only merged in by key —
 * a row with no matching definition is ignored (see [achievementStatuses]).
 */
enum class AchievementCategory(val wire: String) {
    STREAK("streak"),
    COUNT("count"),
    FEATURE("feature"),
    MOOD("mood"),
    SPECIAL("special"),
}

/** One unlocked-when-progress-reaches-[target] badge. */
data class AchievementDef(
    val key: String,
    val name: String,
    val description: String,
    val emoji: String,
    val target: Int,
    val category: AchievementCategory,
)

/**
 * The catalogue, copied 1:1 from the web `ACHIEVEMENTS` (17 entries).
 *
 * `use_scale` and `add_photo` are the two keys the web additionally computes
 * from the open form ("live" flags); here the same rule is [AchievementLiveFlags].
 */
val ACHIEVEMENTS: List<AchievementDef> = listOf(
    // Streaks
    AchievementDef("first_entry", "První zápis", "Vytvoř svůj první záznam", "🌱", 1, AchievementCategory.COUNT),
    AchievementDef("streak_7", "Týden v kuse", "7 dní v řadě", "🔥", 7, AchievementCategory.STREAK),
    AchievementDef("streak_30", "Měsíc v kuse", "30 dní v řadě", "💪", 30, AchievementCategory.STREAK),
    AchievementDef("streak_100", "Sto dní", "100 dní v řadě", "🏆", 100, AchievementCategory.STREAK),
    AchievementDef("streak_365", "Rok v kuse", "365 dní v řadě", "👑", 365, AchievementCategory.STREAK),

    // Counts
    AchievementDef("entries_10", "Desítka", "10 záznamů", "📝", 10, AchievementCategory.COUNT),
    AchievementDef("entries_100", "Stovka", "100 záznamů", "📚", 100, AchievementCategory.COUNT),
    AchievementDef("entries_365", "Rok deníků", "365 záznamů", "📖", 365, AchievementCategory.COUNT),

    // Features
    AchievementDef("add_photo", "Fotograf", "Přidej fotku", "📸", 1, AchievementCategory.FEATURE),
    AchievementDef("use_scale", "Měřič", "Použij škálu", "📊", 1, AchievementCategory.FEATURE),
    AchievementDef("create_goal", "Cílový", "Vytvoř cíl", "🎯", 1, AchievementCategory.FEATURE),
    AchievementDef("complete_goal", "Splněno", "Splň cíl", "✅", 1, AchievementCategory.FEATURE),

    // Moods
    AchievementDef("all_moods_week", "Emoční spektrum", "3 různé nálady v týdnu", "🌈", 1, AchievementCategory.MOOD),
    AchievementDef("perfect_week", "Perfektní týden", "7 dní v řadě skvělá nálada", "✨", 1, AchievementCategory.MOOD),

    // Special
    AchievementDef("early_bird", "Ranní ptáče", "Záznam před 9:00", "🐦", 1, AchievementCategory.SPECIAL),
    AchievementDef("night_owl", "Noční sova", "Záznam po 23:00", "🦉", 1, AchievementCategory.SPECIAL),
    AchievementDef("weekend_warrior", "Víkendový bojovník", "Záznam v sobotu i neděli", "⚔️", 1, AchievementCategory.SPECIAL),
)

/** The zone the web's `hourOf` uses for the time-of-day badges. */
val PRAGUE_ZONE: ZoneId = ZoneId.of("Europe/Prague")

/**
 * One journal day, reduced to exactly what the badges need. Built by the
 * repository from an `entries` row; a plain test can build it inline.
 */
data class AchievementEntry(
    val date: String,
    val mood: Int = 0,
    val createdAt: String? = null,
    val hasPhoto: Boolean = false,
    val scaleValues: Map<String, Int> = emptyMap(),
    val activities: List<String> = emptyList(),
) {
    /** The web's `Object.values(sv).some(v => Number(v) > 0)`. */
    val hasPositiveScale: Boolean get() = scaleValues.values.any { it > 0 }
}

/** One `goals` row, reduced to what `complete_goal`/`create_goal` need. */
data class AchievementGoal(
    val activityKey: String,
    val targetCount: Int = 1,
    val frequency: String = "weekly",
)

/**
 * The web's "live" flags: an achievement may be satisfied by the entry currently
 * open in the form, before it is saved. The panel has no open form, so it passes
 * the default (both false) — the flags only exist so the rule is testable.
 */
data class AchievementLiveFlags(
    val hasPhoto: Boolean = false,
    val hasScale: Boolean = false,
)

/** A definition plus how far the user has got. [unlocked] is `progress >= target`. */
data class AchievementStatus(
    val def: AchievementDef,
    val progress: Int,
    val unlocked: Boolean,
    /**
     * ISO timestamp the badge was earned, when the stored `achievements` row
     * carries one. Null while the badge is still locked, and null for a badge
     * that is newly unlocked on this load — the repository stamps it and writes
     * the row back (web parity with `syncAchievements`), so the next load has it.
     */
    val unlockedAt: String? = null,
) {
    val key: String get() = def.key
    val target: Int get() = def.target

    /** The badge's "x/y". */
    val ratio: String get() = "$progress/$target"
}

/** The whole panel: one status per definition, in catalogue order. */
data class AchievementsData(val statuses: List<AchievementStatus>) {
    val unlockedCount: Int get() = statuses.count { it.unlocked }
    val total: Int get() = statuses.size

    /** The summary the panel shows: "Odemčeno N/M". */
    val unlockedLabel: String get() = "Odemčeno $unlockedCount/$total"

    fun statusFor(key: String): AchievementStatus? = statuses.firstOrNull { it.key == key }
}

// ── Date/time helpers ────────────────────────────────────────────────────────

private fun epochDay(iso: String): Long? =
    try {
        LocalDate.parse(iso).toEpochDay()
    } catch (_: Exception) {
        null
    }

/** Whole days from [b] to [a], the web's `dayDiff` on ISO `YYYY-MM-DD` strings. */
fun dayDiff(a: String, b: String): Int {
    val da = epochDay(a) ?: return NO_DAY
    val db = epochDay(b) ?: return NO_DAY
    return (da - db).toInt()
}

private const val NO_DAY = Int.MIN_VALUE

/**
 * Longest run of consecutive calendar days, the web's `maxStreak`.
 * Duplicate dates collapse (the DB has one row per day anyway).
 */
fun computeMaxStreak(dates: List<String>): Int {
    if (dates.isEmpty()) return 0
    val uniqueSorted = dates.toSortedSet().toList()
    var maxStreak = 1
    var current = 1
    for (i in 1 until uniqueSorted.size) {
        if (dayDiff(uniqueSorted[i], uniqueSorted[i - 1]) == 1) {
            current++
            if (current > maxStreak) maxStreak = current
        } else {
            current = 1
        }
    }
    return maxStreak
}

/** 7 consecutive days all rated mood 5 — the web's `hasPerfectWeek`. */
fun hasPerfectWeek(entries: List<AchievementEntry>): Boolean {
    val dates = entries.filter { it.mood == 5 }.map { it.date }.sorted()
    if (dates.size < 7) return false
    var run = 1
    for (i in 1 until dates.size) {
        if (dayDiff(dates[i], dates[i - 1]) == 1) {
            run++
            if (run >= 7) return true
        } else {
            run = 1
        }
    }
    return false
}

/** A Saturday immediately followed by a Sunday — the web's `hasWeekendPair`. */
fun hasWeekendPair(dates: List<String>): Boolean {
    val byDate = dates.toSortedSet()
    for (dateStr in byDate) {
        val day = epochDay(dateStr) ?: continue
        if (LocalDate.ofEpochDay(day).dayOfWeek == DayOfWeek.SATURDAY) {
            val sunday = LocalDate.ofEpochDay(day + 1).toString()
            if (sunday in byDate) return true
        }
    }
    return false
}

/**
 * Hour of day (0–23) of an ISO timestamp in [zone], or `null` when the entry
 * has no `created_at` (old rows) or the string cannot be parsed.
 */
fun hourOf(iso: String?, zone: ZoneId = PRAGUE_ZONE): Int? {
    if (iso.isNullOrBlank()) return null
    val instant = parseInstant(iso) ?: return null
    return instant.atZone(zone).hour
}

private fun parseInstant(iso: String): Instant? =
    try {
        OffsetDateTime.parse(iso).toInstant()
    } catch (_: Exception) {
        try {
            Instant.parse(iso)
        } catch (_: Exception) {
            null
        }
    }

/**
 * The most [dates] that fall inside a window of [windowDays] starting at any one
 * of them — the web's `maxCountInWindow`, used for goal completion.
 */
fun maxCountInWindow(dates: List<String>, windowDays: Int): Int {
    val uniqueSorted = dates.toSortedSet().toList()
    var maxCount = 0
    for (i in uniqueSorted.indices) {
        val start = epochDay(uniqueSorted[i]) ?: continue
        var count = 0
        for (j in i until uniqueSorted.size) {
            val day = epochDay(uniqueSorted[j]) ?: break
            if (day - start < windowDays) count++ else break
        }
        if (count > maxCount) maxCount = count
    }
    return maxCount
}

/** Daily → 1-day window, weekly → 7, anything else → 30 (the web's rule). */
fun goalWindowDays(frequency: String): Int = when (frequency) {
    "daily" -> 1
    "weekly" -> 7
    else -> 30
}

/** Was [goal]'s target ever reached inside one window? — the web's `goalEverCompleted`. */
fun goalEverCompleted(goal: AchievementGoal, entries: List<AchievementEntry>): Boolean {
    val dates = entries.filter { it.activities.contains(goal.activityKey) }.map { it.date }
    return maxCountInWindow(dates, goalWindowDays(goal.frequency)) >= goal.targetCount
}

// ── Progress ─────────────────────────────────────────────────────────────────

/**
 * Progress for every key in the catalogue, computed exactly as the web's
 * `computeProgress` does — with two deliberate corrections for the panel:
 *
 *  * the last-moods badge looks at the 7 most recent entries *by date* (the web
 *    relies on the query's ordering; sorting here keeps it right even when the
 *    caller hands over rows in another order), and
 *  * nothing is read from a truncated response — the repository pages the
 *    `entries` table (the web's `.limit(10000)` silently returns 1000 rows,
 *    because the project's `db-max-rows` caps responses at 1000, which
 *    under-counts streaks for anyone past a thousand entries).
 */
fun computeProgress(
    entries: List<AchievementEntry>,
    goals: List<AchievementGoal>,
    live: AchievementLiveFlags = AchievementLiveFlags(),
    zone: ZoneId = PRAGUE_ZONE,
): Map<String, Int> {
    val progress = LinkedHashMap<String, Int>()
    val count = entries.size
    val byDate = entries.sortedBy { it.date }

    progress["first_entry"] = if (count >= 1) 1 else 0
    progress["entries_10"] = minOf(count, 10)
    progress["entries_100"] = minOf(count, 100)
    progress["entries_365"] = minOf(count, 365)

    val maxStreak = computeMaxStreak(entries.map { it.date })
    progress["streak_7"] = minOf(maxStreak, 7)
    progress["streak_30"] = minOf(maxStreak, 30)
    progress["streak_100"] = minOf(maxStreak, 100)
    progress["streak_365"] = minOf(maxStreak, 365)

    val hasScaleInDb = entries.any { it.hasPositiveScale }
    val hasPhotoInDb = entries.any { it.hasPhoto }
    progress["use_scale"] = if (hasScaleInDb || live.hasScale) 1 else 0
    progress["add_photo"] = if (hasPhotoInDb || live.hasPhoto) 1 else 0
    progress["create_goal"] = if (goals.size >= 1) 1 else 0
    progress["complete_goal"] = if (goals.any { goalEverCompleted(it, entries) }) 1 else 0

    val uniqueMoods = byDate.takeLast(7).map { it.mood }.filter { it in 1..5 }.toSet()
    progress["all_moods_week"] = if (uniqueMoods.size >= 3) 1 else 0
    progress["perfect_week"] = if (hasPerfectWeek(entries)) 1 else 0

    progress["early_bird"] = if (entries.any { (hourOf(it.createdAt, zone) ?: -1) in 0..8 }) 1 else 0
    progress["night_owl"] = if (entries.any { (hourOf(it.createdAt, zone) ?: -1) >= 23 }) 1 else 0
    progress["weekend_warrior"] = if (hasWeekendPair(entries.map { it.date })) 1 else 0

    return progress
}

/**
 * The server RPC stores progress monotonically (`greatest(stored, computed)`).
 * The panel keeps the same rule so a badge that was already earned — before the
 * entries it was earned from were edited or deleted — never appears to regress.
 */
fun mergeProgress(computed: Map<String, Int>, stored: Map<String, Int>): Map<String, Int> {
    val merged = LinkedHashMap<String, Int>()
    for (key in computed.keys + stored.keys) {
        merged[key] = maxOf(computed[key] ?: 0, stored[key] ?: 0)
    }
    return merged
}

/**
 * Pairs each definition with its progress. Keys in [progress] with no definition
 * are dropped — that is how the stale `use_template` row stays off the panel.
 */
fun achievementStatuses(
    progress: Map<String, Int>,
    definitions: List<AchievementDef> = ACHIEVEMENTS,
    /** `achievement_key` → stored `unlocked_at`, for the badge's date line. */
    unlockedAt: Map<String, String?> = emptyMap(),
): List<AchievementStatus> = definitions.map { def ->
    val current = progress[def.key] ?: 0
    AchievementStatus(
        def = def,
        progress = current,
        unlocked = current >= def.target,
        unlockedAt = unlockedAt[def.key],
    )
}

/** [progress] → the whole panel model. */
fun achievementsData(
    progress: Map<String, Int>,
    definitions: List<AchievementDef> = ACHIEVEMENTS,
    unlockedAt: Map<String, String?> = emptyMap(),
): AchievementsData = AchievementsData(achievementStatuses(progress, definitions, unlockedAt))
