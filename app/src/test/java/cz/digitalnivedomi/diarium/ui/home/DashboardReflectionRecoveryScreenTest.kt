package cz.digitalnivedomi.diarium.ui.home

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.AiReflectionRepository
import cz.digitalnivedomi.diarium.core.data.AiReflectionSaveCoordinator
import cz.digitalnivedomi.diarium.core.data.DashboardRepository
import cz.digitalnivedomi.diarium.core.data.DraftStore
import cz.digitalnivedomi.diarium.core.data.EntriesRepository
import cz.digitalnivedomi.diarium.core.data.HttpRequest
import cz.digitalnivedomi.diarium.core.data.HttpResponse
import cz.digitalnivedomi.diarium.core.data.HttpTransport
import cz.digitalnivedomi.diarium.core.data.PendingSaveStore
import cz.digitalnivedomi.diarium.core.data.ProfileStore
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.SupabaseClient
import cz.digitalnivedomi.diarium.core.i18n.AppLanguage
import cz.digitalnivedomi.diarium.core.i18n.Lang
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class DashboardReflectionRecoveryScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun restoreLanguage() {
        AppLanguage.set(context, Lang.CS)
    }

    @Test
    fun `generated reflection staging can be retried before any remote write`() = runBlocking {
        AppLanguage.set(context, Lang.CS)
        val fixture = fixture(failFirstDraftWrite = true)
        try {
            compose.setContent {
                DiariumTheme {
                    DashboardScreen(
                        deps = fixture.deps,
                        holder = DashboardStateHolder(),
                        today = LocalDate.parse(DATE),
                    )
                }
            }
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText("Vygenerovat reflexi", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Vygenerovat reflexi", substring = true)
                .performScrollTo().performClick()

            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithText(GENERATED_TEXT).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(GENERATED_TEXT).assertExists()
            assertEquals(1, fixture.draftWrites.get())
            assertNull(fixture.drafts.loadRecord(DATE))
            assertEquals("no RPC may run before local staging succeeds", 0, fixture.saveCalls.get())

            compose.onNodeWithText("není uložená v telefonu", substring = true).assertExists()
            compose.onNodeWithTag("dashboard_reflection_stage_retry").assertExists()
            compose.onNodeWithTag("dashboard_reflection_stage_retry").performScrollTo().performClick()
            compose.waitUntil(TIMEOUT) { fixture.saveCalls.get() == 1 }
            compose.waitUntil(TIMEOUT) {
                compose.onAllNodesWithTag("dashboard_reflection_stage_retry").fetchSemanticsNodes().isEmpty()
            }

            assertTrue("the CAS request must be dispatched only after the durable draft exists", fixture.draftReadyAtDispatch.get())
            assertTrue("the failed write was retried", fixture.draftWrites.get() >= 2)
            assertNull("a confirmed server save clears its matching draft", fixture.drafts.loadRecord(DATE))
        } finally {
            fixture.close()
        }
    }

    private fun fixture(failFirstDraftWrite: Boolean): Fixture {
        val session = SessionStore(context).apply {
            clear()
            save(
                JSONObject().apply {
                    put("access_token", "synthetic-access-token")
                    put("refresh_token", "synthetic-refresh-token")
                    put("expires_at", System.currentTimeMillis() / 1000 + 3600)
                    put("user", JSONObject().put("id", OWNER_ID))
                },
            )
        }
        val draftFile = tempFile("reflection_draft")
        val draftScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val draftBacking = PreferenceDataStoreFactory.create(scope = draftScope, produceFile = { draftFile })
        val draftWrites = AtomicInteger()
        val draftStore = DraftStore(
            if (failFirstDraftWrite) FailFirstWriteDataStore(draftBacking, draftWrites) else draftBacking,
            OWNER_ID,
        )
        val profileFile = tempFile("reflection_profile")
        val profileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val profileDataStore = PreferenceDataStoreFactory.create(scope = profileScope, produceFile = { profileFile })
        val profile = ProfileStore.forDataStore(profileDataStore, OWNER_ID)
        runBlocking { profile.setAiEnabled(true) }

        val pending = PendingSaveStore.from(context, OWNER_ID)
        pending.remove(DATE)
        val requests = CopyOnWriteArrayList<HttpRequest>()
        val saveCalls = AtomicInteger()
        val draftReadyAtDispatch = AtomicBoolean(false)
        val transport = HttpTransport { request ->
            requests += request
            when {
                request.method == "GET" && request.url.contains("/entries?") ->
                    HttpResponse(200, entryRows())
                request.url.endsWith("/api/ai/reflect") ->
                    HttpResponse(200, JSONObject().put("reflection", GENERATED_TEXT).toString())
                request.url.endsWith("/rpc/save_daily_entry_v2") -> {
                    saveCalls.incrementAndGet()
                    draftReadyAtDispatch.set(
                        runBlocking { draftStore.loadRecord(DATE)?.pendingAiReflection == GENERATED_TEXT },
                    )
                    HttpResponse(
                        200,
                        JSONObject()
                            .put("status", "saved")
                            .put("entry_id", ROW_ID)
                            .put("revision", 8)
                            .put("replayed", false)
                            .toString(),
                    )
                }
                else -> HttpResponse(200, "[]")
            }
        }
        val client = SupabaseClient(
            sessionStore = session,
            transport = transport,
            baseUrl = BASE_URL,
            anonKey = "synthetic-anon-key",
        )
        val entries = EntriesRepository(
            client = client,
            session = SessionContext(session),
            pending = pending,
            expectedOwnerId = OWNER_ID,
        )
        val deps = DashboardDeps(
            dashboard = DashboardRepository(entries),
            reflection = AiReflectionRepository(
                sessionStore = session,
                transport = transport,
                endpoint = AI_URL,
                expectedOwnerId = OWNER_ID,
            ),
            profile = profile,
            reflectionEntries = entries,
            reflectionDrafts = draftStore,
            reflectionSaver = AiReflectionSaveCoordinator(entries, draftStore),
            sessionStore = session,
            sessionOwnerId = OWNER_ID,
        )
        return Fixture(
            deps = deps,
            drafts = draftStore,
            draftWrites = draftWrites,
            saveCalls = saveCalls,
            draftReadyAtDispatch = draftReadyAtDispatch,
            session = session,
            pending = pending,
            draftScope = draftScope,
            profileScope = profileScope,
            files = listOf(draftFile, profileFile),
        )
    }

    private fun tempFile(prefix: String): File =
        File.createTempFile(prefix, ".preferences_pb", context.cacheDir).also { it.delete() }

    private fun entryRows(): String = """[{"user_id":"$OWNER_ID","id":"$ROW_ID","date":"$DATE","revision":7,"mood":4,"mood_emoji":"🙂","note":"Synthetic test entry","created_at":"2026-11-10T12:00:00Z","updated_at":"2026-11-10T12:00:00Z"}]"""

    private class FailFirstWriteDataStore(
        private val delegate: DataStore<Preferences>,
        private val writeCount: AtomicInteger,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> get() = delegate.data

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (writeCount.incrementAndGet() == 1) throw IOException("synthetic disk full")
            return delegate.updateData(transform)
        }
    }

    private data class Fixture(
        val deps: DashboardDeps,
        val drafts: DraftStore,
        val draftWrites: AtomicInteger,
        val saveCalls: AtomicInteger,
        val draftReadyAtDispatch: AtomicBoolean,
        val session: SessionStore,
        val pending: PendingSaveStore,
        val draftScope: CoroutineScope,
        val profileScope: CoroutineScope,
        val files: List<File>,
    ) {
        suspend fun close() {
            pending.remove(DATE)
            session.clear()
            draftScope.coroutineContext[Job]?.cancelAndJoin()
            profileScope.coroutineContext[Job]?.cancelAndJoin()
            files.forEach { it.delete() }
        }
    }

    private companion object {
        const val DATE = "2026-11-10"
        const val OWNER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val ROW_ID = "ecaf917a-7c84-4ad5-b657-cc5d9af10a36"
        const val BASE_URL = "https://example.supabase.co/rest/v1"
        const val AI_URL = "https://example.test/api/ai/reflect"
        const val GENERATED_TEXT = "Synthetic reflection remains visible after the disk write fails."
        const val TIMEOUT = 10_000L
    }
}
