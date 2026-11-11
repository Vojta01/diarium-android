package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Read-time source/target tokens shown to the user before a date move is sent. */
data class EntryMoveCandidate(
    val ownerId: String,
    val sourceDate: String,
    val sourceSnapshot: StoredEntrySnapshot,
    val targetDate: String,
    val targetSnapshot: StoredEntrySnapshot?,
)

/** Exact immutable intent kept on disk before the move RPC is dispatched. */
data class EntryMoveRequest(
    val ownerId: String,
    val sourceDate: String,
    val sourceId: String,
    val sourceRevision: Long,
    val targetDate: String,
    val targetExpectedExists: Boolean,
    val targetExpectedId: String?,
    val targetExpectedRevision: Long?,
    val confirmOverwrite: Boolean,
    val requestId: String,
    /** Full synthetic/server row snapshots remain local until the receipt is confirmed. */
    val sourceSnapshot: StoredEntrySnapshot,
    val targetSnapshot: StoredEntrySnapshot?,
)

/**
 * Owner-partitioned durable move outbox. A record is written and fsynced before the
 * RPC; ambiguous outcomes retain the exact request and both full row snapshots so a
 * process restart can replay with the same ID and payload. Corrupt or owner-mismatched
 * files fail closed instead of being treated as an empty outbox.
 */
class EntryMoveStore(
    private val file: File,
    val ownerId: String,
) {
    private data class Locks(val file: Mutex = Mutex(), val dispatch: Mutex = Mutex())

    private val locks = sharedLocks.computeIfAbsent(file.absolutePath) { Locks() }

    init {
        require(ownerId.isNotBlank()) { "A date-move outbox must be owner scoped." }
    }

    suspend fun <T> withDispatchLock(block: suspend () -> T): T = locks.dispatch.withLock { block() }

    suspend fun pendingForDate(date: String): EntryMoveRequest? = locks.file.withLock {
        val matches = readRequests().filter { it.sourceDate == date || it.targetDate == date }
        if (matches.size > 1) throw IOException("Multiple unresolved date moves overlap $date.")
        matches.singleOrNull()
    }

    suspend fun pendingForId(requestId: String): EntryMoveRequest? = locks.file.withLock {
        readRequests().singleOrNull { it.requestId == requestId }
    }

    /** Persists only if neither date is already involved in an unresolved request. */
    suspend fun stage(request: EntryMoveRequest) = locks.file.withLock {
        validate(request)
        val requests = readRequests()
        if (requests.any { existing ->
                existing.sourceDate == request.sourceDate || existing.targetDate == request.sourceDate ||
                    existing.sourceDate == request.targetDate || existing.targetDate == request.targetDate
            }
        ) {
            throw PendingEntryMoveException()
        }
        writeRequests(requests + request)
    }

    /** Remove only the exact receipt that was just confirmed. */
    suspend fun remove(requestId: String): Boolean = locks.file.withLock {
        val requests = readRequests()
        val remaining = requests.filterNot { it.requestId == requestId }
        if (remaining.size == requests.size) return@withLock false
        writeRequests(remaining)
        true
    }

    private fun validate(request: EntryMoveRequest) {
        if (request.ownerId != ownerId) throw IOException("Date-move owner does not match this outbox.")
        if (runCatching { UUID.fromString(request.requestId) }.isFailure) {
            throw IOException("Date-move request ID is invalid.")
        }
        if (runCatching { LocalDate.parse(request.sourceDate) }.isFailure ||
            runCatching { LocalDate.parse(request.targetDate) }.isFailure
        ) {
            throw IOException("Date-move dates are invalid.")
        }
        if (request.sourceId.isBlank() || request.sourceRevision < 1 ||
            runCatching { UUID.fromString(request.sourceId) }.isFailure
        ) {
            throw IOException("Date-move source token is invalid.")
        }
        val targetTokenValid = if (request.targetExpectedExists) {
            !request.targetExpectedId.isNullOrBlank() &&
                runCatching { UUID.fromString(request.targetExpectedId) }.isSuccess &&
                request.targetExpectedRevision != null && request.targetExpectedRevision >= 1
        } else {
            request.targetExpectedId == null && request.targetExpectedRevision == null
        }
        if (!targetTokenValid || request.confirmOverwrite && !request.targetExpectedExists) {
            throw IOException("Date-move target token or confirmation is invalid.")
        }
        if (request.sourceSnapshot.id != request.sourceId ||
            request.sourceSnapshot.revision != request.sourceRevision ||
            request.targetSnapshot?.id != request.targetExpectedId ||
            request.targetSnapshot?.revision != request.targetExpectedRevision ||
            request.targetExpectedExists != (request.targetSnapshot != null)
        ) {
            throw IOException("Date-move snapshots do not match their compare-and-swap tokens.")
        }
        validateSnapshot(request.sourceSnapshot, request.sourceDate, request.sourceId, request.sourceRevision)
        request.targetSnapshot?.let {
            validateSnapshot(it, request.targetDate, request.targetExpectedId!!, request.targetExpectedRevision!!)
        }
        if (request.sourceDate == request.targetDate &&
            (request.targetExpectedId != request.sourceId ||
                request.targetExpectedRevision != request.sourceRevision || request.confirmOverwrite)
        ) {
            throw IOException("Same-day date move must be an unchanged token-checked request.")
        }
    }

    private fun validateSnapshot(snapshot: StoredEntrySnapshot, date: String, id: String, revision: Long) {
        val raw = snapshot.rawRowJson ?: throw IOException("Date-move row snapshot is incomplete.")
        val row = try {
            JSONObject(raw)
        } catch (failure: Exception) {
            throw IOException("Date-move row snapshot is malformed.", failure)
        }
        if (row.plainString("user_id") != ownerId || row.plainString("date") != date ||
            row.plainString("id") != id || row.optLong("revision", -1L) != revision
        ) {
            throw IOException("Date-move row snapshot is not bound to its owner/date/token.")
        }
    }

    private fun readRequests(): List<EntryMoveRequest> {
        if (!file.exists()) return emptyList()
        val root = try {
            JSONObject(file.readText())
        } catch (failure: Exception) {
            throw IOException("Date-move recovery data could not be read.", failure)
        }
        if (root.optInt("version", -1) != VERSION || root.plainString("ownerId") != ownerId) {
            throw IOException("Date-move recovery data belongs to another owner or version.")
        }
        val entries = root.optJSONArray("requests")
            ?: throw IOException("Date-move recovery data has no request list.")
        val requests = (0 until entries.length()).map { index ->
            decodeRequest(entries.getJSONObject(index)).also(::validate)
        }
        val duplicateIds = requests.groupBy { it.requestId }.any { it.value.size > 1 }
        if (duplicateIds) throw IOException("Date-move recovery data has duplicate request IDs.")
        return requests
    }

    private fun decodeRequest(json: JSONObject): EntryMoveRequest {
        val exists = json.optBoolean("targetExpectedExists", false)
        val source = decodeSnapshot(json.getJSONObject("sourceSnapshot"))
        val target = if (exists) decodeSnapshot(json.getJSONObject("targetSnapshot")) else null
        return EntryMoveRequest(
            ownerId = json.plainString("ownerId"),
            sourceDate = json.plainString("sourceDate"),
            sourceId = json.plainString("sourceId"),
            sourceRevision = json.optLong("sourceRevision", -1L),
            targetDate = json.plainString("targetDate"),
            targetExpectedExists = exists,
            targetExpectedId = json.plainString("targetExpectedId").takeIf { it.isNotBlank() },
            targetExpectedRevision = if (json.isNull("targetExpectedRevision")) null else json.optLong("targetExpectedRevision", -1L),
            confirmOverwrite = json.optBoolean("confirmOverwrite", false),
            requestId = json.plainString("requestId"),
            sourceSnapshot = source,
            targetSnapshot = target,
        )
    }

    private fun decodeSnapshot(json: JSONObject): StoredEntrySnapshot {
        val raw = json.getString("rawRow")
        val row = JSONObject(raw)
        return StoredEntrySnapshot(
            entry = EntriesRepository.fromRow(row),
            updatedAt = row.plainString("updated_at").takeIf { it.isNotBlank() },
            id = row.plainString("id").takeIf { it.isNotBlank() },
            revision = if (row.has("revision") && !row.isNull("revision")) row.optLong("revision") else null,
            rawRowJson = raw,
        )
    }

    private fun writeRequests(requests: List<EntryMoveRequest>) {
        val directory = file.parentFile ?: throw IOException("Date-move outbox has no parent directory.")
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Date-move outbox directory could not be created.")
        val root = JSONObject()
            .put("version", VERSION)
            .put("ownerId", ownerId)
            .put("requests", JSONArray().apply { requests.forEach { put(encodeRequest(it)) } })
        val temp = File.createTempFile(TEMP_PREFIX, ".tmp", directory)
        try {
            FileOutputStream(temp).use { output ->
                output.write(root.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temp.renameTo(file)) throw IOException("Date-move outbox could not be atomically replaced.")
        } finally {
            temp.delete()
        }
    }

    private fun encodeRequest(request: EntryMoveRequest): JSONObject = JSONObject()
        .put("ownerId", request.ownerId)
        .put("sourceDate", request.sourceDate)
        .put("sourceId", request.sourceId)
        .put("sourceRevision", request.sourceRevision)
        .put("targetDate", request.targetDate)
        .put("targetExpectedExists", request.targetExpectedExists)
        .put("targetExpectedId", request.targetExpectedId ?: JSONObject.NULL)
        .put("targetExpectedRevision", request.targetExpectedRevision ?: JSONObject.NULL)
        .put("confirmOverwrite", request.confirmOverwrite)
        .put("requestId", request.requestId)
        .put("sourceSnapshot", JSONObject().put("rawRow", requireNotNull(request.sourceSnapshot.rawRowJson)))
        .put("targetSnapshot", request.targetSnapshot?.let {
            JSONObject().put("rawRow", requireNotNull(it.rawRowJson))
        } ?: JSONObject.NULL)

    companion object {
        const val FILE_PREFIX = "entry_move_v1_"
        private const val VERSION = 1
        private const val TEMP_PREFIX = "entry_move_"
        private val sharedLocks = ConcurrentHashMap<String, Locks>()

        fun fileName(ownerId: String): String =
            FILE_PREFIX + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ownerId.toByteArray(Charsets.UTF_8)) + ".json"

        fun from(context: Context, ownerId: String): EntryMoveStore =
            EntryMoveStore(File(context.filesDir, fileName(ownerId)), ownerId)
    }
}

class PendingEntryMoveException : IllegalStateException(
    "A date move is awaiting a confirmed result. Retry the same request before starting another move.",
)

class EntryMoveUnknownException(val requestId: String, cause: Throwable? = null) : IllegalStateException(
    "Přesun nelze potvrdit. Místní kopie zůstaly zachované; opakování použije stejný požadavek.",
    cause,
)

class EntryMoveUnavailableException(message: String) : IllegalStateException(message)

class EntryMoveConflictException(val reason: String) : IllegalStateException(
    "Server odmítl přesun podle uložených verzí. Oba dny zůstaly beze změny; znovu je načti.",
)

class EntryMoveConfirmationRequiredException : IllegalStateException(
    "Cílový den už obsahuje zápis. Nejprve potvrď jeho přepsání.",
)
