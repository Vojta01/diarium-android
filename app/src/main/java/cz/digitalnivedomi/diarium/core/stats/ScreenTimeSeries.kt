package cz.digitalnivedomi.diarium.core.stats

/**
 * The bar-label arithmetic the two screen-time charts share.
 *
 * Both charts (Statistiky's [cz.digitalnivedomi.diarium.ui.stats.ScreenTimeChart]
 * and Přehled's screen-time card) print a value under their bars and carry the
 * day's unlock count, and both have to answer the same three questions: how does a
 * duration fit under a bar, when does the chart have no value to show, and which
 * bars get a label at all. Those questions are pure functions of the numbers, so
 * they live here — tested directly (`app/src/test/.../core/stats/ScreenTimeSeriesTest.kt`)
 * instead of through a Compose render.
 *
 * Deliberately **not** in [StatsMath]: that object is the web port's arithmetic
 * (windows, averages, top apps) and its definitions must not drift from
 * `src/components/ScreenTimeChart.tsx`. Presentation rules change for reasons the
 * web has no opinion about, so they stay in their own file.
 */
object ScreenTimeSeries {

    /** The widest window whose bars all carry a label (the "7 dní" chip). */
    const val LABELLED_WINDOW = 7

    /** In a wider window, a label only every Nth bar — matching the weekday row. */
    const val THIN_EVERY = 5

    /** What a bar with nothing reported prints — never a value that reads as 0. */
    const val NO_DATA = "—"

    /**
     * The one line both charts print under their bars. Czech, and short enough to
     * stay a hint rather than a paragraph.
     */
    const val LEGEND = "Číslo pod sloupcem = čas na obrazovce · 🔓 = počet odemknutí."

    /**
     * Compact Czech duration for a value printed under a bar: `358` -> `"5h58"`,
     * `45` -> `"45m"`, `480` -> `"8h"`.
     *
     * No spaces and no "min": a label sits under a ~16dp bar and a 30-day slot is
     * barely 11dp wide, so every character costs. Minutes are run straight on to
     * the hours, which is how the numbers are spoken ("pět padesát osm" is "5h58",
     * not "5 h 58 min"). Zero prints `"0m"` — a synced zero is a real reading, and
     * "there is no data" is [secondsLabel]'s separate dash.
     */
    fun formatMinutesCompact(minutes: Int): String {
        val total = minutes.coerceAtLeast(0)
        val hours = total / 60
        val rest = total % 60
        return when {
            hours == 0 -> "${rest}m"
            rest == 0 -> "${hours}h"
            else -> "${hours}h${rest}"
        }
    }

    /**
     * The value printed under a bar, from the screen-time seconds the worker stored.
     *
     * `null` is "that day was never synced" and prints [NO_DATA]; a synced `0`
     * prints `"0m"`. Keeping those two apart is the same rule the charts already
     * follow for the bars themselves — an unsynced day must never read as a very
     * quiet day.
     */
    fun secondsLabel(seconds: Int?): String =
        seconds?.let { formatMinutesCompact(it / 60) } ?: NO_DATA

    /**
     * The unlock line under a bar, or `null` when the day has nothing to say.
     *
     * Only unlocked days get the line: `null` (never synced) and `0` (synced, no
     * unlocks) both stay silent. A `"🔓0"` under every quiet bar is noise that
     * hides the days that actually have unlocks, and the missing/zero day is
     * already legible from the bar itself.
     */
    fun unlockLabel(unlocks: Int?): String? =
        unlocks?.takeIf { it > 0 }?.let { "$UNLOCK_SIGN$it" }

    /**
     * Which of [count] bars print their value when the window is [windowDays] wide.
     *
     * A 7-day window has room under every bar. A 30-day one does not — a slot is
     * roughly 11dp on a phone — so labels thin to every [THIN_EVERY]th bar, the
     * **same cadence the weekday captions below already use**: a value then sits
     * under a day name instead of between two, and the two rows read as one column.
     *
     * [selectedIndex] (the bar the user tapped) is always labelled, because the tap
     * has to be visible where it landed even in a thinned-out column; the full
     * detail stays in the selected-day card.
     */
    fun labelIndices(count: Int, windowDays: Int, selectedIndex: Int? = null): Set<Int> {
        if (count <= 0) return emptySet()
        if (windowDays <= LABELLED_WINDOW) return (0 until count).toSet()
        return (0 until count)
            .filter { it % THIN_EVERY == 0 || it == selectedIndex }
            .toSet()
    }

    /** 0 is not "unlocked once": the sign carries no plural, a count never does. */
    private const val UNLOCK_SIGN = "🔓"
}
