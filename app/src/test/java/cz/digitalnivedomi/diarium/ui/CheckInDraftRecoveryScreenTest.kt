package cz.digitalnivedomi.diarium.ui

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.i18n.AppLanguage
import cz.digitalnivedomi.diarium.core.i18n.Lang
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.data.DraftStore
import cz.digitalnivedomi.diarium.core.data.EntriesRepository
import cz.digitalnivedomi.diarium.core.data.HttpRequest
import cz.digitalnivedomi.diarium.core.data.HttpResponse
import cz.digitalnivedomi.diarium.core.data.HttpTransport
import cz.digitalnivedomi.diarium.core.data.PendingSaveRecoveryIssueKind
import cz.digitalnivedomi.diarium.core.data.PendingSaveStore
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.SupabaseClient
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDeps
import cz.digitalnivedomi.diarium.ui.checkin.CheckInScreen
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class CheckInDraftRecoveryScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun useCzechLabels() {
        AppLanguage.set(context, Lang.CS)
    }

    @After
    fun restoreCzechLabels() {
        AppLanguage.set(context, Lang.CS)
    }

    @Test
    fun `foreground edit sends the loaded row token and a stable request id to v2`() = runBlocking {
        val file = File.createTempFile("diarium_foreground_cas", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val drafts = DraftStore(dataStore, OWNER_ID)
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val transport = HttpTransport { request ->
            requests += request
            when {
                request.method == "GET" && request.url.contains("/entries?") -> HttpResponse(
                    200,
                    """[{"user_id":"$OWNER_ID","id":"$ROW_ID","date":"$DATE","revision":7,"mood":2,"mood_emoji":"😐","note":"server-side edit","updated_at":"$NEW_REVISION"}]""",
                )
                request.url.endsWith("/rpc/save_daily_entry_v2") -> HttpResponse(
                    200,
                    JSONObject()
                        .put("status", "saved")
                        .put("entry_id", ROW_ID)
                        .put("revision", 8)
                        .put("replayed", false)
                        .toString(),
                )
                else -> HttpResponse(200, "{}")
            }
        }

        try {
            compose.setContent {
                DiariumTheme {
                    CheckInScreen(
                        deps = CheckInDeps(entries = repository(transportOverride = transport), drafts = drafts),
                        today = LocalDate.parse(DATE),
                        lateNight = false,
                    )
                }
            }
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithTag("checkin_edit").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("checkin_edit").performScrollTo().performClick()
            compose.onNodeWithTag("note").performScrollTo().performTextInput("foreground update")
            compose.onNodeWithTag("save_button").performScrollTo().performClick()
            compose.waitUntil(TIMEOUT) {
                requests.any { it.url.endsWith("/rpc/save_daily_entry_v2") }
            }

            val rpc = requests.single { it.url.endsWith("/rpc/save_daily_entry_v2") }
            val body = JSONObject(rpc.body!!)
            assertEquals(true, body.getBoolean("p_expected_exists"))
            assertEquals(ROW_ID, body.getString("p_expected_id"))
            assertEquals(7L, body.getLong("p_expected_revision"))
            assertTrue(body.getString("p_request_id").matches(Regex("[0-9a-fA-F-]{36}")))
            val payload = body.getJSONObject("p_payload")
            assertEquals(OWNER_ID, payload.getString("user_id"))
            assertEquals(DATE, payload.getString("date"))
            assertTrue(payload.getString("note").contains("foreground update"))
            assertTrue(requests.none { it.url.endsWith("/rpc/save_daily_entry") })
            compose.waitUntil(TIMEOUT) { runBlocking { drafts.loadRecord(DATE) == null } }
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            file.delete()
        }
    }

    @Test
    fun `server choice keeps the hidden local draft and blocks saves`() = runBlocking {
        val file = File.createTempFile("diarium_draft_recovery", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val drafts = DraftStore(dataStore, OWNER_ID)
        val localDraft = DiaryEntry(mood = 4, moodEmoji = "🙂", note = "private local draft")
        val requests = mutableListOf<HttpRequest>()
        drafts.save(DATE, localDraft, baselineUpdatedAt = OLD_REVISION, baselineExists = true)

        try {
            compose.setContent {
                DiariumTheme {
                    CheckInScreen(
                        deps = CheckInDeps(entries = repository(requests = requests), drafts = drafts),
                        today = LocalDate.parse(DATE),
                        lateNight = false,
                    )
                }
            }

            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText(CONFLICT_TITLE).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(compose.onAllNodesWithTag("save_button").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithContentDescription("Prohlédnout uložený den").assertHasClickAction()
            compose.onNodeWithContentDescription("Prohlédnout místní návrh").assertHasClickAction()
            compose.onNodeWithText(SAVING_BLOCKED).assertExists()
            compose.onNodeWithText("private local draft").assertExists()
            compose.onNodeWithText("server-side edit").assertExists()

            compose.onNodeWithTag("checkin_review_server").performClick()
            assertTrue(compose.onAllNodesWithTag("save_button").fetchSemanticsNodes().isEmpty())
            compose.waitForIdle()

            assertEquals(localDraft, drafts.loadRecord(DATE)?.entry)
            assertEquals(OLD_REVISION, drafts.loadRecord(DATE)?.baselineUpdatedAt)
            assertEquals(listOf("GET"), requests.map { it.method }.distinct())
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `local choice opens the preserved draft without enabling unsafe server overwrite`() = runBlocking {
        val file = File.createTempFile("diarium_draft_recovery", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val drafts = DraftStore(dataStore, OWNER_ID)
        val localDraft = DiaryEntry(mood = 4, moodEmoji = "🙂", note = "private local draft")
        val requests = mutableListOf<HttpRequest>()
        drafts.save(DATE, localDraft, baselineUpdatedAt = OLD_REVISION, baselineExists = true)

        try {
            compose.setContent {
                DiariumTheme {
                    CheckInScreen(
                        deps = CheckInDeps(entries = repository(requests = requests), drafts = drafts),
                        today = LocalDate.parse(DATE),
                        lateNight = false,
                    )
                }
            }

            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText(CONFLICT_TITLE).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("checkin_review_local_draft").performClick()
            compose.onNodeWithText("Prohlížíš místní návrh. Kopie na serveru se nezměnila.").assertExists()
            compose.onNodeWithText(SAVING_BLOCKED).assertExists()
            assertTrue(compose.onAllNodesWithTag("note").fetchSemanticsNodes().isEmpty())
            assertTrue(
                compose.onAllNodesWithText("private local draft").fetchSemanticsNodes().isNotEmpty(),
            )
            assertTrue(compose.onAllNodesWithTag("save_button").fetchSemanticsNodes().isEmpty())
            assertEquals(localDraft, drafts.loadRecord(DATE)?.entry)
            assertEquals(listOf("GET"), requests.map { it.method }.distinct())
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `server read failure preserves the draft and blocks recovery writes`() = runBlocking {
        val file = File.createTempFile("diarium_draft_recovery_error", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val drafts = DraftStore(dataStore, OWNER_ID)
        val localDraft = DiaryEntry(mood = 4, moodEmoji = "🙂", note = "private local draft")
        val requests = mutableListOf<HttpRequest>()
        drafts.save(DATE, localDraft, baselineUpdatedAt = OLD_REVISION, baselineExists = true)

        try {
            compose.setContent {
                DiariumTheme {
                    CheckInScreen(
                        deps = CheckInDeps(
                            entries = repository(
                                requests = requests,
                                remoteReadResponse = HttpResponse(503, """{"message":"unavailable"}"""),
                            ),
                            drafts = drafts,
                        ),
                        today = LocalDate.parse(DATE),
                        lateNight = false,
                    )
                }
            }

            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText("Serverovou kopii se nepodařilo načíst")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("checkin_load_retry").assertExists()
            compose.onNodeWithTag("checkin_review_local_draft").assertIsNotEnabled()
            compose.onNodeWithTag("checkin_review_server").assertIsNotEnabled()
            assertTrue(compose.onAllNodesWithTag("save_button").fetchSemanticsNodes().isEmpty())
            assertEquals(localDraft, drafts.loadRecord(DATE)?.entry)
            assertEquals(listOf("GET"), requests.map { it.method }.distinct())
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `recovery actions expose English accessible names`() = runBlocking {
        AppLanguage.set(context, Lang.EN)
        val file = File.createTempFile("diarium_draft_recovery_en", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val drafts = DraftStore(dataStore, OWNER_ID)
        val pending = PendingSaveStore.from(context, OWNER_ID)
        pending.remove(DATE)
        drafts.save(
            DATE,
            DiaryEntry(mood = 4, moodEmoji = "🙂", note = "private local draft"),
            baselineUpdatedAt = OLD_REVISION,
            baselineExists = true,
        )
        assertTrue(
            pending.enqueue(
                DATE,
                DiaryEntry(mood = 5, moodEmoji = "😄", note = "pending recovery copy"),
                baselineUpdatedAt = OLD_REVISION,
                baselineExists = true,
            ),
        )

        try {
            compose.setContent {
                DiariumTheme {
                    CheckInScreen(
                        deps = CheckInDeps(
                            entries = repository(pending = pending),
                            drafts = drafts,
                        ),
                        today = LocalDate.parse(DATE),
                        lateNight = false,
                    )
                }
            }

            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithContentDescription("Review the local draft")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("The server version has changed since you started editing.").assertExists()
            compose.onNodeWithContentDescription("Review the local draft").assertHasClickAction()
            compose.onNodeWithContentDescription("Review the saved day").assertHasClickAction()
            compose.onNodeWithText("Unsynced entry on this phone").assertExists()
            compose.onNodeWithText(
                "This older entry has no verified original revision and CAS identity. It remains untouched and will not be sent automatically; user review is required.",
            ).assertExists()
            compose.onNodeWithContentDescription("Review the local entry").assertHasClickAction()
            compose.onNodeWithContentDescription("Review the server copy").assertHasClickAction()
            assertEquals(
                2,
                compose.onAllNodesWithText(
                    "Saving remains blocked. Review both copies; choosing either won't overwrite anything.",
                ).fetchSemanticsNodes().size,
            )
            assertTrue(compose.onAllNodesWithTag("save_button").fetchSemanticsNodes().isEmpty())
        } finally {
            pending.remove(DATE)
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
        Unit
    }

    @Test
    fun `queued local copy is reviewable and remains unsynced and persisted`() = runBlocking {
        val pending = PendingSaveStore.from(context, OWNER_ID)
        pending.remove(DATE)
        val localCopy = DiaryEntry(mood = 5, moodEmoji = "😄", note = "queued local copy")
        assertTrue(pending.enqueue(DATE, localCopy, baselineUpdatedAt = OLD_REVISION, baselineExists = true))
        val requests = mutableListOf<HttpRequest>()

        try {
            compose.setContent {
                DiariumTheme {
                    CheckInScreen(
                        deps = CheckInDeps(
                            entries = repository(requests = requests, pending = pending),
                        ),
                        today = LocalDate.parse(DATE),
                        lateNight = false,
                    )
                }
            }

            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText(PENDING_TITLE).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(UNSYNCED_STATUS).assertExists()
            compose.onNodeWithText("queued local copy").assertExists()
            compose.onNodeWithText("server-side edit").assertExists()
            compose.onNodeWithText(SAVING_BLOCKED).assertExists()
            assertTrue(compose.onAllNodesWithTag("save_button").fetchSemanticsNodes().isEmpty())

            compose.onNodeWithTag("checkin_pending_show_server").performClick()
            compose.onNodeWithText("Prohlížíš serverovou verzi. Místní zápis zůstává uložený v telefonu.")
                .assertExists()
            compose.onNodeWithTag("checkin_pending_show_local").performClick()
            compose.onNodeWithText("Prohlížíš místní zápis. Kopie na serveru se nezměnila.").assertExists()

            assertEquals(localCopy, pending.peekRecord(DATE)?.entry)
            assertEquals(listOf("GET"), requests.map { it.method }.distinct())
        } finally {
            pending.remove(DATE)
        }
    }

    @Test
    fun `ownerless legacy entry is visibly quarantined and never sent`() = runBlocking {
        val preferences = context.getSharedPreferences(PendingSaveStore.PREFS_NAME, Context.MODE_PRIVATE)
        val legacy = JSONObject()
            .put("queuedAt", 12L)
            .put("entry", DiaryEntry(mood = 5, note = "ownerless private copy").toJson())
            .toString()
        preferences.edit().putString(DATE, legacy).commit()
        val pending = PendingSaveStore.from(context, OWNER_ID)
        val requests = CopyOnWriteArrayList<HttpRequest>()

        try {
            compose.setContent {
                DiariumTheme {
                    CheckInScreen(
                        deps = CheckInDeps(
                            entries = repository(requests = requests, pending = pending),
                        ),
                        today = LocalDate.parse(DATE),
                        lateNight = false,
                    )
                }
            }

            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText("Místní zápis vyžaduje ruční obnovu")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(
                "Starší zápis nemá přiřazený účet. Nebyl přiřazen k tomuto přihlášení ani odeslán; zůstává v telefonu.",
            ).assertExists()
            assertTrue(compose.onAllNodesWithTag("save_button").fetchSemanticsNodes().isEmpty())
            assertEquals(PendingSaveRecoveryIssueKind.OWNERLESS, pending.recoveryIssueForDate(DATE)?.kind)
            assertEquals(legacy, preferences.getString(DATE, null))
            assertEquals(listOf("GET"), requests.map { it.method }.distinct())
        } finally {
            preferences.edit().remove(DATE).commit()
        }
    }

    @Test
    fun `opening saved day never creates a draft after debounce navigation or stop`() = runBlocking {
        val file = File.createTempFile("diarium_passive_day_draft", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val drafts = DraftStore(dataStore, OWNER_ID)
        val requests = mutableListOf<HttpRequest>()

        try {
            compose.setContent {
                DiariumTheme {
                    CheckInScreen(
                        deps = CheckInDeps(entries = repository(requests = requests), drafts = drafts),
                        today = LocalDate.parse(DATE),
                        lateNight = false,
                    )
                }
            }
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithTag("checkin_edit").fetchSemanticsNodes().isNotEmpty()
            }

            shadowOf(Looper.getMainLooper()).idleFor(1301, TimeUnit.MILLISECONDS)
            compose.waitForIdle()
            assertNull(drafts.loadRecord(DATE))

            compose.onNodeWithTag("date_prev").performScrollTo().performClick()
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithTag("checkin_edit").fetchSemanticsNodes().isNotEmpty()
            }
            // DateNav is intentionally hidden while the selected day's server row loads.
            compose.onNodeWithText("2026-09-09", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("date_next").performScrollTo().performClick()
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithTag("checkin_edit").fetchSemanticsNodes().isNotEmpty()
            }
            assertNull(drafts.loadRecord(DATE))

            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.waitForIdle()
            assertNull(drafts.loadRecord(DATE))
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithTag("checkin_edit").fetchSemanticsNodes().isNotEmpty()
            }

            assertNull(drafts.loadRecord(DATE))
            assertEquals(listOf("GET"), requests.map { it.method }.distinct())
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `slow selected day load shows accessible progress without a blank form`() = runBlocking {
        val file = File.createTempFile("diarium_slow_day_load", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val drafts = DraftStore(dataStore, OWNER_ID)
        val transport = GatedReadTransport(
            HttpResponse(
                200,
                """[{"user_id":"$OWNER_ID","date":"$DATE","mood":2,"mood_emoji":"😐","note":"server-side edit","updated_at":"$NEW_REVISION"}]""",
            ),
        )

        try {
            compose.setContent {
                DiariumTheme {
                    CheckInScreen(
                        deps = CheckInDeps(
                            entries = repository(transportOverride = transport),
                            drafts = drafts,
                        ),
                        today = LocalDate.parse(DATE),
                        lateNight = false,
                    )
                }
            }
            assertTrue("the server read should remain held", transport.readStarted.await(TIMEOUT, TimeUnit.MILLISECONDS))

            compose.onNodeWithContentDescription("Načítám zápisy…").assertExists()
            assertTrue("an empty note field would look like a new entry", compose.onAllNodesWithTag("note").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithTag("save_button").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithTag("date_prev").fetchSemanticsNodes().isEmpty())
            assertNull("no draft is created while the row is still loading", drafts.loadRecord(DATE))

            transport.releaseRead.countDown()
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText("server-side edit").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(compose.onAllNodesWithTag("checkin_load_retry").fetchSemanticsNodes().isEmpty())
            assertEquals(listOf("GET"), transport.requests.map { it.method }.distinct())
        } finally {
            transport.releaseRead.countDown()
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `on stop draft write failure keeps the form and clear retry persists it`() = runBlocking {
        val file = File.createTempFile("diarium_failed_draft_flush", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val backing = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val drafts = DraftStore(FailOnceWritePreferencesDataStore(backing), OWNER_ID)

        try {
            compose.setContent {
                DiariumTheme {
                    CheckInScreen(
                        deps = CheckInDeps(
                            entries = repository(remoteReadResponse = HttpResponse(200, "[]")),
                            drafts = drafts,
                        ),
                        today = LocalDate.parse(DATE),
                        lateNight = false,
                    )
                }
            }
            waitForEditableForm()
            compose.onNodeWithTag("mood_4").performScrollTo().performClick()

            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.waitForIdle()
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText("Místní návrh zatím není uložený. Obsah zůstává zobrazený; zkus to znovu před odchodem.")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("🙂 4/5").assertExists()
            assertNull("the failed transaction did not create a durable copy", drafts.loadRecord(DATE))

            compose.onNodeWithTag("checkin_load_retry").performClick()
            compose.waitUntil(TIMEOUT) {
                runBlocking { drafts.loadRecord(DATE)?.entry?.mood == 4 }
            }
            assertEquals(4, drafts.loadRecord(DATE)?.entry?.mood)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `manual date navigation flushes the latest edit before debounce`() = runBlocking {
        val file = File.createTempFile("diarium_manual_date_flush", ".preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val drafts = DraftStore(dataStore, OWNER_ID)

        try {
            compose.setContent {
                DiariumTheme {
                    CheckInScreen(
                        deps = CheckInDeps(drafts = drafts),
                        today = LocalDate.parse(DATE),
                        lateNight = false,
                    )
                }
            }
            waitForEditableForm()
            compose.onNodeWithTag("mood_4").performScrollTo().performClick()
            compose.onNodeWithTag("date_prev").performScrollTo().performClick()

            compose.waitUntil(TIMEOUT) {
                runBlocking { drafts.loadRecord(DATE)?.entry?.mood == 4 }
            }
            assertEquals(4, drafts.loadRecord(DATE)?.entry?.mood)
            assertNull(drafts.loadRecord("2026-09-09"))
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            file.delete()
        }
    }

    @Test
    fun `rapid return waits for delayed date-change draft write`() = runBlocking {
        val file = File.createTempFile("diarium_delayed_date_flush", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val backingStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val delayedStore = GatedPreferencesDataStore(backingStore)
        val drafts = DraftStore(delayedStore, OWNER_ID)

        try {
            compose.setContent {
                DiariumTheme {
                    CheckInScreen(
                        deps = CheckInDeps(drafts = drafts),
                        requestedDate = DATE,
                        today = LocalDate.parse(DATE).plusDays(1),
                        lateNight = false,
                    )
                }
            }
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText(DATE).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            waitForEditableForm()
            compose.onNodeWithTag("mood_4").performScrollTo().performClick()
            delayedStore.armWrites()
            compose.onNodeWithTag("date_prev").performScrollTo().performClick()
            compose.waitUntil(TIMEOUT) { delayedStore.writeStarted.isCompleted }

            // A rapid return is rejected while the original day's write is pending.
            compose.onNodeWithTag("date_next").performClick()
            compose.onNodeWithText(DATE).assertExists()

            delayedStore.releaseWrite.complete(Unit)
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText("2026-09-09").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(4, drafts.loadRecord(DATE)?.entry?.mood)
            waitForEditableForm()

            compose.onNodeWithTag("date_next").performScrollTo().performClick()
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText("4/5", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(4, drafts.loadRecord(DATE)?.entry?.mood)
        } finally {
            delayedStore.releaseWrite.complete(Unit)
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `owner change flushes only the previous owners draft`() = runBlocking {
        val file = File.createTempFile("diarium_owner_change_flush", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val ownerA = DraftStore(dataStore, OWNER_ID)
        val ownerB = DraftStore(dataStore, OTHER_OWNER_ID)
        val deps = androidx.compose.runtime.mutableStateOf(CheckInDeps(drafts = ownerA))

        try {
            compose.setContent {
                DiariumTheme {
                    CheckInScreen(
                        deps = deps.value,
                        today = LocalDate.parse(DATE),
                        lateNight = false,
                    )
                }
            }
            waitForEditableForm()
            compose.onNodeWithTag("mood_4").performScrollTo().performClick()
            compose.runOnIdle { deps.value = CheckInDeps(drafts = ownerB) }

            compose.waitUntil(TIMEOUT) {
                runBlocking { ownerA.loadRecord(DATE)?.entry?.mood == 4 }
            }
            assertEquals(4, ownerA.loadRecord(DATE)?.entry?.mood)
            assertNull(ownerB.loadRecord(DATE))
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `save stages the latest draft before cancellable transport and keeps it after disposal`() = runBlocking {
        val file = File.createTempFile("diarium_cancelled_save", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val drafts = DraftStore(dataStore, OWNER_ID)
        val transport = GatedSaveTransport()
        val visible = androidx.compose.runtime.mutableStateOf(true)

        try {
            compose.setContent {
                DiariumTheme {
                    if (visible.value) {
                        CheckInScreen(
                            deps = CheckInDeps(
                                entries = repository(transportOverride = transport, remoteReadResponse = HttpResponse(200, "[]")),
                                drafts = drafts,
                            ),
                            today = LocalDate.parse(DATE),
                            lateNight = false,
                        )
                    }
                }
            }
            waitForEditableForm()
            compose.onNodeWithTag("note").performScrollTo().performTextInput("latest before debounce")
            assertNull("the normal draft debounce should still be pending", drafts.loadRecord(DATE))
            compose.onNodeWithTag("save_button").performScrollTo().performClick()

            assertTrue("the remote save should start after the local stage", transport.writeStarted.await(TIMEOUT, TimeUnit.MILLISECONDS))
            assertEquals("latest before debounce", drafts.loadRecord(DATE)?.entry?.note)

            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            assertEquals("composition disposal must not remove the staged copy", "latest before debounce", drafts.loadRecord(DATE)?.entry?.note)
        } finally {
            transport.releaseWrite.countDown()
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `a confirmed remote save clears only its staged snapshot`() = runBlocking {
        val file = File.createTempFile("diarium_confirmed_save", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val drafts = DraftStore(dataStore, OWNER_ID)
        val transport = GatedSaveTransport()

        try {
            compose.setContent {
                DiariumTheme {
                    CheckInScreen(
                        deps = CheckInDeps(
                            entries = repository(transportOverride = transport, remoteReadResponse = HttpResponse(200, "[]")),
                            drafts = drafts,
                        ),
                        today = LocalDate.parse(DATE),
                        lateNight = false,
                    )
                }
            }
            waitForEditableForm()
            compose.onNodeWithTag("note").performScrollTo().performTextInput("confirmed snapshot")
            compose.onNodeWithTag("save_button").performScrollTo().performClick()
            assertTrue(transport.writeStarted.await(TIMEOUT, TimeUnit.MILLISECONDS))
            assertEquals("confirmed snapshot", drafts.loadRecord(DATE)?.entry?.note)

            transport.releaseWrite.countDown()
            compose.waitUntil(TIMEOUT) { runBlocking { drafts.loadRecord(DATE) == null } }
        } finally {
            transport.releaseWrite.countDown()
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `retry persists the offline edit before a successful day response`() = runBlocking {
        verifyRetryPreservesOfflineNote(
            retryResponse = HttpResponse(
                200,
                """[{"user_id":"$OWNER_ID","date":"$DATE","mood":2,"mood_emoji":"😐","note":"remote note","updated_at":"$NEW_REVISION"}]""",
            ),
            expectedTitle = CONFLICT_TITLE,
        )
    }

    @Test
    fun `retry persists the offline edit before a failed day response`() = runBlocking {
        verifyRetryPreservesOfflineNote(
            retryResponse = HttpResponse(503, """{"message":"still offline"}"""),
            expectedTitle = "Serverovou kopii se nepodařilo načíst",
        )
    }

    @Test
    fun `draft store read failure is recoverable and loading never shows a blank form`() = runBlocking {
        val file = File.createTempFile("diarium_draft_read_retry", ".preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val backing = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        runBlocking { backing.edit { it[stringPreferencesKey("test_seed")] = "ready" } }
        val delayedRead = FailOnceGatedPreferencesDataStore(backing)
        val drafts = DraftStore(delayedRead, OWNER_ID)

        try {
            compose.setContent {
                DiariumTheme {
                    CheckInScreen(
                        deps = CheckInDeps(
                            entries = repository(remoteReadResponse = HttpResponse(200, "[]")),
                            drafts = drafts,
                        ),
                        today = LocalDate.parse(DATE),
                        lateNight = false,
                    )
                }
            }
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithTag("checkin_load_retry").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue("a failed local read must not render an empty note field", compose.onAllNodesWithTag("note").fetchSemanticsNodes().isEmpty())

            compose.onNodeWithTag("checkin_load_retry").performClick()
            compose.waitUntil(TIMEOUT) { delayedRead.retryReadStarted.count == 0L }
            compose.onNodeWithContentDescription("Načítám zápisy…").assertExists()
            assertTrue("the loading state must not render a blank form", compose.onAllNodesWithTag("note").fetchSemanticsNodes().isEmpty())

            delayedRead.releaseRetryRead.countDown()
            waitForEditableForm()
            assertTrue(compose.onAllNodesWithTag("checkin_load_retry").fetchSemanticsNodes().isEmpty())
        } finally {
            delayedRead.releaseRetryRead.countDown()
            scope.coroutineContext[Job]?.cancelAndJoin()
            file.delete()
        }
    }

    private suspend fun verifyRetryPreservesOfflineNote(
        retryResponse: HttpResponse,
        expectedTitle: String,
    ) {
        val file = File.createTempFile("diarium_retry_keeps_edit", ".preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        dataStore.edit { it[stringPreferencesKey("test_seed")] = "ready" }
        val drafts = DraftStore(dataStore, OWNER_ID)
        val transport = RetryReadTransport(retryResponse)

        try {
            compose.setContent {
                DiariumTheme {
                    CheckInScreen(
                        deps = CheckInDeps(
                            entries = repository(transportOverride = transport),
                            drafts = drafts,
                        ),
                        today = LocalDate.parse(DATE),
                        lateNight = false,
                    )
                }
            }
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithTag("checkin_load_retry").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("note").performScrollTo().performTextInput("offline retry note")
            compose.onNodeWithTag("checkin_load_retry").performScrollTo().performClick()
            compose.waitUntil(TIMEOUT) { transport.retryReadStarted.count == 0L }
            compose.waitUntil(TIMEOUT) {
                runBlocking { drafts.loadRecord(DATE)?.entry?.note == "offline retry note" }
            }

            transport.releaseRetryRead.countDown()
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText(expectedTitle).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("offline retry note").assertExists()
            assertEquals("offline retry note", drafts.loadRecord(DATE)?.entry?.note)
        } finally {
            transport.releaseRetryRead.countDown()
            scope.coroutineContext[Job]?.cancelAndJoin()
            file.delete()
        }
    }

    private fun waitForEditableForm() {
        compose.waitUntil(TIMEOUT) {
            runCatching { compose.onNodeWithTag("save_button").assertIsEnabled() }.isSuccess
        }
    }

    private class GatedSaveTransport : HttpTransport {
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)

        override fun execute(request: HttpRequest): HttpResponse {
            if (request.method == "GET" && request.url.contains("/entries?")) {
                return HttpResponse(200, "[]")
            }
            if (request.method == "POST" && request.url.endsWith("/rpc/save_daily_entry_v2")) {
                writeStarted.countDown()
                check(releaseWrite.await(10_000L, TimeUnit.MILLISECONDS)) {
                    "test did not release the gated save"
                }
                return HttpResponse(
                    200,
                    JSONObject()
                        .put("status", "saved")
                        .put("entry_id", ROW_ID)
                        .put("revision", 1)
                        .put("replayed", false)
                        .toString(),
                )
            }
            return HttpResponse(200, "{}")
        }
    }

    private class RetryReadTransport(
        private val retryResponse: HttpResponse,
    ) : HttpTransport {
        val retryReadStarted = CountDownLatch(1)
        val releaseRetryRead = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<HttpRequest>()
        private val dayReadCount = AtomicInteger(0)

        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            if (request.method == "GET" && request.url.contains("/entries?")) {
                if (dayReadCount.incrementAndGet() == 1) {
                    return HttpResponse(503, """{"message":"offline"}""")
                }
                retryReadStarted.countDown()
                check(releaseRetryRead.await(10_000L, TimeUnit.MILLISECONDS)) {
                    "test did not release the gated retry read"
                }
                return retryResponse
            }
            return HttpResponse(200, "{}")
        }
    }

    private class FailOnceGatedPreferencesDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        val retryReadStarted = CountDownLatch(1)
        val releaseRetryRead = CountDownLatch(1)
        private val shouldFail = AtomicBoolean(true)

        override val data: Flow<Preferences>
            get() = flow<Preferences> {
                if (shouldFail.compareAndSet(true, false)) {
                    throw IOException("simulated draft read failure")
                }
                retryReadStarted.countDown()
                check(releaseRetryRead.await(10_000L, TimeUnit.MILLISECONDS)) {
                    "test did not release the gated draft retry"
                }
                emitAll(delegate.data)
            }.flowOn(Dispatchers.IO)

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            delegate.updateData(transform)
    }

    private class FailOnceWritePreferencesDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        private val shouldFail = AtomicBoolean(true)

        override val data: Flow<Preferences> get() = delegate.data

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (shouldFail.compareAndSet(true, false)) throw IOException("simulated local draft write failure")
            return delegate.updateData(transform)
        }
    }

    private class GatedReadTransport(
        private val response: HttpResponse,
    ) : HttpTransport {
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<HttpRequest>()

        override fun execute(request: HttpRequest): HttpResponse {
            requests += request
            if (request.method == "GET" && request.url.contains("/entries?")) {
                readStarted.countDown()
                check(releaseRead.await(10_000L, TimeUnit.MILLISECONDS)) {
                    "test did not release the gated day read"
                }
                return response
            }
            return HttpResponse(200, "{}")
        }
    }

    private class GatedPreferencesDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        @Volatile
        private var writesArmed = false

        override val data: Flow<Preferences> get() = delegate.data

        fun armWrites() {
            writesArmed = true
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (writesArmed && !releaseWrite.isCompleted) {
                writeStarted.complete(Unit)
                releaseWrite.await()
            }
            return delegate.updateData(transform)
        }
    }

    private fun repository(
        requests: MutableList<HttpRequest> = mutableListOf(),
        remoteReadResponse: HttpResponse? = null,
        pending: PendingSaveStore? = null,
        transportOverride: HttpTransport? = null,
    ): EntriesRepository {
        val defaultRemoteResponse = HttpResponse(
            200,
            """[{"user_id":"$OWNER_ID","date":"$DATE","mood":2,"mood_emoji":"😐","note":"server-side edit","updated_at":"$NEW_REVISION"}]""",
        )
        val transport = transportOverride ?: HttpTransport { request ->
            requests += request
            if (request.method == "GET" && request.url.contains("/entries?")) {
                remoteReadResponse ?: defaultRemoteResponse
            } else {
                HttpResponse(200, "{}")
            }
        }
        val store = SessionStore(ApplicationProvider.getApplicationContext()).apply {
            save(
                JSONObject().apply {
                    put("access_token", "test-access-token")
                    put("refresh_token", "test-refresh-token")
                    put("expires_at", System.currentTimeMillis() / 1000 + 3600)
                    put("user", JSONObject().put("id", OWNER_ID))
                },
            )
        }
        return EntriesRepository(
            client = SupabaseClient(
                sessionStore = store,
                transport = transport,
                baseUrl = BASE_URL,
                anonKey = "anon-test-key",
            ),
            session = SessionContext(store),
            pending = pending,
            expectedOwnerId = OWNER_ID,
        )
    }

    private companion object {
        const val DATE = "2026-09-10"
        const val OWNER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val OTHER_OWNER_ID = "7f3d2c8a-1e4b-5a6f-9c20-3d5e7f9a1b24"
        const val ROW_ID = "ecaf917a-7c84-4ad5-b657-cc5d9af10a36"
        const val BASE_URL = "https://example.supabase.co/rest/v1"
        const val OLD_REVISION = "2026-09-10T10:00:00Z"
        const val NEW_REVISION = "2026-09-10T11:00:00Z"
        const val CONFLICT_TITLE = "Serverová verze se od zahájení úpravy změnila"
        const val SAVING_BLOCKED =
            "Ukládání zůstává zablokované. Prohlédni si obě kopie; výběr nic nepřepíše."
        const val PENDING_TITLE = "Nesynchronizovaný zápis v tomto telefonu"
        const val UNSYNCED_STATUS =
            "Tento starší záznam nemá ověřenou původní revizi a identitu CAS. Zůstává nedotčený a nebude automaticky odeslán; je nutná kontrola uživatele."
        const val TIMEOUT = 10_000L
    }
}
