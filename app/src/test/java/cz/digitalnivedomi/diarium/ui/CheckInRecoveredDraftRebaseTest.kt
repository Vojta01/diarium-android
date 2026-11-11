package cz.digitalnivedomi.diarium.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.data.DraftStore
import cz.digitalnivedomi.diarium.core.data.EntriesRepository
import cz.digitalnivedomi.diarium.core.data.HttpRequest
import cz.digitalnivedomi.diarium.core.data.HttpResponse
import cz.digitalnivedomi.diarium.core.data.HttpTransport
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryCopyResult
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryRecordId
import cz.digitalnivedomi.diarium.core.data.LegacyRecoveryType
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.SupabaseClient
import cz.digitalnivedomi.diarium.core.i18n.AppLanguage
import cz.digitalnivedomi.diarium.core.i18n.Lang
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDeps
import cz.digitalnivedomi.diarium.ui.checkin.CheckInScreen
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic UI/transport checks for explicit, conditional recovery of legacy drafts. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class CheckInRecoveredDraftRebaseTest {
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
    fun `pre-existing row is rebased explicitly and saved with the exact v2 row token`() = runBlocking {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val reads = AtomicInteger()
        val transport = HttpTransport { request ->
            requests += request
            when {
                isDayRead(request) -> {
                    reads.incrementAndGet()
                    HttpResponse(200, row(7, "stored server copy"))
                }
                isConditionalSave(request) -> savedResponse(8)
                else -> HttpResponse(200, "{}")
            }
        }
        val fixture = recoveredFixture(transport)
        try {
            show(fixture)
            waitForRecoveryPanel()
            assertTrue(compose.onAllNodesWithTag("save_button").fetchSemanticsNodes().isEmpty())

            reviewAndRebase()
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithTag("save_button").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("save_button").performScrollTo().performClick()
            compose.waitUntil(TIMEOUT) { requests.any(::isConditionalSave) }

            val write = JSONObject(requests.single(::isConditionalSave).body!!)
            assertEquals(true, write.getBoolean("p_expected_exists"))
            assertEquals(ROW_ID, write.getString("p_expected_id"))
            assertEquals(7L, write.getLong("p_expected_revision"))
            assertTrue(requests.none { it.url.endsWith("/rpc/save_daily_entry") })
            assertNull("the account draft is cleared only after the verified v2 save", fixture.drafts.loadRecord(DATE))
            assertEquals(fixture.sourceRaw, fixture.readLegacySource())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `absent row rebase uses an explicit v2 create baseline`() = runBlocking {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val transport = HttpTransport { request ->
            requests += request
            when {
                isDayRead(request) -> HttpResponse(200, "[]")
                isConditionalSave(request) -> savedResponse(1)
                else -> HttpResponse(200, "{}")
            }
        }
        val fixture = recoveredFixture(transport)
        try {
            show(fixture)
            waitForRecoveryPanel()
            reviewAndRebase()
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithTag("save_button").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("save_button").performScrollTo().performClick()
            compose.waitUntil(TIMEOUT) { requests.any(::isConditionalSave) }

            val write = JSONObject(requests.single(::isConditionalSave).body!!)
            assertEquals(false, write.getBoolean("p_expected_exists"))
            assertTrue(write.isNull("p_expected_id"))
            assertTrue(write.isNull("p_expected_revision"))
            assertTrue(requests.none { it.url.endsWith("/rpc/save_daily_entry") })
            assertEquals(fixture.sourceRaw, fixture.readLegacySource())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `competing update after rebase remains visible and neither copy is overwritten`() = runBlocking {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val reads = AtomicInteger()
        val transport = HttpTransport { request ->
            requests += request
            when {
                isDayRead(request) -> {
                    val count = reads.incrementAndGet()
                    HttpResponse(200, if (count <= 2) row(7, "server revision seven") else row(8, "competing server update"))
                }
                isConditionalSave(request) -> HttpResponse(
                    200,
                    JSONObject()
                        .put("status", "conflict")
                        .put("reason", "stale_revision")
                        .put("date", DATE)
                        .put("current_id", ROW_ID)
                        .put("current_revision", 8)
                        .put("replayed", false)
                        .toString(),
                )
                else -> HttpResponse(200, "{}")
            }
        }
        val fixture = recoveredFixture(transport)
        try {
            show(fixture)
            waitForRecoveryPanel()
            reviewAndRebase()
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithTag("save_button").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("save_button").performScrollTo().performClick()
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText("Serverová verze se od zahájení úpravy změnila")
                    .fetchSemanticsNodes().isNotEmpty()
            }

            val write = JSONObject(requests.single(::isConditionalSave).body!!)
            assertEquals(7L, write.getLong("p_expected_revision"))
            assertTrue(compose.onAllNodesWithText("synthetic recovered draft").fetchSemanticsNodes().isNotEmpty())
            assertTrue(compose.onAllNodesWithText("competing server update").fetchSemanticsNodes().isNotEmpty())
            val retained = fixture.drafts.loadRecord(DATE)
            assertEquals("synthetic recovered draft", retained?.entry?.note)
            assertEquals(RECOVERY_ID, retained?.legacyRecoveryOrigin)
            assertEquals(fixture.sourceRaw, fixture.readLegacySource())
            assertTrue(requests.none { it.url.endsWith("/rpc/save_daily_entry") })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `failed fresh read keeps the recovered source and does not enable save`() = runBlocking {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val reads = AtomicInteger()
        val transport = HttpTransport { request ->
            requests += request
            when {
                isDayRead(request) -> if (reads.incrementAndGet() == 1) {
                    HttpResponse(200, row(7, "server copy"))
                } else {
                    HttpResponse(503, """{"message":"synthetic read failure"}""")
                }
                else -> HttpResponse(200, "{}")
            }
        }
        val fixture = recoveredFixture(transport)
        try {
            show(fixture)
            waitForRecoveryPanel()
            reviewAndRebase()
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText("Serverovou kopii se nepodařilo načíst")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("checkin_load_retry").assertExists()
            assertTrue(compose.onAllNodesWithTag("save_button").fetchSemanticsNodes().isEmpty())
            assertNull(fixture.drafts.loadRecord(DATE)?.baselineExists)
            assertEquals(fixture.sourceRaw, fixture.readLegacySource())
            assertTrue(requests.none(::isConditionalSave))
            assertTrue(requests.none { it.url.endsWith("/rpc/save_daily_entry") })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `owner change during fresh read leaves the local source and account draft untouched`() = runBlocking {
        val session = sessionStore(OWNER_ID)
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val reads = AtomicInteger()
        val rebaseReadStarted = CountDownLatch(1)
        val releaseRebaseRead = CountDownLatch(1)
        val transport = HttpTransport { request ->
            requests += request
            if (isDayRead(request) && reads.incrementAndGet() == 2) {
                rebaseReadStarted.countDown()
                check(releaseRebaseRead.await(TIMEOUT, TimeUnit.MILLISECONDS)) { "test did not release rebase read" }
            }
            when {
                isDayRead(request) -> HttpResponse(200, row(7, "server copy"))
                else -> HttpResponse(200, "{}")
            }
        }
        val fixture = recoveredFixture(transport, session)
        try {
            show(fixture, includeLiveSession = false)
            waitForRecoveryPanel()
            reviewAndRebase(start = false)
            assertTrue("fresh authenticated read should start", rebaseReadStarted.await(TIMEOUT, TimeUnit.MILLISECONDS))

            session.save(sessionJson(OTHER_OWNER_ID))
            releaseRebaseRead.countDown()
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithTag("checkin_load_retry").fetchSemanticsNodes().isNotEmpty()
            }

            assertNull(fixture.drafts.loadRecord(DATE)?.baselineExists)
            assertNull(DraftStore(fixture.dataStore, OTHER_OWNER_ID).loadRecord(DATE))
            assertEquals(fixture.sourceRaw, fixture.readLegacySource())
            assertTrue(requests.none(::isConditionalSave))
            assertTrue(requests.none { it.url.endsWith("/rpc/save_daily_entry") })
        } finally {
            releaseRebaseRead.countDown()
            fixture.close()
        }
    }

    @Test
    fun `rebased baseline survives process restart before conditional save`() = runBlocking {
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val transport = HttpTransport { request ->
            requests += request
            when {
                isDayRead(request) -> HttpResponse(200, row(7, "server copy"))
                isConditionalSave(request) -> savedResponse(8)
                else -> HttpResponse(200, "{}")
            }
        }
        val fixture = recoveredFixture(transport)
        val visible = mutableStateOf(true)
        val deps = mutableStateOf(CheckInDeps(entries = fixture.repository, drafts = fixture.drafts))
        var restartedScope: CoroutineScope? = null
        try {
            compose.setContent {
                DiariumTheme {
                    if (visible.value) {
                        CheckInScreen(
                            deps = deps.value,
                            today = java.time.LocalDate.parse(DATE),
                            lateNight = false,
                        )
                    }
                }
            }
            waitForRecoveryPanel()
            reviewAndRebase()
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithTag("save_button").fetchSemanticsNodes().isNotEmpty()
            }
            val rebased = fixture.drafts.loadRecord(DATE)
            assertEquals(true, rebased?.baselineExists)
            assertEquals(ROW_ID, rebased?.baselineId)
            assertEquals(7L, rebased?.baselineRevision)
            assertEquals(RECOVERY_ID, rebased?.legacyRecoveryOrigin)

            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            fixture.closeStoreScope()
            val reopenedScope = CoroutineScope(Dispatchers.IO + Job())
            restartedScope = reopenedScope
            val restartedDataStore = PreferenceDataStoreFactory.create(
                scope = reopenedScope,
                produceFile = { fixture.file },
            )
            val restartedDrafts = DraftStore(restartedDataStore, OWNER_ID)
            val restarted = restartedDrafts.loadRecord(DATE)
            assertEquals(true, restarted?.baselineExists)
            assertEquals(ROW_ID, restarted?.baselineId)
            assertEquals(7L, restarted?.baselineRevision)
            assertEquals(RECOVERY_ID, restarted?.legacyRecoveryOrigin)

            compose.runOnIdle {
                deps.value = CheckInDeps(entries = fixture.repository, drafts = restartedDrafts)
                visible.value = true
            }
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithTag("save_button").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("save_button").performScrollTo().performClick()
            compose.waitUntil(TIMEOUT) { requests.any(::isConditionalSave) }
            val body = JSONObject(requests.single(::isConditionalSave).body!!)
            assertEquals(true, body.getBoolean("p_expected_exists"))
            assertEquals(ROW_ID, body.getString("p_expected_id"))
            assertEquals(7L, body.getLong("p_expected_revision"))
            assertEquals(fixture.sourceRaw, restartedDataStore.data.first()[stringPreferencesKey(DraftStore.legacyKey(DATE))])
            reopenedScope.coroutineContext[Job]?.cancelAndJoin()
        } finally {
            restartedScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            fixture.close()
        }
        Unit
    }

    private fun show(fixture: Fixture, includeLiveSession: Boolean = false) {
        compose.setContent {
            DiariumTheme {
                CheckInScreen(
                    deps = CheckInDeps(
                        entries = fixture.repository,
                        drafts = fixture.drafts,
                        sessionStore = fixture.session.takeIf { includeLiveSession },
                    ),
                    today = java.time.LocalDate.parse(DATE),
                    lateNight = false,
                )
            }
        }
    }

    private fun waitForRecoveryPanel() {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodesWithText("Serverová verze se od zahájení úpravy změnila")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun reviewAndRebase(start: Boolean = true) {
        compose.onNodeWithTag("checkin_review_local_draft").performClick()
        compose.onNodeWithTag("checkin_rebase_confirmation").performClick()
        if (start) {
            compose.onNodeWithTag("checkin_rebase_recovered_draft").assertIsEnabled().performClick()
        } else {
            compose.onNodeWithTag("checkin_rebase_recovered_draft").assertIsEnabled()
        }
    }

    private suspend fun recoveredFixture(
        transport: HttpTransport,
        session: SessionStore = sessionStore(OWNER_ID),
    ): Fixture {
        val file = File.createTempFile("diarium_recovered_draft", ".preferences_pb").also { it.delete() }
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val entry = DiaryEntry(mood = 4, note = "synthetic recovered draft")
        val sourceRaw = entry.toJson().toString()
        dataStore.edit { it[stringPreferencesKey(DraftStore.legacyKey(DATE))] = sourceRaw }
        val drafts = DraftStore(dataStore, OWNER_ID)
        assertEquals(
            LegacyRecoveryCopyResult.COPIED,
            drafts.importLegacyRecoveryIfAbsent(DATE, entry, RECOVERY_ID, sourceRaw),
        )
        val repository = EntriesRepository(
            client = SupabaseClient(
                sessionStore = session,
                transport = transport,
                baseUrl = BASE_URL,
                anonKey = "synthetic-anon-key",
            ),
            session = SessionContext(session),
            expectedOwnerId = OWNER_ID,
        )
        return Fixture(file, scope, dataStore, drafts, repository, session, sourceRaw)
    }

    private class Fixture(
        val file: File,
        private val scope: CoroutineScope,
        val dataStore: DataStore<Preferences>,
        val drafts: DraftStore,
        val repository: EntriesRepository,
        val session: SessionStore,
        val sourceRaw: String,
    ) {
        suspend fun readLegacySource(): String? =
            dataStore.data.first()[stringPreferencesKey(DraftStore.legacyKey(DATE))]

        suspend fun closeStoreScope() {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }

        suspend fun close() {
            scope.coroutineContext[Job]?.cancelAndJoin()
            file.delete()
        }
    }

    private fun sessionStore(owner: String): SessionStore = SessionStore(context).apply {
        save(sessionJson(owner))
    }

    private fun sessionJson(owner: String): JSONObject = JSONObject().apply {
        put("access_token", "synthetic-access-token")
        put("refresh_token", "synthetic-refresh-token")
        put("expires_at", System.currentTimeMillis() / 1000 + 3600)
        put("user", JSONObject().put("id", owner))
    }

    private fun isDayRead(request: HttpRequest): Boolean =
        request.method == "GET" && request.url.contains("/entries?")

    private fun isConditionalSave(request: HttpRequest): Boolean =
        request.method == "POST" && request.url.endsWith("/rpc/save_daily_entry_v2")

    private fun row(revision: Long, note: String): String = JSONArray()
        .put(
            JSONObject()
                .put("user_id", OWNER_ID)
                .put("id", ROW_ID)
                .put("date", DATE)
                .put("revision", revision)
                .put("mood", 2)
                .put("mood_emoji", "😐")
                .put("note", note)
                .put("updated_at", "2026-10-01T10:00:00Z"),
        )
        .toString()

    private fun savedResponse(revision: Long): HttpResponse = HttpResponse(
        200,
        JSONObject()
            .put("status", "saved")
            .put("entry_id", ROW_ID)
            .put("revision", revision)
            .put("replayed", false)
            .toString(),
    )

    private companion object {
        const val DATE = "2026-10-01"
        const val OWNER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val OTHER_OWNER_ID = "7f3d2c8a-1e4b-5a6f-9c20-3d5e7f9a1b24"
        const val ROW_ID = "ecaf917a-7c84-4ad5-b657-cc5d9af10a36"
        const val BASE_URL = "https://example.supabase.co/rest/v1"
        val RECOVERY_ID = LegacyRecoveryRecordId(LegacyRecoveryType.DRAFT, DATE)
        const val TIMEOUT = 10_000L
    }
}
