package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Base64

/**
 * Durable, owner-scoped set of uploaded photo paths that are not yet known to be
 * referenced by an entry. Candidates are never deleted merely because a newer
 * picker result exists; cleanup first confirms the owner-scoped server reference.
 */
interface PhotoCandidateRegistry {
    val ownerId: String?
    fun track(date: String, path: String): Boolean
    fun forDate(date: String): List<String>
    fun remove(date: String, path: String): Boolean
}

class PhotoCandidateStore private constructor(
    private val prefs: SharedPreferences,
    override val ownerId: String?,
) : PhotoCandidateRegistry {
    private val ownerPrefix = ownerId?.takeIf { it.isNotBlank() }?.let(::prefixFor)

    /** Track one exact staged path; repeated registration is idempotent. */
    override fun track(date: String, path: String): Boolean {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return false
        if (date.isBlank() || path.isBlank()) return false
        val key = key(owner, date, path)
        val value = JSONObject()
            .put("ownerId", owner)
            .put("date", date)
            .put("path", path)
            .toString()
        return prefs.edit().putString(key, value).commit()
    }

    /** Distinct valid candidates for this owner's [date], in deterministic order. */
    override fun forDate(date: String): List<String> {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val prefix = ownerPrefix?.plus(encode(date) + "/") ?: return emptyList()
        return prefs.all.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .mapNotNull { key ->
                val raw = runCatching { prefs.getString(key, null) }.getOrNull() ?: return@mapNotNull null
                runCatching {
                    val json = JSONObject(raw)
                    if (json.optString("ownerId") != owner || json.optString("date") != date) return@runCatching null
                    json.optString("path").takeIf { it.isNotBlank() }
                }.getOrNull()
            }
            .distinct()
            .sorted()
            .toList()
    }

    /** Remove only one exact candidate after it is referenced or safely deleted. */
    override fun remove(date: String, path: String): Boolean {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return false
        val key = key(owner, date, path)
        if (!prefs.contains(key)) return false
        return prefs.edit().remove(key).commit()
    }

    companion object {
        const val PREFS_NAME = "diarium_photo_candidates"
        private const val VERSION_PREFIX = "v1/"

        fun from(context: Context, ownerId: String?): PhotoCandidateStore = PhotoCandidateStore(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            ownerId?.takeIf { it.isNotBlank() },
        )

        private fun key(ownerId: String, date: String, path: String): String =
            "${prefixFor(ownerId)}${encode(date)}/${encode(path)}"

        private fun prefixFor(ownerId: String): String = "$VERSION_PREFIX${encode(ownerId)}/"

        private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
    }
}
