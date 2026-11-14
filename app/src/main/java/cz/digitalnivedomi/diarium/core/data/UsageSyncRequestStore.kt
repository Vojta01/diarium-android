package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Exact immutable CAS request retained before dispatch so an unknown response can replay safely. */
internal data class UsageSyncRequest(
    val ownerId: String,
    val date: String,
    /** The original phone-only p_payload JSON, retained verbatim across retries/rebases. */
    val phonePayloadJson: String,
    val baseline: EntryBaseline,
    val requestId: String,
    /** The complete PostgREST body is retained and submitted unchanged on ambiguity. */
    val requestBodyJson: String,
) {
    fun withNewBaseline(baseline: EntryBaseline): UsageSyncRequest =
        create(ownerId, date, phonePayloadJson, baseline, UUID.randomUUID().toString())

    companion object {
        private val PHONE_PAYLOAD_KEYS = setOf(
            "user_id",
            "date",
            "phone_screen_time",
            "phone_unlocks",
            "phone_top_apps",
        )
        private val RPC_BODY_KEYS = setOf(
            "p_payload",
            "p_expected_exists",
            "p_expected_id",
            "p_expected_revision",
            "p_request_id",
        )

        fun create(
            ownerId: String,
            date: String,
            phonePayloadJson: String,
            baseline: EntryBaseline,
            requestId: String = UUID.randomUUID().toString(),
        ): UsageSyncRequest {
            val body = JSONObject()
                .put("p_payload", JSONObject(phonePayloadJson))
                .put("p_expected_exists", baseline.exists)
                .put("p_expected_id", baseline.id ?: JSONObject.NULL)
                .put("p_expected_revision", baseline.revision ?: JSONObject.NULL)
                .put("p_request_id", requestId)
                .toString()
            return UsageSyncRequest(ownerId, date, phonePayloadJson, baseline, requestId, body)
                .also { require(it.isValid()) { "Invalid conditional phone-sync request" } }
        }
    }

    fun isValid(): Boolean {
        if (ownerId.isBlank() || runCatching { LocalDate.parse(date) }.isFailure) return false
        if (baseline.exists != true || !baseline.isValid) return false
        if (runCatching { UUID.fromString(requestId) }.isFailure) return false
        return runCatching {
            val payload = JSONObject(phonePayloadJson)
            if (payload.keys().asSequence().toSet() != PHONE_PAYLOAD_KEYS) return false
            if (payload.getString("user_id") != ownerId || payload.getString("date") != date) return false
            if (!isWholeNonNegative(payload, "phone_screen_time") ||
                !isWholeNonNegative(payload, "phone_unlocks")
            ) return false
            val apps = payload.optJSONArray("phone_top_apps") ?: return false
            for (index in 0 until apps.length()) {
                val app = apps.optJSONObject(index) ?: return false
                if (app.keys().asSequence().toSet() != setOf("app", "minutes")) return false
                if (app.optString("app").isBlank()) return false
                val minutes = app.opt("minutes") as? Number ?: return false
                if (!minutes.toDouble().isFinite() || minutes.toDouble() < 0.0) return false
            }

            val body = JSONObject(requestBodyJson)
            if (body.keys().asSequence().toSet() != RPC_BODY_KEYS) return false
            if (body.getJSONObject("p_payload").toString() != payload.toString()) return false
            if (!body.getBoolean("p_expected_exists")) return false
            if (body.getString("p_expected_id") != baseline.id) return false
            if (body.getLong("p_expected_revision") != baseline.revision) return false
            if (body.getString("p_request_id") != requestId) return false
            true
        }.getOrDefault(false)
    }

    private fun isWholeNonNegative(json: JSONObject, key: String): Boolean {
        val number = json.opt(key) as? Number ?: return false
        val value = number.toDouble()
        return value.isFinite() && value >= 0.0 && value % 1.0 == 0.0
    }
}

/** Distinguishes no queued request from corrupt state, which must never be overwritten. */
internal sealed interface UsageSyncRequestState {
    data object Absent : UsageSyncRequestState
    data object Corrupt : UsageSyncRequestState
    data class Present(val request: UsageSyncRequest) : UsageSyncRequestState
}

/**
 * Owner/date-partitioned durable phone-sync outbox. The exact v2 request is committed
 * before network dispatch; unknown results can therefore replay the same request ID,
 * baseline and body after a worker or process restart.
 */
internal class UsageSyncRequestStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun <T> withDateLock(ownerId: String, date: String, action: suspend () -> T): T {
        val key = "$ownerId\u0000$date"
        val mutex = DATE_LOCKS.computeIfAbsent(key) { Mutex() }
        mutex.lock()
        try {
            return action()
        } finally {
            mutex.unlock()
        }
    }

    fun read(ownerId: String, date: String): UsageSyncRequestState = synchronized(STORE_LOCK) {
        if (ownerId.isBlank() || runCatching { LocalDate.parse(date) }.isFailure) {
            return@synchronized UsageSyncRequestState.Corrupt
        }
        val key = storageKey(ownerId, date)
        val raw = try {
            prefs.getString(key, null)
        } catch (_: Exception) {
            return@synchronized UsageSyncRequestState.Corrupt
        }
        if (raw == null) {
            return@synchronized if (prefs.contains(key)) UsageSyncRequestState.Corrupt
            else UsageSyncRequestState.Absent
        }
        decode(ownerId, date, raw)?.let(UsageSyncRequestState::Present)
            ?: UsageSyncRequestState.Corrupt
    }

    /** Commit the first immutable request only if the date is still unqueued. */
    fun saveIfAbsent(request: UsageSyncRequest): Boolean = synchronized(STORE_LOCK) {
        if (!request.isValid()) return@synchronized false
        if (prefs.contains(storageKey(request.ownerId, request.date))) return@synchronized false
        prefs.edit().putString(storageKey(request.ownerId, request.date), encode(request)).commit()
    }

    /** Replace a confirmed-conflict request only while its exact prior token is stored. */
    fun replaceIfUnchanged(expected: UsageSyncRequest, replacement: UsageSyncRequest): Boolean =
        synchronized(STORE_LOCK) {
            if (!expected.isValid() || !replacement.isValid() ||
                expected.ownerId != replacement.ownerId || expected.date != replacement.date
            ) return@synchronized false
            val key = storageKey(expected.ownerId, expected.date)
            val current = read(expected.ownerId, expected.date)
            if (current !is UsageSyncRequestState.Present || current.request != expected) {
                return@synchronized false
            }
            prefs.edit().putString(key, encode(replacement)).commit()
        }

    /** Remove only the exact request that received a confirmed receipt. */
    fun removeIfUnchanged(request: UsageSyncRequest): Boolean = synchronized(STORE_LOCK) {
        if (!request.isValid()) return@synchronized false
        val key = storageKey(request.ownerId, request.date)
        val current = read(request.ownerId, request.date)
        if (current !is UsageSyncRequestState.Present || current.request != request) {
            return@synchronized false
        }
        prefs.edit().remove(key).commit()
    }

    /** Consent withdrawal discards all retained phone snapshots for this owner. */
    fun clearOwner(ownerId: String): Boolean = synchronized(STORE_LOCK) {
        if (ownerId.isBlank()) return@synchronized false
        val prefix = ownerPrefix(ownerId)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.commit()
    }

    private fun encode(request: UsageSyncRequest): String = JSONObject()
        .put("version", RECORD_VERSION)
        .put("ownerId", request.ownerId)
        .put("date", request.date)
        .put("phonePayloadJson", request.phonePayloadJson)
        .put("baselineExists", request.baseline.exists)
        .put("baselineId", request.baseline.id)
        .put("baselineRevision", request.baseline.revision)
        .put("requestId", request.requestId)
        .put("requestBodyJson", request.requestBodyJson)
        .toString()

    private fun decode(expectedOwner: String, expectedDate: String, raw: String): UsageSyncRequest? =
        runCatching {
            val json = JSONObject(raw)
            if (json.optInt("version", -1) != RECORD_VERSION) return null
            val owner = json.optString("ownerId")
            val date = json.optString("date")
            if (owner != expectedOwner || date != expectedDate) return null
            val baseline = EntryBaseline(
                exists = if (json.has("baselineExists")) json.getBoolean("baselineExists") else null,
                id = json.optString("baselineId").takeIf { it.isNotBlank() && it != "null" },
                revision = if (json.has("baselineRevision") && !json.isNull("baselineRevision")) {
                    json.getLong("baselineRevision")
                } else {
                    null
                },
            )
            UsageSyncRequest(
                ownerId = owner,
                date = date,
                phonePayloadJson = json.getString("phonePayloadJson"),
                baseline = baseline,
                requestId = json.getString("requestId"),
                requestBodyJson = json.getString("requestBodyJson"),
            ).takeIf(UsageSyncRequest::isValid)
        }.getOrNull()

    private fun storageKey(ownerId: String, date: String): String = ownerPrefix(ownerId) + date

    /** Fingerprint of the last payload the server confirmed for this day. */
    fun readConfirmedHash(ownerId: String, date: String): String? = synchronized(STORE_LOCK) {
        prefs.getString(confirmedKey(ownerId, date), null)
    }

    fun writeConfirmedHash(ownerId: String, date: String, hash: String) = synchronized(STORE_LOCK) {
        prefs.edit().putString(confirmedKey(ownerId, date), hash).apply()
    }

    private fun confirmedKey(ownerId: String, date: String): String =
        ownerPrefix(ownerId) + "confirmed/" + date

    private fun ownerPrefix(ownerId: String): String =
        "v1/${Base64.encodeToString(ownerId.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)}/"

    companion object {
        const val PREFS_NAME = "diarium_screen_usage_outbox"
        private const val RECORD_VERSION = 1
        private val STORE_LOCK = Any()
        private val DATE_LOCKS = ConcurrentHashMap<String, Mutex>()
    }
}
