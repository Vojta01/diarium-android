package cz.digitalnivedomi.diarium.ui.home

import cz.digitalnivedomi.diarium.core.data.StoredEntrySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Text and original CAS snapshot kept in process memory when local staging fails. */
internal data class PendingGeneratedReflectionStage(
    val ownerId: String,
    val date: String,
    val snapshot: StoredEntrySnapshot,
    val reflection: String,
)

/**
 * App-shell-owned transient recovery for generated reflections that could not be
 * staged durably. Entries are indexed by both owner and date, so a signed-in account
 * can only retrieve its own failed stage. The holder is deliberately memory-only:
 * a fresh app process cannot recover text after permanent storage failure.
 */
class GeneratedReflectionStageRetryHolder {
    private data class Key(val ownerId: String, val date: String)

    private val lock = Any()
    private val pending = mutableMapOf<Key, PendingGeneratedReflectionStage>()
    private val mutableVersion = MutableStateFlow(0L)

    /** Compose observes only a version, never a cross-account map of reflection text. */
    internal val version: StateFlow<Long> = mutableVersion.asStateFlow()

    internal fun get(ownerId: String?, date: String?): PendingGeneratedReflectionStage? {
        val owner = ownerId?.takeIf(String::isNotBlank) ?: return null
        val day = date?.takeIf(String::isNotBlank) ?: return null
        return synchronized(lock) { pending[Key(owner, day)] }
    }

    internal fun put(retry: PendingGeneratedReflectionStage) {
        require(retry.ownerId.isNotBlank())
        require(retry.date.isNotBlank())
        require(retry.reflection.isNotBlank())
        synchronized(lock) {
            val key = Key(retry.ownerId, retry.date)
            if (pending[key] != retry) {
                pending[key] = retry
                mutableVersion.value += 1
            }
        }
    }

    /** Clear only this exact retry, and only after durable staging is confirmed. */
    internal fun clear(retry: PendingGeneratedReflectionStage): Boolean = synchronized(lock) {
        val key = Key(retry.ownerId, retry.date)
        if (pending[key] != retry) return@synchronized false
        pending.remove(key)
        mutableVersion.value += 1
        true
    }
}

internal const val UNSAVED_REFLECTION_STAGE_WARNING =
    "Reflexe zatím není uložená v telefonu. Při přepnutí obrazovky nebo účtu zůstane v paměti této relace, ale po zavření či restartu aplikace se může ztratit. Zkus opětovné uložení nebo ji zkopíruj."
