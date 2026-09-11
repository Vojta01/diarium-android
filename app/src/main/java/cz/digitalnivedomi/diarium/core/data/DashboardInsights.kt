package cz.digitalnivedomi.diarium.core.data

/**
 * Pure, screen-facing derivations for the dashboard ("Přehled").
 *
 * These live outside [DashboardRepository] and outside the composables so the
 * rules the screen must not get wrong — which mark a day carries in the mood row,
 * which apps are really the most used, how a duration reads — are plain JVM
 * functions the unit tests exercise directly. No Compose, no Android, no clock.
 */

/**
 * The three deliberate marks a day can carry in the mood row.
 *
 * A day the user logged without answering the mood question is not the same as a
 * day they never opened: the first is [LoggedWithoutMood] and has to read as "no
 * mood filled in", the second is [NoEntry] and stays quiet. Collapsing the two is
 * exactly what made the Monday disc look like a broken, empty grey circle.
 */
enum class MoodDiscState {
    /** The day has a mood: emoji on a soft mood-coloured disc. */
    Mood,

    /** The day has an entry but no mood: a neutral indigo disc with an en-dash. */
    LoggedWithoutMood,

    /** No entry at all: the quietest treatment. */
    NoEntry,
}

/**
 * Which mark [mood] plus entry presence select.
 *
 * A mood of 0 means "not answered" (the check-in stores 0 for an untouched
 * picker), so it becomes the neutral mark rather than the worst colour on the
 * scale.
 */
fun moodDiscState(hasEntry: Boolean, mood: Int): MoodDiscState = when {
    !hasEntry -> MoodDiscState.NoEntry
    mood > 0 -> MoodDiscState.Mood
    else -> MoodDiscState.LoggedWithoutMood
}

/** How many apps the "Nejpoužívanější aplikace" block lists. */
const val TOP_APPS_LIMIT = 5

/**
 * Minutes an app must exceed to count as "most used". Below this the row is
 * launch noise — a `<1 min` line would only push a real leader off the list.
 */
const val TOP_APPS_MIN_MINUTES = 0.25

/**
 * The apps actually worth ranking: drops the sub-quarter-minute noise, sorts the
 * rest by minutes descending and keeps the top [limit].
 *
 * The screen used to render `apps.take(5)` — the raw database order — so the real
 * leaders never appeared: five rows the query happened to return first hid
 * Snooker 37 min behind Hermes WebUI 8 min.
 *
 * The model carries whole minutes (`JSONObject.optInt`), so "more than
 * [TOP_APPS_MIN_MINUTES]" is what drops the sub-minute rows the worker recorded
 * (0.5, 0.72, 0.97) and the parser read back as 0.
 *
 * Ties are broken by name so the output is deterministic.
 */
fun rankTopApps(apps: List<PhoneTopApp>, limit: Int = TOP_APPS_LIMIT): List<PhoneTopApp> =
    apps
        .filter { it.minutes > TOP_APPS_MIN_MINUTES }
        .sortedWith(compareByDescending<PhoneTopApp> { it.minutes }.thenBy { it.app })
        .take(limit)

/**
 * A top-app duration in the app's short Czech format: `23 min`, `1 h 15 min`.
 * A value under a minute reads `<1 min`, never the ambiguous `0 min`.
 */
fun formatTopAppDuration(minutes: Int): String = when {
    minutes < 1 -> "<1 min"
    minutes < 60 -> "$minutes min"
    else -> {
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0) "$hours h" else "$hours h $rest min"
    }
}
