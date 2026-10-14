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
 * The two deliberate marks a day can carry in the mood row.
 *
 * There used to be a third — a neutral indigo disc with an en-dash for "a row
 * exists but the mood question was never answered" — but the owner's rule
 * (2026-09-11, see [isRecordedDay]) is that a day counts only when the mood is
 * filled. The phone sync writes an `entries` row for every day, so the presence
 * of a row says nothing about whether the owner journaled: 2026-09-07 carries
 * 5 h 58 min of screen time and 196 unlocks yet no mood. Such a day must read
 * exactly like one with no row at all — [Missing] — never like a logged day.
 */
enum class MoodDiscState {
    /** The day has a mood: emoji on a soft mood-coloured disc. */
    Mood,

    /** No mood filled in — whether or not a synced row exists: the quiet disc. */
    Missing,
}

/**
 * Which mark [mood] selects.
 *
 * Only `mood` can be trusted to mean "the owner journaled": screen time, unlocks
 * and top apps are written by the phone-sync worker, which creates an `entries`
 * row for every day, so the mere presence of a row says nothing. That is why this
 * function deliberately ignores whether a row exists — a mood-less day (0 or null)
 * is [MoodDiscState.Missing] even when the phone synced numbers for it, and a day
 * with a mood is [MoodDiscState.Mood]. See [isRecordedDay] for the full rule.
 *
 * This is the intentional deviation from the web, which draws any row as a logged
 * day.
 *
 * A mood of 0 means "not answered" (the check-in stores 0 for an untouched
 * picker) and null means "no value", so both read as missing rather than as the
 * worst colour on the scale.
 */
fun moodDiscState(mood: Int?): MoodDiscState =
    if (isRecordedDay(mood)) MoodDiscState.Mood else MoodDiscState.Missing

/** How many apps the "Nejpoužívanější aplikace" block lists. */
const val TOP_APPS_LIMIT = 5

/**
 * Minutes an app must reach to count as "most used". Sub-minute rows are launch
 * noise — a `<1 min` line would only push a real leader off the list. (Not 0.25
 * anymore: now that the parse keeps fractional minutes, 0.25 would have let the
 * 0.5–0.97 min rows back in the moment truncation stopped hiding them.)
 */
const val TOP_APPS_MIN_MINUTES = 1.0

/**
 * The apps actually worth ranking: drops the sub-minute noise (see
 * [TOP_APPS_MIN_MINUTES]), sorts the rest by minutes descending and keeps the
 * top [limit].
 *
 * The screen used to render `apps.take(5)` — the raw database order — so the real
 * leaders never appeared: five rows the query happened to return first hid
 * Snooker 37 min behind Hermes WebUI 8 min.
 *
 * The model keeps fractional minutes (the worker records seconds); the numbers
 * still sum in full in the stats breakdown, while this display list drops the
 * sub-minute rows that would only crowd out a real leader.
 *
 * Ties are broken by name so the output is deterministic.
 */
fun rankTopApps(apps: List<PhoneTopApp>, limit: Int = TOP_APPS_LIMIT): List<PhoneTopApp> =
    apps
        .filter { it.minutes >= TOP_APPS_MIN_MINUTES }
        .sortedWith(compareByDescending<PhoneTopApp> { it.minutes }.thenBy { it.app })
        .take(limit)

/**
 * A top-app duration in the app's short Czech format: `23 min`, `1 h 15 min`.
 * A value under a minute reads `<1 min`, never the ambiguous `0 min`; fractions
 * round to the nearest minute for display (`Math.round`), never truncate.
 */
fun formatTopAppDuration(minutes: Double): String = when {
    minutes < 1.0 -> "<1 min"
    else -> {
        val total = Math.round(minutes).toInt()
        val hours = total / 60
        val rest = total % 60
        when {
            hours == 0 -> "$total min"
            rest == 0 -> "$hours h"
            else -> "$hours h $rest min"
        }
    }
}

/** Whole-minute overload for callers that carry ints (kept so no caller needs a cast). */
fun formatTopAppDuration(minutes: Int): String = formatTopAppDuration(minutes.toDouble())
