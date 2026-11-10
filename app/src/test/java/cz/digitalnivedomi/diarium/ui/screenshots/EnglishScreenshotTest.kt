package cz.digitalnivedomi.diarium.ui.screenshots

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.AchievementsRepository
import cz.digitalnivedomi.diarium.core.data.AiReportsRepository
import cz.digitalnivedomi.diarium.core.data.DashboardRepository
import cz.digitalnivedomi.diarium.core.data.EntriesRepository
import cz.digitalnivedomi.diarium.core.data.GoalsRepository
import cz.digitalnivedomi.diarium.core.data.HistoryRepository
import cz.digitalnivedomi.diarium.core.data.PickersRepository
import cz.digitalnivedomi.diarium.core.data.ProfileStore
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.StatsRepository
import cz.digitalnivedomi.diarium.core.data.SupabaseClient
import cz.digitalnivedomi.diarium.core.i18n.AppLanguage
import cz.digitalnivedomi.diarium.core.i18n.Lang
import cz.digitalnivedomi.diarium.ui.SettingsScreen
import cz.digitalnivedomi.diarium.ui.achievements.AchievementsDeps
import cz.digitalnivedomi.diarium.ui.achievements.AchievementsScreen
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDeps
import cz.digitalnivedomi.diarium.ui.checkin.CheckInScreen
import cz.digitalnivedomi.diarium.ui.components.DiariumBackground
import cz.digitalnivedomi.diarium.ui.components.SubScreenChrome
import cz.digitalnivedomi.diarium.ui.goals.GoalsDeps
import cz.digitalnivedomi.diarium.ui.goals.GoalsScreen
import cz.digitalnivedomi.diarium.ui.history.HistoryDeps
import cz.digitalnivedomi.diarium.ui.history.HistoryScreen
import cz.digitalnivedomi.diarium.ui.home.DashboardDeps
import cz.digitalnivedomi.diarium.ui.home.DashboardScreen
import cz.digitalnivedomi.diarium.ui.home.DashboardStateHolder
import cz.digitalnivedomi.diarium.ui.reports.ReportsDeps
import cz.digitalnivedomi.diarium.ui.reports.ReportsScreen
import cz.digitalnivedomi.diarium.ui.reports.ReportsState
import cz.digitalnivedomi.diarium.ui.settings.AiSettingsDeps
import cz.digitalnivedomi.diarium.ui.stats.StatsDeps
import cz.digitalnivedomi.diarium.ui.stats.StatsScreen
import cz.digitalnivedomi.diarium.ui.stats.StatsStateHolder
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.Locale

/**
 * The reproducible English documentation screenshot harness.
 *
 * Every scene is the real Compose screen, wired to the production repositories and
 * state holders; only the HTTP transport is replaced by [FakeDiariumBackend]. The
 * Goals, Achievements and AI report scenes sit inside the same [SubScreenChrome]
 * (back arrow + title) the app shell uses, so they look like actual app states rather
 * than free-floating content.
 *
 * The UI language is English, the data is synthetic, and the date is pinned through
 * the `diarium.screenshot.today` system property that `:app:screenshotEn` sets to
 * 2026-11-07. That is what makes two runs byte-identical: the fixture, the dashboard
 * window, the statistics window, the history month and the check-in header all use
 * that date instead of the wall clock.
 *
 * The class is excluded from normal unit-test runs and also checks the
 * `diarium.screenshots` property in [setUp], so only `./gradlew :app:screenshotEn`
 * can write the tracked PNGs. PNGs land in `docs/screenshots/en/` at 1080 × 2400
 * (432 × 960 dp @ 400 dpi).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w432dp-h960dp-400dpi")
class EnglishScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var context: Context
    private lateinit var backend: FakeDiariumBackend
    private lateinit var entries: EntriesRepository
    private lateinit var pickers: PickersRepository
    private lateinit var goals: GoalsRepository
    private lateinit var dashboard: DashboardRepository
    private lateinit var history: HistoryRepository
    private lateinit var stats: StatsRepository
    private lateinit var achievements: AchievementsRepository
    private lateinit var reports: AiReportsRepository
    private lateinit var profile: ProfileStore

    /** Pinned by `:app:screenshotEn`; the fallback keeps a manual IDE run possible. */
    private val today: LocalDate = System.getProperty("diarium.screenshot.today")
        ?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() }
        ?: LocalDate.now()

    @Before
    fun setUp() {
        // The Gradle task excludes this class from normal test runs; this second guard
        // covers an IDE run or an explicit `--tests` invocation, either of which would
        // otherwise rewrite the tracked PNGs as a side effect of `testDebugUnitTest`.
        assumeTrue(
            "EnglishScreenshotTest runs only through :app:screenshotEn",
            System.getProperty("diarium.screenshots") == "true",
        )

        context = ApplicationProvider.getApplicationContext()
        AppLanguage.set(context, Lang.EN)
        Locale.setDefault(Locale.ENGLISH)

        val sessionStore = SessionStore(context)
        sessionStore.save(
            JSONObject().apply {
                put("access_token", "fixture-access-token")
                put("refresh_token", "fixture-refresh-token")
                put("expires_at", 4_102_444_800L) // 2100 — never refreshed in a test
                put("expires_in", 3600)
                put("token_type", "bearer")
                put("user", JSONObject().apply {
                    put("id", FakeDiariumBackend.USER_ID)
                    put("user_metadata", JSONObject().put("full_name", "Alex"))
                })
            },
        )

        backend = FakeDiariumBackend(today)
        val client = SupabaseClient(sessionStore, backend)
        val session = SessionContext(sessionStore)
        entries = EntriesRepository(client, session)
        pickers = PickersRepository(client, session)
        goals = GoalsRepository(client, session, today = { today.toString() })
        dashboard = DashboardRepository(entries)
        history = HistoryRepository(entries)
        stats = StatsRepository(entries)
        achievements = AchievementsRepository(client, session, today = { today })
        reports = AiReportsRepository(client, session)

        profile = ProfileStore.from(context, FakeDiariumBackend.USER_ID)
        runBlocking {
            profile.saveName("Alex")
            profile.saveBirthYear(1990)
            profile.setAiEnabled(true)
            profile.setOnboardingDone(true)
        }
    }

    @Test
    fun `01 dashboard`() {
        capture("01-dashboard.png", marker = "STREAK") {
            DashboardScreen(
                deps = DashboardDeps(
                    dashboard = dashboard,
                    goals = goals,
                    profile = profile,
                ),
                holder = DashboardStateHolder(),
                today = today,
            )
        }
    }

    @Test
    fun `02 check-in`() {
        capture("02-check-in.png", marker = "Walking") {
            CheckInScreen(
                deps = CheckInDeps(
                    entries = entries,
                    pickers = pickers,
                    goals = goals,
                    profile = profile,
                ),
                requestedDate = today.toString(),
                today = today,
                lateNight = false,
            )
        }
    }

    @Test
    fun `03 history calendar`() {
        // November 2026 is the production shape that exposed the partial-week bug: it
        // starts on a Sunday (six leading blanks) and ends with Monday the 30th alone
        // in the final week. The pinned date opens the screen on that month, so the
        // shot no longer depends on the day the harness happens to run.
        capture("03-history.png", marker = "Tap a day in the calendar") {
            HistoryScreen(
                deps = HistoryDeps(history = history, pickers = pickers),
                today = today,
            )
        }
    }

    @Test
    fun `04 statistics`() {
        capture("04-statistics.png", marker = "Total") {
            StatsScreen(
                deps = StatsDeps(stats = stats, goals = goals),
                holder = StatsStateHolder(),
                today = today,
            )
        }
    }

    @Test
    fun `05 settings`() {
        capture("05-settings.png", marker = "Alex") {
            SettingsScreen(
                onSignOut = {},
                onOpen = {},
                aiDeps = AiSettingsDeps.offline(),
            )
        }
    }

    @Test
    fun `06 goals`() {
        capture("06-goals.png", marker = "Morning walk") {
            SubScreenChrome(title = "Cíle", onBack = {}) {
                GoalsScreen(deps = GoalsDeps(goals = goals, pickers = pickers))
            }
        }
    }

    @Test
    fun `07 achievements`() {
        capture("07-achievements.png", marker = "First entry") {
            SubScreenChrome(title = "Odznaky", onBack = {}) {
                AchievementsScreen(deps = AchievementsDeps(achievements = achievements))
            }
        }
    }

    @Test
    fun `08 ai report`() {
        // The body is Czech on purpose: ReportPromptBuilder asks DeepSeek for Czech
        // prose and Settings says so. The fixture must not pretend the AI writes
        // English; the English chrome and the language note are what the user sees.
        capture("08-ai-report.png", marker = "Tento týden měl jasný rytmus") {
            SubScreenChrome(title = ReportsState.TITLE, onBack = {}) {
                ReportsScreen(deps = ReportsDeps(repository = reports, profile = profile))
            }
        }
    }

    // ── capture plumbing ─────────────────────────────────────────────────────

    private fun capture(
        name: String,
        marker: String,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            DiariumTheme {
                DiariumBackground {
                    content()
                }
            }
        }
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText(marker, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()

        val userDir = System.getProperty("user.dir") ?: "."
        val outputDir = File(
            System.getProperty("diarium.screenshot.dir")
                ?: File(userDir).resolve("../docs/screenshots/en").path,
        ).apply { mkdirs() }
        val output = File(outputDir, name)

        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            check(view.width == 1080 && view.height == 2400) {
                "screenshot viewport is ${view.width}x${view.height}, expected 1080x2400"
            }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("SCREENSHOT ${output.absolutePath} ${bitmap.width}x${bitmap.height} ${output.length()}B")
        }
    }
}
