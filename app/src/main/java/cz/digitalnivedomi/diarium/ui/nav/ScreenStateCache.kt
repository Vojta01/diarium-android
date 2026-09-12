package cz.digitalnivedomi.diarium.ui.nav

import cz.digitalnivedomi.diarium.ui.history.HistoryStateHolder
import cz.digitalnivedomi.diarium.ui.home.DashboardStateHolder
import cz.digitalnivedomi.diarium.ui.stats.StatsStateHolder

/**
 * The tab screens' state holders, created once for the lifetime of the signed-in shell
 * and handed down from above the `NavHost`.
 *
 * ## Why this exists
 *
 * Every screen used to create its holder in a `remember { … }` inside its own
 * composable. Navigation Compose disposes a destination's composition as soon as
 * another destination is pushed on top of it — even though its back-stack entry stays
 * alive — so coming back from a sub-screen (or another tab) built a brand new holder.
 * The screen then re-fetched everything, and because the whole content collapsed into
 * skeletons while loading, the scroll position had nothing to restore into and was
 * clamped to the top. That is the exact behaviour reported on 2026-09-12:
 * *"nelíbí se mi, jak se to načítá celé od začátku, šlo by aby se to prostě v mžiku
 * vteřiny vrátilo zpět na tu předchozí stránku včetně toho, kde to bylo zaskrolované?"*
 *
 * Keeping the holders here means returning to a tab re-renders the numbers it already
 * had, so the state Navigation saved for the entry (including the scroll offset, which
 * is `rememberSaveable`-backed) lands back on the same pixels.
 *
 * ## What this is not
 *
 * It is **caching, never skipping updates**. The holders themselves are unchanged: the
 * screens still re-read on resume and after a failed load, and `markLoading()` still
 * runs. What changed alongside this is only *when skeletons are shown* — a screen now
 * shows placeholder cards when it has nothing to show yet, not when it is refreshing
 * numbers it already has. So a returning tab is instant and a stale one still corrects
 * itself a moment later.
 *
 * `lazy` because a screen that is never opened must never pay to build its holder, and
 * because the first screen to ask defines the order the rest are created in.
 */
class ScreenStateCache {
    val dashboard: DashboardStateHolder by lazy { DashboardStateHolder() }
    val stats: StatsStateHolder by lazy { StatsStateHolder() }
    val history: HistoryStateHolder by lazy { HistoryStateHolder() }
}
