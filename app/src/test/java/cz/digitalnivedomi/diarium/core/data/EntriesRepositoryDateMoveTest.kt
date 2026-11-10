package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.URLDecoder

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EntriesRepositoryDateMoveTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sent = mutableListOf<HttpRequest>()
    private lateinit var sessionStore: SessionStore
    private val sourceRow = JSONObject()
        .put("id", SOURCE_ID)
        .put("user_id", OWNER_ID)
        .put("date", SOURCE_DATE)
        .put("revision", 7)
        .put("updated_at", "2026-09-10T12:00:00Z")
        .put("mood", 4)
        .put("note", "synthetic source")
        .put("photo_path", "https://example.supabase.co/storage/v1/object/public/diary-photos/$OWNER_ID/source.jpg")
        .put("scale_values", JSONObject().put("focus", 4))

    private val transport = HttpTransport { request ->
        sent += request
        when {
            request.method == "GET" && request.url.contains("/entries?") -> {
                val query = URLDecoder.decode(request.url.substringAfter('?'), Charsets.UTF_8.name())
                val date = query.split('&').first { it.startsWith("date=") }.substringAfter("date=eq.")
                if (date == SOURCE_DATE) HttpResponse(200, JSONArray().put(sourceRow).toString())
                else HttpResponse(200, "[]")
            }
            request.url.endsWith("/rpc/move_daily_entry_v1") -> {
                val moved = JSONObject(sourceRow.toString())
                    .put("date", TARGET_DATE)
                    .put("revision", 8)
                HttpResponse(
                    200,
                    JSONObject()
                        .put("status", "moved")
                        .put("source_date", SOURCE_DATE)
                        .put("target_date", TARGET_DATE)
                        .put("entry_id", SOURCE_ID)
                        .put("revision", 8)
                        .put("overwrote", false)
                        .put("scale_rows_moved", 1)
                        .put("entry", moved)
                        .put("replayed", false)
                        .toString(),
                )
            }
            else -> HttpResponse(500, "unexpected synthetic request")
        }
    }

    @Before
    fun setUp() {
        sessionStore = SessionStore(context).apply {
            save(
                JSONObject()
                    .put("access_token", "synthetic-jwt")
                    .put("refresh_token", "synthetic-refresh")
                    .put("expires_at", System.currentTimeMillis() / 1000 + 3600)
                    .put("user", JSONObject().put("id", OWNER_ID)),
            )
        }
    }

    @Test
    fun `moving into an absent day uses the atomic RPC with exact source and target tokens`() = runTest {
        val result = repository().move(EntriesRepository.fromRow(sourceRow), SOURCE_DATE, TARGET_DATE)

        assertTrue("the tested atomic move should succeed for a token-matched absent target", result.isSuccess)
        val rpc = sent.single { it.url.endsWith("/rpc/move_daily_entry_v1") }
        val body = JSONObject(rpc.body!!)
        assertEquals(SOURCE_DATE, body.getString("p_source_date"))
        assertEquals(SOURCE_ID, body.getString("p_source_id"))
        assertEquals(7L, body.getLong("p_source_revision"))
        assertEquals(TARGET_DATE, body.getString("p_target_date"))
        assertFalse(body.getBoolean("p_target_expected_exists"))
        assertTrue(body.isNull("p_target_expected_id"))
        assertTrue(body.isNull("p_target_expected_revision"))
        assertFalse(body.getBoolean("p_confirm_overwrite"))
        assertTrue("date moves must not fall back to separate save/delete writes", sent.none {
            it.method == "PATCH" || it.method == "DELETE" ||
                (it.method == "POST" && !it.url.endsWith("/rpc/move_daily_entry_v1"))
        })
    }

    private fun repository() = EntriesRepository(
        client = SupabaseClient(
            sessionStore = sessionStore,
            transport = transport,
            baseUrl = BASE_URL,
            anonKey = "synthetic-anon-key",
        ),
        session = SessionContext(sessionStore),
        expectedOwnerId = OWNER_ID,
    )

    private companion object {
        const val BASE_URL = "https://example.supabase.co/rest/v1"
        const val OWNER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val SOURCE_ID = "ecaf917a-7c84-4ad5-b657-cc5d9af10a36"
        const val SOURCE_DATE = "2026-09-10"
        const val TARGET_DATE = "2026-09-11"
    }
}
