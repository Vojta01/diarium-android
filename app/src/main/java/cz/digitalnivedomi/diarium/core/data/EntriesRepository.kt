package cz.digitalnivedomi.diarium.core.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.time.LocalDate
import java.util.UUID

/**
 * Outcome of [EntriesRepository.uploadPhoto] — exactly one field is set.
 *
 * [error] is short and user-facing („fotka je moc velká“), never a raw HTTP
 * dump; the code and body go to logcat instead.
 */
data class PhotoUpload(val url: String? = null, val error: String? = null)

/** Server token captured by the successful foreground day read. */
data class EntryBaseline(
    val exists: Boolean?,
    val id: String?,
    val revision: Long?,
) {
    val isValid: Boolean
        get() = when (exists) {
            false -> id == null && revision == null
            true -> !id.isNullOrBlank() &&
                runCatching { UUID.fromString(id) }.isSuccess &&
                revision != null && revision >= 1
            null -> false
        }
}

/** Immutable request retained across retries; never mutate a request after dispatch. */
data class ConditionalEntrySaveRequest(
    val ownerId: String,
    val date: String,
    val entry: DiaryEntry,
    val baseline: EntryBaseline,
    val requestId: String,
    /** Include only when this request is intentionally persisting new AI text. */
    val writeAiReflection: Boolean = false,
)

data class ConditionalSaveReceipt(
    val entryId: String,
    val revision: Long,
    val replayed: Boolean,
)

class ConditionalSaveConflictException(
    val reason: String,
    val currentSnapshot: StoredEntrySnapshot?,
    val currentReadError: String? = null,
) : IllegalStateException(
    when {
        currentReadError != null -> "Server version changed; your local draft is preserved, but the current server copy could not be read."
        currentSnapshot == null -> "Server version changed; your local draft is preserved and the server day is now empty."
        else -> "Server version changed; your local draft and the current server copy are both preserved for review."
    },
)

class ConditionalSaveUnknownException(
    val requestId: String,
    cause: Throwable? = null,
) : IllegalStateException(
    "Uložení nelze potvrdit. Návrh zůstal zachovaný; opakování pošli se stejným požadavkem.",
    cause,
)

class ConditionalSaveUnavailableException(message: String) : IllegalStateException(message)

private enum class PhotoCleanupResult { DELETED, REFERENCED, FAILED }

/**
 * Reads the `entries` table through an owner-pinned PostgREST client. The ordinary
 * foreground check-in uses [save] with a [ConditionalEntrySaveRequest], which calls
 * `save_daily_entry_v2` with the read-time `(exists, id, revision)` token and a stable
 * request ID. The compatibility save path below remains for existing non-foreground
 * callers and retains their legacy write/cleanup behavior.
 */
class EntriesRepository(
    private val client: SupabaseClient,
    private val session: SessionContext = SessionContext(),
    /**
     * Local snapshot of the range reads (file-backed, see [EntriesCacheStore]).
     * Optional on purpose: with null — as in every test that builds the
     * repository by hand — reads and writes behave exactly as before, and a
     * cache that cannot be written degrades to „no cache", never an error.
     */
    private val cache: EntriesCacheStore? = null,
    /**
     * Offline outbox. Ambiguous v2 saves persist the exact immutable CAS request
     * and may be replayed only for the verified owner and baseline. Legacy,
     * malformed, rejected, and conflicting records remain local for recovery.
     */
    private val pending: PendingSaveStore? = null,
    /** A screen/worker may pin the repository to the owner it was created for. */
    private val expectedOwnerId: String? = null,
    /** Durable staging candidates; only the selected/referenced path survives cleanup. */
    private val photoCandidates: PhotoCandidateRegistry? = null,
    /** Read-only outbox guard for worker cleanup, separate from the save write barrier. */
    private val photoCleanupOutbox: PendingSaveStore? = null,
) {

    suspend fun currentUserId(): String? = authorizedUserId()

    private fun authorizedUserId(): String? = session.userId()?.takeIf { current ->
        expectedOwnerId == null || current == expectedOwnerId
    }

    private fun pendingFor(userId: String): PendingSaveStore? =
        pending?.takeIf { it.ownerId == userId }

    private fun cacheFor(userId: String): EntriesCacheStore? =
        cache?.takeIf { it.ownerId == userId }

    /** Every request is pinned at dispatch to the owner whose data it carries. */
    private fun ownerClient(userId: String): SupabaseClient = client.forOwner(userId)

    /** Exposed for enqueueing this repository's outbox even after a session switch. */
    val pendingOwnerId: String? get() = pending?.ownerId ?: expectedOwnerId

    /**
     * Loads the stored entry for [date].
     *
     * `Result.success(null)` means the day genuinely has no row yet; a failed read
     * (offline phone, HTTP error, a body the client cannot parse) is a
     * `Result.failure`. That distinction is load-bearing: the check-in screen used to
     * treat both as "empty day", so opening a stored day during a network blip and
     * pressing save wrote an empty payload over the real mood, stress and arrays.
     *
     * A queued offline save is the newest state of that day: it beats the server's
     * row (older, or not there yet) and survives a failed read.
     */
    suspend fun loadEntry(date: String): Result<DiaryEntry?> = withContext(Dispatchers.IO) {
        val userId = authorizedUserId()
            ?: return@withContext Result.failure(IllegalStateException(SIGNED_OUT))
        pendingFor(userId)?.peek(date)?.let { return@withContext Result.success(it) }
        try {
            Result.success(readEntrySnapshot(date, userId)?.entry)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // Already a sentence written for the screen (signed out, HTTP error, bad body).
            Result.failure(e)
        } catch (_: IOException) {
            Result.failure(IllegalStateException(CONNECT_FAILED))
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException("Načtení zápisu se nezdařilo (${e.javaClass.simpleName})."),
            )
        }
    }

    /**
     * Reads the actual server row for recovery and returns its row-token fields. A GET
     * alone does not authorize a write; the foreground v2 save sends this snapshot's
     * `(exists, id, revision)` as an atomic compare-and-swap baseline.
     */
    suspend fun loadRemoteEntrySnapshot(date: String): Result<StoredEntrySnapshot?> =
        withContext(Dispatchers.IO) {
            val userId = authorizedUserId()
                ?: return@withContext Result.failure(IllegalStateException(SIGNED_OUT))
            try {
                Result.success(readEntrySnapshot(date, userId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                Result.failure(e)
            } catch (_: IOException) {
                Result.failure(IllegalStateException(CONNECT_FAILED))
            } catch (e: Exception) {
                Result.failure(
                    IllegalStateException("Načtení zápisu se nezdařilo (${e.javaClass.simpleName})."),
                )
            }
        }

    /** The real read for the owner-bound load APIs; throws on transport, HTTP or parse failure. */
    private suspend fun readEntrySnapshot(date: String, userId: String): StoredEntrySnapshot? {
        val resp = ownerClient(userId).get(
            "entries",
            mapOf(
                "user_id" to "eq.$userId",
                "date" to "eq.$date",
                "select" to "*",
                "limit" to "1",
            ),
        )
        if (!resp.isSuccessful) {
            throw IllegalStateException(resp.errorMessage ?: "Načtení zápisu se nezdařilo (${resp.code}).")
        }
        val rows = resp.asJsonArray() ?: throw IllegalStateException("Server vrátil neplatnou odpověď.")
        if (rows.length() == 0) return null
        val row = rows.getJSONObject(0)
        return StoredEntrySnapshot(
            entry = fromRow(row),
            updatedAt = row.plainString("updated_at").takeIf { it.isNotBlank() },
            id = row.plainString("id").takeIf { it.isNotBlank() },
            revision = if (row.has("revision") && !row.isNull("revision")) row.optLong("revision") else null,
        )
    }

    /** The exact owner-bound local save shown by the recovery UI, if one exists. */
    fun pendingSaveRecord(date: String): PendingSave? {
        val userId = authorizedUserId() ?: return null
        return pendingFor(userId)?.peekRecord(date)?.takeIf { it.ownerId == userId }
    }

    /** Text-free marker for malformed or ownerless outbox data; never migrates it. */
    fun pendingSaveRecoveryIssue(date: String): PendingSaveRecoveryIssue? {
        val userId = authorizedUserId() ?: return null
        return pendingFor(userId)?.recoveryIssueForDate(date)
    }

    /**
     * Loads every stored entry in `[from, to]` (inclusive ISO dates) in a single
     * round-trip — the dashboard derives all of its numbers from this one call.
     *
     * The date bound is expressed with PostgREST's `and=(...)` operator because a
     * Kotlin `Map` cannot carry two values for the same `date` column, and both
     * bounds matter: the lower one is the dashboard's window, the upper one keeps
     * anything future-dated out. [limit] is only a safety cap — a 30-day window is
     * far below it — and the order matches the web's `fetchDailyEntries()`.
     *
     * Unlike [loadEntry] this reports failures instead of returning an empty
     * result: the dashboard must be able to tell "nothing saved yet" from "the
     * read failed", otherwise a dead request renders as a screen full of zeroes.
     */
    suspend fun loadRange(
        from: String,
        to: String,
        limit: Int = RANGE_LIMIT,
    ): Result<List<DatedEntry>> = withContext(Dispatchers.IO) {
        val userId = authorizedUserId()
            ?: return@withContext Result.failure(IllegalStateException(SIGNED_OUT))
        try {
            val rows = readRange(userId, from, to, limit)
            Result.success(withPendingOverlay(rows, from, to, userId))
        } catch (e: CancellationException) {
            // Cancellation is the caller walking away, not a failure to report.
            throw e
        } catch (e: IllegalStateException) {
            // Already a sentence written for the screen (signed out, HTTP error, bad body).
            Result.failure(e)
        } catch (_: IOException) {
            // A phone that lost signal is a normal state: it has to reach the screen as
            // a retryable failure in Czech, never as an exception that kills the coroutine.
            Result.failure(IllegalStateException(CONNECT_FAILED))
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException("Načtení přehledu se nezdařilo (${e.javaClass.simpleName})."),
            )
        }
    }

    /**
     * The real read behind [loadRange]: throws on a transport failure, an HTTP error
     * or a body the client cannot parse, so [loadRange] has a single place that turns
     * each of them into the [Result] the dashboard renders.
     */
    private suspend fun readRange(userId: String, from: String, to: String, limit: Int): List<DatedEntry> {
        val resp = ownerClient(userId).get(
            "entries",
            mapOf(
                "user_id" to "eq.$userId",
                "and" to "(date.gte.$from,date.lte.$to)",
                "select" to "*",
                "order" to "date.desc",
                "limit" to limit.toString(),
            ),
        )
        if (!resp.isSuccessful) {
            throw IllegalStateException(resp.errorMessage ?: "Načtení přehledu se nezdařilo (${resp.code})")
        }
        val rows = resp.asJsonArray() ?: throw IllegalStateException("Server vrátil neplatnou odpověď.")
        val raw = (0 until rows.length()).map { index -> rows.getJSONObject(index) }
        // Feed the local snapshot — best effort: a cache that cannot be written
        // must never fail a read that succeeded.
        runCatching { cacheFor(userId)?.upsertRows(raw) }
        return raw.map { row -> DatedEntry(row.plainString("date"), fromRow(row)) }
    }

    /**
     * The cached snapshot of `[from, to]`, or null when nothing was ever cached.
     * Pending offline saves are overlaid exactly like on a live read, so the
     * instant render shows the same day the fresh read is about to.
     */
    suspend fun loadRangeCachedFirst(from: String, to: String): List<DatedEntry>? {
        val userId = authorizedUserId() ?: return null
        val rows = cacheFor(userId)?.readRange(from, to) ?: return null
        return withPendingOverlay(rows, from, to, userId)
    }

    /**
     * Best-effort read of specific days — the memory lane asks for the dates a
     * year, half a year and a month back. Any failure (offline phone, HTTP
     * error, a bad body) yields an empty map: the caller treats it as „no
     * memory today" and the card simply stays off.
     */
    suspend fun loadDates(dates: List<String>): Map<String, DiaryEntry> =
        withContext(Dispatchers.IO) {
            if (dates.isEmpty()) return@withContext emptyMap()
            try {
                val userId = authorizedUserId() ?: return@withContext emptyMap()
                val resp = ownerClient(userId).get(
                    "entries",
                    mapOf(
                        "user_id" to "eq.$userId",
                        "date" to "in.(${dates.joinToString(",")})",
                        "select" to "*",
                    ),
                )
                val rows = resp.asJsonArray() ?: return@withContext emptyMap()
                val raw = (0 until rows.length()).map { index -> rows.getJSONObject(index) }
                runCatching { cacheFor(userId)?.upsertRows(raw) }
                val byDate = raw.associate { row -> row.plainString("date") to fromRow(row) }.toMutableMap()
                pendingFor(userId)?.peekAll()
                    ?.filter { it.date in dates }
                    ?.forEach { byDate[it.date] = it.entry }
                byDate
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
        }

    /** A queued offline save replaces the server's row for the same date. */
    private fun withPendingOverlay(
        rows: List<DatedEntry>,
        from: String,
        to: String,
        ownerId: String,
    ): List<DatedEntry> {
        val store = pendingFor(ownerId) ?: return rows
        val queued = store.peekAll().filter { it.date in from..to }
        if (queued.isEmpty()) return rows
        val byDate = rows.associateBy { it.date }.toMutableMap()
        for (save in queued) byDate[save.date] = DatedEntry(save.date, save.entry)
        return byDate.values.sortedByDescending { it.date }
    }

    /**
     * Compatibility save path retained for non-foreground callers. When a baseline
     * token is supplied it dispatches through [save] with a
     * [ConditionalEntrySaveRequest]; the normal foreground screen passes that typed
     * immutable request directly. Calls without a token retain the existing legacy
     * RPC and cleanup behavior; they must not be used for check-in foreground saves.
     */
    suspend fun save(
        entry: DiaryEntry,
        date: String,
        cleanupPhotoAfterPersistence: Boolean = true,
        /** Date moves require both writes online; never enqueue only the target half. */
        allowOfflineQueue: Boolean = true,
        /** Server revision the edit was based on; informational only without atomic CAS. */
        baselineUpdatedAt: String? = null,
        /** Whether a server row existed when editing began, if it was verified. */
        baselineExists: Boolean? = null,
        baselineId: String? = null,
        baselineRevision: Long? = null,
        requestId: String = UUID.randomUUID().toString(),
    ): Result<Unit> {
        if (baselineExists != null || baselineId != null || baselineRevision != null) {
            val ownerId = authorizedUserId()
                ?: return Result.failure(IllegalStateException(SIGNED_OUT))
            return save(
                ConditionalEntrySaveRequest(
                    ownerId = ownerId,
                    date = date,
                    entry = entry,
                    baseline = EntryBaseline(baselineExists, baselineId, baselineRevision),
                    requestId = requestId,
                ),
                cleanupPhotoAfterPersistence = cleanupPhotoAfterPersistence,
            ).map { Unit }
        }
        val userId = authorizedUserId()
            ?: return Result.failure(IllegalStateException(SIGNED_OUT))
        val outbox = pendingFor(userId)
        if (pending != null && outbox == null) {
            return Result.failure(IllegalStateException(SIGNED_OUT))
        }
        return withContext(Dispatchers.IO) {
            if (outbox != null) {
                outbox.withDateLock(date) {
                    saveAuthorized(
                        userId,
                        entry,
                        date,
                        cleanupPhotoAfterPersistence,
                        allowOfflineQueue,
                        baselineUpdatedAt,
                        baselineExists,
                        outbox,
                    )
                }
            } else {
                saveAuthorized(
                    userId,
                    entry,
                    date,
                    cleanupPhotoAfterPersistence,
                    allowOfflineQueue,
                    baselineUpdatedAt,
                    baselineExists,
                    null,
                )
            }
        }
    }

    /** Foreground compare-and-swap. A retry must receive the same immutable request. */
    suspend fun save(
        request: ConditionalEntrySaveRequest,
        cleanupPhotoAfterPersistence: Boolean = true,
    ): Result<ConditionalSaveReceipt> {
        val userId = authorizedUserId()
            ?: return Result.failure(IllegalStateException(SIGNED_OUT))
        if (userId != request.ownerId) return Result.failure(SessionOwnerMismatchException())
        if (runCatching { LocalDate.parse(request.date) }.isFailure) {
            return Result.failure(IllegalArgumentException("The conditional save date is invalid."))
        }
        if (!request.baseline.isValid) {
            return Result.failure(
                ConditionalSaveUnavailableException(
                    "The server version for this day is not verified. Your draft is preserved and nothing was sent.",
                ),
            )
        }
        if (runCatching { UUID.fromString(request.requestId) }.isFailure) {
            return Result.failure(IllegalArgumentException("The conditional save request ID is invalid."))
        }
        val outbox = pendingFor(userId)
        if (pending != null && outbox == null) {
            return Result.failure(IllegalStateException(SIGNED_OUT))
        }
        return withContext(Dispatchers.IO) {
            suspend fun saveAndQueueUnknown(): Result<ConditionalSaveReceipt> {
                if (authorizedUserId() != userId) {
                    return Result.failure(SessionOwnerMismatchException())
                }
                if (outbox?.peekRecord(request.date) != null ||
                    outbox?.recoveryIssueForDate(request.date) != null
                ) {
                    return Result.failure(IllegalStateException(PENDING_SAVE_RECOVERY_BLOCKED))
                }
                val result = saveConditionalAuthorized(
                    request,
                    userId,
                    cleanupPhotoAfterPersistence,
                    verifyReplayedReceipt = true,
                )
                if (result.exceptionOrNull() is ConditionalSaveUnknownException && outbox != null) {
                    if (!outbox.enqueueConditional(request)) {
                        Log.w(LOG_TAG, "conditional offline request could not be durably queued")
                    }
                }
                return result
            }
            if (outbox != null) {
                outbox.withDateLock(request.date) { saveAndQueueUnknown() }
            } else {
                saveAndQueueUnknown()
            }
        }
    }

    /** Worker entrypoint; the caller already holds the outbox owner/date lock. */
    internal suspend fun replayPendingSave(request: ConditionalEntrySaveRequest): Result<ConditionalSaveReceipt> {
        val userId = authorizedUserId()
            ?: return Result.failure(IllegalStateException(SIGNED_OUT))
        if (userId != request.ownerId) return Result.failure(SessionOwnerMismatchException())
        if (runCatching { LocalDate.parse(request.date) }.isFailure) {
            return Result.failure(ConditionalSaveUnavailableException("The queued CAS date is invalid."))
        }
        if (!request.baseline.isValid || runCatching { UUID.fromString(request.requestId) }.isFailure) {
            return Result.failure(
                ConditionalSaveUnavailableException("The queued CAS request is not fully verified."),
            )
        }
        return withContext(Dispatchers.IO) {
            if (authorizedUserId() != userId) {
                Result.failure(SessionOwnerMismatchException())
            } else {
                saveConditionalAuthorized(
                    request,
                    userId,
                    cleanupPhotoAfterPersistence = true,
                    verifyReplayedReceipt = true,
                )
            }
        }
    }

    private suspend fun saveConditionalAuthorized(
        request: ConditionalEntrySaveRequest,
        userId: String,
        cleanupPhotoAfterPersistence: Boolean,
        verifyReplayedReceipt: Boolean = false,
    ): Result<ConditionalSaveReceipt> {
        if (authorizedUserId() != userId || request.ownerId != userId) {
            return Result.failure(SessionOwnerMismatchException())
        }
        val entry = request.entry
        val oldPhotoPath = if (entry.clearPhoto) {
            entry.photoPathToDelete ?: entry.photoPath
        } else {
            entry.photoPathToDelete?.takeIf { it.isNotBlank() && it != entry.photoPath }
        }
        if (!oldPhotoPath.isNullOrBlank()) {
            val candidates = photoCandidates?.takeIf { it.ownerId == userId }
                ?: return Result.failure(IllegalStateException(PHOTO_CANDIDATE_TRACK_FAILED))
            if (!runCatching { candidates.track(request.date, oldPhotoPath) }.getOrDefault(false)) {
                return Result.failure(IllegalStateException(PHOTO_CANDIDATE_TRACK_FAILED))
            }
        }

        val payload = buildPayload(entry, userId, request.date).apply {
            if (!request.writeAiReflection) remove("ai_reflection")
            put("clear_photo", entry.clearPhoto)
            put("clear_scale_ids", JSONArray(entry.clearedScaleIds.sorted()))
        }
        val body = JSONObject()
            .put("p_payload", payload)
            .put("p_expected_exists", request.baseline.exists)
            .put("p_expected_id", request.baseline.id ?: JSONObject.NULL)
            .put("p_expected_revision", request.baseline.revision ?: JSONObject.NULL)
            .put("p_request_id", request.requestId)
        val response = try {
            ownerClient(userId).post("rpc/save_daily_entry_v2", body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            return Result.failure(ConditionalSaveUnknownException(request.requestId, failure))
        } catch (failure: Exception) {
            return Result.failure(failure)
        }

        if (!response.isSuccessful) {
            if (response.code == 404) {
                return Result.failure(
                    ConditionalSaveUnavailableException(
                        "This server does not support safe conditional saves. Your local draft is preserved; no legacy write was attempted.",
                    ),
                )
            }
            if (response.code >= 500 || response.code == 408 || response.code == 429) {
                return Result.failure(
                    ConditionalSaveUnknownException(
                        request.requestId,
                        IllegalStateException("Server returned HTTP ${response.code}."),
                    ),
                )
            }
            return Result.failure(
                IllegalStateException(response.errorMessage ?: "Uložení se nezdařilo (${response.code})."),
            )
        }
        val envelope = response.asJsonObject()
            ?: return Result.failure(ConditionalSaveUnknownException(request.requestId))
        return when (envelope.plainString("status")) {
            "conflict" -> {
                val reason = envelope.plainString("reason").ifBlank { "unknown_conflict" }
                val current = try {
                    readEntrySnapshot(request.date, userId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    return Result.failure(
                        ConditionalSaveConflictException(reason, null, failure.message ?: CONNECT_FAILED),
                    )
                }
                Result.failure(ConditionalSaveConflictException(reason, current))
            }
            "saved" -> {
                val entryId = envelope.plainString("entry_id")
                val revision = if (envelope.has("revision")) envelope.optLong("revision") else 0L
                if (entryId.isBlank() || revision < 1) {
                    return Result.failure(ConditionalSaveUnknownException(request.requestId))
                }
                if (authorizedUserId() != userId) {
                    return Result.failure(SessionOwnerMismatchException())
                }
                val replayed = envelope.optBoolean("replayed", false)
                val receipt = ConditionalSaveReceipt(
                    entryId = entryId,
                    revision = revision,
                    replayed = replayed,
                )
                if (verifyReplayedReceipt && replayed) {
                    val current = try {
                        readEntrySnapshot(request.date, userId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        if (authorizedUserId() != userId) {
                            return Result.failure(SessionOwnerMismatchException())
                        }
                        return Result.failure(
                            ConditionalSaveConflictException(
                                REPLAYED_RECEIPT_MISMATCH,
                                currentSnapshot = null,
                                currentReadError = failure.message ?: CONNECT_FAILED,
                            ),
                        )
                    }
                    if (authorizedUserId() != userId) {
                        return Result.failure(SessionOwnerMismatchException())
                    }
                    if (current == null || current.id != entryId || current.revision != revision) {
                        return Result.failure(
                            ConditionalSaveConflictException(
                                REPLAYED_RECEIPT_MISMATCH,
                                currentSnapshot = current,
                            ),
                        )
                    }
                }
                val cached = envelope.optJSONObject("entry") ?: buildCacheRow(payload, entry, receipt)
                runCatching { cacheFor(userId)?.upsertRows(listOf(cached)) }
                if (cleanupPhotoAfterPersistence) {
                    cleanupPhotoCandidates(
                        request.date,
                        preservePaths = entry.photoPath?.takeIf { !entry.clearPhoto }?.let(::setOf).orEmpty(),
                        confirmedPaths = entry.photoPath?.takeIf { !entry.clearPhoto }?.let(::setOf).orEmpty(),
                    )
                }
                if (!receipt.replayed) {
                    runCatching {
                        ownerClient(userId).post("rpc/check_achievements", buildAchievementsPayload(entry, userId))
                    }
                }
                Result.success(receipt)
            }
            else -> Result.failure(ConditionalSaveUnknownException(request.requestId))
        }
    }

    private fun buildCacheRow(
        payload: JSONObject,
        entry: DiaryEntry,
        receipt: ConditionalSaveReceipt,
    ): JSONObject = JSONObject(payload.toString()).apply {
        remove("clear_photo")
        remove("clear_scale_ids")
        if (entry.clearPhoto) put("photo_path", JSONObject.NULL)
        if (entry.clearedScaleIds.isNotEmpty()) {
            val scales = optJSONObject("scale_values") ?: JSONObject()
            entry.clearedScaleIds.forEach(scales::remove)
            put("scale_values", scales)
        }
        put("id", receipt.entryId)
        put("revision", receipt.revision)
    }

    /** Caller holds the per-date lock when an owner-bound outbox is present. */
    private suspend fun saveAuthorized(
        userId: String,
        entry: DiaryEntry,
        date: String,
        cleanupPhotoAfterPersistence: Boolean,
        allowOfflineQueue: Boolean,
        baselineUpdatedAt: String?,
        baselineExists: Boolean?,
        outbox: PendingSaveStore?,
    ): Result<Unit> {
        if (authorizedUserId() != userId) return Result.failure(SessionOwnerMismatchException())
        val oldPhotoPath = if (entry.clearPhoto) {
            entry.photoPathToDelete ?: entry.photoPath
        } else {
            entry.photoPathToDelete?.takeIf { it.isNotBlank() && it != entry.photoPath }
        }
        if (cleanupPhotoAfterPersistence && !oldPhotoPath.isNullOrBlank()) {
            val candidates = photoCandidates?.takeIf { it.ownerId == userId }
                ?: return Result.failure(IllegalStateException(PHOTO_CANDIDATE_TRACK_FAILED))
            val tracked = runCatching { candidates.track(date, oldPhotoPath) }.getOrDefault(false)
            if (!tracked) return Result.failure(IllegalStateException(PHOTO_CANDIDATE_TRACK_FAILED))
        }
        val payload = buildPayload(entry, userId, date)
        val dispatch = dispatchSave(
            userId,
            entry,
            date,
            payload,
            allowOfflineQueue,
            baselineUpdatedAt,
            baselineExists,
            outbox,
        )
        val resp = dispatch.getOrElse { return Result.failure(it) }
        // Keep the local snapshot in step: the payload carries exactly the keys
        // this save touched, and the overlay keeps the worker-owned ones
        // (phone_screen_time, ai_reflection).
        runCatching { cacheFor(userId)?.upsertRows(listOf(payload)) }

        // The exact old path was durably registered before dispatch. Cleanup below
        // may now retry it, but this request never loses the only local reference.
        // The removals the RPC cannot express. Both run only after the day is
        // safely stored, and both are scoped to this user and this date.
        if (entry.clearPhoto && !clearPhoto(userId, date)) {
            return Result.failure(
                IllegalStateException(
                    "Zápis je uložený, ale fotku se nepodařilo odebrat. Zkus to znovu.",
                ),
            )
        }
        if (cleanupPhotoAfterPersistence) {
            val savedPhoto = entry.photoPath?.takeIf { !entry.clearPhoto }
            cleanupPhotoCandidates(
                date,
                preservePaths = savedPhoto?.let(::setOf).orEmpty(),
                confirmedPaths = savedPhoto?.let(::setOf).orEmpty(),
            )
        }
        val failedScaleClears = entry.clearedScaleIds.filterNot { scaleId ->
            deleteScaleEntry(userId, scaleId, date)
        }
        if (failedScaleClears.isNotEmpty()) {
            return Result.failure(
                IllegalStateException(
                    "Zápis je uložený, ale hodnoty škál se nepodařilo smazat. Zkus to znovu.",
                ),
            )
        }

        // Achievements are a side effect — never lose the entry over them.
        runCatching {
            ownerClient(userId).post("rpc/check_achievements", buildAchievementsPayload(entry, userId))
        }
        return Result.success(Unit)
    }

    /** Queue or dispatch the save while the caller holds its per-date outbox lock. */
    private suspend fun dispatchSave(
        userId: String,
        entry: DiaryEntry,
        date: String,
        payload: JSONObject,
        allowOfflineQueue: Boolean,
        baselineUpdatedAt: String?,
        baselineExists: Boolean?,
        outbox: PendingSaveStore?,
    ): Result<HttpResponse> {
        if (authorizedUserId() != userId) {
            return Result.failure(SessionOwnerMismatchException())
        }
        val queuedRecord = outbox?.peekRecord(date)
        if (outbox?.recoveryIssueForDate(date) != null ||
            queuedRecord?.conditionalRequest != null || queuedRecord?.recoveryReason != null
        ) {
            return Result.failure(IllegalStateException(PENDING_SAVE_RECOVERY_BLOCKED))
        }
        if (queuedRecord != null) {
            if (!allowOfflineQueue) return Result.failure(IllegalStateException(MOVE_OFFLINE_BLOCKED))
            if (outbox?.enqueue(
                    date,
                    entry,
                    baselineUpdatedAt = baselineUpdatedAt,
                    baselineExists = baselineExists,
                ) != true
            ) {
                return Result.failure(IllegalStateException(CONNECT_FAILED))
            }
            runCatching { cacheFor(userId)?.upsertRows(listOf(payload)) }
            return Result.failure(QueuedOfflineException())
        }

        val response = try {
            ownerClient(userId).post("rpc/save_daily_entry", JSONObject().put("p_payload", payload))
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            if (!allowOfflineQueue) {
                return Result.failure(IllegalStateException(MOVE_OFFLINE_BLOCKED))
            }
            if (outbox == null) {
                return Result.failure(
                    IllegalStateException(if (pending == null) CONNECT_FAILED else SIGNED_OUT),
                )
            }
            if (!outbox.enqueue(
                    date,
                    entry,
                    baselineUpdatedAt = baselineUpdatedAt,
                    baselineExists = baselineExists,
                )
            ) {
                return Result.failure(IllegalStateException(CONNECT_FAILED))
            }
            runCatching { cacheFor(userId)?.upsertRows(listOf(payload)) }
            return Result.failure(QueuedOfflineException())
        } catch (e: Exception) {
            return Result.failure(e)
        }
        if (!response.isSuccessful) {
            return Result.failure(
                IllegalStateException(response.errorMessage ?: "Uložení se nezdařilo (${response.code})"),
            )
        }
        return Result.success(response)
    }

    /**
     * Clears `entries.photo_path` for one day — the app-only half of removing a
     * photo. `save_daily_entry` only assigns columns present in the payload, and
     * the form deliberately omits `photo_path` when the photo is being removed;
     * a `null` PATCH is what actually clears the column. RLS (`entries_own`)
     * scopes the write to the signed-in user's own row.
     *
     * The storage object is deleted best-effort only *after* the row no longer
     * references it: a failed object delete leaves an orphan file (harmless),
     * while deleting it first could lose the photo of a day whose PATCH failed.
     */
    private suspend fun clearPhoto(
        userId: String,
        date: String,
    ): Boolean {
        val resp = try {
            ownerClient(userId).patch(
                "entries",
                JSONObject().put("photo_path", JSONObject.NULL),
                mapOf("user_id" to "eq.$userId", "date" to "eq.$date"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "photo_path PATCH failed: ${e.javaClass.simpleName}")
            return false
        }
        if (!resp.isSuccessful) return false
        runCatching {
            cacheFor(userId)?.upsertRows(
                listOf(JSONObject().put("date", date).put("photo_path", JSONObject.NULL)),
            )
        }
        // The old path was durably registered before the save RPC; cleanup is
        // intentionally deferred to the caller's candidate reconciliation.
        return true
    }

    /**
     * Deletes an object only after an owner-scoped read proves that no entries row
     * references it and no queued transaction needs it. Read/parse/auth errors fail
     * closed and leave the object (and staged-candidate record) untouched.
     */
    private suspend fun deleteStoredPhoto(userId: String, photoPath: String?): PhotoCleanupResult {
        val objectPath = storageObjectPathForDeletion(photoPath, userId, client.projectUrl())
            ?: return PhotoCleanupResult.FAILED
        if (authorizedUserId() != userId) return PhotoCleanupResult.FAILED
        val response = try {
            ownerClient(userId).get(
                "entries",
                mapOf(
                    "user_id" to "eq.$userId",
                    "photo_path" to "eq.$photoPath",
                    "select" to "date",
                    "limit" to "1",
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "photo reference check failed: ${e.javaClass.simpleName}")
            return PhotoCleanupResult.FAILED
        }
        if (!response.isSuccessful) return PhotoCleanupResult.FAILED
        val rows = response.asJsonArray() ?: return PhotoCleanupResult.FAILED
        if (rows.length() > 0) return PhotoCleanupResult.REFERENCED

        val cleanupOutbox = pendingFor(userId)
            ?: photoCleanupOutbox?.takeIf { it.ownerId == userId }
        if (cleanupOutbox?.peekAll()?.any { it.entry.photoPath == photoPath } == true) {
            return PhotoCleanupResult.REFERENCED
        }
        if (authorizedUserId() != userId) return PhotoCleanupResult.FAILED
        return try {
            val deleted = ownerClient(userId).deleteObject("diary-photos/$objectPath")
            if (deleted.isSuccessful) PhotoCleanupResult.DELETED else {
                Log.w(LOG_TAG, "photo cleanup failed: HTTP ${deleted.code}")
                PhotoCleanupResult.FAILED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "photo cleanup failed: ${e.javaClass.simpleName}")
            PhotoCleanupResult.FAILED
        }
    }

    /**
     * Reconciles staged paths after a confirmed remote read/write. [preservePaths]
     * include the current form and draft photo; [confirmedPaths] were just observed
     * in the saved row and may be removed from the staged-candidate registry without
     * deleting the object. Any unreferenced candidate is deleted only after the
     * owner-scoped reference read above succeeds.
     */
    suspend fun cleanupPhotoCandidates(
        date: String,
        preservePaths: Set<String> = emptySet(),
        confirmedPaths: Set<String> = emptySet(),
    ) = withContext(Dispatchers.IO) {
        val userId = authorizedUserId() ?: return@withContext
        val candidates = photoCandidates?.takeIf { it.ownerId == userId } ?: return@withContext
        val cleanupOutbox = pendingFor(userId)
            ?: photoCleanupOutbox?.takeIf { it.ownerId == userId }
        for (path in candidates.forDate(date)) {
            if (path in confirmedPaths) {
                candidates.remove(date, path)
                continue
            }
            if (path in preservePaths) continue
            if (cleanupOutbox?.peekAll()?.any { it.entry.photoPath == path } == true) continue
            when (deleteStoredPhoto(userId, path)) {
                PhotoCleanupResult.DELETED, PhotoCleanupResult.REFERENCED -> candidates.remove(date, path)
                PhotoCleanupResult.FAILED -> Unit
            }
        }
    }

    /** Call only after the caller has persisted the new row or removed the old one. */
    suspend fun cleanupStoredPhoto(photoPath: String?) = withContext(Dispatchers.IO) {
        val userId = authorizedUserId() ?: return@withContext
        deleteStoredPhoto(userId, photoPath)
    }

    /** Persist a moved/retired path before cleanup so failures can be retried on a later load. */
    suspend fun cleanupStoredPhoto(
        date: String,
        photoPath: String?,
        preservePaths: Set<String> = emptySet(),
    ) = withContext(Dispatchers.IO) {
        val userId = authorizedUserId() ?: return@withContext
        val candidates = photoCandidates?.takeIf { it.ownerId == userId }
        if (candidates != null && !photoPath.isNullOrBlank() && !candidates.track(date, photoPath)) {
            return@withContext
        }
        cleanupPhotoCandidates(date, preservePaths = preservePaths, confirmedPaths = preservePaths)
    }

    /** Worker-only cleanup after a conditionally completed outbox transaction. */
    suspend fun cleanupStoredPhotoForOwner(userId: String, photoPath: String?) {
        if (authorizedUserId() != userId) return
        deleteStoredPhoto(userId, photoPath)
    }

    /** Deletes one `scale_entries` row; RLS scopes it to the signed-in user. */
    private suspend fun deleteScaleEntry(userId: String, scaleId: String, date: String): Boolean =
        ownerClient(userId).delete(
            "scale_entries",
            mapOf(
                "user_id" to "eq.$userId",
                "scale_id" to "eq.$scaleId",
                "date" to "eq.$date",
            ),
        ).isSuccessful

    /**
     * Deletes the day's row — the second half of moving a stored check-in to another
     * date (see [save]). The owner's RLS policy (`entries_own`) covers DELETE, so the
     * app needs no server-side RPC for this.
     *
     * The day's `scale_entries` rows are deleted explicitly afterwards: the entries
     * delete does not cascade into them, and leaving them behind would keep the moved
     * or deleted day in every scale's 30-day statistics. A failure is reported, not
     * swallowed: the caller has usually already written the entry to its new day, and
     * a stale copy left behind must reach the screen as a sentence instead of silently
     * duplicating the day.
     */
    suspend fun delete(date: String): Result<Unit> {
        val userId = authorizedUserId()
            ?: return Result.failure(IllegalStateException(SIGNED_OUT))
        val outbox = pendingFor(userId)
        if (pending != null && outbox == null) {
            return Result.failure(IllegalStateException(SIGNED_OUT))
        }
        return if (outbox != null) {
            outbox.withDateLock(date) { deleteAuthorized(userId, date, outbox) }
        } else {
            deleteAuthorized(userId, date, null)
        }
    }

    private suspend fun deleteAuthorized(
        userId: String,
        date: String,
        outbox: PendingSaveStore?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (authorizedUserId() != userId) {
            return@withContext Result.failure(SessionOwnerMismatchException())
        }
        val resp = try {
            ownerClient(userId).delete(
                "entries",
                mapOf("user_id" to "eq.$userId", "date" to "eq.$date"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        if (!resp.isSuccessful) {
            return@withContext Result.failure(
                IllegalStateException(
                    resp.errorMessage ?: "Původní den se nepodařilo smazat (${resp.code}).",
                ),
            )
        }
        runCatching { cacheFor(userId)?.removeDate(date) }
        if (outbox != null && !outbox.remove(date)) {
            return@withContext Result.failure(
                IllegalStateException("Den byl smazán, ale místní frontu se nepodařilo vyčistit."),
            )
        }
        val scalesResp = try {
            ownerClient(userId).delete(
                "scale_entries",
                mapOf("user_id" to "eq.$userId", "date" to "eq.$date"),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
        if (!scalesResp.isSuccessful) {
            return@withContext Result.failure(
                IllegalStateException(
                    "Den byl smazán, ale hodnoty škál zůstaly (${scalesResp.code}). Zkus to znovu.",
                ),
            )
        }
        Result.success(Unit)
    }

    /**
     * A two-date move cannot be made safe with the server's currently available
     * blind save RPC plus a separate DELETE. Re-reading both rows is not atomic and
     * still permits either a concurrent target overwrite or source loss. Fail closed
     * without issuing a read or write until an atomic move/CAS RPC is available.
     */
    suspend fun move(entry: DiaryEntry, fromDate: String, toDate: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (fromDate == toDate) {
                return@withContext Result.failure(IllegalArgumentException("Cílový den musí být jiný."))
            }
            if (authorizedUserId() == null) {
                return@withContext Result.failure(IllegalStateException(SIGNED_OUT))
            }
            Result.failure(UnsafeMoveException())
        }

    /**
     * Stages the day's photo at a unique owner-scoped path in the public
     * `diary-photos` bucket. The row does not reference it until a later save
     * succeeds, so picking a replacement cannot overwrite the stored image.
     *
     * Returns the public URL on success, or a short user-facing reason on
     * failure. The reason matters: the previous `null` return hid the bucket's
     * 5 MB cap (HTTP 413), so an oversized photo looked exactly like a dead
     * network and the entry silently kept `photo_path = null`.
     */
    suspend fun uploadPhoto(date: String, bytes: ByteArray): PhotoUpload = withContext(Dispatchers.IO) {
        val userId = authorizedUserId()
            ?: return@withContext PhotoUpload(error = "přihlášení vypršelo")
        val candidates = photoCandidates?.takeIf { it.ownerId == userId }
            ?: return@withContext PhotoUpload(error = "fotku nelze bezpečně evidovat pro pozdější obnovu")
        val path = "$userId/$date-${UUID.randomUUID()}.jpg"
        val resp = try {
            ownerClient(userId).uploadObject("diary-photos/$path", bytes, "image/jpeg", upsert = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(LOG_TAG, "photo upload failed: ${e.javaClass.simpleName} (${bytes.size} B)")
            return@withContext PhotoUpload(error = "nepodařilo se připojit")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "photo upload failed: ${e.javaClass.simpleName} (${bytes.size} B)")
            return@withContext PhotoUpload(error = "neočekávaná chyba")
        }
        if (!resp.isSuccessful) {
            Log.w(LOG_TAG, "photo upload failed: HTTP ${resp.code} ${resp.errorMessage.orEmpty()} (${bytes.size} B)")
            return@withContext PhotoUpload(error = uploadError(resp))
        }
        val url = client.publicStorageUrl("diary-photos/$path")
        val tracked = runCatching { candidates.track(date, url) }.getOrDefault(false)
        if (!tracked) {
            Log.w(LOG_TAG, "photo candidate persistence failed")
            return@withContext PhotoUpload(error = "fotku se nepodařilo bezpečně uložit")
        }
        PhotoUpload(url = url)
    }

    /**
     * Extracts an owned object key from either the canonical public URL or a
     * legacy relative object key. Signed query parameters are ignored. Any
     * foreign origin, bucket, owner, traversal, encoded separator, or unsafe
     * segment fails closed.
     */
    private fun storageObjectPathForDeletion(
        photoPath: String?,
        userId: String,
        projectUrl: String,
    ): String? {
        if (photoPath.isNullOrBlank() || userId.isBlank()) return null
        val photoUri = try {
            URI(photoPath.trim())
        } catch (_: Exception) {
            return null
        }
        val rawKey = if (photoUri.isAbsolute) {
            val projectUri = try {
                URI(projectUrl)
            } catch (_: Exception) {
                return null
            }
            if (!sameOrigin(photoUri, projectUri) || photoUri.rawUserInfo != null || photoUri.rawFragment != null) {
                return null
            }
            val publicPrefix = "/storage/v1/object/public/diary-photos/"
            val rawPath = photoUri.rawPath ?: return null
            if (!rawPath.startsWith(publicPrefix)) return null
            rawPath.removePrefix(publicPrefix)
        } else {
            if (photoUri.rawAuthority != null || photoUri.rawQuery != null || photoUri.rawFragment != null) {
                return null
            }
            val rawPath = photoUri.rawPath?.removePrefix("/") ?: return null
            when {
                rawPath.startsWith("diary-photos/") -> rawPath.removePrefix("diary-photos/")
                else -> rawPath
            }
        }
        val rawSegments = rawKey.split('/')
        if (rawSegments.size < 2 || rawSegments.any { it.isEmpty() }) return null
        val segments = rawSegments.map { raw ->
            val decoded = try {
                // In URI paths '+' is literal; URLDecoder otherwise treats it as a space.
                URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name())
            } catch (_: Exception) {
                return null
            }
            if (
                decoded.isEmpty() || decoded == "." || decoded == ".." ||
                decoded.contains('/') || decoded.contains('\\') ||
                decoded.any { it.isISOControl() } ||
                !decoded.matches(Regex("[A-Za-z0-9._~-]+"))
            ) {
                return null
            }
            decoded
        }
        if (segments.first() != userId) return null
        return segments.joinToString("/")
    }

    private fun sameOrigin(first: URI, second: URI): Boolean {
        val firstHost = first.host ?: return false
        val secondHost = second.host ?: return false
        return first.scheme.equals(second.scheme, ignoreCase = true) &&
            firstHost.equals(secondHost, ignoreCase = true) &&
            normalizedPort(first) == normalizedPort(second)
    }

    private fun normalizedPort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }

    /** Maps a Storage failure onto a reason the form can show next to the buttons. */
    private fun uploadError(resp: HttpResponse): String = when {
        resp.code == 413 -> "fotka je moc velká"
        resp.code == 401 || resp.code == 403 -> "přihlášení vypršelo"
        resp.code == 400 && resp.errorMessage?.contains("mime", ignoreCase = true) == true ->
            "nepodporovaný formát fotky"
        else -> "HTTP ${resp.code}"
    }

    companion object {

        /**
         * Safety cap for [loadRange]. The dashboard's 30-day window is far below
         * it, so the cap only guards against a malformed range, never truncates a
         * real read.
         */
        const val RANGE_LIMIT = 100

        /** Logcat tag for the photo upload — the only failure here that used to be silent. */
        private const val LOG_TAG = "Diarium/Photo"

        /** Same wording `save` uses, so a signed-out read reads like a session problem. */
        private const val SIGNED_OUT = "Přihlášení vypršelo, přihlas se znovu."

        /**
         * Copy for a read that never reached the server — the same sentence the AI
         * section shows, so an offline phone reads the same everywhere in the app.
         */
        private const val CONNECT_FAILED = "Nepodařilo se připojit k serveru."

        /** Historical idempotency receipts are not proof of the current row version. */
        private const val REPLAYED_RECEIPT_MISMATCH = "replayed_receipt_current_row_unverified"

        private const val PHOTO_CANDIDATE_TRACK_FAILED =
            "Původní fotku nelze bezpečně evidovat. Uložení změn je zablokované a fotka zůstala zachovaná."

        /** A move is two remote writes; queuing only its target would duplicate the day. */
        private const val MOVE_OFFLINE_BLOCKED =
            "Přesun data vyžaduje připojení. Zápis zůstal v původním dni; zkus to znovu online."

        private const val PENDING_SAVE_RECOVERY_BLOCKED =
            "Místní zápis čeká na bezpečné dokončení nebo ruční kontrolu. Původní kopie zůstala zachovaná."

        /**
         * Maps a [DiaryEntry] onto the exact `save_daily_entry(jsonb)` payload.
         *
         * Fields the form does not own (`phone_screen_time`, `phone_unlocks`,
         * `phone_top_apps`) are deliberately omitted: the RPC assigns `excluded.*`
         * on conflict for any key present in the payload, so sending them as
         * `null` would wipe the screen-time worker's data.
         *
         * `ai_reflection` is the same rule pointing the other way — it travels
         * only when the day already has one, so an ordinary form save (which
         * always starts from an entry loaded without a reflection, or from an
         * empty one) can never blank out what the AI endpoint stored. That is the
         * behaviour of `src/app/api/save-entry/route.ts` on the web side.
         *
         * `photo_path` is omitted when the form asked to remove the photo. Legacy
         * callers still clear it after persistence; the foreground v2 request carries
         * an explicit `clear_photo` intent so the column clear is part of the atomic
         * RPC transaction. An ordinary edit of a day that still has its photo keeps
         * sending the URL, exactly as before.
         */
        fun buildPayload(entry: DiaryEntry, userId: String, date: String): JSONObject =
            JSONObject().apply {
                put("user_id", userId)
                put("date", date)
                put("mood", entry.mood)
                put("mood_emoji", entry.moodEmoji)
                put("sleep_quality", entry.sleepQuality)
                put("stress", entry.stress)
                put("activities", JSONArray(entry.activities))
                put("habits", JSONObject(entry.habits))
                put("gratitude", JSONArray(entry.filteredGratitude()))
                put("note", entry.note)
                put("weather", JSONArray(entry.weather))
                put("scale_values", JSONObject(entry.positiveScaleValues()))
                entry.aiReflection?.takeIf { it.isNotBlank() }?.let { put("ai_reflection", it) }
                if (!entry.clearPhoto) {
                    entry.photoPath?.takeIf { it.isNotBlank() }?.let { put("photo_path", it) }
                }
            }

        /** `check_achievements(user_id, has_photo, has_scale)` — the post-save hook. */
        fun buildAchievementsPayload(entry: DiaryEntry, userId: String): JSONObject =
            JSONObject().apply {
                put("p_user_id", userId)
                put("p_has_photo", !entry.photoPath.isNullOrBlank())
                put("p_has_scale", entry.positiveScaleValues().isNotEmpty())
            }

        /** Maps an `entries` row back onto the form model. */
        fun fromRow(row: JSONObject): DiaryEntry = DiaryEntry(
            mood = row.optInt("mood", 0),
            moodEmoji = row.plainString("mood_emoji"),
            sleepQuality = row.optInt("sleep_quality", 0),
            stress = row.optInt("stress", 0),
            activities = row.optArrayTolerant("activities").stringList(),
            habits = row.optJSONObject("habits").booleanMap(),
            gratitude = row.optArrayTolerant("gratitude").slotList(DiaryEntry.GRATITUDE_SLOTS),
            note = row.plainString("note"),
            photoPath = row.plainString("photo_path").takeIf { it.isNotBlank() },
            scaleValues = row.optJSONObject("scale_values").intMap(),
            weather = row.optArrayTolerant("weather").stringList(),
            phoneScreenTime = row.optInt("phone_screen_time", -1).takeIf { it >= 0 },
            phoneUnlocks = row.optInt("phone_unlocks", -1).takeIf { it >= 0 },
            phoneTopApps = row.optArrayTolerant("phone_top_apps").topApps(),
            aiReflection = row.plainString("ai_reflection").takeIf { it.isNotBlank() },
        )
    }
}

/**
 * One `entries` row together with its `date`.
 *
 * [DiaryEntry] has no date on purpose — the check-in form is always scoped to the
 * day it is editing — but the dashboard works across days, so the range read
 * carries the row's date alongside the model instead of widening the form's shape.
 */
data class DatedEntry(val date: String, val entry: DiaryEntry)

/** A server entry together with the row identity/revision used by foreground compare-and-swap. */
data class StoredEntrySnapshot(
    val entry: DiaryEntry,
    val updatedAt: String?,
    val id: String? = null,
    val revision: Long? = null,
)

/**
 * The save went to the on-device outbox instead of the server. It is retained for
 * explicit review and is not sent automatically. [EntriesRepository.save] reports
 * this as a failed [Result] so the screen cannot mistake local persistence for sync.
 */
class QueuedOfflineException :
    Exception("Nesynchronizováno — uloženo jen v tomto telefonu. Automaticky se neodešle.")

/** Date moves are disabled until the backend can compare and move both rows atomically. */
class UnsafeMoveException : IllegalStateException(
    "Přesun je dočasně vypnutý: server neumí bezpečně ověřit oba dny najednou. Původní zápis zůstal beze změny.",
)

/** Target persistence succeeded, but source deletion was not confirmed. */
class MoveSourceDeleteFailedException(cause: Throwable?) : IllegalStateException(
    "Cílový den je uložený, ale smazání původního dne se nepodařilo potvrdit. Zkontroluj oba dny.",
    cause,
)
