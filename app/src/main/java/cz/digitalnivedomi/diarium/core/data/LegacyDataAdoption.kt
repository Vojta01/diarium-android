package cz.digitalnivedomi.diarium.core.data

/**
 * Explicit, user-confirmed adoption of ownerless beta5 data into the account
 * that is signed in now.
 *
 * Rules:
 *  - it runs only with a verified account and never crosses accounts;
 *  - it fills only empty fields and never overwrites an existing value;
 *  - it never deletes the legacy bytes, so the same data can be adopted again
 *    on another device that still holds the old install;
 *  - AI consent and the guide flag are never adopted: consent belongs to the
 *    account that is signed in and must be given again.
 */
data class LegacyAdoptionAvailability(
    val name: String?,
    val birthYear: Int?,
    val keyPresent: Boolean,
) {
    val hasProfile: Boolean get() = name != null || birthYear != null
    val hasAnything: Boolean get() = hasProfile || keyPresent
}

sealed interface LegacyAdoptionResult {
    data class Adopted(val profileFields: Int, val keyAdopted: Boolean) : LegacyAdoptionResult
    data object NothingToAdopt : LegacyAdoptionResult
    data object OwnerUnavailable : LegacyAdoptionResult
    data object OwnerChanged : LegacyAdoptionResult
    data object ReadFailed : LegacyAdoptionResult
}

class LegacyDataAdoption(
    private val profileFor: (String) -> ProfileStore,
    private val keyFor: (String) -> AiKeyStore,
    private val verifiedOwnerId: () -> String?,
) {

    /** What the phone still holds from beta5 and the account does not have yet. */
    suspend fun availability(): LegacyAdoptionAvailability {
        val owner = verifiedOwnerId()?.takeIf { it.isNotBlank() }
        val legacy = runCatching { profileFor(previewOwner()).legacyProfileValues() }.getOrNull()
            ?: return LegacyAdoptionAvailability(null, null, false)
        val legacyKey = runCatching { keyFor(previewOwner()).legacyKeyOrNull() != null }
            .getOrDefault(false)
        if (owner == null) {
            return LegacyAdoptionAvailability(legacy.name, legacy.birthYear, legacyKey)
        }
        val account = runCatching { profileFor(owner).load() }.getOrNull()
        val keyMissing = runCatching { keyFor(owner).key() == null }.getOrDefault(true)
        return LegacyAdoptionAvailability(
            name = legacy.name.takeIf { account?.name.isNullOrBlank() },
            birthYear = legacy.birthYear.takeIf { account?.birthYear == null },
            keyPresent = legacyKey && keyMissing,
        )
    }

    suspend fun adopt(): LegacyAdoptionResult {
        val owner = verifiedOwnerId()?.takeIf { it.isNotBlank() }
            ?: return LegacyAdoptionResult.OwnerUnavailable
        if (verifiedOwnerId() != owner) return LegacyAdoptionResult.OwnerChanged
        return try {
            val fields = profileFor(owner).adoptLegacyProfile()
            if (verifiedOwnerId() != owner) return LegacyAdoptionResult.OwnerChanged
            val key = keyFor(owner).adoptLegacyKey()
            if (fields == 0 && !key) {
                LegacyAdoptionResult.NothingToAdopt
            } else {
                LegacyAdoptionResult.Adopted(profileFields = fields, keyAdopted = key)
            }
        } catch (_: Exception) {
            LegacyAdoptionResult.ReadFailed
        }
    }

    /**
     * Legacy keys are ownerless, so any namespace reads the same bytes. An empty
     * owner keeps the store in read-only mode.
     */
    private fun previewOwner(): String = verifiedOwnerId().orEmpty()
}
