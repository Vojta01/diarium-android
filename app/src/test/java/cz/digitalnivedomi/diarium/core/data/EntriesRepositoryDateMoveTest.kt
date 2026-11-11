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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EntriesRepositoryDateMoveTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val moveFile = File(context.cacheDir, "entry-move-${UUID.randomUUID()}.json")
    private val sent = mutableListOf<HttpRequest>()
    private val serverRows = linkedMapOf<String, JSONObject>()
    private val receiptBodies = mutableMapOf<String, String>()
    private val receipts = mutableMapOf<String, HttpResponse>()
    private lateinit var sessionStore: SessionStore
    private var targetReadFailureDate: String? = null
    private var timeoutFirstMoveResponse = false
    private var switchAccountOnNextRpc = false
    private var moveResponseOverride: ((JSONObject) -> HttpResponse)? = null
    private var serverRpcCalls = 0
    private var serverMutations = 0
    private var moveRouteVisible = true
    private var capabilityRouteVisible = true
    private var capabilityEnabled = true
    private var capabilityResponseOverride: HttpResponse? = null
    private var throwCapabilityRead = false

    private val sourceRow = JSONObject()
        .put("id", SOURCE_ID)
        .put("user_id", OWNER_ID)
        .put("date", SOURCE_DATE)
        .put("revision", 7)
        .put("updated_at", "2026-09-10T12:00:00Z")
        .put("created_at", "2026-09-10T11:00:00Z")
        .put("mood", 4)
        .put("mood_emoji", "🙂")
        .put("sleep_quality", 4)
        .put("stress", 2)
        .put("activities", JSONArray().put("running"))
        .put("habits", JSONObject().put("reading", true))
        .put("gratitude", JSONArray().put("synthetic gratitude"))
        .put("weather", "sunny")
        .put("note", "synthetic source")
        .put("photo_path", "https://example.supabase.co/storage/v1/object/public/diary-photos/$OWNER_ID/source.jpg")
        .put("scale_values", JSONObject().put("focus", 4).put("calm", 2))
        .put("phone_screen_time", 1234)
        .put("phone_unlocks", 10)
        .put("phone_top_apps", JSONArray().put(JSONObject().put("app", "com.example.synthetic").put("minutes", 5)))
        .put("ai_reflection", "synthetic reflection")

    private val transport = HttpTransport { request ->
        sent += request
        when {
            request.method == "GET" && request.url.endsWith("/rest/v1/") -> openApiResponse()
            request.method == "GET" && request.url.endsWith("/rpc/android_move_capability_v1") -> {
                if (throwCapabilityRead) throw IOException("synthetic offline capability read")
                capabilityResponseOverride ?: HttpResponse(
                    200,
                    JSONObject()
                        .put("contract", "diarium.android.move-capability.v1")
                        .put("move_daily_entry_v1", capabilityEnabled)
                        .toString(),
                )
            }
            request.method == "GET" && request.url.contains("/entries?") -> {
                val query = URLDecoder.decode(request.url.substringAfter('?'), Charsets.UTF_8.name())
                val date = query.split('&').first { it.startsWith("date=") }.substringAfter("date=eq.")
                if (date == targetReadFailureDate) {
                    HttpResponse(503, "{\"message\":\"synthetic target read failure\"}")
                } else {
                    val row = serverRows[date]
                    HttpResponse(200, if (row == null) "[]" else JSONArray().put(JSONObject(row.toString())).toString())
                }
            }
            request.url.endsWith("/rpc/move_daily_entry_v1") -> handleMoveRequest(request)
            else -> HttpResponse(500, "unexpected synthetic request")
        }
    }

    @Before
    fun setUp() {
        moveFile.delete()
        sent.clear()
        serverRows.clear()
        serverRows[SOURCE_DATE] = JSONObject(sourceRow.toString())
        receiptBodies.clear()
        receipts.clear()
        targetReadFailureDate = null
        timeoutFirstMoveResponse = false
        switchAccountOnNextRpc = false
        moveResponseOverride = null
        serverRpcCalls = 0
        serverMutations = 0
        moveRouteVisible = true
        capabilityRouteVisible = true
        capabilityEnabled = true
        capabilityResponseOverride = null
        throwCapabilityRead = false
        sessionStore = SessionStore(context)
        saveSession(OWNER_ID)
    }

    @Test
    fun `absent destination uses exact tokens and updates cached photo and scale data`() = runTest {
        val cache = cacheStore("absent")
        cache.upsertRows(listOf(sourceRow, targetRow(TARGET_DATE, TARGET_ID, "stale cache target", PHOTO_TARGET)))

        val result = repository(cache = cache).move(EntriesRepository.fromRow(sourceRow), SOURCE_DATE, TARGET_DATE)

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
        assertTrue(serverRows[SOURCE_DATE] == null)
        assertEquals(SOURCE_ID, serverRows[TARGET_DATE]?.getString("id"))
        assertEquals(PHOTO_SOURCE, result.getOrThrow().entry.entry.photoPath)
        assertEquals(mapOf("focus" to 4, "calm" to 2), result.getOrThrow().entry.entry.scaleValues)
        assertEquals(listOf(TARGET_DATE), cache.readRange(SOURCE_DATE, TARGET_DATE)?.map { it.date })
        val cached = cache.readRange(SOURCE_DATE, TARGET_DATE)!!.single().entry
        assertEquals(PHOTO_SOURCE, cached.photoPath)
        assertEquals(mapOf("focus" to 4, "calm" to 2), cached.scaleValues)
        assertNoBlindWriteOrStorageDelete()
    }

    @Test
    fun `same date is a token checked no-op`() = runTest {
        val result = repository(withMoveOutbox = true).move(
            EntriesRepository.fromRow(sourceRow), SOURCE_DATE, SOURCE_DATE,
        )

        assertTrue(result.isSuccess)
        assertEquals(EntryMoveStatus.UNCHANGED, result.getOrThrow().status)
        assertEquals(1, sent.count { it.method == "GET" && it.url.contains("/entries?") })
        val body = JSONObject(sent.single { it.url.endsWith("/rpc/move_daily_entry_v1") }.body!!)
        assertTrue(body.getBoolean("p_target_expected_exists"))
        assertEquals(SOURCE_ID, body.getString("p_target_expected_id"))
        assertEquals(7L, body.getLong("p_target_expected_revision"))
        assertFalse(body.getBoolean("p_confirm_overwrite"))
        assertEquals(SOURCE_ID, serverRows[SOURCE_DATE]?.getString("id"))
        assertNull(serverRows[TARGET_DATE])
        assertNoBlindWriteOrStorageDelete()
    }

    @Test
    fun `occupied destination cannot be sent until explicit confirmation and photo candidate never deletes storage`() = runTest {
        serverRows[TARGET_DATE] = targetRow(TARGET_DATE, TARGET_ID, "target local copy", PHOTO_TARGET)
        val cache = cacheStore("occupied")
        cache.upsertRows(listOf(sourceRow, serverRows.getValue(TARGET_DATE)))
        val repo = repository(withMoveOutbox = true, cache = cache)
        val candidate = repo.prepareMove(SOURCE_DATE, TARGET_DATE, SOURCE_ID, 7L).getOrThrow()

        val unconfirmed = repo.move(candidate, confirmOverwrite = false)
        assertTrue(unconfirmed.exceptionOrNull() is EntryMoveConfirmationRequiredException)
        assertEquals(0, sent.count { it.url.endsWith("/rpc/move_daily_entry_v1") })
        assertEquals("synthetic source", serverRows[SOURCE_DATE]?.getString("note"))
        assertEquals("target local copy", serverRows[TARGET_DATE]?.getString("note"))

        val confirmed = repo.move(candidate, confirmOverwrite = true)
        assertTrue(confirmed.isSuccess)
        val rpc = sent.single { it.url.endsWith("/rpc/move_daily_entry_v1") }
        val body = JSONObject(rpc.body!!)
        assertTrue(body.getBoolean("p_target_expected_exists"))
        assertEquals(TARGET_ID, body.getString("p_target_expected_id"))
        assertEquals(3L, body.getLong("p_target_expected_revision"))
        assertTrue(body.getBoolean("p_confirm_overwrite"))
        assertEquals(SOURCE_ID, serverRows[TARGET_DATE]?.getString("id"))
        assertNull(serverRows[SOURCE_DATE])
        val cached = cache.readRange(SOURCE_DATE, TARGET_DATE)!!.single().entry
        assertEquals(PHOTO_SOURCE, cached.photoPath)
        assertEquals(mapOf("focus" to 4, "calm" to 2), cached.scaleValues)
        assertEquals(EntryMoveStatus.MOVED, confirmed.getOrThrow().status)
        assertNoBlindWriteOrStorageDelete()
    }

    @Test
    fun `stale source token conflicts without changing source or creating target`() = runTest {
        val repo = repository(withMoveOutbox = true)
        val candidate = repo.prepareMove(SOURCE_DATE, TARGET_DATE, SOURCE_ID, 7L).getOrThrow()
        serverRows[SOURCE_DATE]!!.put("revision", 8).put("note", "newer source")

        val result = repo.move(candidate, confirmOverwrite = false)

        assertTrue(result.exceptionOrNull() is EntryMoveConflictException)
        assertEquals("newer source", serverRows[SOURCE_DATE]?.getString("note"))
        assertNull(serverRows[TARGET_DATE])
        assertEquals(1, sent.count { it.url.endsWith("/rpc/move_daily_entry_v1") })
        assertNoBlindWriteOrStorageDelete()
    }

    @Test
    fun `stale target token conflicts even after overwrite confirmation`() = runTest {
        serverRows[TARGET_DATE] = targetRow(TARGET_DATE, TARGET_ID, "target before concurrent update", PHOTO_TARGET)
        val repo = repository(withMoveOutbox = true)
        val candidate = repo.prepareMove(SOURCE_DATE, TARGET_DATE, SOURCE_ID, 7L).getOrThrow()
        serverRows[TARGET_DATE]!!.put("revision", 4).put("note", "newer target")

        val result = repo.move(candidate, confirmOverwrite = true)

        assertTrue(result.exceptionOrNull() is EntryMoveConflictException)
        assertEquals("synthetic source", serverRows[SOURCE_DATE]?.getString("note"))
        assertEquals("newer target", serverRows[TARGET_DATE]?.getString("note"))
        assertNoBlindWriteOrStorageDelete()
    }

    @Test
    fun `new target created after preflight conflicts and preserves both rows`() = runTest {
        val repo = repository(withMoveOutbox = true)
        val candidate = repo.prepareMove(SOURCE_DATE, TARGET_DATE, SOURCE_ID, 7L).getOrThrow()
        serverRows[TARGET_DATE] = targetRow(TARGET_DATE, TARGET_ID, "concurrent destination", PHOTO_TARGET)

        val result = repo.move(candidate, confirmOverwrite = false)

        assertTrue(result.exceptionOrNull() is EntryMoveConflictException)
        assertEquals("synthetic source", serverRows[SOURCE_DATE]?.getString("note"))
        assertEquals("concurrent destination", serverRows[TARGET_DATE]?.getString("note"))
        assertNull(EntryMoveStore(moveFile, OWNER_ID).pendingForDate(SOURCE_DATE))
        assertNoBlindWriteOrStorageDelete()
    }

    @Test
    fun `target read failure is not treated as an absent destination`() = runTest {
        targetReadFailureDate = TARGET_DATE
        val repo = repository(withMoveOutbox = true)

        val result = repo.prepareMove(SOURCE_DATE, TARGET_DATE, SOURCE_ID, 7L)

        assertTrue(result.isFailure)
        assertEquals(0, sent.count { it.url.endsWith("/rpc/move_daily_entry_v1") })
        assertNull(EntryMoveStore(moveFile, OWNER_ID).pendingForDate(SOURCE_DATE))
        assertEquals(SOURCE_ID, serverRows[SOURCE_DATE]?.getString("id"))
        assertNoBlindWriteOrStorageDelete()
    }

    @Test
    fun `timeout then process restart retries same durable payload exactly once`() = runTest {
        val cache = cacheStore("timeout")
        cache.upsertRows(listOf(sourceRow))
        timeoutFirstMoveResponse = true
        val firstRepo = repository(cache = cache)
        val first = firstRepo.move(EntriesRepository.fromRow(sourceRow), SOURCE_DATE, TARGET_DATE)

        assertTrue(first.exceptionOrNull() is EntryMoveUnknownException)
        val staged = EntryMoveStore(moveFile, OWNER_ID).pendingForDate(SOURCE_DATE)
        assertNotNull(staged)
        assertEquals(PHOTO_SOURCE, staged!!.sourceSnapshot.entry.photoPath)
        assertEquals(mapOf("focus" to 4, "calm" to 2), staged.sourceSnapshot.entry.scaleValues)
        assertEquals(1, serverMutations)
        assertEquals(SOURCE_ID, serverRows[TARGET_DATE]?.getString("id"))

        // A new repository and store object model a fresh process reading the durable outbox.
        val restartedRepo = repository(cache = cache)
        val retried = restartedRepo.retryPendingMove(staged.requestId)

        assertTrue(retried.isSuccess)
        assertTrue(retried.getOrThrow().replayed)
        assertEquals(1, serverMutations)
        assertEquals(2, sent.count { it.url.endsWith("/rpc/move_daily_entry_v1") })
        val rpcBodies = sent.filter { it.url.endsWith("/rpc/move_daily_entry_v1") }.map { it.body }
        assertEquals(rpcBodies[0], rpcBodies[1])
        assertNull(EntryMoveStore(moveFile, OWNER_ID).pendingForDate(SOURCE_DATE))
        assertEquals(listOf(TARGET_DATE), cache.readRange(SOURCE_DATE, TARGET_DATE)?.map { it.date })
        val cached = cache.readRange(SOURCE_DATE, TARGET_DATE)!!.single().entry
        assertEquals(PHOTO_SOURCE, cached.photoPath)
        assertEquals(mapOf("focus" to 4, "calm" to 2), cached.scaleValues)
        assertNoBlindWriteOrStorageDelete()
    }

    @Test
    fun `session change during ambiguous move keeps old owner outbox isolated`() = runTest {
        val cache = cacheStore("account-change")
        cache.upsertRows(listOf(sourceRow))
        switchAccountOnNextRpc = true
        val repo = repository(withMoveOutbox = true, cache = cache)

        val result = repo.move(EntriesRepository.fromRow(sourceRow), SOURCE_DATE, TARGET_DATE)

        assertTrue(result.exceptionOrNull() is SessionOwnerMismatchException)
        val oldOwnerRequest = EntryMoveStore(moveFile, OWNER_ID).pendingForDate(SOURCE_DATE)
        assertNotNull(oldOwnerRequest)
        val otherOwnerFile = File(context.cacheDir, EntryMoveStore.fileName(OTHER_ID))
        assertNull(EntryMoveStore(otherOwnerFile, OTHER_ID).pendingForDate(SOURCE_DATE))
        assertEquals(1, serverMutations)
        assertTrue(cache.readRange(SOURCE_DATE, TARGET_DATE)!!.any { it.date == SOURCE_DATE })

        val otherAccountRepo = repository(
            expectedOwnerId = OTHER_ID,
            outbox = EntryMoveStore(otherOwnerFile, OTHER_ID),
        )
        val crossAccountRetry = otherAccountRepo.retryPendingMove(oldOwnerRequest!!.requestId)
        assertTrue(crossAccountRetry.isFailure)
        assertEquals(1, sent.count { it.url.endsWith("/rpc/move_daily_entry_v1") })

        // Returning to the original account permits only its own idempotent receipt replay.
        saveSession(OWNER_ID)
        val recovered = repository(withMoveOutbox = true, cache = cache).retryPendingMove(oldOwnerRequest.requestId)
        assertTrue(recovered.isSuccess)
        assertTrue(recovered.getOrThrow().replayed)
        assertNull(EntryMoveStore(moveFile, OWNER_ID).pendingForDate(SOURCE_DATE))
        assertNoBlindWriteOrStorageDelete()
    }

    @Test
    fun `stale advertised rpc returning 404 is retained and not retried after capability revocation`() = runTest {
        val cache = cacheStore("missing-rpc")
        cache.upsertRows(listOf(sourceRow))
        moveResponseOverride = { HttpResponse(404, "{\"message\":\"function not found\"}") }
        val repo = repository(cache = cache)

        val result = repo.move(EntriesRepository.fromRow(sourceRow), SOURCE_DATE, TARGET_DATE)

        assertTrue(result.exceptionOrNull() is EntryMoveUnavailableException)
        val pending = EntryMoveStore(moveFile, OWNER_ID).pendingForDate(SOURCE_DATE)
        assertNotNull(pending)
        assertEquals(SOURCE_ID, pending!!.sourceSnapshot.id)
        assertEquals(PHOTO_SOURCE, pending.sourceSnapshot.entry.photoPath)
        assertEquals(mapOf("focus" to 4, "calm" to 2), pending.sourceSnapshot.entry.scaleValues)
        assertNull(pending.targetSnapshot)
        assertEquals(SOURCE_ID, serverRows[SOURCE_DATE]?.getString("id"))
        assertNull(serverRows[TARGET_DATE])
        assertEquals(listOf(SOURCE_DATE), cache.readRange(SOURCE_DATE, TARGET_DATE)?.map { it.date })
        val rpcCountAfter404 = sent.count { it.url.endsWith("/rpc/move_daily_entry_v1") }

        // A request that reached an endpoint but did not yield a trustworthy receipt is
        // not deleted. Once the server revokes the capability, retry is refused locally.
        capabilityEnabled = false
        val retry = repo.retryPendingMove(pending.requestId)

        assertTrue(retry.exceptionOrNull() is EntryMoveUnavailableException)
        assertEquals(rpcCountAfter404, sent.count { it.url.endsWith("/rpc/move_daily_entry_v1") })
        assertNotNull(EntryMoveStore(moveFile, OWNER_ID).pendingForDate(SOURCE_DATE))
        assertNoBlindWriteOrStorageDelete()
    }

    @Test
    fun `missing rpc is rejected before any request is staged`() = runTest {
        moveRouteVisible = false
        val repo = repository()
        val candidate = repo.prepareMove(SOURCE_DATE, TARGET_DATE, SOURCE_ID, 7L).getOrThrow()

        val result = repo.move(candidate, confirmOverwrite = false)

        assertTrue(result.exceptionOrNull() is EntryMoveUnavailableException)
        assertNull(EntryMoveStore(moveFile, OWNER_ID).pendingForDate(SOURCE_DATE))
        assertEquals(0, sent.count { it.url.endsWith("/rpc/move_daily_entry_v1") })
        assertEquals(SOURCE_ID, serverRows[SOURCE_DATE]?.getString("id"))
        assertNull(serverRows[TARGET_DATE])
    }

    @Test
    fun `unreadable capability response fails closed before staging`() = runTest {
        capabilityResponseOverride = HttpResponse(503, "synthetic capability outage")
        val repo = repository()
        val candidate = repo.prepareMove(SOURCE_DATE, TARGET_DATE, SOURCE_ID, 7L).getOrThrow()

        val result = repo.move(candidate, confirmOverwrite = false)

        assertTrue(result.exceptionOrNull() is EntryMoveUnavailableException)
        assertNull(EntryMoveStore(moveFile, OWNER_ID).pendingForDate(SOURCE_DATE))
        assertEquals(0, sent.count { it.url.endsWith("/rpc/move_daily_entry_v1") })
    }

    @Test
    fun `capability false defeats a stale OpenAPI route advertisement`() = runTest {
        capabilityEnabled = false
        val repo = repository()
        val candidate = repo.prepareMove(SOURCE_DATE, TARGET_DATE, SOURCE_ID, 7L).getOrThrow()

        val result = repo.move(candidate, confirmOverwrite = false)

        assertTrue(result.exceptionOrNull() is EntryMoveUnavailableException)
        assertNull(EntryMoveStore(moveFile, OWNER_ID).pendingForDate(SOURCE_DATE))
        assertEquals(0, sent.count { it.url.endsWith("/rpc/move_daily_entry_v1") })
    }

    @Test
    fun `capability revoked before staging is rejected locally`() = runTest {
        capabilityEnabled = false
        val repo = repository()
        val candidate = repo.prepareMove(SOURCE_DATE, TARGET_DATE, SOURCE_ID, 7L).getOrThrow()

        val result = repo.move(candidate, confirmOverwrite = false)

        assertTrue(result.exceptionOrNull() is EntryMoveUnavailableException)
        assertNull(EntryMoveStore(moveFile, OWNER_ID).pendingForDate(SOURCE_DATE))
        assertEquals(0, sent.count { it.url.endsWith("/rpc/move_daily_entry_v1") })
    }

    @Test
    fun `offline capability lookup fails closed without staging`() = runTest {
        throwCapabilityRead = true
        val repo = repository()
        val candidate = repo.prepareMove(SOURCE_DATE, TARGET_DATE, SOURCE_ID, 7L).getOrThrow()

        val result = repo.move(candidate, confirmOverwrite = false)

        assertTrue(result.exceptionOrNull() is EntryMoveUnavailableException)
        assertNull(EntryMoveStore(moveFile, OWNER_ID).pendingForDate(SOURCE_DATE))
        assertEquals(0, sent.count { it.url.endsWith("/rpc/move_daily_entry_v1") })
    }

    @Test
    fun `pending unknown request remains retained and cannot retry offline`() = runTest {
        timeoutFirstMoveResponse = true
        val repo = repository()
        val first = repo.move(EntriesRepository.fromRow(sourceRow), SOURCE_DATE, TARGET_DATE)
        assertTrue(first.exceptionOrNull() is EntryMoveUnknownException)
        val pending = EntryMoveStore(moveFile, OWNER_ID).pendingForDate(SOURCE_DATE)
        assertNotNull(pending)
        val callsBeforeOfflineRetry = sent.count { it.url.endsWith("/rpc/move_daily_entry_v1") }
        throwCapabilityRead = true

        val retry = repo.retryPendingMove(pending!!.requestId)

        assertTrue(retry.exceptionOrNull() is EntryMoveUnavailableException)
        assertEquals(callsBeforeOfflineRetry, sent.count { it.url.endsWith("/rpc/move_daily_entry_v1") })
        assertEquals(pending.requestId, EntryMoveStore(moveFile, OWNER_ID).pendingForDate(SOURCE_DATE)?.requestId)
        assertEquals(SOURCE_ID, serverRows[TARGET_DATE]?.getString("id"))
        assertNoBlindWriteOrStorageDelete()
    }

    private fun handleMoveRequest(request: HttpRequest): HttpResponse {
        serverRpcCalls += 1
        val body = JSONObject(request.body ?: "{}")
        val requestId = body.getString("p_request_id")
        val payload = body.toString()
        val existingBody = receiptBodies[requestId]
        if (existingBody != null) {
            if (existingBody != payload) return HttpResponse(400, "{\"message\":\"request ID payload changed\"}")
            val original = receipts.getValue(requestId).asJsonObject()!!
            return HttpResponse(200, JSONObject(original.toString()).put("replayed", true).toString())
        }
        moveResponseOverride?.let { return it(body) }

        val response = executeMove(body)
        receiptBodies[requestId] = payload
        receipts[requestId] = response
        if (switchAccountOnNextRpc) {
            switchAccountOnNextRpc = false
            saveSession(OTHER_ID)
        }
        if (timeoutFirstMoveResponse) {
            timeoutFirstMoveResponse = false
            throw IOException("synthetic response lost after server commit")
        }
        return response
    }

    private fun executeMove(body: JSONObject): HttpResponse {
        val sourceDate = body.getString("p_source_date")
        val source = serverRows[sourceDate]
        if (source == null) return conflict("source_missing", sourceDate, body.getString("p_target_date"))
        if (source.getString("id") != body.getString("p_source_id") ||
            source.getLong("revision") != body.getLong("p_source_revision")
        ) return conflict("stale_source", sourceDate, body.getString("p_target_date"))

        val targetDate = body.getString("p_target_date")
        val expectsTarget = body.getBoolean("p_target_expected_exists")
        val expectedTargetId = body.optString("p_target_expected_id").takeIf { it.isNotBlank() && it != "null" }
        val expectedTargetRevision = if (body.isNull("p_target_expected_revision")) null else body.getLong("p_target_expected_revision")
        val confirmOverwrite = body.getBoolean("p_confirm_overwrite")
        val target = serverRows[targetDate]
        if (sourceDate == targetDate) {
            if (!expectsTarget || expectedTargetId != source.getString("id") ||
                expectedTargetRevision != source.getLong("revision") || confirmOverwrite
            ) return conflict("stale_target", sourceDate, targetDate)
            return successResponse("unchanged", sourceDate, targetDate, JSONObject(source.toString()), replayed = false)
        }
        if (expectsTarget && target == null) return conflict("target_missing", sourceDate, targetDate)
        if (!expectsTarget && target != null) return conflict("destination_exists", sourceDate, targetDate)
        if (expectsTarget && target != null &&
            (target.getString("id") != expectedTargetId || target.getLong("revision") != expectedTargetRevision)
        ) return conflict("stale_target", sourceDate, targetDate)
        if (target != null && !confirmOverwrite) {
            return HttpResponse(200, JSONObject()
                .put("status", "confirmation_required")
                .put("source_date", sourceDate)
                .put("target_date", targetDate)
                .put("replayed", false)
                .toString())
        }

        val moved = JSONObject(source.toString())
            .put("date", targetDate)
            .put("revision", source.getLong("revision") + 1)
            .put("updated_at", "2026-09-10T12:30:00Z")
        serverRows.remove(sourceDate)
        serverRows[targetDate] = moved
        serverMutations += 1
        val cleanup = target?.optString("photo_path")?.takeIf { it.isNotBlank() && it != source.optString("photo_path") }
        val response = successResponse("moved", sourceDate, targetDate, moved, replayed = false, overwrote = target != null)
        if (cleanup != null) {
            val json = response.asJsonObject()!!
            json.put("photo_cleanup_candidate", JSONObject()
                .put("actor_id", OWNER_ID)
                .put("photo_path", cleanup)
                .put("status", "pending_validation")
                .put("safe_to_delete", false)
                .put("required_checks", JSONArray().put("verify_storage_object_owner").put("verify_no_remaining_entry_references"))
                .put("uncertain_validation_action", "retain_object"))
            return HttpResponse(200, json.toString())
        }
        return response
    }

    private fun successResponse(
        status: String,
        sourceDate: String,
        targetDate: String,
        entry: JSONObject,
        replayed: Boolean,
        overwrote: Boolean? = null,
    ): HttpResponse {
        val envelope = JSONObject()
            .put("status", status)
            .put("source_date", sourceDate)
            .put("target_date", targetDate)
            .put("entry_id", entry.getString("id"))
            .put("revision", entry.getLong("revision"))
            .put("entry", entry)
            .put("replayed", replayed)
        if (status == "moved") envelope.put("scale_rows_moved", entry.optJSONObject("scale_values")?.length() ?: 0)
        if (overwrote != null) envelope.put("overwrote", overwrote)
        return HttpResponse(200, envelope.toString())
    }

    private fun conflict(reason: String, sourceDate: String, targetDate: String): HttpResponse = HttpResponse(
        200,
        JSONObject()
            .put("status", "conflict")
            .put("reason", reason)
            .put("source_date", sourceDate)
            .put("target_date", targetDate)
            .put("replayed", false)
            .toString(),
    )

    private fun targetRow(date: String, id: String, note: String, photoPath: String?) = JSONObject()
        .put("id", id)
        .put("user_id", OWNER_ID)
        .put("date", date)
        .put("revision", 3)
        .put("updated_at", "2026-09-11T12:00:00Z")
        .put("created_at", "2026-09-11T11:00:00Z")
        .put("mood", 2)
        .put("mood_emoji", "😐")
        .put("sleep_quality", 2)
        .put("stress", 4)
        .put("activities", JSONArray().put("reading"))
        .put("habits", JSONObject().put("reading", false))
        .put("gratitude", JSONArray())
        .put("weather", "cloudy")
        .put("note", note)
        .put("photo_path", photoPath?.let { it } ?: JSONObject.NULL)
        .put("scale_values", JSONObject().put("focus", 1))
        .put("phone_screen_time", 222)
        .put("phone_unlocks", 2)
        .put("phone_top_apps", JSONArray())
        .put("ai_reflection", "target reflection")

    private fun repository(
        withMoveOutbox: Boolean = true,
        cache: EntriesCacheStore? = null,
        expectedOwnerId: String = OWNER_ID,
        outbox: EntryMoveStore? = null,
    ) = EntriesRepository(
        client = SupabaseClient(
            sessionStore = sessionStore,
            transport = transport,
            baseUrl = BASE_URL,
            anonKey = "synthetic-anon-key",
        ),
        session = SessionContext(sessionStore),
        cache = cache,
        expectedOwnerId = expectedOwnerId,
        moveOutbox = outbox ?: if (withMoveOutbox) EntryMoveStore(moveFile, expectedOwnerId) else null,
    )

    private fun openApiResponse(): HttpResponse {
        val paths = JSONObject()
        if (moveRouteVisible) paths.put("/rpc/move_daily_entry_v1", JSONObject().put("post", JSONObject()))
        if (capabilityRouteVisible) {
            paths.put("/rpc/android_move_capability_v1", JSONObject().put("get", JSONObject()))
        }
        return HttpResponse(200, JSONObject().put("openapi", "3.0.0").put("paths", paths).toString())
    }

    private fun cacheStore(label: String) = EntriesCacheStore(
        File(context.cacheDir, "entry-move-cache-$label-${UUID.randomUUID()}.json"),
        OWNER_ID,
    )

    private fun assertNoBlindWriteOrStorageDelete() {
        assertTrue("date move must use only the atomic RPC, not a patch/delete/save fallback", sent.none {
            it.method == "PATCH" || it.method == "DELETE" ||
                (it.method == "POST" && !it.url.endsWith("/rpc/move_daily_entry_v1"))
        })
        assertTrue("the photo cleanup candidate is not authorization to delete storage", sent.none {
            it.url.contains("/storage/v1/object/")
        })
    }

    private fun saveSession(userId: String) {
        sessionStore.save(
            JSONObject()
                .put("access_token", "synthetic-jwt-$userId")
                .put("refresh_token", "synthetic-refresh-$userId")
                .put("expires_at", System.currentTimeMillis() / 1000 + 3600)
                .put("user", JSONObject().put("id", userId)),
        )
    }

    private companion object {
        const val BASE_URL = "https://example.supabase.co/rest/v1"
        const val OWNER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val OTHER_ID = "11111111-2222-4333-8444-555555555555"
        const val SOURCE_ID = "ecaf917a-7c84-4ad5-b657-cc5d9af10a36"
        const val TARGET_ID = "4ec648bb-0be0-4d34-925f-5b3999d71bca"
        const val SOURCE_DATE = "2026-09-10"
        const val TARGET_DATE = "2026-09-11"
        const val PHOTO_SOURCE = "https://example.supabase.co/storage/v1/object/public/diary-photos/$OWNER_ID/source.jpg"
        const val PHOTO_TARGET = "https://example.supabase.co/storage/v1/object/public/diary-photos/$OWNER_ID/target.jpg"
    }
}
