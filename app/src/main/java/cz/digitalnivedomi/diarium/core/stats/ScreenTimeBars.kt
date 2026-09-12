package cz.digitalnivedomi.diarium.core.stats

/**
 * The bar arithmetic of Statistiky's two screen-time charts — time on screen, and the
 * day's unlock count.
 *
 * [ScreenTimeSeries] answers the label questions (how a duration is spelled, which bars
 * carry a label at all); this object answers the bar questions: how tall a bar stands
 * against the window's maximum, when a day draws no bar at all, which colour band a
 * value falls into, and what the unlock chart's three summary numbers are.
 *
 * Deliberately **not** in [StatsMath]: that object is the web port's arithmetic and its
 * definitions must not drift from `src/components/ScreenTimeChart.tsx`. The unlock
 * chart does not exist on the web — its bands and its averages are a native decision —
 * so they live here, next to the presentation rules they belong to.
 *
 * Everything here is a pure function of the numbers, so the geometry the two charts
 * draw is tested directly (`app/src/test/.../core/stats/ScreenTimeBarsTest.kt`) instead
 * of through a Compose render.
 */
object ScreenTimeBars {

    /** Colour bands a bar can fall into — the web's six `getBarColorTotals` buckets. */
    const val BUCKETS = 6

    /** The web's bucket captions, in colour order. */
    val TIME_BUCKET_LABELS: List<String> = listOf("<30m", "30m–1h", "1–2h", "2–4h", "4–6h", "6h+")

    /**
     * The unlock chart's bands. Not from the web (it has no unlock chart): round
     * thresholds that put an ordinary day (roughly 40–80 unlocks) in the middle, and
     * keep the legend exactly as wide as the screen-time one.
     */
    val UNLOCK_BUCKET_LABELS: List<String> = listOf("<20", "20–49", "50–79", "80–119", "120–179", "180+")

    /** The screen-time band, with the web's `getBarColorTotals` thresholds verbatim. */
    fun timeBucket(seconds: Int): Int = when {
        seconds < 30 * 60 -> 0
        seconds < 60 * 60 -> 1
        seconds < 2 * 60 * 60 -> 2
        seconds < 4 * 60 * 60 -> 3
        seconds < 6 * 60 * 60 -> 4
        else -> 5
    }

    /** The unlock count's band, over [UNLOCK_BUCKET_LABELS]. */
    fun unlockBucket(unlocks: Int): Int = when {
        unlocks < 20 -> 0
        unlocks < 50 -> 1
        unlocks < 80 -> 2
        unlocks < 120 -> 3
        unlocks < 180 -> 4
        else -> 5
    }

    /**
     * Whether a day is drawn as a bar at all.
     *
     * `null` is "the worker never reported that day" and `0` is "the phone said nothing
     * happened" — neither is a bar. Both are still labelled
     * ([ScreenTimeSeries.secondsLabel] prints the dash, [ScreenTimeSeries.unlockLabel]
     * stays silent, [unlockCountLabel] prints the zero), which is exactly what keeps the
     * two apart on screen: the chart shows no column, the label row says why.
     */
    fun drawsBar(value: Int?): Boolean = (value ?: 0) > 0

    /**
     * A bar's height as a fraction of the track: the day's [value] over the window's
     * [max]. The floor keeps a real but small day visible instead of invisible; the
     * ceiling cannot be reached with a max taken from the same window, but a bar that
     * overflows the track is not worth the risk.
     */
    fun barFraction(value: Int, max: Int): Float =
        (value.toFloat() / max.coerceAtLeast(1)).coerceIn(MIN_BAR_FRACTION, 1f)

    /**
     * Where the dashed average line sits, `0f` on the baseline and `1f` at the top.
     *
     * Unlike [barFraction] this has to reach zero: an average of zero is a fact about
     * the window, and that line belongs on the floor, not a hair above it.
     */
    fun averageLineFraction(average: Double, max: Int): Float =
        (average.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f)

    /**
     * The unlock chart's bar label: the raw count, or [ScreenTimeSeries.NO_DATA] for a
     * day the worker never reported.
     *
     * A synced `0` prints `"0"` — the count is this chart's whole subject, so a zero is
     * a reading ("nothing happened") rather than the noise it was in the fused chart,
     * where it hid the days that actually had unlocks.
     */
    fun unlockCountLabel(unlocks: Int?): String = unlocks?.toString() ?: ScreenTimeSeries.NO_DATA

    /**
     * The unlock chart's three summary numbers, computed with the same denominators
     * [StatsMath.screenTimeSummary] uses for screen time: the average and the maximum
     * come from the days that actually reported unlocks, while the total sums every day
     * of the window (a day with no report adds nothing). [UnlockSummary.maxDate] is the
     * earliest reported day that reached the maximum, matching the web's `reduce`.
     *
     * Over a window where nothing was reported all three are null/none, never an
     * invented zero — the same rule the screen-time chart already follows.
     */
    fun unlockSummary(window: List<ScreenTimeDay>): UnlockSummary {
        val reported = window.filter { drawsBar(it.unlocks) }
        val max = reported.maxOfOrNull { it.unlocks ?: 0 }
        return UnlockSummary(
            averageUnlocks = if (reported.isEmpty()) {
                null
            } else {
                reported.sumOf { it.unlocks ?: 0 }.toDouble() / reported.size
            },
            totalUnlocks = window.sumOf { it.unlocks ?: 0 },
            maxUnlocks = max,
            maxDate = reported.firstOrNull { it.unlocks == max }?.date,
        )
    }

    /**
     * The screen-time chart's legend, now that its bars carry the duration only.
     * ([ScreenTimeSeries.LEGEND] stays as it is for the dashboard's fused card.)
     */
    const val SCREEN_TIME_LEGEND = "Číslo pod sloupcem = čas na obrazovce."

    /** The unlock chart's own legend. */
    const val UNLOCK_LEGEND = "Číslo pod sloupcem = počet odemknutí."

    /** Smallest fraction of the track a real value draws — a stub, not a dot. */
    private const val MIN_BAR_FRACTION = 0.02f
}

/**
 * The unlock chart's three numbers above its bars, in the order the screen-time chart
 * prints its own. Nulls mean "nothing was reported in this window" — never a zero that
 * would read as a real measurement.
 */
data class UnlockSummary(
    val averageUnlocks: Double?,
    val totalUnlocks: Int,
    val maxUnlocks: Int?,
    val maxDate: String?,
)
